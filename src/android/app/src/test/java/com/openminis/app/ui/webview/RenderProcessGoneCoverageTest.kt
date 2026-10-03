package com.openminis.app.ui.webview

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-android-webview-render-process-gone] (GH#341) EVERY WebViewClient must
 * override `onRenderProcessGone`.
 *
 * This is all-or-nothing in a way that is easy to get wrong: by default all
 * WebViews in a process share one renderer, so a single death fans out to all
 * of them, and Android kills the app unless *every* associated client handled
 * it. One new WebViewClient added later without the override re-opens the
 * crash for the whole app, and nothing in the compiler or a normal test run
 * would say so.
 *
 * A source guard because the condition is "no file may do X" — there is no
 * object to construct and assert against, and the failure only reproduces when
 * the OS kills a renderer.
 */
class RenderProcessGoneCoverageTest {

    private val mainSrc = File("src/main/java/com/openminis/app")

    private fun kotlinSources(): List<File> {
        assertTrue("source root not found (cwd=${File(".").absolutePath})", mainSrc.isDirectory)
        return mainSrc.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun `every WebViewClient overrides onRenderProcessGone`() {
        val offenders = mutableListOf<String>()
        for (f in kotlinSources()) {
            val text = f.readText()
            // Count client definitions vs overrides in the same file. Every
            // client in this codebase is an anonymous `object : WebViewClient()`
            // local to the file that installs it.
            val clients = Regex("""object\s*:\s*WebViewClient\(\)""").findAll(text).count()
            if (clients == 0) continue
            val overrides = Regex("""override\s+fun\s+onRenderProcessGone""").findAll(text).count()
            if (overrides < clients) {
                offenders += "${f.name}: $clients WebViewClient(s), $overrides override(s)"
            }
        }
        assertTrue(
            "these files define a WebViewClient without onRenderProcessGone — a dead " +
                "renderer will take the whole app down: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `no bare WebViewClient instance is installed`() {
        // `webViewClient = WebViewClient()` cannot carry an override at all.
        // FilePreviewScreen had one; this stops it coming back.
        val offenders = kotlinSources().filter { f ->
            Regex("""webViewClient\s*=\s*WebViewClient\(\)""").containsMatchIn(f.readText())
        }.map { it.name }
        assertTrue(
            "a bare `WebViewClient()` cannot override onRenderProcessGone — use " +
                "`object : WebViewClient() { … }`: $offenders",
            offenders.isEmpty(),
        )
    }
}
