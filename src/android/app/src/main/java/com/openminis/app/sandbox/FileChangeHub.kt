package com.openminis.app.sandbox

import android.content.Context
import android.os.FileObserver
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * [T-file-hub] inotify-backed file change monitoring for session workspaces.
 *
 * The missing piece aicode has (FileChangeHub) and OpenMinis did not: a push
 * channel for "something in this session's workspace changed". Groundwork
 * for file-sync features and live UI refresh — the first consumer is the
 * Git panel, which re-probes status when working-tree files change instead
 * of only on manual pull-to-refresh.
 *
 * Mechanics:
 * - [FileObserver] is per-directory, so a watch registers one observer per
 *   subdirectory of the session workspace (host side of the PRoot bind —
 *   guest writes land on the same inodes, so inotify sees agent edits),
 *   bounded by [MAX_OBSERVERS]/[MAX_DEPTH] and extended at runtime when new
 *   directories appear (CREATE + IN_ISDIR).
 * - Raw events are noisy (editors emit bursts; AtomicFileWrite emits
 *   tmp-create plus rename). Everything goes through [FileEventCoalescer]:
 *   per-path quiet-window merging, tmp-name drops, DELETE dominance,
 *   create+delete cancellation, pending cap.
 * - One shared ticker drains due events into per-session SharedFlows
 *   (tryEmit + DROP_OLDEST: a watcher that cannot keep up must never apply
 *   backpressure to inotify delivery).
 *
 * Paths are published as GUEST paths (/var/minis/workspace/...) so
 * consumers talk the same vocabulary as every file tool.
 */
object FileChangeHub {

    const val GUEST_ROOT = "/var/minis/workspace"

    private const val MAX_OBSERVERS = 64
    private const val MAX_DEPTH = 6
    private const val TICK_MS = 200L

    /** inotify IN_ISDIR — not exposed as a public FileObserver constant. */
    private const val IN_ISDIR = 0x40000000

    private val MASK =
        FileObserver.CREATE or FileObserver.MODIFY or FileObserver.DELETE or
            FileObserver.MOVED_TO or FileObserver.MOVED_FROM or
            FileObserver.DELETE_SELF or FileObserver.MOVE_SELF

    /**
     * Churn-heavy generated trees — watching them burns the observer cap for
     * nothing. `.git` is skipped deliberately: consumers run git commands
     * (status/commit rewrite index+refs constantly), so watching it would
     * feed the panel's own probes back in as change events. Working-tree
     * edits — the signal that actually matters — still fire.
     */
    private val SKIP_DIR_NAMES = setOf("node_modules", ".gradle", ".cxx", ".git")

    data class FileChangeEvent(
        val sessionId: String,
        /** Guest path, e.g. /var/minis/workspace/src/main.py */
        val guestPath: String,
        val kind: FileEventCoalescer.Kind,
        val isDir: Boolean,
        /** How many raw inotify events merged into this one. */
        val mergedCount: Int,
        val atMs: Long,
    )

    private class Registration(
        val sessionId: String,
        val hostRoot: File,
        val coalescer: FileEventCoalescer,
        val observers: ConcurrentHashMap<File, FileObserver>,
    )

    private val registrations = ConcurrentHashMap<String, Registration>()
    private val flows = ConcurrentHashMap<String, MutableSharedFlow<FileChangeEvent>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var ticker: Job? = null

    /**
     * Start watching a session's workspace. Idempotent; returns false when
     * the workspace host directory does not exist (sandbox not provisioned
     * yet) — callers may retry later.
     */
    fun watch(sessionId: String, context: Context): Boolean {
        if (sessionId.isBlank()) return false
        if (registrations.containsKey(sessionId)) return true
        val root = PRootKernel.resolveSessionHostPath(sessionId, GUEST_ROOT, context)
        if (root == null || !root.isDirectory) return false
        val reg = Registration(sessionId, root, FileEventCoalescer(), ConcurrentHashMap())
        registrations[sessionId] = reg
        addTree(reg, root, 0)
        ensureTicker()
        return true
    }

    fun unwatch(sessionId: String) {
        registrations.remove(sessionId)?.observers?.values?.forEach {
            runCatching { it.stopWatching() }
        }
    }

    /** Change events for one session. Safe to collect before [watch]. */
    fun eventsFor(sessionId: String): Flow<FileChangeEvent> =
        flowFor(sessionId).asSharedFlow()

    private fun flowFor(sessionId: String): MutableSharedFlow<FileChangeEvent> {
        // [T-computeifabsent-agreement] computeIfAbsent (atomic) rather than
        // getOrPut (get→compute→put, NOT atomic) or putIfAbsent (which forces
        // an eager allocation even when the key is already present): the ticker
        // (Default) and eventsFor() (Main) race here — a last-writer-wins put
        // could strand a collector on a flow instance the ticker never emits
        // into. Same guarantee ExecutionCoordinator's mutex map relies on, so
        // the two files now agree on one primitive.
        return flows.computeIfAbsent(sessionId) {
            MutableSharedFlow<FileChangeEvent>(
                replay = 0,
                extraBufferCapacity = 128,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        }
    }

    // ------------------------------------------------------------ internals

    private fun addTree(reg: Registration, dir: File, depth: Int) {
        if (depth > MAX_DEPTH) return
        // A queued CREATE-dir expansion can outlive unwatch(); never attach
        // observers to a deregistered session (they would leak forever).
        if (registrations[reg.sessionId] !== reg) return
        attachObserver(reg, dir)
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (c in children) {
            if (!c.isDirectory || shouldSkipDir(c)) continue
            if (reg.observers.size >= MAX_OBSERVERS) return
            addTree(reg, c, depth + 1)
        }
    }

    private fun attachObserver(reg: Registration, dir: File) {
        if (reg.observers.size >= MAX_OBSERVERS) return
        if (reg.observers.containsKey(dir)) return
        @Suppress("DEPRECATION") // String-path ctor: works on every API level.
        val obs = object : FileObserver(dir.absolutePath, MASK) {
            override fun onEvent(event: Int, path: String?) {
                runCatching { handleEvent(reg, dir, event, path) }
            }
        }
        reg.observers[dir] = obs
        obs.startWatching()
    }

    private fun handleEvent(reg: Registration, dir: File, event: Int, path: String?) {
        val isDirEvent = (event and IN_ISDIR) != 0
        if (path == null) {
            // DELETE_SELF / MOVE_SELF: the watched directory itself went away.
            if (dir != reg.hostRoot) {
                reg.observers.remove(dir)?.let { runCatching { it.stopWatching() } }
            }
            reg.coalescer.offer(guestDirPathOf(reg, dir), FileEventCoalescer.Kind.DELETE, true)
            return
        }
        val child = File(dir, path)
        val kind = when {
            (event and FileObserver.CREATE) != 0 -> FileEventCoalescer.Kind.CREATE
            (event and FileObserver.MODIFY) != 0 -> FileEventCoalescer.Kind.MODIFY
            (event and FileObserver.DELETE) != 0 -> FileEventCoalescer.Kind.DELETE
            (event and FileObserver.MOVED_TO) != 0 -> FileEventCoalescer.Kind.CREATE
            (event and FileObserver.MOVED_FROM) != 0 -> FileEventCoalescer.Kind.DELETE
            else -> return
        }
        if (kind == FileEventCoalescer.Kind.CREATE && isDirEvent && !shouldSkipDir(child)) {
            scope.launch { addTree(reg, child, depthOf(reg, child)) }
        }
        if (kind == FileEventCoalescer.Kind.DELETE && isDirEvent) {
            reg.observers.remove(child)?.let { runCatching { it.stopWatching() } }
        }
        reg.coalescer.offer(guestPathOf(reg, dir, path), kind, isDirEvent)
    }

    private fun depthOf(reg: Registration, dir: File): Int =
        runCatching { dir.relativeTo(reg.hostRoot).path.count { it == File.separatorChar } }
            .getOrDefault(MAX_DEPTH + 1)

    private fun relOf(reg: Registration, dir: File): String =
        runCatching { dir.relativeTo(reg.hostRoot).path }.getOrDefault("")

    private fun guestPathOf(reg: Registration, dir: File, name: String): String {
        val rel = relOf(reg, dir)
        return if (rel.isEmpty()) "$GUEST_ROOT/$name" else "$GUEST_ROOT/$rel/$name"
    }

    private fun guestDirPathOf(reg: Registration, dir: File): String {
        val rel = relOf(reg, dir)
        return if (rel.isEmpty()) GUEST_ROOT else "$GUEST_ROOT/$rel"
    }

    private fun shouldSkipDir(dir: File): Boolean = dir.name in SKIP_DIR_NAMES

    private fun ensureTicker() {
        if (ticker?.isActive == true) return
        synchronized(this) {
            if (ticker?.isActive == true) return
            ticker = scope.launch {
                while (isActive) {
                    delay(TICK_MS)
                    if (registrations.isEmpty()) continue
                    for (reg in registrations.values) {
                        val due = reg.coalescer.drainDue()
                        if (due.isEmpty()) continue
                        val flow = flowFor(reg.sessionId)
                        for (e in due) {
                            flow.tryEmit(
                                FileChangeEvent(
                                    sessionId = reg.sessionId,
                                    guestPath = e.relPath,
                                    kind = e.kind,
                                    isDir = e.isDir,
                                    mergedCount = e.count,
                                    atMs = System.currentTimeMillis(),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}
