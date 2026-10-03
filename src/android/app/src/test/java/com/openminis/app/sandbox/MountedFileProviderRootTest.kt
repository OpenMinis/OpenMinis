package com.openminis.app.sandbox

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * [T-android-mount-fileprovider-root] A file inside a mounted folder can be
 * shared and opened with another app.
 *
 * Reported (2026-09-24, with log + screen recording): an .xlsx the agent wrote
 * into a mounted folder showed in the file preview, but Share failed with
 * "Failed to find configured root that contains
 * /storage/emulated/0/agents/meituan/…xlsx", and "Open externally" said
 * "No app available to open this file." Both went through
 * FileProvider.getUriForFile, and no declared root covered shared storage —
 * mounts are picked with OpenDocumentTree (on-device only) and used by their
 * real /storage path.
 *
 * FileProvider needs a Context, so this reads the declared roots from the
 * resource and applies its matching rule (a file is covered when its path
 * lies under a root's directory). Only the storage roots are resolved;
 * files-/cache- paths live under the app's private dir and cannot match.
 */
class MountedFileProviderRootTest {

    private val xml = File("src/main/res/xml/file_provider_paths.xml")

    /** (tag, directory) for every root that resolves to an absolute device path. */
    private fun storageRoots(): List<Pair<String, String>> {
        assertTrue("missing ${xml.absolutePath}", xml.exists())
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
        val nodes = doc.documentElement.childNodes
        return (0 until nodes.length).mapNotNull { i ->
            val el = nodes.item(i) as? Element ?: return@mapNotNull null
            val path = el.getAttribute("path").trim('/')
            val base = when (el.tagName) {
                "root-path" -> ""
                // Environment.getExternalStorageDirectory() on every device we ship to.
                "external-path" -> "/storage/emulated/0"
                else -> return@mapNotNull null
            }
            el.tagName to (if (path.isEmpty()) base.ifEmpty { "/" } else "$base/$path")
        }
    }

    private fun covered(file: String) = storageRoots().any { (_, dir) ->
        dir == "/" || file == dir || file.startsWith("$dir/")
    }

    @Test
    fun `a file in a mounted folder on internal storage has a provider root`() {
        // The exact path from the report.
        assertTrue(covered("/storage/emulated/0/agents/meituan/益丰大药房_搜索页_商品清单.xlsx"))
    }

    @Test
    fun `a file in a mounted folder on an SD card has a provider root`() {
        // OpenDocumentTree also offers removable volumes; they mount at /storage/<UUID>.
        assertTrue(covered("/storage/1A2B-3C4D/Documents/report.pdf"))
    }

    @Test
    fun `the storage root is scoped to storage, not the whole filesystem`() {
        val roots = storageRoots()
        assertFalse("a root-path of \"/\" would expose every path on the device", roots.any { it.second == "/" })
        assertFalse(covered("/data/data/com.other.app/databases/x.db"))
        assertFalse(covered("/system/etc/hosts"))
    }

    @Test
    fun `open externally no longer reports every failure as a missing viewer app`() {
        val src = File("src/main/java/com/openminis/app/ui/sandbox/FilePreviewScreen.kt").readText()
        val body = src.substringAfter("private fun openExternally(").substringBefore("\n}\n")
        assertTrue(
            "only a real ActivityNotFoundException may say no app is available",
            body.contains("catch (e: android.content.ActivityNotFoundException)"),
        )
        assertTrue(
            "any other failure (e.g. getUriForFile) must say what went wrong",
            body.contains("R.string.file_open_failed_toast"),
        )
    }
}
