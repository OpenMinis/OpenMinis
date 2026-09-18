package com.openminis.app.ui.chat

import com.openminis.app.data.model.LLMError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ChatRetryPolicyTest {

    @Test
    fun `429 retries on the same provider without a cap`() {
        for (attempt in 0..40) {
            assertTrue(
                "attempt $attempt must still retry",
                ChatViewModel.shouldRetrySameProvider(
                    retryAttempt = attempt,
                    isRateLimit = true,
                    isTransient = false,
                ),
            )
        }
    }

    @Test
    fun `429 backoff grows then caps at 60s`() {
        assertEquals(5, ChatViewModel.sameProviderRetryDelaySec(0, true))
        assertEquals(10, ChatViewModel.sameProviderRetryDelaySec(1, true))
        assertEquals(20, ChatViewModel.sameProviderRetryDelaySec(2, true))
        assertEquals(40, ChatViewModel.sameProviderRetryDelaySec(3, true))
        assertEquals(60, ChatViewModel.sameProviderRetryDelaySec(4, true))
        assertEquals(60, ChatViewModel.sameProviderRetryDelaySec(99, true))
    }

    @Test
    fun `transient errors still stop after three bounded retries`() {
        assertTrue(ChatViewModel.shouldRetrySameProvider(0, false, true))
        assertTrue(ChatViewModel.shouldRetrySameProvider(2, false, true))
        assertFalse(ChatViewModel.shouldRetrySameProvider(3, false, true))
        assertEquals(1, ChatViewModel.sameProviderRetryDelaySec(0, false))
        assertEquals(2, ChatViewModel.sameProviderRetryDelaySec(1, false))
        assertEquals(4, ChatViewModel.sameProviderRetryDelaySec(2, false))
    }

    @Test
    fun `rate limited is retryable and does not split compact`() {
        val err = LLMError.RateLimited()
        assertTrue(err.isRetryable)
        assertFalse(ChatViewModel.shouldSplitOnError(err))
        assertFalse(LLMError.InvalidApiKey().isRetryable)
        assertTrue(LLMError.NetworkError(IOException("offline")).isRetryable)
    }
}
