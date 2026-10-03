package com.openminis.app.browser

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Thrown when a tab's WebView can no longer answer: its renderer process died,
 * or a JS evaluation got no callback within [BrowserActionGuard.JS_EVAL_TIMEOUT_MS].
 * The manager marks itself unusable before throwing, so the pool rebuilds the
 * tab on the next acquire even when an intermediate caller swallows this.
 */
class WebViewWedgedException(message: String) : IllegalStateException(message)

/**
 * (GH#245) Timing rules for one browser action, kept free of WebView so they
 * can be unit-tested with plain coroutines.
 *
 * Background: a renderer that HANGS (page JS spinning, compositor stuck) never
 * fires `onRenderProcessGone`, and `WebView.evaluateJavascript` then never
 * calls back. Before this, nothing bounded that wait inside the pool, nothing
 * marked the tab bad, and the CLI's single-tab driver kept routing every
 * command back to the same frozen tab — each one burning 60s and returning a
 * misleading "tab busy" error, forever. iOS had the sibling bug (the CLI gave
 * up after 90s but the native side held its serial slot for 300s); on Android
 * structured concurrency already propagates the CLI's cancellation into the
 * pool and `Mutex` releases on it, so what was missing is the deadline and the
 * rebuild, not a cancel bridge.
 */
internal object BrowserActionGuard {
    /** Budget for one `evaluateJavascript` callback. Healthy pages answer in ms. */
    const val JS_EVAL_TIMEOUT_MS = 15_000L

    /**
     * Hard ceiling on one page operation once its tab is held. Longer than the
     * longest legitimate action (fetch awaits up to 60s; wait_for_dom_stable
     * caps at 60s) and shorter than the CLI's 90s, so the pool trips first,
     * reports the real cause and marks the tab for rebuild.
     */
    const val ACTION_DEAD_TIMEOUT_MS = 75_000L

    /**
     * A cancellation that arrives after the action has run this long is taken
     * as "the page stopped answering" (the CLI's 90s timeout, or the deadline
     * above). A shorter one is a user pressing Stop on a healthy page, which
     * must not throw that page away.
     */
    const val WEDGE_SUSPECT_MS = 30_000L

    /**
     * How long a call waits for ANOTHER call's operation on the same explicit
     * tab id to release its lock. Purely the lock wait — the operation itself
     * is bounded by [ACTION_DEAD_TIMEOUT_MS].
     */
    const val TAB_SERIAL_WAIT_TIMEOUT_MS = 60_000L

    sealed class Outcome<out T> {
        data class Done<T>(val value: T) : Outcome<T>()
        /** The operation outlived [ACTION_DEAD_TIMEOUT_MS]; the tab was flagged. */
        data class DeadlineExceeded(val elapsedMs: Long) : Outcome<Nothing>()
        /** The WebView reported itself unusable mid-action. */
        data class Wedged(val message: String) : Outcome<Nothing>()
    }

    /**
     * Wait at most [waitMs] for [mutex]. Returns true when the caller now owns
     * it (and must unlock it in a finally), false when another holder kept it
     * past the window. A lock wait cancelled mid-way never leaves the mutex
     * owned — `Mutex.lock` gives prompt-cancellation guarantees.
     */
    suspend fun lockWithin(mutex: Mutex, waitMs: Long): Boolean =
        withTimeoutOrNull(waitMs) { mutex.lock() } != null

    /**
     * Run one page operation under [deadlineMs].
     *
     * [onSuspect] is called with a reason whenever the tab should be treated
     * as frozen: the deadline fired, or the caller was cancelled after at
     * least [suspectMs]. A cancellation is ALWAYS rethrown — swallowing it
     * would break the caller's structured concurrency.
     */
    suspend fun <T : Any> runGuarded(
        deadlineMs: Long = ACTION_DEAD_TIMEOUT_MS,
        suspectMs: Long = WEDGE_SUSPECT_MS,
        clock: () -> Long = { System.nanoTime() / 1_000_000L },
        onSuspect: (String) -> Unit,
        block: suspend () -> T,
    ): Outcome<T> {
        val startedAt = clock()
        return try {
            val value = withTimeoutOrNull(deadlineMs) { block() }
            if (value != null) {
                Outcome.Done(value)
            } else {
                val elapsed = clock() - startedAt
                onSuspect("action exceeded ${deadlineMs / 1000}s deadline")
                Outcome.DeadlineExceeded(elapsed)
            }
        } catch (e: WebViewWedgedException) {
            Outcome.Wedged(e.message ?: "browser tab stopped responding")
        } catch (e: CancellationException) {
            val elapsed = clock() - startedAt
            if (elapsed >= suspectMs) onSuspect("cancelled after ${elapsed}ms")
            throw e
        }
    }
}
