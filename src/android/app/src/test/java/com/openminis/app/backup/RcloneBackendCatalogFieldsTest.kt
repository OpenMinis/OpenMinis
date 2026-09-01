package com.openminis.app.backup

import com.openminis.app.backup.remote.RcloneBackendCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which destination fields the form asks for, and which it insists on.
 *
 * The shape here is a product decision that came out of real failures, not a
 * mirror of rclone's config schema — see the comments in the catalog — so it
 * is pinned against accidental "let's just expose what rclone takes" drift.
 */
class RcloneBackendCatalogFieldsTest {

    private fun fields(type: String) =
        RcloneBackendCatalog.backend(type)?.fields ?: error("no backend $type")

    private fun key(type: String, key: String) = fields(type).firstOrNull { it.key == key }

    /**
     * SMB is configured share-less: the share is the folder browser's first
     * level. Asking for it as text put a flat network name in front of users
     * whose NAS shows a disk path, and SMB reports a missing share as a LOGON
     * error — so the app blamed perfectly good credentials.
     */
    @Test
    fun `smb does not ask for a share`() {
        assertFalse(
            "the Share field must not come back",
            fields("smb").any { it.key == "share" },
        )
    }

    @Test
    fun `smb credentials are optional for guest shares`() {
        assertTrue("smb user must be optional", key("smb", "user")!!.isOptional)
        assertTrue("smb pass must be optional", key("smb", "pass")!!.isOptional)
    }

    @Test
    fun `webdav and ftp credentials are optional for anonymous servers`() {
        for (type in listOf("webdav", "ftp")) {
            assertTrue("$type user must be optional", key(type, "user")!!.isOptional)
            assertTrue("$type pass must be optional", key(type, "pass")!!.isOptional)
        }
    }

    /**
     * The task's explicit boundary: these effectively always need credentials,
     * so relaxing them would only produce configs that cannot connect.
     */
    @Test
    fun `sftp and s3 keep their credentials required`() {
        assertFalse("sftp user stays required", key("sftp", "user")!!.isOptional)
        assertFalse("sftp pass stays required", key("sftp", "pass")!!.isOptional)
        assertFalse("s3 access key stays required", key("s3", "access_key_id")!!.isOptional)
        assertFalse("s3 secret stays required", key("s3", "secret_access_key")!!.isOptional)
    }

    @Test
    fun `every backend still has its connection target field`() {
        assertNotNull(key("smb", "host"))
        assertNotNull(key("webdav", "url"))
        assertNotNull(key("sftp", "host"))
        assertNotNull(key("s3", "endpoint"))
        assertNotNull(key("ftp", "host"))
    }

    /** The secret field the store looks up must exist and be marked secret. */
    @Test
    fun `each backend exposes exactly one secret field`() {
        for (type in listOf("smb", "webdav", "sftp", "s3", "ftp")) {
            val secrets = fields(type).filter { it.isSecret }
            assertEquals("$type should have one secret field", 1, secrets.size)
        }
        assertEquals("secret_access_key", fields("s3").first { it.isSecret }.key)
    }
}
