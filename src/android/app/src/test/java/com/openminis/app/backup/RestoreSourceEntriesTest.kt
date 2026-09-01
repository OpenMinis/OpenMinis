package com.openminis.app.backup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the restore screen offers as a source, and where the Server row goes
 * when nothing is configured yet.
 *
 * Both rules below are decisions about a Compose tree, and the behaviour a
 * user sees is one `onClick` lambda and one absent row — there is no seam to
 * drive them through without an instrumentation host. Rather than skip the
 * coverage or stand up Robolectric for two facts, this asserts against the
 * source itself: the rows are declared literally, so their presence and the
 * branch in the Server row's onClick are readable and pin exactly the thing a
 * future edit would undo.
 *
 * The weakness is honest — this is a structural test, not a behavioural one.
 * It cannot catch a row that renders but is invisible. It CAN catch the two
 * regressions that actually happened here (a duplicate source entry coming
 * back, and the empty-state dialog returning as a dead end), which is what it
 * exists for.
 */
class RestoreSourceEntriesTest {

    private val screen: String by lazy {
        val f = File("src/main/java/com/openminis/app/ui/settings/backup/BackupAndRestoreScreen.kt")
        assertTrue("expected to find ${f.path} relative to the app module", f.isFile)
        f.readText()
    }

    /** Body of `RestoreTab`, so Backup-tab rows cannot satisfy these checks. */
    private val restoreTab: String by lazy {
        val start = screen.indexOf("private fun RestoreTab(")
        assertTrue("RestoreTab not found", start >= 0)
        // Up to the next top-level composable declaration.
        val end = screen.indexOf("\n@Composable", start + 1).let { if (it < 0) screen.length else it }
        screen.substring(start, end)
    }

    @Test
    fun `restore offers exactly two sources — local file and server`() {
        assertTrue(
            "the local-file source row should still exist",
            restoreTab.contains("R.string.backup_choose_file"),
        )
        assertTrue(
            "the server source row should still exist",
            restoreTab.contains("R.string.backup_choose_server"),
        )
    }

    @Test
    fun `the shared-folders source entry is gone`() {
        // The system document picker behind "Choose Backup File…" already
        // reaches mounted shared folders, so a second narrower entry was pure
        // redundancy. iOS never had it.
        assertFalse(
            "the Shared Folders restore source should not come back",
            screen.contains("backup_choose_shared"),
        )
        assertFalse(
            "SharedFoldersDialog should not come back",
            screen.contains("SharedFoldersDialog"),
        )
    }

    @Test
    fun `its strings are gone from every locale`() {
        val dead = listOf("backup_choose_shared", "backup_shared_title", "backup_shared_empty")
        for (locale in listOf("values", "values-zh", "values-es")) {
            val f = File("src/main/res/$locale/strings.xml")
            assertTrue("missing $locale/strings.xml", f.isFile)
            val xml = f.readText()
            for (key in dead) {
                assertFalse("$locale still declares $key", xml.contains("\"$key\""))
            }
        }
    }

    @Test
    fun `the server row always opens the restore-server list, with no empty-case branch`() {
        // The regression this guards, in BOTH directions:
        //  - the old dead end: onClick opened a dialog whose entire content
        //    was "no servers configured" over a lone Cancel;
        //  - the interim fix: branching on `destinations.isEmpty()` to jump
        //    straight into the Add form, which made the empty state a special
        //    case again and skipped the list a configured user still needs.
        // One unconditional destination is the property worth pinning.
        val rowStart = restoreTab.indexOf("R.string.backup_choose_server")
        assertTrue("server row not found in RestoreTab", rowStart >= 0)
        val rowEnd = restoreTab.indexOf("showDivider", rowStart)
        assertTrue("could not find the end of the server row", rowEnd > rowStart)
        val body = restoreTab.substring(rowStart, rowEnd)

        assertTrue(
            "the server row must go to the restore-server list",
            body.contains("onClick = onChooseRestoreServer"),
        )
        assertFalse(
            "the empty state must not be a special case again",
            body.contains("destinations.isEmpty()"),
        )
    }

    @Test
    fun `the dead-end server dialog is gone`() {
        assertFalse(
            "ServerRestoreDialog should not come back",
            screen.contains("ServerRestoreDialog"),
        )
        assertFalse(
            "its empty-state string should no longer be rendered",
            screen.contains("backup_server_empty"),
        )
    }

    @Test
    fun `the restore-server list always offers Add Server, in both states`() {
        val list = File(
            "src/main/java/com/openminis/app/ui/settings/backup/RestoreServersScreen.kt",
        ).readText()
        // The Add row is declared OUTSIDE the remotes loop, so it renders
        // whether or not any server exists — that is what stops "none
        // configured" from being a dead end.
        val loopEnd = list.indexOf("remotes.forEach")
        assertTrue("the servers loop should exist", loopEnd >= 0)
        val addIdx = list.indexOf("R.string.backup_dest_add_server")
        assertTrue("an Add Server row should exist", addIdx > loopEnd)
        assertTrue(
            "picking a server should browse it",
            list.contains("onClick = { onPickServer(r.name) }"),
        )
    }

    @Test
    fun `the Add Server form is reused, not reimplemented`() {
        val list = File(
            "src/main/java/com/openminis/app/ui/settings/backup/RestoreServersScreen.kt",
        ).readText()
        // Hosting the shared composable is fine; declaring a second one is not.
        assertTrue(
            // Matched on the call, not a one-line spelling of it: the argument
            // list has grown (pickFolder / onSaved) and will grow again.
            "the list should host the shared AddServerForm",
            list.contains("AddServerForm("),
        )
        assertFalse(
            "it must not declare its own form",
            list.contains("fun AddServerForm("),
        )
        assertEquals(
            "there should be exactly one AddServerForm in the app",
            1,
            Regex("""fun AddServerForm\(""")
                .findAll(
                    File(
                        "src/main/java/com/openminis/app/ui/settings/backup/RcloneDestinationsScreen.kt",
                    ).readText(),
                ).count(),
        )
    }

    @Test
    fun `cancelling the add form returns to the list, not out of the screen`() {
        val list = File(
            "src/main/java/com/openminis/app/ui/settings/backup/RestoreServersScreen.kt",
        ).readText()
        assertTrue(
            "cancel should return to the list",
            list.contains("onCancel = { adding = false }"),
        )
    }

    @Test
    fun `the abandoned direct-to-form entry point is gone`() {
        val dest = File(
            "src/main/java/com/openminis/app/ui/settings/backup/RcloneDestinationsScreen.kt",
        ).readText()
        val nav = File(
            "src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt",
        ).readText()
        for (marker in listOf("startOnAddForm", "BACKUP_DESTINATIONS_ADD")) {
            assertFalse("$marker should have been withdrawn", dest.contains(marker))
            assertFalse("$marker should have been withdrawn", nav.contains(marker))
        }
    }
}
