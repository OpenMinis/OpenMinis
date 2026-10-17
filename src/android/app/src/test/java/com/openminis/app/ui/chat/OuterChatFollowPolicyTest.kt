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
        assertTrue(source.contains("if (b.kind != \"thinking\")"))
        assertFalse(
            "the outer follower must not add thinking token length unconditionally",
            source.contains("growth += b.content.length.toLong()\n                blockSig = blockSig xor b.content.length.toLong()"),
        )
    }
}
