package com.openminis.app.ui.chat

/**
 * [T-android-queued-message-interrupt-thinking] Control-flow signal thrown
 * from the stream collector when a queued prompt arrives while the model is
 * still in a pure-thinking phase (ThinkingDelta / ReasoningContent chunks,
 * no reply text yet). Before this, the only mid-loop injection point was the
 * post-tool-result boundary — a prompt queued during a long reasoning stream
 * waited for the entire agent loop to drain.
 *
 * Not an error: the collect loop's dedicated `catch (e: QueuedPromptInterrupt)`
 * (which must stay BEFORE the generic `catch (e: Exception)` so the retry /
 * fallback machinery never sees it) settles the thinking block, mirrors the
 * success-path cleanup, and breaks the stream loop; the post-loop
 * `queuedPromptInterrupted` block in runAgentLoop then injects the queued
 * prompt as a standalone turn (same seal-and-swap the post-tool-result
 * boundary performs) so the next iteration answers it directly.
 *
 * Carries no stack trace — it is thrown per-occurrence at token cadence and
 * unwinds only a few frames inside the collector.
 */
internal class QueuedPromptInterrupt : Exception() {
    // No stack: thrown at token cadence, unwinds only a few frames inside
    // the collector. (Exception(null, writableStackTrace = false) doesn't
    // compile — the Java constructor has no defaults to skip to.)
    override fun fillInStackTrace(): Throwable = this
}
