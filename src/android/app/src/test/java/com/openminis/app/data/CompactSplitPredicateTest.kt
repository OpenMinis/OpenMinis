package com.openminis.app.data

import com.openminis.app.data.model.LLMError
import com.openminis.app.ui.chat.ChatViewModel.Companion.shouldSplitOnError
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * [T-android-compact-runaway] Which failures justify retrying a compaction with
 * a halved payload.
 *
 * The predicate used to answer "everything except cancellation and IO", which
 * meant a 429 or a 5xx — neither caused by request size — fanned a single
 * failure out into up to 15 sequential model calls. With a slow or
 * rate-limited model that is the 15-20 minute hang users reported. Splitting is
 * now reserved for failures a smaller request could plausibly fix.
 */
class CompactSplitPredicateTest {

    // ── Must NOT split: retrying smaller cannot help ────────────────────

    @Test
    fun `rate limiting does not split`() {
        // The regression that mattered most: 429 is about quota, not length.
        assertFalse(shouldSplitOnError(LLMError.RateLimited()))
    }

    @Test
    fun `transient server errors do not split`() {
        assertFalse(shouldSplitOnError(LLMError.TransientError("502 Bad Gateway")))
    }

    @Test
    fun `auth failures do not split`() {
        assertFalse(shouldSplitOnError(LLMError.InvalidApiKey("bad key")))
    }

    @Test
    fun `cancellation does not split`() {
        assertFalse(shouldSplitOnError(CancellationException("user stopped")))
        assertFalse(shouldSplitOnError(LLMError.Cancelled()))
    }

    @Test
    fun `network failures do not split`() {
        assertFalse(shouldSplitOnError(LLMError.NetworkError(IOException("offline"))))
        assertFalse(shouldSplitOnError(IOException("connection reset")))
        // A socket timeout is an IOException — payload size is not the cause,
        // so halving would only double the waiting.
        assertFalse(shouldSplitOnError(SocketTimeoutException("read timed out")))
    }

    // ── Must split: a smaller request plausibly succeeds ────────────────

    @Test
    fun `over-length provider refusals still split`() {
        // This is the case the whole split mechanism exists for. Providers word
        // it inconsistently and it arrives untyped, so it must stay retryable.
        assertTrue(
            shouldSplitOnError(
                LLMError.ProviderError(
                    "[context_length_exceeded] Your input exceeds the context window of this model"
                )
            )
        )
        assertTrue(shouldSplitOnError(LLMError.ProviderError("400 request too large")))
    }

    @Test
    fun `unclassified errors still split`() {
        // Burden of proof stays on NOT retrying: a summary built from halves
        // beats no summary, and the call budget bounds the downside.
        assertTrue(shouldSplitOnError(LLMError.Unknown(RuntimeException("???"))))
        assertTrue(shouldSplitOnError(LLMError.DecodingError(RuntimeException("bad json"))))
        assertTrue(shouldSplitOnError(IllegalStateException("something odd")))
    }

    // ── [OpenMinis#377] parameter rejections: refused, not too big ───────

    @Test
    fun `an unsupported-parameter 400 does not split`() {
        // Issue #377: compaction sent `reasoning.effort:"none"` to a model that
        // does not accept it and halved 963 → 481 → 240 → 120 messages, failing
        // four times on one unchanged parameter. Halving cannot fix a parameter.
        assertFalse(
            shouldSplitOnError(
                LLMError.ProviderError(
                    "[400] reasoning.effort: 'none' is not supported. " +
                        "Supported values are: 'low', 'medium', 'high', 'xhigh' and 'max'.",
                    httpStatus = 400,
                ),
            ),
        )
    }

    @Test
    fun `other parameter rejections do not split`() {
        for (m in listOf(
            "[400] invalid_request_error: unknown field 'reasoning'",
            "[422] extra_forbidden body.reasoning",
            "Model grok-build-0.1 does not support parameter reasoningEffort",
            "[400] Unsupported parameter: 'temperature'",
        )) {
            assertFalse("must not split: $m", shouldSplitOnError(LLMError.ProviderError(m, httpStatus = 400)))
        }
    }

    @Test
    fun `an over-length 400 still splits`() {
        // The critical non-regression. Over-length refusals are ALSO HTTP 400 —
        // keying the rule on the status code alone would have disabled
        // splitting for the exact case it exists to serve.
        assertTrue(
            shouldSplitOnError(
                LLMError.ProviderError(
                    "[400] context_length_exceeded: input too long",
                    httpStatus = 400,
                ),
            ),
        )
        assertTrue(
            shouldSplitOnError(
                LLMError.ProviderError(
                    "[400] This model's maximum context length is 128000 tokens",
                    httpStatus = 400,
                ),
            ),
        )
    }

    @Test
    fun `a message mixing both signals errs towards splitting`() {
        // Burden of proof stays on NOT retrying: anything that looks even
        // slightly like a size problem keeps the split path.
        assertTrue(
            shouldSplitOnError(
                LLMError.ProviderError(
                    "[400] invalid_request_error: input exceeds the context window",
                    httpStatus = 400,
                ),
            ),
        )
    }

    @Test
    fun `5xx and status-less errors are unaffected`() {
        assertTrue(shouldSplitOnError(LLMError.ProviderError("upstream boom", httpStatus = 500)))
        assertTrue(shouldSplitOnError(LLMError.ProviderError("no status here")))
    }

    @Test
    fun `the amplification path is closed for the reported failure modes`() {
        // Spelled out as the regression guard: each of these previously
        // returned true and could become 15 sequential slow calls.
        val previouslyAmplified = listOf<Throwable>(
            LLMError.RateLimited(),
            LLMError.TransientError("503"),
            LLMError.InvalidApiKey(),
        )
        for (e in previouslyAmplified) {
            assertFalse("${e.javaClass.simpleName} must not split", shouldSplitOnError(e))
        }
    }
}
