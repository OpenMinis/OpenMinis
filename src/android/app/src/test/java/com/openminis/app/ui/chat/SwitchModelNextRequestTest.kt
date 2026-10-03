package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-switch-model-next-request] + [T-android-ctx-overflow-attribute-dispatch]
 * Ports of iOS 86a045284 (+ the d6e475a35 follow-ups) and 08f3feea2.
 *
 * Picking another model while a task runs used to take effect only after the
 * whole turn: runAgentLoop kept its own provider for every request of the turn,
 * so a task with 20 tool calls ran all of them on the old model. Now the next
 * REQUEST uses the new model, and the context calibration is credited to the
 * model a request actually went to.
 */
class SwitchModelNextRequestTest {

    private val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")

    private val a = Any()
    private val b = Any()

    @Test
    fun `a pending switch is applied once the picker has put a different provider in place`() {
        assertTrue(ChatViewModel.modelSwitchToApply(pending = true, chosen = b, loopProvider = a))
    }

    @Test
    fun `nothing is applied without a pending switch - a group fallback moving the provider is not a user switch`() {
        assertFalse(ChatViewModel.modelSwitchToApply(pending = false, chosen = b, loopProvider = a))
    }

    @Test
    fun `a switch the picker has not applied yet stays pending`() {
        // The flag is set before the picker rebinds; a loop head in between
        // must not consume it.
        assertFalse(ChatViewModel.modelSwitchToApply(pending = true, chosen = a, loopProvider = a))
        assertFalse(ChatViewModel.modelSwitchToApply(pending = true, chosen = null, loopProvider = a))
    }

    @Test
    fun `a healthy turn is flagged, not cancelled, and the retry ladder still is`() {
        val seam = vm.substringAfter("private fun cancelWorkBoundToPreviousModel(")
            .substringBefore("private var pendingModelSwitch")
        val healthy = seam.substringAfter("if (!isBoundToAbandonedModel()) {").substringBefore("return\n")
        assertTrue(healthy.contains("pendingModelSwitch = true"))
        assertFalse(healthy.contains("streamJob?.cancel()"))
        val ladder = seam.substringAfter("if (!isBoundToAbandonedModel()) {").substringAfter("return\n")
        assertTrue(ladder.contains("pendingModelSwitch = false"))
        assertTrue(ladder.contains("streamJob?.cancel()"))
    }

    @Test
    fun `the loop applies the switch at the top of every iteration, before the request`() {
        val loop = vm.substringAfter("for (turn in 0 until turnCap) {")
        val apply = loop.indexOf("if (c != null && modelSwitchToApply(pendingModelSwitch, c, currentProvider)) {")
        val sanitize = loop.indexOf("sanitizeAgentHistory()")
        val request = loop.indexOf("currentProvider.streamMessage(")
        assertTrue(apply in 0 until sanitize)
        assertTrue(sanitize < request)
        val body = loop.substring(apply, sanitize)
        assertTrue(body.contains("currentProvider = chosen"))
        assertTrue("a later fallback may rebind again", body.contains("classProviderAtSend = chosen"))
        // [T-android-switch-model-fallback-rebind] The fallback chain and its
        // strategy follow the pick, rebuilt in the same Main snapshot.
        assertTrue(body.contains("Triple(c, buildFallbackProviders(c), strategy)"))
        assertTrue(body.contains("remainingFallbacks.clear()"))
        assertTrue(body.contains("remainingFallbacks.addAll(switched.second)"))
        assertTrue(body.contains("loopFallbackStrategy = switched.third"))
        assertTrue(loop.contains("loopFallbackStrategy == com.openminis.app.data.model.FallbackStrategy.always"))
        assertTrue(body.contains("loopSystemPrompt = systemPromptFor(chosen, loopSystemPrompt)"))
    }

    @Test
    fun `the request carries the loop's prompt, and a stale flag is cleared when the turn starts`() {
        assertTrue(vm.contains("loopSystemPrompt, dynamicMaxTokens(currentProvider, dispatchInputTokens),"))
        assertTrue(vm.contains("if (this@ChatViewModel.currentProvider === provider) pendingModelSwitch = false"))
    }

    @Test
    fun `calibration and overflow are scored against the model the request went to`() {
        assertTrue(vm.contains("recordContextDispatch(outboundHistory, currentProvider.model)"))
        val calibrate = vm.substringAfter("private fun calibrateContextSize(").substringBefore("private fun noteContextOverflow(")
        assertTrue(calibrate.contains("val model = lastDispatchModel?.id ?: currentModel?.id"))
        val overflow = vm.substringAfter("private fun noteContextOverflow(").substringBefore("private fun seedContextCalibration(")
        assertTrue(overflow.contains("val model = lastDispatchModel?.id ?: currentModel?.id"))
        assertTrue(overflow.contains("lastDispatchWindow"))
    }
}
