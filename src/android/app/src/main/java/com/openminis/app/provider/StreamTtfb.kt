package com.openminis.app.provider

import com.openminis.app.data.model.LLMError
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Response

/**
 * Time-to-first-byte watchdog for providers whose only bound is OkHttp's
 * `readTimeout`.
 *
 * Anthropic/Gemini used a 10-minute read timeout and nothing else. A request
 * written into a half-open connection (local proxy survived a network flap,
 * server never answers) produced no headers, no failure and no bytes until
 * that 10-minute timeout — the UI sat in "thinking" and retry/cancel felt
 * broken. OpenAI already had a per-call watchdog; this is the same guard for
 * the other two.
 *
 * The watchdog only covers the wait for response HEADERS. Once the stream is
 * flowing there is deliberately no total-duration limit: a long thinking pause
 * between SSE events is legitimate. Call cancellation unblocks `execute()`
 * with an [IOException], which is mapped to a retryable error.
 */
internal object StreamTtfb {

    const val TIMEOUT_MS = 120_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun execute(call: Call, provider: String, timeoutMs: Long = TIMEOUT_MS): Response {
        require(timeoutMs > 0) { "timeoutMs must be positive" }
        val headersArrived = AtomicBoolean(false)
        val timedOut = AtomicBoolean(false)
        val watchdog = scope.launch {
            delay(timeoutMs)
            if (!headersArrived.get()) {
                timedOut.set(true)
                com.openminis.app.logging.AppLogger.warning(
                    provider,
                    "[T-android-stream-ttfb] no response headers within ${timeoutMs / 1000}s — cancelling call",
                )
                call.cancel()
            }
        }
        return try {
            call.execute()
        } catch (e: IOException) {
            if (timedOut.get()) {
                throw LLMError.TransientError(
                    "no response from server (${timeoutMs / 1000}s TTFB) — check network/proxy",
                )
            }
            throw e
        } finally {
            headersArrived.set(true)
            watchdog.cancel()
        }
    }
}
