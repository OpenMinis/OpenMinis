package com.openminis.app.provider

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

class BundledModelsCatalogTest {
    @Test
    fun gzipAssetIsReadableCatalog() {
        // [T-apk-size] The 4.1MB plaintext copy was removed — the gzip is the
        // only bundled form (the app reads it directly; a failed gunzip means
        // a corrupt APK and the network path takes over). What still needs a
        // guard is the gzip itself: update_models_dev.sh must never produce a
        // truncated or non-gzip catalog that would silently disable the
        // bundled fallback at runtime.
        val assets = find("src/android/app/src/main/assets")
            ?: find("app/src/main/assets")
            ?: find("src/main/assets")
            ?: error("assets dir not found from ${System.getProperty("user.dir")}")
        val gz = File(assets, "models-dev-api.json.gzip")
        assertTrue("bundled gzip catalog missing: ${gz.path}", gz.isFile)
        val json = GZIPInputStream(gz.inputStream()).bufferedReader().use { it.readText() }
        assertTrue("catalog is empty", json.isNotBlank())
        assertTrue("catalog is not a JSON object: ${json.take(16)}", json.trimStart().startsWith("{"))
        // Known-majority provider keys must survive a catalog refresh; if one
        // disappears the stage-2 index would silently change resolution.
        assertTrue("anthropic missing from catalog", json.contains("\"anthropic\""))
        assertTrue("openai missing from catalog", json.contains("\"openai\""))
    }

    private fun find(relative: String): File? {
        var dir = File(System.getProperty("user.dir") ?: return null)
        repeat(8) {
            val candidate = File(dir, relative)
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: return null
        }
        return null
    }
}
