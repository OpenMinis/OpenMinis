package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-codeblock-fence-indent] Copying a command from a code block that
 * sits inside a list item gave it leading spaces ("  npm install").
 */
class FencedCodeIndentTest {

    @Test fun `a fence under a bullet loses its two-space indent`() {
        assertEquals("npm install", fencedCodeContent("  ```bash", listOf("  npm install")))
    }

    @Test fun `a fence under a numbered item loses its three-space indent`() {
        assertEquals("git status\ngit pull", fencedCodeContent("   ```", listOf("   git status", "   git pull")))
    }

    @Test fun `deeper indentation inside the code is kept`() {
        val code = fencedCodeContent("  ```python", listOf("  def f():", "      return 1", ""))
        assertEquals("def f():\n    return 1\n", code)
    }

    @Test fun `a line indented less than the fence loses only what it has`() {
        assertEquals("a\nb", fencedCodeContent("    ```", listOf("  a", "b")))
    }

    @Test fun `an unindented fence leaves the content untouched`() {
        assertEquals("  keep\nme", fencedCodeContent("```", listOf("  keep", "me")))
    }

    @Test fun `the parser builds code blocks through it`() {
        val src = ProductionSources.read("ui/chat/StreamingMarkdownText.kt")
        assertTrue(src.contains("MdBlock.CodeBlock(rawLines.joinToString(\"\\n\"), lang, fencedCodeContent(line, codeLines))"))
    }
}
