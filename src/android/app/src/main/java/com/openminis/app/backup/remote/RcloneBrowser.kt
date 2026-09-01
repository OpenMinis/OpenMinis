package com.openminis.app.backup.remote

import com.openminis.app.logging.AppLogger

/**
 * [T-android-rclone-browse] Directory browsing + folder creation for the
 * add-destination flow, over librclone's `operations` RPCs. Mirrors the
 * folder-picker half of iOS `RcloneAddServerView`.
 *
 * SFTP absolute paths (iOS fix T-sftp-absolute-path, 4712be53f): browsing is
 * driven by an explicit `remote` PATH string that the caller controls, and the
 * add-server UI offers a free-text absolute-path field seeded from the browser.
 * There is no forced "start at home" — an empty path lists whatever the server
 * resolves as the connection root, and typing `/etc` (or any absolute path)
 * lists it directly on a non-chrooted account, exactly as rclone supports. A
 * chrooted SFTP account still cannot escape its home; that is a server-side
 * boundary, not an app limitation.
 *
 * Every call blocks on network I/O — run off the main thread.
 */
object RcloneBrowser {

    private const val TAG = "Rclone"

    /**
     * One entry in a remote directory listing.
     *
     * [modified] is epoch millis, or null when the backend does not report a
     * usable `ModTime` — several do not for directories (S3 synthesises them,
     * some WebDAV servers omit them entirely). The picker shows nothing rather
     * than a fabricated date in that case.
     */
    data class Entry(
        val name: String,
        val path: String,
        val isDir: Boolean,
        val modified: Long? = null,
    )

    /**
     * Parse an rclone RFC3339 timestamp to epoch millis, tolerating the
     * varying sub-second precision different backends emit.
     */
    fun parseModTime(s: String?): Long? {
        if (s.isNullOrEmpty()) return null
        // rclone reports a zero time for "unknown"; showing 0001-01-01 would be
        // worse than showing nothing.
        if (s.startsWith("0001-01-01")) return null
        for (pattern in TIME_PATTERNS) {
            runCatching {
                val f = java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }
                return f.parse(s)?.time
            }
        }
        return null
    }

    private val TIME_PATTERNS = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSSXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
    )

    /**
     * List directories under [path] on [remote]. Files are omitted — the picker
     * only navigates folders. [path] "" means the remote's connection root.
     */
    fun listDirectories(remote: RcloneRemoteStore.Remote, path: String): List<Entry> {
        val resp = RcloneBridge.rpc(
            "operations/list",
            mapOf(
                "fs" to remote.fsSpec,
                "remote" to path,
                // dirsOnly keeps the payload small and the picker folder-only.
                "opt" to mapOf("dirsOnly" to true),
            ),
        )
        val arr = resp.optJSONArray("list") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (!o.optBoolean("IsDir")) return@mapNotNull null
            Entry(
                name = o.optString("Name"),
                path = o.optString("Path"),
                isDir = true,
                modified = parseModTime(o.optString("ModTime")),
            )
        }.sortedBy { it.name.lowercase() }
    }

    /**
     * Create [name] under [parentPath] on [remote]. Returns the new folder's
     * full path. rclone's mkdir is idempotent (no error if it already exists).
     */
    fun createFolder(remote: RcloneRemoteStore.Remote, parentPath: String, name: String): String {
        val clean = name.trim().trim('/')
        require(clean.isNotEmpty()) { "Folder name is empty." }
        val full = if (parentPath.isEmpty()) clean else "${parentPath.trimEnd('/')}/$clean"
        RcloneBridge.rpc("operations/mkdir", mapOf("fs" to remote.fsSpec, "remote" to full))
        AppLogger.info(TAG, "[Rclone] created folder: $full on ${remote.name}")
        return full
    }

    /**
     * Prove a freshly-entered config actually connects, by listing its root.
     * Throws RcloneBridge.RPCException with the server's reason on failure, so
     * the add-server form can surface "auth failed" / "host not found" before
     * the remote is saved.
     */
    fun testConnection(remote: RcloneRemoteStore.Remote) {
        RcloneBridge.rpc(
            "operations/list",
            mapOf("fs" to remote.fsSpec, "remote" to "", "opt" to mapOf("dirsOnly" to true)),
        )
    }
}
