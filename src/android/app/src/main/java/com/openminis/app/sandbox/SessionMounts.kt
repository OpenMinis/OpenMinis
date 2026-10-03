package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import java.io.File

/**
 * [T-android-session-private-mounts] The ONE place a shell's `/var/minis`
 * bind mounts are decided — for the agent's shells (ExecutionCoordinator →
 * PersistentShell / FreshProcessShell) and the interactive terminal alike.
 *
 * Why this exists: each chat session has its own `workspace`, `attachments`,
 * `offloads` and `browser` directories, but they used to be ALSO written into
 * the process-global [PRootKernel.bindMounts] every time a shell was built.
 * That map is last-writer-wins, so whichever session built a shell most
 * recently decided what `/var/minis/workspace` meant for every reader of the
 * global map — including the interactive terminal, which replayed it
 * verbatim and so showed a random session's files. The per-session mounts
 * now live only in the argv of the shell that owns them; the global map keeps
 * only what is genuinely global (`memory`, `skills`, `shared`, `mcp-servers`,
 * `mounts/<name>`).
 *
 * A directory that cannot be created is left OUT of the mounts, with an error
 * log, rather than passed through. PRoot does not fail on a missing `-b` host
 * path — it prints one WARNING and silently drops the binding — so the guest
 * would see the empty rootfs placeholder and "successfully" write files that
 * no reader will ever find. Deciding here makes the loss observable.
 */
object SessionMounts {
    private const val TAG = "SessionMounts"

    /** Subdirs under `minis-sessions/<sessionId>/`, one set per chat session. */
    val SESSION_SUBDIRS = listOf("attachments", "offloads", "workspace", "browser")

    /** Subdirs under `minis-global/`, shared by every session. */
    val GLOBAL_SUBDIRS = listOf("memory", "skills", "shared", "mcp-servers")

    /**
     * Soft ceiling on the bytes the `-b` arguments add to PRoot's argv. The
     * kernel limit (ARG_MAX, shared with envp) is far higher; this only
     * warns early if external mounts ever grow to where it could matter.
     */
    const val ARGV_WARN_BYTES = 64 * 1024

    data class Built(
        /** Linux path → host path, in bind order. */
        val mounts: Map<String, String>,
        /** Linux paths left out because their host directory is unusable. */
        val skipped: List<String>,
    )

    fun sessionDir(filesDir: File, sessionId: String, subdir: String): File =
        File(filesDir, "minis-sessions/$sessionId/$subdir")

    /**
     * Mounts for a shell. [sessionId] null means a shell tied to no chat (the
     * global terminal): it gets the global dirs and external mounts only, so
     * `/var/minis/workspace` there is the rootfs placeholder rather than some
     * other session's workspace.
     *
     * All directories are prepared and checked first and the map is built
     * after, so a failure report names every unusable directory at once.
     */
    fun build(
        filesDir: File,
        sessionId: String?,
        externalMounts: List<Pair<String, String>> = emptyList(),
    ): Built {
        val candidates = linkedMapOf<String, File>()
        if (sessionId != null) {
            for (sub in SESSION_SUBDIRS) candidates["/var/minis/$sub"] = sessionDir(filesDir, sessionId, sub)
        }
        val globalBase = File(filesDir, "minis-global")
        for (sub in GLOBAL_SUBDIRS) candidates["/var/minis/$sub"] = File(globalBase, sub)

        val mounts = linkedMapOf<String, String>()
        val skipped = mutableListOf<String>()
        for ((linux, dir) in candidates) {
            if (ensureDir(dir)) mounts[linux] = dir.absolutePath else skipped += linux
        }
        // External (SAF-picked) folders are the user's, not ours to create,
        // and are passed through as before: under scoped storage a Java
        // `isDirectory` can say false for a tree the process can still reach,
        // so dropping on that answer would take working mounts away. Only log.
        for ((linux, host) in externalMounts) {
            mounts[linux] = host
            if (!File(host).isDirectory) Log.w(TAG, "external mount $linux -> $host is not visible as a directory")
        }
        if (skipped.isNotEmpty()) {
            Log.e(
                TAG,
                "session=${sessionId ?: "<global>"} skipped ${skipped.size} bind mount(s) whose host dir " +
                    "is missing or cannot be created: $skipped — the guest will see the rootfs placeholder there",
            )
        }
        return Built(mounts, skipped)
    }

    /** [build] with the app's files dir and the current external mounts. */
    fun forContext(context: Context, sessionId: String?): Built =
        build(context.filesDir, sessionId, externalMountsSnapshot())

    /** `-b host:linux` pairs for [mounts], with a soft size warning. */
    fun toProotArgs(mounts: Map<String, String>): List<String> {
        val args = ArrayList<String>(mounts.size * 2)
        var bytes = 0
        for ((linux, host) in mounts) {
            val spec = "$host:$linux"
            args += "-b"
            args += spec
            bytes += spec.length + 4 // "-b" + two NULs
        }
        if (bytes > ARGV_WARN_BYTES) {
            Log.w(TAG, "bind-mount argv is $bytes bytes (> $ARGV_WARN_BYTES) across ${mounts.size} mounts")
        }
        return args
    }

    /**
     * True when [dir] is a usable directory after trying to create it.
     * `mkdirs()` alone is not the answer: it returns false both on failure
     * and when the directory already exists (e.g. a concurrent creator won).
     */
    internal fun ensureDir(dir: File): Boolean {
        if (dir.isDirectory) return true
        dir.mkdirs()
        return dir.isDirectory
    }

    private fun externalMountsSnapshot(): List<Pair<String, String>> =
        PRootKernel.mountedFoldersStore?.entries?.value.orEmpty().mapNotNull { entry ->
            val host = entry.resolvedHostPath ?: return@mapNotNull null
            "/var/minis/mounts/${entry.name}" to host
        }
}
