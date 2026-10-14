package com.openminis.app.sandbox

import com.openminis.app.logging.AppLogger
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Host layout for chat workspaces.
 *
 * Per session (`filesDir/minis-sessions/<id>/`):
 *   memory  — daily logs, deleted with the session
 *
 * Per project folder (`filesDir/minis-workspaces/<folderId>/`), shared by every
 * session filed in that folder:
 *   attachments, offloads, workspace, browser
 *
 * Ungrouped sessions keep the 1.36.13 layout: those four dirs live under
 * `minis-sessions/<id>/` so isolation is preserved until they are filed.
 *
 * Shared across sessions (`filesDir/minis-global/`):
 *   skills, shared, mcp-servers
 *
 * SOUL.md / LEARNED.md stay under minis-global/memory (not bind-mounted as
 * `/var/minis/memory`). `/var/minis/memory` is this chat's daily logs.
 */
// Cache-bust marker: the previous CI run compiled a stale source tree where
// File.isDirectory was still written as a property. This comment exists so the
// next run's source fingerprint differs from the cached one.
object SessionWorkspace {
    const val SESSIONS_DIR = "minis-sessions"
    const val WORKSPACES_DIR = "minis-workspaces"
    const val GLOBAL_DIR = "minis-global"

    val SHARED_SUBDIRS: List<String> = listOf(
        "attachments",
        "offloads",
        "workspace",
        "browser",
    )

    val SESSION_SUBDIRS: List<String> = SHARED_SUBDIRS + "memory"

    val GLOBAL_BIND_SUBDIRS: List<String> = listOf(
        "skills",
        "shared",
        "mcp-servers",
    )

    private val folderIds = ConcurrentHashMap<String, String>()

    /**
     * [T-android-startup-splash-hang] Released once this process has loaded the
     * folder map from the database (or tried and failed — see
     * `ChatRepository.warmupWorkspaceOwners`, which completes it in a `finally`).
     *
     * The warmup used to be a `runBlocking(Dispatchers.IO)` on the **main
     * thread** inside `Application.onCreate`, justified by "must finish before
     * the first file tool or shell". That made the first Room open, any pending
     * schema migrations, and a full hydration of the sessions table all happen
     * before `subsystemsInitialized` was set and before `MainActivity` even
     * existed — so the system splash (a white `windowBackground`) stayed up for
     * as long as the user's history took to load, and past ~5s it was an ANR.
     * Cost scaled with session count, which is why only *some* users saw it.
     *
     * The warmup now runs on `Dispatchers.IO` like everything else at startup,
     * and the ordering guarantee is this gate instead of a blocked UI thread:
     * the launch resolver awaits it before mounting any destination, so no chat
     * screen — and therefore no shell or file tool — can exist before the map
     * is populated.
     */
    private val warmupGate = kotlinx.coroutines.CompletableDeferred<Unit>()

    /** Idempotent. Safe to call from a `finally`, and safe to call twice. */
    fun completeWarmup() {
        warmupGate.complete(Unit)
    }

    /**
     * Wait for the folder map, bounded. The timeout is a safety valve, not the
     * normal path: the warmup is one indexed two-column query, so it completes
     * in milliseconds. A stuck warmup must degrade to "session looks unfiled"
     * rather than hang the launcher forever.
     */
    suspend fun awaitWarmup(timeoutMillis: Long = 3_000L) {
        kotlinx.coroutines.withTimeoutOrNull(timeoutMillis) { warmupGate.await() }
    }

    fun rememberFolder(sessionId: String, folderId: String?) {
        if (!isSafeId(sessionId)) return
        if (folderId.isNullOrBlank()) folderIds.remove(sessionId)
        else if (isSafeId(folderId)) folderIds[sessionId] = folderId
    }

    fun folderIdFor(sessionId: String): String? {
        parseDraftFolderId(sessionId)?.let { return it }
        val owner = ownerSessionId(sessionId)
        return folderIds[owner]
    }

    fun parseDraftFolderId(sessionId: String): String? {
        val i = sessionId.indexOf("__fld__")
        if (i < 0) return null
        val rest = sessionId.substring(i + 7)
        val end = rest.indexOf("__grp__")
        val id = if (end >= 0) rest.substring(0, end) else rest
        return id.takeIf { isSafeId(it) }
    }

    fun isSafeId(id: String): Boolean {
        if (id.isBlank() || id == "." || id == "..") return false
        if (id.contains('/') || id.contains('\\')) return false
        return true
    }

    fun base(filesDir: File, sessionId: String): File =
        File(filesDir, "$SESSIONS_DIR/$sessionId")

    fun projectBase(filesDir: File, folderId: String): File =
        File(filesDir, "$WORKSPACES_DIR/$folderId")

    fun memoryDir(filesDir: File, sessionId: String): File =
        File(base(filesDir, ownerSessionId(sessionId)), "memory")

    /**
     * Host directory mounted at `/var/minis/[subdir]`. Shared project dirs when
     * the session is filed in a folder; otherwise per-session.
     *
     * A filed session whose project directory is MISSING falls back to its own
     * private dir. Without this, the mount resolves to a path that does not
     * exist and the sandbox comes up with an empty `/var/minis` — which reads
     * to the user as "my files vanished", when in fact nothing was ever moved
     * (an interrupted [WorkspaceMover] run, or a project dir deleted behind
     * our back). The private copy is still the authoritative one in that case,
     * so preferring it is also the non-destructive choice. The fallback is
     * logged at warning level because it means the recorded layout and the
     * on-disk layout disagree.
     */
    fun hostDir(filesDir: File, sessionId: String, subdir: String): File {
        val folderId = folderIdFor(sessionId)
        if (folderId != null && subdir in SHARED_SUBDIRS) {
            val projectRoot = projectBase(filesDir, folderId)
            val shared = File(projectRoot, subdir)
            if (hasEntries(shared)) return shared
            val privateDir = File(base(filesDir, ownerSessionId(sessionId)), subdir)
            if (hasEntries(privateDir)) {
                AppLogger.warning(
                    "SessionWorkspace",
                    "project subdir '$subdir' empty for folder=$folderId " +
                        "session=$sessionId — using private copy",
                )
                return privateDir
            }
            if (shared.isDirectory()) return shared
            if (projectRoot.isDirectory()) {
                AppLogger.warning(
                    "SessionWorkspace",
                    "project dir missing subdir '$subdir' for folder=$folderId " +
                        "session=$sessionId — falling back to the private copy",
                )
            }
        }
        return File(base(filesDir, ownerSessionId(sessionId)), subdir)
    }

    private fun hasEntries(dir: File): Boolean =
        dir.isDirectory && dir.listFiles()?.isNotEmpty() == true

    /**
     * Resolve a `/var/minis/<subdir>/...` path to the same host file the shell
     * bind uses. Returns null for `..` or paths this layout does not own.
     */
    fun resolveGuestPath(filesDir: File, sessionId: String, linuxPath: String): File? {
        if (!linuxPath.startsWith("/var/minis/")) return null
        val rest = linuxPath.removePrefix("/var/minis/")
        if (rest.isEmpty()) return null
        val parts = rest.split('/')
        if (parts.any { it == ".." }) return null
        val subdir = parts[0]
        if (subdir.isEmpty()) return null
        val root = when (subdir) {
            in SESSION_SUBDIRS -> hostDir(filesDir, sessionId, subdir)
            in GLOBAL_BIND_SUBDIRS -> File(File(filesDir, GLOBAL_DIR), subdir)
            else -> return null
        }
        val tail = parts.drop(1).filter { it.isNotEmpty() }.joinToString("/")
        return if (tail.isEmpty()) root else File(root, tail)
    }

    fun ensureProjectDirs(filesDir: File, folderId: String) {
        if (!isSafeId(folderId)) return
        val root = projectBase(filesDir, folderId)
        for (subdir in SHARED_SUBDIRS) {
            File(root, subdir).mkdirs()
        }
    }

    fun ensureDirs(filesDir: File, sessionId: String) {
        val root = base(filesDir, sessionId)
        File(root, "memory").mkdirs()
        val folderId = folderIdFor(sessionId)
        if (folderId != null) {
            ensureProjectDirs(filesDir, folderId)
        } else {
            for (subdir in SHARED_SUBDIRS) {
                File(root, subdir).mkdirs()
            }
        }
    }

    /** Delete the whole session tree, including memory. Does not touch a shared project. */
    fun deleteEntire(filesDir: File, sessionId: String): Boolean {
        if (!isSafeId(sessionId)) return false
        folderIds.remove(sessionId)
        val dir = base(filesDir, sessionId)
        if (!dir.exists()) return true
        return dir.deleteRecursively()
    }

    fun deleteProject(filesDir: File, folderId: String): Boolean {
        if (!isSafeId(folderId)) return false
        val dir = projectBase(filesDir, folderId)
        if (!dir.exists()) return true
        return dir.deleteRecursively()
    }

    fun ownerSessionId(sessionId: String): String {
        val prefix = "subagent:"
        if (!sessionId.startsWith(prefix)) return sessionId
        val rest = sessionId.substring(prefix.length)
        val cut = rest.lastIndexOf(':')
        return if (cut > 0) rest.substring(0, cut) else sessionId
    }

    /** True when [candidate] does not climb out of [root] via `..`. */
    fun staysInside(root: File, candidate: File): Boolean {
        val base = normalized(root).path.trimEnd('\\', '/')
        val file = normalized(candidate).path.trimEnd('\\', '/')
        return file == base || file.startsWith(base + File.separator)
    }

    private fun normalized(file: File): File =
        try {
            file.canonicalFile
        } catch (_: Exception) {
            lexicalNormalize(file)
        }

    /**
     * File tools may read the caller's private tree, the caller's project
     * tree, rootfs, and global skills. They must not land in another
     * session's `minis-sessions/<other>` or another project's
     * `minis-workspaces/<other>`.
     */
    fun acceptsResolved(filesDir: File, sessionId: String, candidate: File): Boolean {
        val owner = ownerSessionId(sessionId)
        if (!isSafeId(owner)) return false
        // [T-branch-uses-canonical-view] The branch below is chosen from the
        // same CANONICAL view that staysInside() later enforces — not the
        // lexical one. `lexicalNormalize` collapses `..`/`.` but does not
        // resolve symlinks, so a candidate that lexically reads as inside the
        // sessions tree but canonically escapes (or vice-versa) used to enter
        // the wrong branch: the branch gate was lexical, the containment gate
        // canonical, and they could disagree about which tree owns the path.
        val canon = normalized(candidate)
        val sessions = normalized(File(filesDir, SESSIONS_DIR))
        val projects = normalized(File(filesDir, WORKSPACES_DIR))
        if (staysInside(sessions, canon)) {
            return staysInside(File(sessions, owner), canon)
        }
        if (staysInside(projects, canon)) {
            val folder = folderIdFor(sessionId) ?: return false
            if (!isSafeId(folder)) return false
            return staysInside(File(projects, folder), canon)
        }
        // Documented default, not an oversight: a path that lives OUTSIDE both
        // the per-session (minis-sessions) and per-project (minis-workspaces)
        // trees is admitted. The jail only fences those two trees against each
        // other — the rootfs, the global skills/shared/mcp-servers dirs, and a
        // user SAF mount are legitimately reachable and must resolve. Owner vs
        // project semantics above are unchanged.
        return true
    }

    internal fun lexicalNormalize(file: File): File {
        val abs = file.absolutePath
        val prefix: String
        val rest: String
        when {
            abs.length >= 2 && abs[1] == ':' -> {
                prefix = abs.substring(0, 2)
                rest = abs.substring(2)
            }
            abs.startsWith("\\\\") -> {
                prefix = "\\\\"
                rest = abs.removePrefix("\\\\")
            }
            abs.startsWith("/") || abs.startsWith("\\") -> {
                prefix = File.separator
                rest = abs.trimStart('/', '\\')
            }
            else -> {
                prefix = ""
                rest = abs
            }
        }
        val parts = ArrayDeque<String>()
        for (seg in rest.split('/', '\\')) {
            when {
                seg.isEmpty() || seg == "." -> Unit
                seg == ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(seg)
            }
        }
        val body = parts.joinToString(File.separator)
        val path = when {
            prefix.endsWith(":") -> prefix + File.separator + body
            prefix == File.separator -> File.separator + body
            prefix == "\\\\" -> "\\\\$body"
            else -> body
        }
        return File(path)
    }
}
