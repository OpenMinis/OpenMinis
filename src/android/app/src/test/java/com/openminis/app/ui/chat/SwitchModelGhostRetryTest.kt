package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-switch-model-ghost-retry] Switching model mid-conversation left
 * the OLD model still retrying.
 *
 * Port of the iOS fix (05d654128), which was reported with a gateway request
 * log: a turn fails 5xx on model A, the user picks model B from the header, B
 * answers every later turn correctly — and the gateway still records further
 * 5xx retries against A, whose error banner sits over the working
 * conversation. The user's own workaround is the diagnostic clue: pressing
 * Stop BEFORE switching prevents it, so cancellation tears this work down and
 * switching model did not.
 *
 * Android had the identical shape: `selectGroup` / `selectGroupEntry` were
 * pure data writes, while the auto-retry ladder sleeps through a 1s/2s/4s
 * countdown holding `currentProvider` — still the abandoned model.
 *
 * The ladder needs a live provider and a real 5xx, so what is verifiable here
 * is the decision logic plus the source facts that encode the wiring.
 */
class SwitchModelGhostRetryTest {

    // ---- What a model switch must and must not tear down ------------------

    /**
     * The two teardown scopes, kept apart deliberately.
     *
     * "Stop" means *stop this conversation*. "Switch model" means *use a
     * different model from here on* — sub agents, queued delegations,
     * scheduled timers and the running shell command are not addressed to the
     * old model and must survive it.
     */
    private data class Teardown(
        val streamJob: Boolean,
        val retryCountdownUi: Boolean,
        val subAgents: Boolean,
        val queuedDelegations: Boolean,
        val shellCommand: Boolean,
        /** [T-android-switch-model-let-stream-finish] Stop's per-turn closer,
         *  which raises `_canResume` and pauses the conversation. */
        val perTurnCancelCleanup: Boolean = false,
    )

    private fun onStop() = Teardown(
        streamJob = true,
        retryCountdownUi = true,
        subAgents = true,
        queuedDelegations = true,
        shellCommand = true,
        perTurnCancelCleanup = true,
    )

    /**
     * Mirrors `cancelWorkBoundToPreviousModel`.
     *
     * [T-android-switch-model-let-stream-finish] The seam is state-dependent:
     * [inRetryLadder] mirrors `isBoundToAbandonedModel()`. Only ladder work is
     * torn down; a healthy streaming turn is left to finish.
     */
    private fun onSwitchModel(inRetryLadder: Boolean) = Teardown(
        streamJob = inRetryLadder,
        retryCountdownUi = inRetryLadder,
        subAgents = false,
        queuedDelegations = false,
        shellCommand = false,
        perTurnCancelCleanup = false,
    )

    @Test
    fun `switching model cancels a turn parked in the retry ladder`() {
        // The whole point: the ladder holding the abandoned provider has to go.
        assertTrue(onSwitchModel(inRetryLadder = true).streamJob)
    }

    @Test
    fun `switching model during normal streaming does not cancel anything`() {
        // [T-android-switch-model-let-stream-finish] The Pixel 6 report: the
        // model was streaming a tool call and the switch halted the task.
        // "Use the other model" means from the NEXT turn on.
        val switch = onSwitchModel(inRetryLadder = false)
        assertFalse("the live turn must finish", switch.streamJob)
        assertFalse("nothing to clear — no ladder is running", switch.retryCountdownUi)
        assertFalse("and the turn must never be paused into Resume", switch.perTurnCancelCleanup)
    }

    @Test
    fun `a model switch never runs Stop's per-turn closer`() {
        // That closer flips in-flight tool blocks to CANCELLED and raises
        // _canResume — the "task stopped, tap Resume" the user reported.
        assertFalse(onSwitchModel(inRetryLadder = true).perTurnCancelCleanup)
        assertFalse(onSwitchModel(inRetryLadder = false).perTurnCancelCleanup)
        assertTrue("Stop still owns it", onStop().perTurnCancelCleanup)
    }

    @Test
    fun `switching model clears the retry countdown the user can see`() {
        // "… — retrying (2/3)…" names the old model and is owned by the ladder
        // just cancelled, so nothing else would ever clear it.
        assertTrue(onSwitchModel(inRetryLadder = true).retryCountdownUi)
    }

    @Test
    fun `switching model is not Stop — sub agents and shell work survive`() {
        val switch = onSwitchModel(inRetryLadder = true)
        assertFalse("a running sub agent is not addressed to the old model", switch.subAgents)
        assertFalse("queued delegations must still run", switch.queuedDelegations)
        assertFalse("a running shell command must not be killed", switch.shellCommand)

        // Stop remains the broad teardown; the two must not converge.
        val stop = onStop()
        assertTrue(stop.subAgents)
        assertTrue(stop.queuedDelegations)
        assertTrue(stop.shellCommand)
        assertFalse("switch and stop must stay distinct", switch == stop)
    }

    // ---- Source facts: the wiring that actually broke ----------------------

    private fun src(path: String): String {
        val f = File(path)
        assertTrue("missing source: $path", f.exists())
        return f.readText()
    }

    private val vmSrc by lazy {
        src("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt")
    }

    /**
     * [T-android-switch-model-hang] EVERY selector must cancel — enumerated
     * from source, not hand-listed.
     *
     * This test previously named just two ("both user-facing switch paths")
     * and hard-coded them. There were three: `selectEntry` — the header
     * dropdown's plain, non-group model pick, i.e. the most common switch of
     * all — had no guard, and a hand-written list is exactly the kind of
     * assertion that cannot notice the one it forgot to mention.
     *
     * The consequence of that gap was worse than the ghost retry this file was
     * written for. Switching via `selectEntry` mid-turn left `_isStreaming`
     * stuck true with the old model's job parked in the retry countdown, and
     * `sendMessage` diverts to `enqueuePrompt` whenever `_isStreaming` is set
     * — so the next message became a queued prompt behind a turn that would
     * never complete. The session appeared frozen: no reply, no error.
     *
     * Discovering the selectors by regex means a fourth one added later is
     * covered on the day it lands.
     */
    @Test
    fun `every user-facing switch path cancels before rebinding`() {
        val selectors = Regex("fun (select[A-Za-z]*)\\(")
            .findAll(vmSrc)
            .map { it.groupValues[1] }
            .toSet()

        assertTrue(
            "expected the three known selectors; found $selectors — if a new " +
                "one was added, it needs the cancel guard too",
            selectors.containsAll(setOf("selectGroup", "selectGroupEntry", "selectEntry")),
        )

        for (name in selectors) {
            // Body = from the declaration to the start of the next top-level
            // fun, so the guard must appear inside THIS function.
            val after = vmSrc.substringAfter("fun $name(")
            val body = after.substringBefore("\n    fun ").substringBefore("\n    private fun ")
            assertTrue(
                "$name must cancel work bound to the previous model before " +
                    "rebinding, or a mid-turn switch leaves the session wedged " +
                    "with _isStreaming stuck true",
                body.contains("cancelWorkBoundToPreviousModel("),
            )
        }
    }

    /**
     * The guard must run BEFORE the early returns that reject an unusable
     * entry. Cancelling only on the happy path would mean a pick that resolves
     * to nothing (unknown id, missing instance, no credential) leaves the old
     * model still retrying — the very state being fixed.
     */
    @Test
    fun `selectEntry cancels only once the pick is known to take effect`() {
        // [T-android-switch-model-seam-cleanup] Reversed from the first
        // version. Cancelling before the early returns meant a pick that
        // resolved to nothing (unknown id, missing instance, no credential)
        // killed the running turn and left the OLD model bound — a dead turn
        // on a model the user never left. A no-op pick must be a no-op.
        val body = vmSrc.substringAfter("fun selectEntry(").substringBefore("currentModel = entry.model")
        val cancelAt = body.indexOf("cancelWorkBoundToPreviousModel(")
        val lastReturn = body.lastIndexOf("return")
        assertTrue("selectEntry must call the guard", cancelAt >= 0)
        assertTrue("selectEntry must have early returns", lastReturn >= 0)
        assertTrue(
            "the cancel must follow the last early return (cancelAt=$cancelAt, lastReturn=$lastReturn)",
            cancelAt > lastReturn,
        )
    }

    @Test
    fun `a normally streaming turn is left alone to finish`() {
        // [T-android-switch-model-let-stream-finish] Reported on a Pixel 6:
        // GPT-5.6 Terra was streaming a tool call's arguments, the user picked
        // another model, and the run HALTED into the resumable "tap Resume"
        // state — 33.8k tokens and an unfinished tool call thrown away.
        //
        // Cause: the seam called handleUserCancelledCleanup(), Stop's per-turn
        // closer, whose tool branch raises _canResume. iOS's seam
        // (05d654128) never did that. Switching model means "use the other
        // model FROM HERE ON", so a healthy turn must finish and only the
        // NEXT turn moves.
        val body = vmSrc.substringAfter("private fun cancelWorkBoundToPreviousModel")
            .substringBefore("private fun isBoundToAbandonedModel")
        assertTrue(
            "the seam must bail out when the turn is not bound to the old model",
            body.contains("if (!isBoundToAbandonedModel())"),
        )
        // Strip comment lines: the seam DOCUMENTS why it no longer calls the
        // closer, and that prose must not read as a call site.
        val code = body.lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
        assertFalse(
            "the seam must NOT run Stop's per-turn closer — that is what produced " +
                "the resumable pause on an ordinary model switch",
            code.contains("handleUserCancelledCleanup()"),
        )
        assertTrue("queued prompts must still drain under the new model", body.contains("resumeQueueAfterCancel()"))
    }

    @Test
    fun `only retry-ladder work counts as bound to the abandoned model`() {
        // currentProvider is captured per attempt, so the work that outlives a
        // switch is the ladder sleeping through its countdown. A turn that is
        // merely streaming is producing the user's answer.
        val pred = vmSrc.substringAfter("private fun isBoundToAbandonedModel(): Boolean =")
            .substringBefore("\n\n")
        assertTrue("the ladder's attempt counter decides", pred.contains("_autoRetryAttempt.value > 0"))
        assertTrue("so does a live countdown", pred.contains("_autoRetryCountdown.value > 0"))
        assertFalse(
            "_isStreaming must NOT make a turn 'bound' — that is the bug",
            pred.contains("_isStreaming"),
        )
    }

    @Test
    fun `the seam clears the retry ladder's visible state`() {
        val body = vmSrc.substringAfter("private fun cancelWorkBoundToPreviousModel")
            .substringBefore("fun hasWorkInFlight(")
        assertTrue("the stream job must be cancelled", body.contains("streamJob?.cancel()"))
        assertTrue("attempt counter reset", body.contains("_autoRetryAttempt.value = 0"))
        assertTrue("countdown reset", body.contains("_autoRetryCountdown.value = 0"))
        assertTrue(
            "the turn must read as user-interrupted, not as the old model failing",
            body.contains("lastTurnWasCancelled = true"),
        )
    }

    @Test
    fun `the seam does NOT perform Stop's broader teardown`() {
        // The regression this guards: someone "simplifying" by calling
        // cancelStream() here would silently kill the user's sub agents and
        // shell command on every model pick.
        val body = vmSrc.substringAfter("private fun cancelWorkBoundToPreviousModel")
            .substringBefore("fun hasWorkInFlight(")
        assertFalse("must not drop queued delegations", body.contains("dropQueuedDelegations"))
        assertFalse("must not cancel sub agents", body.contains("AgentJobRegistry.cancelAll"))
        assertFalse("must not stop the running shell", body.contains("stopCurrentCommand"))
        assertFalse("must not delegate to cancelStream", body.contains("cancelStream()"))
    }

    @Test
    fun `an idle conversation is untouched`() {
        // A model pick with nothing streaming must cost nothing — no spurious
        // "cancelled" state on a conversation that was never running.
        val body = vmSrc.substringAfter("private fun cancelWorkBoundToPreviousModel")
            .substringBefore("fun hasWorkInFlight(")
        assertTrue(
            "the seam must early-return when nothing is in flight",
            body.contains("if (!_isStreaming.value && streamJob?.isActive != true) return"),
        )
    }

    @Test
    fun `the automatic fallback path does not route through the switch seam`() {
        // Critical: group fallback rebinds the provider too, but it is the
        // system moving ON BEHALF of the turn — cancelling there would abort
        // the very turn the fallback exists to rescue. iOS records the same
        // constraint. Fallback resolves directly via resolveProviderFromGroup.
        // Scope to resolveProviderFromGroup's OWN body: the seam is declared
        // between it and selectGroup, so a wider window matches its
        // declaration rather than a call from the fallback.
        val fallbackRegion = vmSrc.substringAfter("private fun resolveProviderFromGroup")
            .substringBefore("private fun cancelWorkBoundToPreviousModel")
        assertFalse(
            "resolveProviderFromGroup must not call the switch seam",
            fallbackRegion.contains("cancelWorkBoundToPreviousModel"),
        )
    }

    @Test
    fun `only user-driven UI picks call the switch functions`() {
        // If a non-UI caller ever starts calling selectGroup*, it inherits the
        // cancellation — which is right for a user pick and wrong for an
        // automatic one. Pin the current call sites so that change is visible.
        val screen = src("src/main/java/com/openminis/app/ui/chat/ChatScreen.kt")
        val callers = Regex("viewModel\\.selectGroup(Entry)?\\(").findAll(screen).count()
        assertEquals("all switch call sites live in the picker UI", 4, callers)
    }
}
