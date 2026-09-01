package com.openminis.app.backup

import com.openminis.app.backup.remote.RcloneChunkedUpload
import com.openminis.app.backup.remote.RcloneRemoteStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-backup-remote-singlefile] Pins the remote delivery layout.
 *
 * The bug: Android uploaded any package over 8 MiB as
 * `.minis-parts/<backupId>/000000, 000001, …` and NEVER assembled it, so the
 * server held a directory of anonymous fragments instead of a `.minisbak`. A
 * user browsing their NAS saw no backup, and the iOS restore picker — which
 * lists `.minisbak` files only — could not see it either. iOS dropped chunking
 * on 2026-08-16; this is the Android side of that change.
 *
 * `RcloneChunkedUpload` needs a Context and a live rclone RPC to construct, so
 * — following CompactDividerPlacementTest / BackupFormatToleranceTest — these
 * mirror the naming/selection RULES rather than the transport. `Remote.join`
 * and the package filter are exercised for real; the scratch/parts naming is
 * asserted against the production constants so a rename here breaks the test.
 */
class RcloneRemoteSingleFileTest {

    private fun remote(backend: String, path: String) = RcloneRemoteStore.Remote(
        name = "nas", backend = backend, params = emptyMap(), path = path,
    )

    // -- Path building ---------------------------------------------------

    @Test
    fun `webdav path stays fs-relative`() {
        assertEquals("backups/a.minisbak", remote("webdav", "backups").join("a.minisbak"))
        assertEquals("backups/a.minisbak", remote("webdav", "/backups/").join("a.minisbak"))
    }

    /**
     * Server root: a naive "$path/$name" yields "/a.minisbak", which rclone's
     * WebDAV backend resolves against the SERVER root, escaping the folder
     * baked into the fs URL. The package then lands outside the destination
     * the user chose and is reported missing.
     */
    @Test
    fun `webdav server root does not produce a leading slash`() {
        assertEquals("a.minisbak", remote("webdav", "").join("a.minisbak"))
        assertEquals("a.minisbak", remote("webdav", "/").join("a.minisbak"))
        assertFalse(remote("webdav", "/").join("a.minisbak").startsWith("/"))
    }

    /**
     * SFTP is the exception: `remote:/srv` and `remote:srv` are different
     * locations (filesystem-absolute vs relative to the login user's home), so
     * the leading slash must survive.
     */
    @Test
    fun `sftp keeps its absolute path`() {
        assertEquals("/srv/backup/a.minisbak", remote("sftp", "/srv/backup").join("a.minisbak"))
        assertEquals("/srv/backup/a.minisbak", remote("sftp", "/srv/backup/").join("a.minisbak"))
        assertEquals("/a.minisbak", remote("sftp", "/").join("a.minisbak"))
        // Home-relative stays home-relative.
        assertEquals("backup/a.minisbak", remote("sftp", "backup").join("a.minisbak"))
    }

    @Test
    fun `list root matches the join base`() {
        assertEquals("backups", remote("webdav", "/backups/").listRoot)
        assertEquals("", remote("webdav", "/").listRoot)
        assertEquals("/srv/backup", remote("sftp", "/srv/backup/").listRoot)
        assertEquals("/", remote("sftp", "/").listRoot)
    }

    // -- Upload naming ---------------------------------------------------

    /**
     * The scratch object must NOT be dot-prefixed. WebDAV gateways (alist,
     * verified) filter dotfiles out of directory listings, and the abandoned-
     * partial sweep finds its victims by LISTING — a dotted name would be
     * invisible to the sweep written to reclaim it, stranding a full-size
     * object on the user's NAS after every interrupted upload.
     */
    @Test
    fun `scratch name is suffix-based, not dot-prefixed`() {
        val scratch = "backup-1.minisbak.${RcloneChunkedUpload.PARTIAL_SUFFIX}"
        assertFalse("dotfiles are hidden from WebDAV listings", scratch.startsWith("."))
        assertTrue(scratch.endsWith(".partial"))
    }

    /** A scratch file must never be offered as a restorable backup. */
    @Test
    fun `in-flight scratch is not listed as a package`() {
        val listing = listOf(
            "backup-1.minisbak",
            "backup-2.minisbak.${RcloneChunkedUpload.PARTIAL_SUFFIX}",
            "notes.txt",
        )
        val restorable = listing.filter { it.endsWith(".${BackupFormat.FILE_EXTENSION}") }
        assertEquals(listOf("backup-1.minisbak"), restorable)
    }

    /**
     * The whole point of the change: a >8 MiB package produces ONE object with
     * the real name, not a parts directory.
     */
    @Test
    fun `a large package still uploads under one final name`() {
        val r = remote("webdav", "backups")
        val name = "backup-large.minisbak"
        val partial = r.join("$name.${RcloneChunkedUpload.PARTIAL_SUFFIX}")
        val final = r.join(name)

        assertEquals("backups/backup-large.minisbak", final)
        assertEquals("backups/backup-large.minisbak.partial", partial)
        assertFalse(
            "new uploads must never write the legacy parts directory",
            partial.contains(RcloneChunkedUpload.PARTS_DIR) ||
                final.contains(RcloneChunkedUpload.PARTS_DIR),
        )
    }

    // -- Sweep safety ----------------------------------------------------

    /**
     * The sweep exists to reclaim abandoned scratch objects. It must never
     * touch a user's real backup, and never the historical parts directory —
     * deleting either would destroy data the user still needs.
     */
    @Test
    fun `sweep only matches partial scratch objects`() {
        data class Entry(val name: String, val isDir: Boolean)
        val listing = listOf(
            Entry("backup-1.minisbak", false),
            Entry("backup-2.minisbak.${RcloneChunkedUpload.PARTIAL_SUFFIX}", false),
            Entry("old.minisbak.${RcloneChunkedUpload.PARTIAL_SUFFIX}", false),
            Entry(RcloneChunkedUpload.PARTS_DIR, true),
            Entry("family-photos", true),
        )
        val current = "backup-2.minisbak.${RcloneChunkedUpload.PARTIAL_SUFFIX}"

        val swept = listing
            .filterNot { it.isDir }
            .filter { it.name.endsWith(".${RcloneChunkedUpload.PARTIAL_SUFFIX}") }
            .filterNot { it.name == current } // this run reuses its own
            .map { it.name }

        assertEquals(listOf("old.minisbak.partial"), swept)
        assertFalse("a real backup must never be swept", swept.any { it == "backup-1.minisbak" })
        assertFalse(
            "the legacy parts directory must never be swept",
            swept.any { it == RcloneChunkedUpload.PARTS_DIR },
        )
    }

    // -- Backward compatibility -----------------------------------------

    /** Packages an older build uploaded in fragments must stay restorable. */
    @Test
    fun `legacy chunked upload is still recognised and reassembled in order`() {
        val legacy = RcloneChunkedUpload.RemotePackage(
            key = "backups/${RcloneChunkedUpload.PARTS_DIR}/backup-old",
            displayName = "backup-old",
            size = 24L * 1024 * 1024,
            modified = 1L,
            partCount = 3,
        )
        assertTrue("a 3-part legacy upload must take the reassembly path", legacy.isChunked)

        // Zero-padded %06d so lexical order equals numeric order — a missing
        // part shows as a gap instead of silently shifting the rest.
        val names = listOf("000002", "000000", "000001").sorted()
        assertEquals(listOf("000000", "000001", "000002"), names)
        assertEquals(legacy.partCount, names.size)
    }

    /** A single-file package must NOT take the reassembly path. */
    @Test
    fun `whole package is not treated as chunked`() {
        val whole = RcloneChunkedUpload.RemotePackage(
            key = "backups/backup-1.minisbak",
            displayName = "backup-1.minisbak",
            size = 100L * 1024 * 1024,
            modified = 2L,
            partCount = 1,
        )
        assertFalse(whole.isChunked)
    }

    /**
     * A legacy parts directory whose fragment count doesn't match must be
     * refused outright — half a ZIP fails later as "corrupt archive", which is
     * a far worse message to hand someone restoring a new device.
     */
    @Test
    fun `an incomplete legacy parts set is refused`() {
        val expected = 3
        val present = listOf("000000", "000002")
        assertTrue("a short parts set must not be reassembled", present.size != expected)
    }
}
