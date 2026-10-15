package com.openminis.app.sandbox

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * [T-android-workspaces-provider-root] Sessions filed into a project folder
 * move their workspace/attachments/offloads/browser trees to
 * `filesDir/minis-workspaces/<folderId>/`. When that layout landed, the
 * FileProvider roots were not extended, so `FileProvider.getUriForFile` threw
 * "Failed to find configured root" for every agent-generated file in a filed
 * session — share/export from the file preview was dead for them (three
 * "share failed for aii_v1.1.0.zip" WARNs in one user session).
 *
 * This test parses the REAL res/xml/file_provider_paths.xml (not a fixture)
 * and pins one provider root per SessionWorkspace directory constant, so a
 * future layout change cannot silently drop one again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class) // [T-robolectric-bare-app] MinisApp.onCreate pulls RootfsManager; tests don't need it.
class FileProviderPathsCoverageTest {

    private lateinit var roots: Map<String, String> // name -> path attr

    @Before
    fun parseProviderPaths() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val parser = context.resources.getXml(
            context.resources.getIdentifier("file_provider_paths", "xml", context.packageName),
        )
        roots = buildMap {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name != "paths") {
                    val name = parser.getAttributeValue(null, "name")
                    val path = parser.getAttributeValue(null, "path")
                    if (name != null) put("${parser.name}:$name", path ?: "")
                }
                event = parser.next()
            }
        }
    }

    private fun assertFilesRoot(dirName: String) {
        val hit = roots.entries.firstOrNull { it.key.startsWith("files-path:") && it.value == "$dirName/" }
        assertTrue(
            "file_provider_paths.xml has no <files-path> for $dirName/ — files under it cannot be shared. roots=$roots",
            hit != null,
        )
    }

    @Test fun sessionsDirHasProviderRoot() = assertFilesRoot(SessionWorkspace.SESSIONS_DIR)

    @Test fun workspacesDirHasProviderRoot() = assertFilesRoot(SessionWorkspace.WORKSPACES_DIR)

    @Test fun globalDirHasProviderRoot() = assertFilesRoot(SessionWorkspace.GLOBAL_DIR)

    @Test fun everyRootPathIsSlashTerminated() {
        // FileProvider matches by prefix; a root written without the trailing
        // slash (path="minis-sessions") would still match, but the project
        // convention is slash-terminated — keep the file uniform.
        roots.values.filter { it.isNotEmpty() }.forEach {
            assertTrue("root path '$it' should end with '/'", it.endsWith("/"))
        }
    }

    @Test fun parsesAtLeastOneRoot() {
        // Guards against the xml identifier lookup silently resolving to
        // nothing, which would make the three root assertions vacuous.
        assertTrue("file_provider_paths.xml parsed to zero roots", roots.isNotEmpty())
    }
}
