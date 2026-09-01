package com.openminis.app.ui.chat

/**
 * [T-android-composer-placeholder-rotation] Picks which composer placeholder
 * to show, rotating through feature hints as the user focuses the input.
 *
 * Ported from iOS `457ee8cb4`. The rotation is driven by the **focus edge**,
 * not a timer: the composer is static most of the time, and the focus moment
 * is exactly when the user is looking at the field anyway. A timer would burn
 * work on every idle screen for the same discovery value.
 *
 * Semantics that matter (all pinned by `ComposerPlaceholderRotationTest`):
 *
 *  - **The default is element 0 of the pool**, a rotation candidate like any
 *    other, so it cycles back instead of being replaced forever.
 *  - **First focus is split by session state.** A session WITH messages
 *    rotates immediately — the default has nothing left to teach someone
 *    already mid-conversation. An EMPTY session keeps the default, because
 *    there it is the only line telling a new user what the field is for.
 *  - **Never repeats the entry already on screen**, so a rotation always
 *    visibly changes something.
 *  - **Accessibility**: with a screen reader active the index is pinned and
 *    never rotates — swapping the label would retrigger announcements over
 *    the user mid-read.
 *
 * State is a plain index so the caller can hold it in `rememberSaveable` and
 * survive rotation/process death. Pure and side-effect free: no Android types,
 * no Compose types, unit-testable without a device.
 */
internal object ComposerPlaceholderRotation {

    /** Index of the default placeholder within the pool. */
    const val DEFAULT_INDEX = 0

    /**
     * Total pool size = the default plus [HINT_COUNT] feature hints. Keep in
     * sync with the string list built in `composerPlaceholderPool`.
     */
    const val HINT_COUNT = 5
    const val POOL_SIZE = HINT_COUNT + 1

    /**
     * Resolve the next placeholder index on a focus gain.
     *
     * @param current        index currently on screen
     * @param hasFocusedBefore whether the composer has been focused before in
     *                       this session — distinguishes the first focus
     * @param sessionHasMessages whether the conversation already has messages
     * @param screenReaderOn whether a screen reader (TalkBack) is active
     * @param randomIndex    supplier of a candidate index in [0, POOL_SIZE);
     *                       injected so tests are deterministic
     */
    fun nextIndex(
        current: Int,
        hasFocusedBefore: Boolean,
        sessionHasMessages: Boolean,
        screenReaderOn: Boolean,
        randomIndex: (Int) -> Int,
    ): Int {
        // TalkBack: pin whatever is showing. Rotating re-announces the field
        // while the user may be partway through reading it.
        if (screenReaderOn) return current

        // First focus in an empty session: the default is the new user's only
        // explanation of the field. It survives the first tap.
        if (!hasFocusedBefore && !sessionHasMessages) return current

        // Pick anything but what's already displayed, so the rotation is
        // always visible. Drawing from a size-1 space below the cap and
        // shifting past `current` gives a uniform pick without a retry loop.
        val draw = randomIndex(POOL_SIZE - 1)
        return if (draw >= current) draw + 1 else draw
    }
}
