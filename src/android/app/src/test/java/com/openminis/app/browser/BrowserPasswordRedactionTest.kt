package com.openminis.app.browser

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-browser-password-redact] The browser snapshot (getBackbone) read
 * `el.value` of every INPUT, so whatever sat in a password field — typed by
 * the user or filled from an env var — went into the tool result and on to
 * the model as ` val=<password>`. iOS has redacted `type=password` at capture
 * since BrowserUseJavaScript.swift's snapshot fix; this pins the Android port.
 *
 * Source pins: the script runs inside a WebView, which a JVM test has not got.
 */
class BrowserPasswordRedactionTest {

    private val js = ProductionSources.read("browser/BrowserUseJS.kt")

    private val backbone = js.substringAfter("fun getBackbone(").substringBefore("\n    fun ")

    @Test
    fun `the snapshot redacts a password field's value at capture`() {
        assertTrue(
            backbone.contains("info.value = (el.type === 'password') ? '[redacted]' : el.value.substring(0, 60);"),
        )
    }

    @Test
    fun `the snapshot still reports that a password field is filled`() {
        // Redacted, not dropped: `if (el.value)` still sets info.value, so the
        // serialized node keeps ` val=[redacted]` and the agent can tell an
        // empty login form from a filled one.
        assertTrue(backbone.contains("if (el.value) info.value = (el.type === 'password')"))
        assertTrue(backbone.contains("(node.value ? ' val=' + node.value : '')"))
    }

    @Test
    fun `no script in BrowserUseJS reads a field value except the redacted snapshot read`() {
        // Every `el.value` READ in the injected scripts. The only other
        // occurrence is the type action's assignment (`el.value = '...'`).
        val reads = Regex("""el\.value(?!\s*=[^=])""").findAll(js).map { m ->
            js.substring(maxOf(0, m.range.first - 40), minOf(js.length, m.range.last + 40))
        }.toList()
        assertEquals(
            "unexpected el.value read — redact password fields before it leaves the page:\n" + reads.joinToString("\n---\n"),
            2,
            reads.size,
        )
        assertTrue(reads.all { "if (el.value)" in it || "[redacted]" in it })
    }
}
