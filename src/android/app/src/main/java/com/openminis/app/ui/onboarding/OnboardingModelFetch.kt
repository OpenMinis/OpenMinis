package com.openminis.app.ui.onboarding

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [T-onboarding-model-fetch-fallback] The onboarding model picker's own,
 * bounded model fetch. iOS parity (OnboardingModelFetch.withTimeout,
 * 7d17d29c4).
 *
 * The picker used to show a spinner for as long as its list was empty. The
 * fetch that fills the list runs through the repository's reconciler, which
 * logs a failure and tells no one, so a provider whose /models endpoint timed
 * out, errored or does not exist left the page spinning forever, with Skip as
 * the only way forward.
 *
 * Pure (no Android types) so the timeout and error collection are unit-tested
 * with virtual time.
 */
internal object OnboardingModelFetch {

    /** Page-level budget for all providers together (iOS: 20 s). */
    const val TIMEOUT_MS = 20_000L

    data class Result(
        /** Error messages the providers reported, in completion order. */
        val errors: List<String>,
        /** The budget ran out before every provider answered. */
        val timedOut: Boolean,
    )

    /**
     * Run [fetch] for every target in parallel within [timeoutMs]. [fetch]
     * returns an error message, or null when it had nothing to report; a
     * thrown exception counts as its message. On timeout the fetches still
     * running are cancelled.
     */
    suspend fun <T> run(
        targets: List<T>,
        timeoutMs: Long = TIMEOUT_MS,
        fetch: suspend (T) -> String?,
    ): Result {
        val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
        val finished = withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                targets.map { target ->
                    async {
                        try {
                            fetch(target)?.let { errors += it }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            errors += e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                        }
                    }
                }.awaitAll()
            }
            true
        }
        return Result(errors = synchronized(errors) { errors.toList() }, timedOut = finished == null)
    }
}
