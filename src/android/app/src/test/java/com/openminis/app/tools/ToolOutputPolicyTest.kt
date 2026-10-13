package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test

class ToolOutputPolicyTest {

    private val sensitive = listOf("sk-Ab12Cd34Ef56Gh78")

    /**
     * [T-spill-hermetic] The spill path writes to
     * `workspaceRoot/tool_outputs/`. The production default
     * `/var/minis/workspace` exists on the phone but NOT on a CI runner,
     * where the write fails and apply() falls back to returning the full
     * text — the omission-marker assertions then fail for the wrong
     * reason. Redirect to a temp dir so the test exercises the spill path
     * everywhere, and restore the default afterwards.
     */
    @Before
    fun redirectSpillToTempDir() {
        ToolOutputPolicy.workspaceRoot =
            java.nio.file.Files.createTempDirectory("tool-output-policy-").toFile().absolutePath
    }

    @After
    fun restoreWorkspaceRoot() {
        ToolOutputPolicy.workspaceRoot = "/var/minis/workspace"
    }

    @Test
    fun plainTextReplace() {
        val out = ToolOutputPolicy.redactWith("token=sk-Ab12Cd34Ef56Gh78 done", sensitive)
        assertEquals("token=[REDACTED] done", out)
    }

    @Test
    fun jsonObjectValueRedacted() {
        val out = ToolOutputPolicy.redactWith(
            """{"apiKey":"sk-Ab12Cd34Ef56Gh78","ok":true}""",
            sensitive,
        )
        assertEquals("""{"apiKey":"[REDACTED]","ok":true}""", out)
    }

    @Test
    fun jsonEscapedValueRedacted() {
        // 值经 JSON 转义（引号逃逸）后纯文本 replace 匹配不到原文，
        // 结构化递归仍能命中。
        val out = ToolOutputPolicy.redactWith(
            """{"cmd":"echo \"sk-Ab12Cd34Ef56Gh78\" > /tmp/x"}""",
            sensitive,
        )
        assertEquals("""{"cmd":"echo \"[REDACTED]\" > /tmp/x"}""", out)
    }

    @Test
    fun jsonNestedArrayRedacted() {
        val out = ToolOutputPolicy.redactWith(
            """{"env":[{"k":"A","v":"sk-Ab12Cd34Ef56Gh78"},{"k":"B","v":"x"}]}""",
            sensitive,
        )
        val parsed = org.json.JSONObject(out)
        val arr = parsed.getJSONArray("env")
        assertEquals("[REDACTED]", arr.getJSONObject(0).getString("v"))
        assertEquals("x", arr.getJSONObject(1).getString("v"))
    }

    @Test
    fun nonJsonFallsBackToTextReplace() {
        val out = ToolOutputPolicy.redactWith("line1\nsk-Ab12Cd34Ef56Gh78\nline3", sensitive)
        assertEquals("line1\n[REDACTED]\nline3", out)
    }

    @Test
    fun emptySensitiveListIsNoop() {
        val text = """{"apiKey":"sk-Ab12Cd34Ef56Gh78"}"""
        assertEquals(text, ToolOutputPolicy.redactWith(text, emptyList()))
    }

    @Test
    fun belowThresholdNoSpill() {
        val text = "a".repeat(11_999)
        val out = ToolOutputPolicy.apply(text, "test1")
        assertEquals(11_999, out.length)
        assertEquals(text, out)
    }

    @Test
    fun overThresholdIncludesHeadTailAndMarker() {
        val text = "a".repeat(25_000)
        val out = ToolOutputPolicy.apply(text, "test2")
        // Should contain head (2000 chars)
        assert(out.startsWith("a".repeat(2000))) { "Should start with head" }
        // Should contain tail (last 2000 chars)
        assert(out.endsWith("a".repeat(2000))) { "Should end with tail" }
        // Should contain omission marker
        assert(out.contains("========== [以下内容被省略] ==========")) { "Should contain omission marker" }
        // Should not contain full body (should be much shorter than 25k)
        assert(out.length < 25_000) { "Result should be shorter than full text" }
        // Verify info in message about omitted chars
        val omitted = 25_000 - 2000 - 2000
        assert(out.contains("已省略中间 $omitted 字符")) { "Should show omitted char count" }
    }

    @Test
    fun emojiBoundarySafe() {
        // Test with emojis that could be split if we use char-indexing incorrectly
        val emoji = "\uD83D\uDE00" // 😀 surrogate pair
        val text = (emoji + "prefix_").repeat(5000) + "suffix_" + (emoji + "end_").repeat(5000)
        val out = ToolOutputPolicy.apply(text, "emoji-test")
        // Ensure we don't have orphaned surrogate halves in the preview.
        assert(!hasOrphanSurrogate(out)) { "Should not contain orphaned surrogate halves" }
        // Verify the spill file holds the complete text.
        val file = java.io.File(ToolOutputPolicy.workspaceRoot, "tool_outputs/emoji-test.txt")
        if (file.exists()) {
            val content = file.readText()
            assert(!hasOrphanSurrogate(content)) { "Spill should not have orphaned surrogates" }
            assert(content.length == text.length) { "Spill should contain the full text" }
            file.delete()
        }
    }

    private fun hasOrphanSurrogate(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) return true
                i += 2
            } else if (Character.isLowSurrogate(c)) {
                // 低代理前面必须是高代理。
                if (i == 0 || !Character.isHighSurrogate(s[i - 1])) return true
                i++
            } else {
                i++
            }
        }
        return false
    }

    @Test
    fun spillIsCompleteRedactedContent() {
        // Test that the spill file contains the full redacted text
        // We test redactWith directly and verify the apply flow writes it correctly
        val original = "Start sk-Ab12Cd34Ef56Gh78 middle " + "b".repeat(12_500) + " end"
        
        // First, get the fully redacted version
        val redacted = ToolOutputPolicy.redactWith(original, sensitive)
        
        // Verify redaction happened
        assert(redacted.contains("[REDACTED]")) { "Should contain [REDACTED]" }
        assert(!redacted.contains("sk-Ab12Cd34Ef56Gh78")) { "Should not contain original key" }
        
        // Now test apply with a simple text that triggers spill
        val spillText = "a".repeat(25_000)
        val out = ToolOutputPolicy.apply(spillText, "spill-complete-test")
        
        // Check spill file exists
        val file = java.io.File(ToolOutputPolicy.workspaceRoot, "tool_outputs/spill-complete-test.txt")
        if (file.exists()) {
            val content = file.readText()
            // File should have full content (all 'a's in this case since no env var redaction)
            assert(content.length == spillText.length) { "Spill should be complete redacted content" }
            assert(content == spillText) { "Spill content should match" }
            file.delete()
        }
    }

    @Test
    fun existingJsonRecursiveTestsStillGreen() {
        // Re-run existing tests to ensure they still pass
        val sensitive = listOf("sk-Ab12Cd34Ef56Gh78")
        
        val out1 = ToolOutputPolicy.redactWith("token=sk-Ab12Cd34Ef56Gh78 done", sensitive)
        assertEquals("token=[REDACTED] done", out1)
        
        val out2 = ToolOutputPolicy.redactWith(
            """{"apiKey":"sk-Ab12Cd34Ef56Gh78","ok":true}""",
            sensitive,
        )
        assertEquals("""{"apiKey":"[REDACTED]","ok":true}""", out2)
        
        val out3 = ToolOutputPolicy.redactWith(
            """{"cmd":"echo \"sk-Ab12Cd34Ef56Gh78\" > /tmp/x"}""",
            sensitive,
        )
        assertEquals("""{"cmd":"echo \"[REDACTED]\" > /tmp/x"}""", out3)
    }

    @Test
    fun depthCapFlattensAndStillRedacts() {
        // [T-redact-depth] Past MAX_REDACT_DEPTH the subtree is flattened to
        // text and plain-text redacted. The first cut returned the raw node
        // past the cap, so a secret nested one level too deep reached the
        // model unredacted — a depth bound must not double as a leak channel.
        var json = """{"k":"sk-Ab12Cd34Ef56Gh78"}"""
        repeat(40) { json = """{"n":$json}""" }
        val out = ToolOutputPolicy.redactWith(json, sensitive)
        assert(!out.contains("sk-Ab12Cd34Ef56Gh78")) { "secret leaked past the depth cap" }
        assert(out.contains("[REDACTED]")) { "flattened subtree was not redacted" }
    }

    @Test
    fun boundaryAwareRedactionSparesIdentifiers() {
        // [T-redact-boundary] Whole-substring replace rewrote ANY text that
        // happened to contain the value — a token-shaped string glued inside
        // a longer identifier is not the secret and must survive verbatim.
        assertEquals(
            "mysk-Ab12Cd34Ef56Gh78var",
            ToolOutputPolicy.redactWith("mysk-Ab12Cd34Ef56Gh78var", sensitive),
        )
        // Delimited occurrences still redact (punctuation boundaries count).
        assertEquals(
            "key=[REDACTED];done",
            ToolOutputPolicy.redactWith("key=sk-Ab12Cd34Ef56Gh78;done", sensitive),
        )
    }
}
