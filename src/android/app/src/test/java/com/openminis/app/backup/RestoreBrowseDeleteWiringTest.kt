package com.openminis.app.backup

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-restore-browse-swipe-delete] Swipe-to-delete in the restore browser.
 *
 * The browser walks subfolders, so a package's address is its full path
 * (`RemoteEntry.path`). The package-level delete used to rebuild the address
 * as `<remote root>/<name>`, which for a package in a subfolder points at a
 * file that does not exist — rclone's deletefile then fails, or worse, removes
 * a same-named package at the root.
 */
class RestoreBrowseDeleteWiringTest {

    private val uploader =
        File("src/main/java/com/openminis/app/backup/remote/RcloneChunkedUpload.kt").readText()
    private val screen =
        File("src/main/java/com/openminis/app/ui/settings/backup/RestoreBrowseScreen.kt").readText()
    private val vm =
        File("src/main/java/com/openminis/app/ui/settings/backup/BackupViewModel.kt").readText()

    @Test
    fun `a listed whole package is deleted by its key, not rebuilt from its name`() {
        val body = uploader.substringAfter("fun deletePackage(remote: RcloneRemoteStore.Remote, pkg: RemotePackage)")
            .substringBefore("operations/purge")
        assertTrue(body.contains("\"remote\" to pkg.key"))
        assertFalse(body.contains("deletePackage(remote, pkg.displayName)"))
    }

    @Test
    fun `the browser deletes the entry by its full path and re-lists the same folder`() {
        val body = vm.substringAfter("fun deleteBrowsedPackage(").substringBefore("fun clearBrowse()")
        assertTrue(body.contains("key = entry.path"))
        assertTrue(body.contains("browseDestination(remote, folder)"))
    }

    @Test
    fun `only packages are swipeable, and the swipe asks before deleting`() {
        assertTrue(screen.contains("actions = if (e.isDirectory) emptyList() else listOf("))
        assertTrue(screen.contains("onClick = { pendingDelete = e }"))
        assertTrue(screen.contains("vm.deleteBrowsedPackage(remote, e)"))
    }
}
