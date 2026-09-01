package com.openminis.app.backup.remote

/**
 * The remote types offered in "Add Server", and the fields each one needs.
 * Mirrors `src/ios/Agent/Backup/Remote/RcloneBackendCatalog.swift` — same
 * types, same rclone parameter keys, same order, so a user who configures a
 * NAS on one platform recognises the form on the other.
 *
 * rclone accepts dozens of options per backend; asking for all of them would
 * make this unusable. Each entry lists only what is actually required to
 * connect, with everything else left at rclone's defaults — a user adding a
 * NAS should answer four questions, not forty.
 *
 * Adding a type here does NOT add the code for it: the backend must also be
 * imported in `deps/rclone-mobile/backends/backends.go` and the .aar rebuilt,
 * or rclone rejects the config at runtime with "didn't find backend called …".
 */
object RcloneBackendCatalog {

    data class Field(
        /** rclone parameter name. */
        val key: String,
        val label: String,
        val placeholder: String,
        /** Routed to the encrypted store instead of plain prefs. */
        val isSecret: Boolean = false,
        val isOptional: Boolean = false,
        val keyboard: KeyboardKind = KeyboardKind.DEFAULT,
    )

    enum class KeyboardKind { DEFAULT, URL, NUMERIC, EMAIL }

    data class Backend(
        /** rclone backend type — must match a directory under rclone's backend/. */
        val type: String,
        val title: String,
        val subtitle: String,
        val fields: List<Field>,
    )

    /**
     * Whether this backend treats a leading `/` in the destination path as a
     * FILESYSTEM-ABSOLUTE path that must be preserved. Mirrors
     * `RcloneBackendCatalog.usesAbsolutePaths` on iOS.
     *
     * Only SFTP. For sftp, `remote:` resolves to the login user's home and
     * `remote:/etc` is a genuinely different, valid location, so stripping the
     * slash silently rewrites such a path into a home-relative one.
     *
     * Every other backend here is URL/prefix-based, where a leading `/`
     * resolves against the SERVER root and escapes the folder baked into the
     * remote's fs spec — a package written to `http://host/backup-….minisbak`
     * instead of `…/backups/…`.
     */
    fun usesAbsolutePaths(backendType: String): Boolean = backendType == "sftp"

    /** Ordered by how likely someone backing up a phone is to want it. */
    val all: List<Backend> = listOf(
        Backend(
            type = "smb",
            title = "SMB / Windows Share",
            subtitle = "NAS, Windows shared folder, Samba",
            fields = listOf(
                Field("host", "Server", "192.168.1.10"),
                // No "Share" field on purpose. rclone takes the share as a
                // config option, and mirroring that put a flat network name
                // ("backups") in front of a user whose NAS displays a disk path
                // ("/volume1/backups") — they type the path, and SMB reports a
                // missing share as a LOGON failure, so the app blamed their
                // credentials. Connecting share-less instead makes the share
                // the browser's first level, which is what every desktop SMB
                // client does. See the `share` strip in RcloneRemoteStore.
                //
                // Optional credentials: guest shares are a NAS default
                // (Synology/QNAP ship one), and requiring them left Connect
                // permanently greyed out with nothing explaining why.
                Field("user", "Username", "admin", isOptional = true),
                Field("pass", "Password", "", isSecret = true, isOptional = true),
                Field("domain", "Domain", "WORKGROUP", isOptional = true),
            ),
        ),
        Backend(
            type = "webdav",
            title = "WebDAV",
            subtitle = "Nextcloud, ownCloud, Synology, alist",
            fields = listOf(
                Field("url", "URL", "https://example.com/dav", keyboard = KeyboardKind.URL),
                // Optional on purpose: anonymous WebDAV shares are ordinary
                // (a guest NAS folder, alist public dir, a LAN test server).
                // Requiring credentials left Connect permanently greyed out
                // with no way to proceed and nothing explaining why.
                Field("user", "Username", "", isOptional = true),
                Field("pass", "Password", "", isSecret = true, isOptional = true),
            ),
        ),
        Backend(
            type = "sftp",
            title = "SFTP",
            subtitle = "SSH file transfer",
            fields = listOf(
                Field("host", "Server", "example.com"),
                Field("user", "Username", ""),
                Field("pass", "Password", "", isSecret = true),
                Field("port", "Port", "22", isOptional = true, keyboard = KeyboardKind.NUMERIC),
            ),
        ),
        Backend(
            type = "s3",
            title = "S3-Compatible Storage",
            subtitle = "MinIO, Cloudflare R2, Wasabi, Alibaba OSS, Tencent COS…",
            fields = listOf(
                Field("endpoint", "Endpoint", "s3.example.com", keyboard = KeyboardKind.URL),
                Field("access_key_id", "Access Key ID", ""),
                Field("secret_access_key", "Secret Access Key", "", isSecret = true),
                Field("region", "Region", "us-east-1", isOptional = true),
            ),
        ),
        Backend(
            type = "ftp",
            title = "FTP",
            subtitle = "Plain or explicit TLS",
            fields = listOf(
                Field("host", "Server", "ftp.example.com"),
                // Anonymous FTP is the protocol's own convention; leaving these
                // blank means exactly that.
                Field("user", "Username", "", isOptional = true),
                Field("pass", "Password", "", isSecret = true, isOptional = true),
                Field("port", "Port", "21", isOptional = true, keyboard = KeyboardKind.NUMERIC),
            ),
        ),
    )

    fun backend(type: String): Backend? = all.firstOrNull { it.type == type }

    /** The field whose value belongs in the encrypted store, if any. */
    fun secretField(type: String): String? =
        backend(type)?.fields?.firstOrNull { it.isSecret }?.key
}
