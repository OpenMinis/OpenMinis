package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-fab-up-target] The scroll-to-previous-turn button walks "the
 * user's turns". This pins what that means.
 *
 * The bug: `role == "user"` was used as the test, but two kinds of
 * system-written message carry the user role and render as their own card
 * instead of a `user:` bubble — a scheduled task's prompt and a sub agent
 * callback. Counting them made the button aim at turns with no row to land on,
 * so it either stopped on the wrong content or fell through to RESTORE and
 * looked dead. It surfaced once sub agent cards shipped, because that is when
 * callbacks started appearing in quantity.
 *
 * These assertions are the contract between the walk and
 * `buildFlatChatItems`: anything that returns true here must be emitted as a
 * `UserBubble` there, and anything false must not be.
 */
class UserBubbleTargetTest {

    private fun msg(role: String, content: String) =
        ChatMessage(id = "m1", role = role, content = content)

    @Test
    fun `a typed message is a user bubble`() {
        assertTrue(msg("user", "帮我查一下今天的天气").rendersAsUserBubble())
    }

    /** Content that merely mentions the markers is still the user talking. */
    @Test
    fun `a message that only talks about the markers is still a bubble`() {
        assertTrue(msg("user", "what does <agent_callback> mean?").rendersAsUserBubble())
        assertTrue(msg("user", "the scheduled_task tag confused me").rendersAsUserBubble())
    }

    @Test
    fun `a sub agent callback is not a user bubble`() {
        assertFalse(
            msg("user", "<agent_callback kind=\"finished\" job=\"j1\">done</agent_callback>")
                .rendersAsUserBubble(),
        )
        assertFalse(msg("user", "<agent_callback>bare</agent_callback>").rendersAsUserBubble())
    }

    @Test
    fun `a scheduled task prompt is not a user bubble`() {
        assertFalse(
            msg("user", "<scheduled_task id=\"t1\">check the build</scheduled_task>")
                .rendersAsUserBubble(),
        )
    }

    /**
     * A marker with no `id` is NOT a scheduled card — `ScheduledTaskMarker.parse`
     * requires the id and returns null without it, so `buildFlatChatItems` falls
     * through and emits a plain bubble. The predicate has to agree, or the walk
     * would skip a row that really is on screen.
     *
     * Asserted rather than assumed: the first version of this test expected the
     * opposite and failed, which is how the asymmetry with the callback tag
     * (whose check is a bare `startsWith`) came to light.
     */
    @Test
    fun `an id-less scheduled marker still renders as a bubble`() {
        assertTrue(msg("user", "<scheduled_task>bare</scheduled_task>").rendersAsUserBubble())
    }

    @Test
    fun `assistant and system messages are never user bubbles`() {
        assertFalse(msg("assistant", "here you go").rendersAsUserBubble())
        assertFalse(msg("system", "you are a helpful assistant").rendersAsUserBubble())
    }
}
