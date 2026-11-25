package com.openminis.app.ui.chat

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharedResourceSamplingTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun `two consumers share one sample and the existing two second cadence`() = runTest(dispatcher) {
        var resets = 0
        var samples = 0
        val sampling = SharedResourceSampling(backgroundScope, { resets++ }, { samples++ })
        runCurrent()
        assertEquals(0, samples)

        var firstTicks = 0
        val first = backgroundScope.launch { sampling.ticks.collect { firstTicks++ } }
        runCurrent()
        assertEquals(1, samples)
        assertEquals(1, resets)
        assertEquals(1, firstTicks)

        var secondTicks = 0
        val second = backgroundScope.launch { sampling.ticks.collect { secondTicks++ } }
        runCurrent()
        assertEquals("opening the sheet must not reread the process", 1, samples)
        assertEquals("the new HUD immediately receives the existing sample", 1, secondTicks)
        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(1, samples)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, samples)
        assertEquals(2, firstTicks)
        assertEquals(2, secondTicks)

        first.cancel()
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals("the remaining HUD keeps the same producer", 3, samples)
        assertEquals(1, resets)
        second.cancel()
        runCurrent()
    }

    @Test
    fun `last subscriber stops polling and next subscriber resets the CPU baseline`() = runTest(dispatcher) {
        var resets = 0
        var samples = 0
        val sampling = SharedResourceSampling(backgroundScope, { resets++ }, { samples++ })
        val first = backgroundScope.launch { sampling.ticks.collect {} }
        runCurrent()
        first.cancel()
        runCurrent()
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(1, samples)
        assertEquals("no stale replay remains without subscribers", emptyList<Unit>(), sampling.ticks.replayCache)

        val returning = backgroundScope.launch { sampling.ticks.collect {} }
        runCurrent()
        assertEquals(2, resets)
        assertEquals(2, samples)
        returning.cancel()
        runCurrent()
    }

    @Test
    fun `inactive tool and stopped lifecycle cannot keep the sampler awake`() = runTest(dispatcher) {
        var resets = 0
        var samples = 0
        val sampling = SharedResourceSampling(backgroundScope, { resets++ }, { samples++ })
        val owner = Owner()
        owner.registry.currentState = Lifecycle.State.STARTED
        val inactive = backgroundScope.launch {
            sampling.collectWhileStarted(owner.lifecycle, active = false) {}
        }
        runCurrent()
        assertTrue(inactive.isCompleted)
        assertEquals(0, samples)

        owner.registry.currentState = Lifecycle.State.CREATED
        val active = backgroundScope.launch {
            sampling.collectWhileStarted(owner.lifecycle, active = true) {}
        }
        runCurrent()
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(0, samples)

        owner.registry.currentState = Lifecycle.State.STARTED
        runCurrent()
        assertEquals(1, samples)
        owner.registry.currentState = Lifecycle.State.CREATED
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals("backgrounded but still-composed tool must not poll", 1, samples)

        owner.registry.currentState = Lifecycle.State.STARTED
        runCurrent()
        assertEquals(2, samples)
        assertEquals(2, resets)
        owner.registry.currentState = Lifecycle.State.DESTROYED
        runCurrent()
        assertTrue(active.isCompleted)
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(2, samples)
    }

    @Test
    fun `a visible second owner keeps sampling when the first owner stops`() = runTest(dispatcher) {
        var resets = 0
        var samples = 0
        val sampling = SharedResourceSampling(backgroundScope, { resets++ }, { samples++ })
        val thumbnail = Owner().apply { registry.currentState = Lifecycle.State.STARTED }
        val sheet = Owner().apply { registry.currentState = Lifecycle.State.STARTED }
        backgroundScope.launch { sampling.collectWhileStarted(thumbnail.lifecycle, true) {} }
        backgroundScope.launch { sampling.collectWhileStarted(sheet.lifecycle, true) {} }
        runCurrent()
        assertEquals(1, samples)

        thumbnail.registry.currentState = Lifecycle.State.CREATED
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(2, samples)
        assertEquals("one hidden owner must not reset another HUD's window", 1, resets)

        sheet.registry.currentState = Lifecycle.State.CREATED
        runCurrent()
        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(2, samples)
        thumbnail.registry.currentState = Lifecycle.State.DESTROYED
        sheet.registry.currentState = Lifecycle.State.DESTROYED
        runCurrent()
    }

    @Test
    fun `cadence waits two seconds after a sample completes`() = runTest(dispatcher) {
        var starts = 0
        var completed = 0
        val sampling = SharedResourceSampling(backgroundScope, {}, {
            starts++
            delay(250)
            completed++
        })
        val collector = backgroundScope.launch { sampling.ticks.collect {} }
        runCurrent()
        assertEquals(1, starts)
        advanceTimeBy(250)
        runCurrent()
        assertEquals(1, completed)
        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(1, starts)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, starts)
        assertEquals(1, completed)
        collector.cancel()
        runCurrent()
    }

    @Test
    fun `cancelled sample finishes before a replacement producer starts`() = runTest(dispatcher) {
        var resets = 0
        var starts = 0
        var inFlight = 0
        var peakInFlight = 0
        var completed = 0
        val publishedCompletions = mutableListOf<Int>()
        val sampling = SharedResourceSampling(backgroundScope, { resets++ }, {
            starts++
            inFlight++
            peakInFlight = maxOf(peakInFlight, inFlight)
            try {
                // Model an outstanding blocking IO read: cancellation cannot
                // finish this sample until its existing work has returned.
                withContext(NonCancellable) {
                    delay(5_000)
                    completed++
                }
            } finally {
                inFlight--
            }
        })
        val first = backgroundScope.launch {
            sampling.ticks.collect { publishedCompletions += completed }
        }
        runCurrent()
        assertEquals(1, inFlight)
        first.cancel()
        runCurrent()
        assertEquals("cancellation must wait for the outstanding read", 1, inFlight)

        // Subscribe again BEFORE the cancelled read has returned. The shared
        // producer must join that read before resetting or starting another.
        val replacement = backgroundScope.launch {
            sampling.ticks.collect { publishedCompletions += completed }
        }
        runCurrent()
        assertEquals(1, resets)
        assertEquals(1, starts)
        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(1, inFlight)
        assertEquals(1, resets)
        assertEquals(1, starts)
        assertEquals(0, completed)
        assertTrue(publishedCompletions.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, completed)
        assertEquals(2, resets)
        assertEquals(2, starts)
        assertEquals(1, inFlight)
        assertEquals(1, peakInFlight)
        assertTrue("the cancelled producer must not publish its stale sample",
            publishedCompletions.isEmpty())

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, completed)
        assertEquals(listOf(2), publishedCompletions)
        assertEquals(1, peakInFlight)
        replacement.cancel()
        runCurrent()
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(0, inFlight)
        assertEquals(2, starts)
        assertEquals(listOf(2), publishedCompletions)
    }
}
