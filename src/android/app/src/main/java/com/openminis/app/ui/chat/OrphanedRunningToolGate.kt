package com.openminis.app.ui.chat

/**
 * [T-android-orphaned-running-tool-spin] Decides whether a tool block may keep
 * rendering as RUNNING.
 *
 * Measured on a Pixel 6: opening a 144-message session took the app from 0% to
 * a sustained 85-130% of a CPU core while completely idle, producing 730 frames
 * in 8 seconds (91 fps) with no stream, no task and nothing on screen changing.
 * Backgrounding the app dropped it to 0%; reopening that one session brought it
 * straight back. Smaller sessions never did it.
 *
 * The session contained exactly one tool block still marked RUNNING, left over
 * from a sub-agent delegation whose completion never landed. A RUNNING block
 * draws a `rememberInfiniteTransition` shimmer (ChatAssistantMessageUI
 * `toolPillShimmer`), and an infinite transition invalidates every frame for as
 * long as it is composed — so one orphaned block pins the whole UI at display
 * refresh rate forever, heating the device and draining battery with no work
 * being done.
 *
 * Two things conspired to make the state permanent:
 *
 *  - `delegateBlockOverrides` (ChatViewModel) is a process-lifetime map keyed
 *    by tool id. It is cleared per-tool on completion but never on session
 *    load, so a delegate that never completed keeps re-applying RUNNING over
 *    the freshly rebuilt blocks on every single reload.
 *  - The DB rebuild path itself can never produce RUNNING (it derives
 *    SUCCESS / FAILED / CANCELLED from the persisted tool_result), so the
 *    stale override is the only source of truth that says "still running" —
 *    and nothing ever contradicts it.
 *
 * The rule below closes that: RUNNING is a claim about *right now*, so it must
 * be backed by a live job. A block whose job is gone is not running, whatever
 * the persisted or overridden status says.
 */
internal object OrphanedRunningToolGate {

    /**
     * Whether [status] may be shown as-is, given whether the owning job is
     * still live.
     *
     * Only the spinner-bearing states are gated. A terminal status is a
     * historical fact and is always allowed through — re-deriving those would
     * risk rewriting a correct SUCCESS into something else.
     *
     * @param isLiveRun true when this view model is actively streaming this
     *   very turn. During a live turn the registry may not have registered the
     *   job yet (the block is created before the child starts), so the
     *   in-flight case is trusted and never downgraded.
     * @param jobIsAlive whether a job backing this block is present and active
     *   in `AgentJobRegistry`.
     */
    fun resolve(
        status: ToolBlockStatus?,
        isLiveRun: Boolean,
        jobIsAlive: Boolean,
    ): ToolBlockStatus? {
        if (status == null) return null
        if (!isSpinner(status)) return status
        if (isLiveRun || jobIsAlive) return status
        // Orphaned: the run it belonged to is not happening any more. TIMEOUT
        // rather than FAILED — we do not know that the tool errored, only that
        // its outcome never came back, which is exactly what a timeout is. It
        // also renders without a spinner, which is the point.
        return ToolBlockStatus.TIMEOUT
    }

    /** The states that draw an infinite shimmer / progress animation. */
    fun isSpinner(status: ToolBlockStatus?): Boolean =
        status == ToolBlockStatus.RUNNING ||
            status == ToolBlockStatus.STREAMING ||
            status == ToolBlockStatus.PENDING

    /**
     * Whether a stale override may be applied on top of a rebuilt block.
     *
     * `mergeDelegateOverrides` re-applies the in-memory override after every
     * reload. A spinner override from a dead run must not win over the status
     * the DB just derived, or the orphan is immortal.
     */
    fun overrideMayApply(
        overrideStatus: ToolBlockStatus?,
        isLiveRun: Boolean,
        jobIsAlive: Boolean,
    ): Boolean {
        if (!isSpinner(overrideStatus)) return true
        return isLiveRun || jobIsAlive
    }
}

/**
 * [T-android-orphaned-running-tool-spin] Display-side repair for a message list
 * that came back from the database.
 *
 * A tool block persisted as STREAMING/PENDING/RUNNING means the app died (or the
 * process was reclaimed) before its outcome was written. Nothing will ever
 * resolve it, so it shimmers forever 鈥?the measured 85-130% idle CPU in the
 * KDoc above. [resolve] turns those into TIMEOUT unless the run is live or a job
 * with that id is still alive; terminal states pass through untouched.
 *
 * @param isLiveRun the session currently has a turn in flight
 * @param jobIsAlive asks the job registry whether a block id still has work
 */
internal fun List<ChatMessage>.demoteOrphanedToolSpinners(
    isLiveRun: Boolean,
    jobIsAlive: (String) -> Boolean = { false },
): List<ChatMessage> = map { message ->
    if (message.toolBlocks.isEmpty()) {
        message
    } else {
        message.copy(
            toolBlocks = message.toolBlocks.map { block ->
                block.copy(
                    toolStatus = OrphanedRunningToolGate.resolve(
                        status = block.toolStatus,
                        isLiveRun = isLiveRun,
                        jobIsAlive = jobIsAlive(block.id),
                    ),
                )
            },
        )
    }
}
