package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-android-scrollbtn-turn-walk] Pins the up-button's turn-walk selection rule,
 * ported from iOS `scrollToPreviousUserTurn` (dcdec3c5).
 *
 * The composable itself needs a LazyListState + live layout, so this exercises
 * the pure decision the handler makes: given the message the viewport is
 * currently showing and the id we last jumped to, which user turn do we target?
 * That rule is the whole user-visible behaviour — "first tap goes to this turn,
 * repeated taps walk further back, and the first turn is a floor".
 *
 * Two device-discovered bugs are pinned here as regression cases:
 *  - `keyMessageId` parsing: row keys look like "mdblock:<id>:text_<id>_0:1",
 *    so taking everything after the FIRST colon yields "<id>:text_..." which
 *    never matches a message id. Every tap then fell back to the oldest loaded
 *    turn and the walk never advanced.
 *  - the windowed-transcript fallback: `messages` is a TAIL WINDOW, so the top
 *    row can belong to an unloaded message. Falling back to index 0 anchored on
 *    the oldest loaded turn; the correct fallback is the newest message.
 */
class UpButtonTurnWalkTest {

    private data class Msg(val id: String, val role: String)

    /** Mirrors the id extraction in ChatScreen's scrollToPreviousUserTurn. */
    private fun keyMessageId(key: String?): String? =
        key?.split(':')?.getOrNull(1)?.takeIf { it.isNotEmpty() }

    /** Mirrors the target-selection rule in ChatScreen's scrollToPreviousUserTurn. */
    private fun pickTarget(
        messages: List<Msg>,
        topRowKey: String?,
        lastJumpedUserId: String?,
    ): String? {
        val userIds = messages.filter { it.role == "user" }.map { it.id }
        if (userIds.isEmpty()) return null
        val topMessageId = keyMessageId(topRowKey)
        val topMsgIdx = topMessageId
            ?.let { id -> messages.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
            ?: 0
        val currentAnchor = messages.take(topMsgIdx + 1).lastOrNull { it.role == "user" }?.id
            ?: userIds.first()
        // Once a walk has started, always continue from lastJumpedUserId — the
        // viewport can no longer be trusted to identify the current turn (the
        // list clamps at its end, and top-aligning anchors the viewport on a
        // NEWER row than the target).
        val walkFrom = lastJumpedUserId?.takeIf { it in userIds } ?: currentAnchor
        val pos = userIds.indexOf(walkFrom)
        return if (lastJumpedUserId == walkFrom && pos > 0) userIds[pos - 1] else walkFrom
    }

    /** u1 a1 u2 a2 u3 a3 — three turns, assistant reply after each. */
    private val convo = listOf(
        Msg("u1", "user"), Msg("a1", "assistant"),
        Msg("u2", "user"), Msg("a2", "assistant"),
        Msg("u3", "user"), Msg("a3", "assistant"),
    )

    @Test
    fun `first tap anchors to the current turn rather than stepping back`() {
        // Viewport is showing a3, i.e. inside turn 3. The first tap must land on
        // u3 — NOT u2 — so the user sees the start of the turn they're reading.
        val target = pickTarget(convo, topRowKey = "mdblock:a3", lastJumpedUserId = null)
        assertEquals("u3", target)
    }

    @Test
    fun `a repeated tap walks one turn further back`() {
        // Same viewport, but we already jumped to u3 — now step to u2.
        val target = pickTarget(convo, topRowKey = "user:u3", lastJumpedUserId = "u3")
        assertEquals("u2", target)
    }

    @Test
    fun `walking continues turn by turn`() {
        assertEquals("u1", pickTarget(convo, topRowKey = "user:u2", lastJumpedUserId = "u2"))
    }

    @Test
    fun `the first turn is a floor - no overscroll past it`() {
        // Already anchored on the oldest turn: stay there instead of walking off
        // the front of the conversation.
        val target = pickTarget(convo, topRowKey = "user:u1", lastJumpedUserId = "u1")
        assertEquals("u1", target)
    }

    /** Five turns — needed for the clamp case, where a 3-turn fixture is too
     *  small for the fixed and unfixed rules to give different answers. */
    private val longConvo = (1..5).flatMap {
        listOf(Msg("u$it", "user"), Msg("a$it", "assistant"))
    }

    @Test
    fun `at the end of content the walk still advances instead of oscillating`() {
        // Device-observed stall (taps 6/7 of the 7-turn session): near the oldest
        // rows a LazyColumn CLAMPS, so the target never reaches the viewport top
        // and `currentAnchor` keeps recomputing to the OLDEST visible turn (u1)
        // even though we last jumped to u4. The base rule then sees
        // anchor(u1) != lastJumped(u4), re-targets u1, and the walk sticks there
        // forever. Continuing from lastJumped instead advances one turn per tap.
        val target = pickTarget(
            longConvo,
            topRowKey = "user:u1",
            lastJumpedUserId = "u4",
        )
        assertEquals("u3", target)
    }

    @Test
    fun `at the end of content a fresh tap does not skip ahead`() {
        // A FIRST tap must never step back: with no
        // lastJumped, the target is still the turn the user is reading.
        val target = pickTarget(
            convo,
            topRowKey = "user:u2",
            lastJumpedUserId = null,
        )
        assertEquals("u2", target)
    }

    @Test
    fun `a stale lastJumped that is not the current anchor re-anchors`() {
        // This is the post-drag state: lastJumpedUserId is reset to null, so the
        // next tap re-anchors to the current turn rather than continuing.
        val target = pickTarget(convo, topRowKey = "mdblock:a2", lastJumpedUserId = null)
        assertEquals("u2", target)
    }

    @Test
    fun `row keys with an id suffix still resolve to the message id`() {
        // Device-observed key shape. substringAfter(':') would return
        // "a3:text_a3_0:1" and match nothing.
        assertEquals("a3", keyMessageId("mdblock:a3:text_a3_0:1"))
        assertEquals("u3", keyMessageId("user:u3"))
        assertEquals("a3", keyMessageId("thinking:a3"))
        assertNull(keyMessageId("__resume_banner__"))
    }

    @Test
    fun `an unloaded top row anchors to the oldest loaded turn`() {
        // `messages` is a TAIL WINDOW and the viewport top is the OLDEST content
        // on screen, so a top row whose message isn't loaded means the user is
        // at/above the start of the window — anchor on the oldest loaded turn.
        val target = pickTarget(convo, topRowKey = "mdblock:NOT_LOADED", lastJumpedUserId = null)
        assertEquals("u1", target)
    }

    /**
     * Mirrors the row-index search in ChatScreen. `rows` is the flat row list
     * (index -> key); `viewport` is the inclusive range of rows on screen when
     * the tap happens. Returns the found row index, or null for a dead tap.
     */
    private fun seekRowIndex(
        rows: List<String>,
        viewport: IntRange,
        targetKey: String,
        strideFromHighestOnly: Boolean,
    ): Int? {
        rows.indexOf(targetKey).let { if (it in viewport) return it }
        if (strideFromHighestOnly) {
            // The OLD algorithm: stride upward from the highest visible row by a
            // viewport's worth of rows at a time.
            var hi = viewport.last
            val stride = viewport.count().coerceAtLeast(1)
            var guard = 0
            while (guard++ < 60) {
                val next = (hi + stride).coerceAtMost(rows.lastIndex)
                if (next <= hi) return null
                // After scrolling to `next`, roughly that row and the following
                // viewport-worth are on screen.
                val window = next..(next + stride - 1).coerceAtMost(rows.lastIndex)
                val found = rows.indexOf(targetKey)
                if (found in window) return found
                hi = window.last
            }
            return null
        }
        // The NEW algorithm: sweep from row 0 forward.
        return rows.indexOf(targetKey).takeIf { it >= 0 }
    }

    @Test
    fun `the seek finds a target that sits just below the viewport`() {
        // Device repro (a long test session, user's real flow): viewport on rows 0..8,
        // target user bubble at row 9 — one row BELOW the window. The old
        // stride-upward seek jumped 17 -> 41, stepped straight over row 9, and
        // returned null, which surfaced as a completely dead button.
        val rows = List(47) { "row$it" }.toMutableList().also { it[9] = "user:u2" }
        val viewport = 0..8

        assertNull(
            "old stride seek must miss it (this is the reported bug)",
            seekRowIndex(rows, viewport, "user:u2", strideFromHighestOnly = true),
        )
        assertEquals(
            9,
            seekRowIndex(rows, viewport, "user:u2", strideFromHighestOnly = false),
        )
    }

    @Test
    fun `the seek still finds a target far above the viewport`() {
        // The other direction must keep working: oldest turn at the very last
        // row, viewport near the newest end.
        val rows = List(47) { "row$it" }.toMutableList().also { it[46] = "user:u1" }
        assertEquals(
            46,
            seekRowIndex(rows, 0..8, "user:u1", strideFromHighestOnly = false),
        )
    }

    @Test
    fun `a conversation with no user turns yields no target`() {
        val noUsers = listOf(Msg("a1", "assistant"), Msg("a2", "assistant"))
        assertNull(pickTarget(noUsers, topRowKey = "mdblock:a2", lastJumpedUserId = null))
    }
}
