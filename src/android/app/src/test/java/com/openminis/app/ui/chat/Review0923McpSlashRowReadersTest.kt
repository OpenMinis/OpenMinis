package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review 2026-09-23 — guards f42128b81 (GH#372, T-android-mcp-slash-dispatch).
 *
 * That fix's own lesson: "If you add another row KIND, give it a flag AND a
 * reader" — the bug was a composer-fill guard that read `isSkill` alone. The
 * ViewModel guard was fixed, but ChatScreen's tap handler has a second
 * composer-fill reader: after `executeSlashCommand` it re-focuses the composer
 * and shows the IME for `cmd.isSkill` only. Tapping an MCP row fills
 * "/<server> " and leaves the keyboard down, unlike a skill row (iOS treats
 * both kinds the same).
 *
 * McpSlashDispatchTest covers the ViewModel; this pins the screen reader.
 */
class Review0923McpSlashRowReadersTest {

    private fun src(path: String): String {
        val f = File(path)
        assertTrue("missing ${f.absolutePath}", f.exists())
        return f.readText()
    }

    @Test
    fun `BUG the tap handler refocuses the composer for MCP rows too`() {
        val screen = src("src/main/java/com/openminis/app/ui/chat/ChatScreen.kt")
        val tap = screen.indexOf("viewModel.setInputText(viewModel.executeSlashCommand(cmd, inputText))")
        assertTrue("tap handler not found", tap >= 0)
        val window = screen.substring(tap, minOf(screen.length, tap + 1500))
        val focusGuard = window.indexOf("inputFocusRequester.requestFocus()")
        assertTrue("focus call not found near the tap handler", focusGuard >= 0)
        val guard = window.substring(0, focusGuard)
        assertTrue(
            "the post-tap focus/IME block must treat isMcp like isSkill",
            guard.contains("isMcp"),
        )
    }
}
