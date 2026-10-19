package com.openminis.app.ui.chat

/**
 * [T-android-context-usage-hint] Context-window pressure model shared by the
 * composer placeholder line, the composer's inner glow and the voice panel's
 * status row.
 *
 * Port of iOS `ContextUsage` / `ContextUsageHint` / `ContextTierCrossingTracker`
 * (`src/ios/Agent/Chat/ChatModels.swift`). Product behaviour is aligned with
 * iOS; the shape is Kotlin-idiomatic (data classes + a pure tracker) rather
 * than a transliteration, and everything here is free of Android and Compose
 * types so it is unit-testable on the JVM — the same rule
 * [ComposerPlaceholderRotation] follows.
 */

/**
 * A single observation of "how full is the context window right now".
 *
 * [usedTokens] is the most recent `LLMUsage.latestContextTokens` (input +
 * cache_read + cache_creation — the whole prompt the model just saw), and
 * [windowTokens] is the effective window for the bound model/group. Both come
 * from data the ViewModel already tracks; nothing here re-derives them.
 */
internal data class ContextUsage(
    val usedTokens: Int,
    val windowTokens: Int,
) {
    val fraction: Double
        get() = if (windowTokens > 0) usedTokens.toDouble() / windowTokens.toDouble() else 0.0

    val percent: Int
        get() = Math.round(fraction * 100).toInt()

    val tier: Tier
        get() = when {
            fraction >= CRITICAL_FRACTION -> Tier.CRITICAL
            fraction >= WARNING_FRACTION -> Tier.WARNING
            else -> Tier.NORMAL
        }

    /**
     * Pressure bands. [rank] exists so "crossed upward" is an ordering
     * comparison rather than a `when` over pairs — the tracker below leans on
     * it, and adding a band later cannot silently break that comparison.
     */
    internal enum class Tier(val rank: Int) {
        /** Below 70%: no line, no glow. The feature is invisible here. */
        NORMAL(0),

        /** 70–80%: amber numbers, amber glow. */
        WARNING(1),

        /** 80%+: red numbers, red glow, breathing. */
        CRITICAL(2),
    }

    companion object {
        const val WARNING_FRACTION = 0.70
        const val CRITICAL_FRACTION = 0.80

        /**
         * Build a usage from raw numbers, or null when there is nothing
         * trustworthy to show.
         *
         * Guarding on BOTH numbers being positive is what keeps a provider
         * that omits usage from rendering a confident "Context 0% used · 0 /
         * 0". A missing measurement must look like no measurement, never like
         * an empty context.
         */
        fun from(usedTokens: Int, windowTokens: Int?): ContextUsage? {
            if (usedTokens <= 0) return null
            if (windowTokens == null || windowTokens <= 0) return null
            return ContextUsage(usedTokens, windowTokens)
        }
    }
}

/**
 * A formatted line ready for the composer, plus the substrings that get the
 * highlight colour.
 *
 * [generation] is the de-duplication key: the UI renders a given generation
 * once, so a recomposition (or a second collector) cannot re-show a line the
 * user already dismissed by typing. [highlights] carries the number segments
 * so the renderer can colour them without re-parsing the localized string —
 * which would break the moment a translation reorders `%1$s` / `%2$s`.
 */
internal data class ContextUsageHint(
    val generation: Int,
    val text: String,
    val highlights: List<String>,
    val tier: ContextUsage.Tier,
)

/**
 * Short token counts ("850", "1.2k", "124k").
 *
 * Deliberately identical to iOS `TokenCountFormatter.short` and to the
 * existing `formatTokenCount` in `ChatAssistantMessageUI.kt`, so the usage
 * line and the per-message usage capsule can never disagree on rounding for
 * the same number.
 */
internal object TokenCountFormatter {
    fun short(count: Int): String {
        if (count < 1000) return count.toString()
        val k = count / 1000.0
        return if (k % 1.0 == 0.0) "${k.toInt()}k"
        else String.format(java.util.Locale.US, "%.1fk", k)
    }

    /** The "124k / 200k" half of the line. */
    fun sizePair(usage: ContextUsage): String =
        "${short(usage.usedTokens)} / ${short(usage.windowTokens)}"
}

/**
 * [T-android-context-usage-hint] Decides whether a usage update is a "first
 * crossing" worth interrupting the user for while a turn is still running.
 *
 * The window is not monotonic — switching models changes the denominator and a
 * compaction shrinks the numerator — so the tier can rise, fall and rise again
 * inside one session. Hence: every observation updates [lastTier], but only an
 * observation whose tier is strictly higher than the previous one counts as a
 * crossing.
 *
 * The rate limit is deliberately narrower than "one hint per interval". A
 * value flapping across a line (69% → 71% → 69% → 71%) must not flash the
 * composer, but a genuine climb past a HIGHER line — warning then critical a
 * second later — is exactly the case the user most needs to see, so it is
 * never held back. That is why [observe] only applies the interval when the
 * new tier is at or below the last tier actually announced.
 *
 * Pure: `now` is injected rather than read from the clock, so the tests below
 * can pin rate-limit behaviour without sleeping.
 */
internal class ContextTierCrossingTracker {

    var lastTier: ContextUsage.Tier = ContextUsage.Tier.NORMAL
        private set

    var lastFiredAtMs: Long? = null
        private set

    var lastFiredTier: ContextUsage.Tier? = null
        private set

    /**
     * Session load / switch: adopt the loaded tier WITHOUT treating it as a
     * crossing, and forget the previous session's rate-limit state.
     *
     * Without this, opening an already-long session would fire a hint for a
     * threshold the user crossed hours ago on a different conversation.
     */
    fun reset(tier: ContextUsage.Tier = ContextUsage.Tier.NORMAL) {
        lastTier = tier
        lastFiredAtMs = null
        lastFiredTier = null
    }

    /**
     * Record [tier] and report whether it is an upward crossing that is not
     * rate-limited.
     *
     * Does NOT mark a firing — the caller still has veto conditions the
     * tracker cannot see (composer non-empty, sub-agent turn, no usage data).
     * It calls [markFired] only once a hint is genuinely shown, so a
     * suppressed crossing does not start the cooldown.
     */
    fun observe(tier: ContextUsage.Tier, nowMs: Long): Boolean {
        val previous = lastTier
        lastTier = tier
        if (tier.rank <= previous.rank) return false
        val firedAt = lastFiredAtMs
        if (firedAt != null &&
            nowMs - firedAt < MIN_INTERVAL_MS &&
            tier.rank <= (lastFiredTier?.rank ?: -1)
        ) {
            return false
        }
        return true
    }

    fun markFired(tier: ContextUsage.Tier, nowMs: Long) {
        lastFiredAtMs = nowMs
        lastFiredTier = tier
    }

    /**
     * True when a hint for this same tier fired less than [MIN_INTERVAL_MS]
     * ago — lets the loop-END path skip a duplicate line for a tier the
     * mid-loop path just announced.
     */
    fun recentlyFired(tier: ContextUsage.Tier, nowMs: Long): Boolean {
        val firedAt = lastFiredAtMs ?: return false
        if (lastFiredTier != tier) return false
        return nowMs - firedAt < MIN_INTERVAL_MS
    }

    companion object {
        /** Matches iOS `ContextTierCrossingTracker.minInterval` (4s). */
        const val MIN_INTERVAL_MS = 4_000L
    }
}

/**
 * [T-android-context-usage-hint] Lifecycle of the usage line once it is on
 * screen: how focus and typing retire it.
 *
 * Kept separate from the tracker (which decides when a line is BORN) because
 * the two answer different questions and are driven by different events — the
 * tracker by usage arriving, this by the user touching the composer. Both are
 * pure so `ContextUsageHintTest` can drive them without a device.
 *
 * The rule set, mirroring iOS `rotatePlaceholderOnFocus`:
 *
 *  - **The first focus gain is protected.** After a reply lands, the keyboard
 *    may auto-raise (a Minis setting) and that focus must not instantly swap
 *    the line the user has not read yet. The user's own first tap is protected
 *    identically — there is no way to tell the two apart, and both deserve it.
 *  - **The second focus retires it**, handing the label back to the ordinary
 *    rotation. Someone who has focused twice without typing is no longer
 *    reading the figure.
 *  - **Typing retires it immediately** — the line is behind the text anyway.
 *  - **Clearing the text does not revive it.** A retired hint is gone for
 *    good; only a NEW generation (the next reply) shows a line again.
 */
internal class ContextUsageHintLifecycle {

    /** The line currently owning the placeholder, or null. */
    var hint: ContextUsageHint? = null
        private set

    /** Whether the one protected focus gain has been spent. */
    var hasProtectedFirstFocus: Boolean = false
        private set

    /** Highest generation this lifecycle has ever accepted. */
    private var lastAcceptedGeneration: Int = -1

    val isShowing: Boolean get() = hint != null

    /**
     * Adopt [candidate], unless it is one we have already shown.
     *
     * The generation guard is what makes `present` idempotent: Compose may
     * re-deliver the same state object across recompositions, and without this
     * a line the user retired by typing would pop back on the next frame.
     */
    fun present(candidate: ContextUsageHint): Boolean {
        if (candidate.generation <= lastAcceptedGeneration) return false
        lastAcceptedGeneration = candidate.generation
        hint = candidate
        hasProtectedFirstFocus = false
        return true
    }

    /**
     * A composer "focus event" happened. Returns true when the caller should
     * run its normal placeholder rotation, false when the usage line keeps
     * the label.
     *
     * [T-android-context-usage-second-focus] "Focus event" deliberately means
     * *the keyboard came up*, not *Compose's `isFocused` went true* — see
     * [onImeShown] for why the two are not the same on Android.
     */
    fun onFocusGained(): Boolean {
        if (hint == null) return true
        if (!hasProtectedFirstFocus) {
            hasProtectedFirstFocus = true
            return false
        }
        retire()
        return true
    }

    /**
     * [T-android-context-usage-second-focus] The IME became visible.
     *
     * This is the Android-correct trigger for "the user came back to the
     * composer", and it exists because the original wiring — an `isFocused`
     * rising edge — cannot see the second visit at all.
     *
     * The back key hides the keyboard *without clearing Compose focus*
     * (verified on a Pixel 4a: `mInputShown=false` while the field still held
     * focus). So after the first visit `isFocused` stays true forever, no
     * rising edge is ever produced again, and the hint sat in its protected
     * state permanently instead of retiring on the second visit. iOS does not
     * have this problem because dismissing the keyboard resigns first
     * responder, making focus loss and keyboard dismissal the same event.
     *
     * Keyboard visibility is the signal that actually tracks the user's
     * attention here, and it is correct for every dismissal route (back key,
     * scroll-to-dismiss, send, an explicit `clearFocus`), not just the ones a
     * focus edge happens to cover.
     */
    fun onImeShown(): Boolean = onFocusGained()

    /**
     * Composer text changed. Any non-empty text retires the line.
     *
     * Note the asymmetry with [present]: going back to empty does NOT restore
     * it, because [lastAcceptedGeneration] has already consumed that
     * generation. "Cleared the box" is not "asked to see the figure again".
     */
    fun onTextChanged(text: String) {
        if (text.isNotEmpty()) retire()
    }

    /** Drop the line without consuming a generation (session switch). */
    fun retire() {
        hint = null
        hasProtectedFirstFocus = false
    }
}
