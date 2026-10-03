package com.openminis.app.ui.chat

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [GH#325] `clearChat()` must refresh the session row it just emptied.
 *
 * A source guard rather than a behavioural test, deliberately and with the
 * limitation stated: `clearChat` lives on a ViewModel that needs an Android
 * Context, a Room database and a repository graph, and this module has no
 * Robolectric — so there is no way to invoke it in a JVM unit test. The choice
 * is between pinning the call at the source level and pinning nothing at all.
 * `OverlayLingerTimeoutGuardTest` and `InAppThemeSourceGuardTest` take the same
 * approach for the same reason.
 *
 * What went wrong: clearing a chat deleted every message but left `updated_at`
 * pinned to the last of them. The session list sorts and date-buckets on that
 * column, so a chat cleared seconds ago stayed filed under an old date — and
 * inside a group the accordion had collapsed, it rendered nowhere at all. Users
 * reported the session as deleted when every row and file was still present.
 */
class ClearChatTouchesSessionTest {

    private val clearChatBody: String by lazy {
        val f = File("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt")
        assertTrue("ChatViewModel.kt not found (cwd=${File(".").absolutePath})", f.isFile)
        val src = f.readText()
        val start = src.indexOf("fun clearChat() {")
        assertTrue("clearChat() not found — was it renamed?", start >= 0)
        // Bounded by the next top-level declaration comment, which is enough to
        // cover the function and none of its neighbours.
        val end = src.indexOf("// ─── Share Injection", start)
        assertTrue("could not bound clearChat()", end > start)
        src.substring(start, end)
    }

    @Test
    fun `clearChat refreshes the session row timestamp`() {
        assertTrue(
            "clearChat() must bump updated_at (via updateLastMessage or touchSession), " +
                "or a cleared chat stays sorted under its pre-clear date",
            clearChatBody.contains("updateLastMessage(") ||
                clearChatBody.contains("touchSession("),
        )
    }

    @Test
    fun `clearChat drops the stale last-message preview`() {
        // The preview described a message clearChat had just deleted, so the
        // row advertised content the chat could no longer show. Passing null
        // is `updateLastMessage`'s "no preview", and it writes updated_at in
        // the same statement so the two columns cannot drift apart.
        assertTrue(
            "clearChat() must clear last_message, not just the timestamp",
            Regex("""updateLastMessage\(\s*\w+\s*,\s*null""").containsMatchIn(clearChatBody),
        )
    }

    @Test
    fun `clearChat still deletes messages and compact markers`() {
        // The new write must be an addition, not a replacement.
        assertTrue(clearChatBody.contains("deleteMessages("))
        assertTrue(clearChatBody.contains("deleteCompactMarkers("))
    }

    @Test
    fun `clearChat does not delete the session row`() {
        // The whole point: files and the row survive. If a future edit reaches
        // for deleteSession here, the user's reported symptom becomes real.
        assertTrue(
            "clearChat() must never delete the session row",
            !clearChatBody.contains("deleteSession("),
        )
    }
}
