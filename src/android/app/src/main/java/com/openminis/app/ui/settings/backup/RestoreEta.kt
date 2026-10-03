package com.openminis.app.ui.settings.backup

/**
 * [T-android-restore-ui] Remaining-time estimate for a running restore.
 *
 * A sliding window, not an average since the start. Restore throughput is
 * steady WITHIN a category (measured on a Pixel 4a: 619 records/s, 21% spread
 * over a 226 s window) but differs sharply BETWEEN them — chats move at a very
 * different rate from skills or providers. An all-time average would therefore
 * lurch every time the importer changes category, which is exactly when the
 * user is watching the number.
 *
 * Kept as a plain class with an injected clock so the behaviour is testable
 * without a device: the interesting cases are a stalled restore, a category
 * switch, and the first sample, none of which are convenient to reproduce on
 * real hardware.
 */
class RestoreEta(
    /** Samples kept in the window. ~8 covers 13 s at the observed emit rate. */
    private val windowSize: Int = 8,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private data class Sample(val atMs: Long, val done: Int)

    private val samples = ArrayDeque<Sample>()
    private var categoryKey: String? = null

    /**
     * Feeds a progress reading. Returns the estimate in seconds, or null when
     * one cannot honestly be made yet.
     */
    fun update(categoryKey: String, done: Int, total: Int?): Long? {
        // A new category invalidates the window: its rate says nothing about
        // the next one's, and carrying it over is what makes an ETA jump.
        if (categoryKey != this.categoryKey) {
            this.categoryKey = categoryKey
            samples.clear()
        }
        // `done` going backwards means a fresh pass over the same category;
        // treat it as a restart rather than computing a negative rate.
        val last = samples.lastOrNull()
        if (last != null && done < last.done) samples.clear()

        samples.addLast(Sample(nowMs(), done))
        while (samples.size > windowSize) samples.removeFirst()

        if (total == null || total <= 0) return null
        val remaining = total - done
        if (remaining <= 0) return 0L

        val first = samples.first()
        val cur = samples.last()
        val elapsedMs = cur.atMs - first.atMs
        val progressed = cur.done - first.done
        // Need two distinct readings separated in time; one sample, or a window
        // where nothing moved, cannot produce a rate.
        if (samples.size < 2 || elapsedMs <= 0L || progressed <= 0) return null

        val perMs = progressed.toDouble() / elapsedMs
        if (perMs <= 0.0) return null
        return Math.round(remaining / perMs / 1000.0).coerceAtLeast(0L)
    }

    /** Drops all state — call when a restore ends, so the next one starts clean. */
    fun reset() {
        samples.clear()
        categoryKey = null
    }

    companion object {
        /**
         * Buckets an estimate for display. Returns null when there is nothing
         * worth saying, so the caller can omit the line entirely rather than
         * render a placeholder.
         *
         * Deliberately coarse. The estimate is inherently noisy, and a
         * second-by-second countdown invites the user to notice it being
         * wrong; "about 2 minutes" stays true across a much wider band than
         * "1 m 47 s" does.
         */
        fun bucket(seconds: Long?): EtaBucket? = when {
            seconds == null -> null
            seconds <= 3L -> EtaBucket.AlmostDone
            seconds < 60L -> EtaBucket.Seconds(seconds)
            else -> EtaBucket.MinutesSeconds(seconds / 60L, seconds % 60L)
        }
    }

    sealed class EtaBucket {
        /** Close enough that a number would only flicker. */
        object AlmostDone : EtaBucket()
        data class Seconds(val seconds: Long) : EtaBucket()
        data class MinutesSeconds(val minutes: Long, val seconds: Long) : EtaBucket()
    }
}
