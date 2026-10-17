package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression contract: thinking tokens belong to the inner output box. */
class OuterChatFollowPolicyTest {
    @Test
    fun thinkingBlockContentDoesNotWakeOuterTimelineFollower() {
        val source = ProductionSources.read("ui/chat/ChatScreen.kt")
        // [T-android-thinking-stable-expand] The fold now hoists the predicate
        // into `isThinking` and consults it twice (growth + block signature);
        // the contract below is about the EFFECT — thinking content length
        // must never reach `growth` — so the guard tracks that spelling.
        assertTrue(
            "the fold must classify thinking blocks explicitly",
            source.contains("val isThinking = b.kind == \"thinking\""),
        )
        assertTrue(
            "thinking content length must be excluded from the growth term",
            source.contains("if (!isThinking) growth += b.content.length.toLong()"),
        )
        assertFalse(
            "the outer follower must not add thinking token length unconditionally",
            source.contains("growth += b.content.length.toLong()\n                blockSig = blockSig xor b.content.length.toLong()"),
        )
    }
}
