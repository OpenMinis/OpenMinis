package com.openminis.app.ui.chat

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThinkingStreamBufferTest {
    @Test
    fun `a burst copies and publishes first and latest snapshots rather than every delta`() = runTest {
        var copies = 0
        val buffer = ThinkingStreamBuffer("thinking_1", materialize = { copies++; it.toString() })
        val published = mutableListOf<ThinkingStreamBuffer.Snapshot>()
        backgroundScope.launch { buffer.publishPending({ testScheduler.currentTime }) { published += it } }
        buffer.append("first")
        runCurrent()
        assertEquals(listOf("first"), published.map { it.text })

        repeat(1_000) { buffer.append("x") }
        runCurrent()
        assertEquals(1, copies)
        advanceTimeBy(199)
        runCurrent()
        assertEquals(1, published.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, copies)
        assertEquals("first" + "x".repeat(1_000), published.last().text)
        assertEquals(2, published.size)
    }

    @Test
    fun `sustained deltas have bounded materializations and retain every character`() = runTest {
        var copies = 0
        val buffer = ThinkingStreamBuffer("thinking_1", materialize = { copies++; it.toString() })
        val published = mutableListOf<String>()
        backgroundScope.launch { buffer.publishPending({ testScheduler.currentTime }) { published += it.text } }
        repeat(1_000) {
            buffer.append("a")
            runCurrent()
            advanceTimeBy(1)
        }
        runCurrent()
        val final = buffer.closeAndSnapshot()!!
        assertEquals("a".repeat(1_000), final.text)
        assertTrue("materialized $copies snapshots for 1000 deltas", copies <= 7)
        assertTrue("published ${published.size} snapshots", published.size <= 6)
        assertTrue(published.zipWithNext().all { (a, b) -> b.startsWith(a) })
    }

    @Test
    fun `a provider pause still publishes its pending tail on the deadline`() = runTest {
        val buffer = ThinkingStreamBuffer("thinking_1")
        val published = mutableListOf<String>()
        backgroundScope.launch { buffer.publishPending({ testScheduler.currentTime }) { published += it.text } }
        buffer.append("a")
        runCurrent()
        advanceTimeBy(50)
        buffer.append("b")
        runCurrent()
        advanceTimeBy(150)
        runCurrent()
        assertEquals(listOf("a", "ab"), published)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals("idle periods do not repeat unchanged snapshots", 2, published.size)
    }

    @Test
    fun `text and tool boundaries drain complete thinking without reordering blocks`() {
        val buffer = ThinkingStreamBuffer("thinking_1")
        val before = AssistantBlock(id = "text_0", kind = "text", content = "before")
        var blocks = listOf(before)
        buffer.append("first ")
        blocks = buffer.takeIfDue(0)!!.applyTo(blocks)
        buffer.append("final")
        blocks = buffer.snapshot(5)!!.applyTo(blocks)
        val tool = AssistantBlock(id = "tool_1", kind = "tool_use", toolStatus = ToolBlockStatus.SUCCESS, content = "result")
        val after = AssistantBlock(id = "text_1", kind = "text", content = "after")
        blocks = blocks + tool + after

        // A later reasoning segment still updates the existing turn's block.
        buffer.append(" suffix")
        blocks = buffer.snapshot(6)!!.applyTo(blocks)
        assertEquals(listOf("text_0", "thinking_1", "tool_1", "text_1"), blocks.map { it.id })
        assertEquals("first final suffix", blocks[1].content)
        assertSame(before, blocks[0])
        assertSame(tool, blocks[2])
        assertEquals(ToolBlockStatus.SUCCESS, blocks[2].toolStatus)
        assertSame(after, blocks[3])
    }

    @Test
    fun `completion flushes unicode tail before the throttle interval`() {
        val buffer = ThinkingStreamBuffer("thinking_1")
        val parts = listOf("漢字", "\uD83D", "\uDE80", " e\u0301", "\n終")
        buffer.append(parts.first())
        assertEquals(parts.first(), buffer.takeIfDue(0)!!.text)
        parts.drop(1).forEach { buffer.append(it) }
        assertNull(buffer.takeIfDue(10))
        val final = buffer.snapshot(10)!!
        assertEquals(parts.joinToString(""), final.text)
        assertEquals(parts.joinToString(""), buffer.toString())
        assertEquals(parts.joinToString("").length, buffer.length)
    }

    @Test
    fun `stop synchronously drains latest data and prevents a pending worker from publishing`() = runTest {
        val buffer = ThinkingStreamBuffer("thinking_1")
        val published = mutableListOf<String>()
        val worker = backgroundScope.launch { buffer.publishPending({ testScheduler.currentTime }) { published += it.text } }
        buffer.append("visible")
        runCurrent()
        buffer.append(" pending")
        runCurrent()
        val stopped = buffer.closeAndSnapshot()!!
        assertEquals("visible pending", stopped.text)
        buffer.append(" late")
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(worker.isCompleted)
        assertEquals(listOf("visible"), published)
        assertEquals("visible pending", buffer.toString())
        assertNull(buffer.takeIfDue(1_000))
    }

    @Test
    fun `stop before the first Main publication still retains the first chunk`() = runTest {
        val buffer = ThinkingStreamBuffer("thinking_1")
        val published = mutableListOf<String>()
        backgroundScope.launch { buffer.publishPending({ testScheduler.currentTime }) { published += it.text } }
        buffer.append("received before stop")
        val stopped = buffer.closeAndSnapshot()!!
        runCurrent()
        assertTrue(published.isEmpty())
        val blocks = stopped.applyTo(emptyList())
        assertEquals("received before stop", blocks.single().content)
        assertEquals("thinking_1", blocks.single().id)
    }

    @Test
    fun `publisher errors propagate and leave the complete buffer available to the error drain`() = runTest {
        val buffer = ThinkingStreamBuffer("thinking_1")
        val failure = IOException("publisher failed")
        supervisorScope {
            val worker = async {
                buffer.publishPending({ testScheduler.currentTime }) { throw failure }
            }
            buffer.append("received")
            try {
                worker.await()
                throw AssertionError("publisher error was swallowed")
            } catch (actual: IOException) {
                assertEquals(failure.javaClass, actual.javaClass)
                assertEquals(failure.message, actual.message)
                // Coroutine stack recovery may copy the exception at await().
                // Require the original object in the cause chain, not merely an
                // unrelated exception with an equal type/message; leave recovery on.
                val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
                var original: Throwable? = actual
                while (original != null && seen.add(original) && original !== failure) original = original.cause
                assertSame("original publisher exception must survive stack recovery", failure, original)
            }
        }
        assertEquals("received", buffer.closeAndSnapshot()!!.text)
    }

    @Test
    fun `retry resets content and time baseline without replaying failed-attempt text`() = runTest {
        val buffer = ThinkingStreamBuffer("thinking_1")
        val old = backgroundScope.launch { buffer.publishPending({ testScheduler.currentTime }) {} }
        buffer.append("discarded")
        runCurrent()
        old.cancel()
        runCurrent()
        buffer.closeAndSnapshot()
        buffer.clear()
        assertFalse(buffer.isNotEmpty())
        assertFalse(buffer.isClosed)
        assertNull(buffer.snapshot(1))
        buffer.append("replacement")
        assertEquals("replacement", buffer.takeIfDue(1)!!.text)
    }

    @Test
    fun `unchanged boundary snapshots reuse immutable text and the same block list`() {
        var copies = 0
        val buffer = ThinkingStreamBuffer("thinking_1", materialize = { copies++; it.toString() })
        buffer.append("complete")
        val snapshot = buffer.snapshot(0)!!
        val blocks = snapshot.applyTo(emptyList())
        repeat(10) {
            val next = buffer.snapshot(it.toLong())!!
            assertSame(snapshot.text, next.text)
            assertSame(blocks, next.applyTo(blocks))
        }
        assertEquals(1, copies)
    }

    @Test
    fun `queued snapshots cannot overwrite a newer boundary or retry generation`() {
        val buffer = ThinkingStreamBuffer("thinking_1")
        buffer.append("old")
        val old = buffer.takeIfDue(0)!!
        assertTrue(buffer.accepts(old))
        buffer.append(" tail")
        val boundary = buffer.snapshot(1)!!
        assertFalse(buffer.accepts(old))
        assertTrue(buffer.accepts(boundary))
        buffer.clear()
        buffer.append("new data") // Same length as the previous attempt.
        val retry = buffer.takeIfDue(2)!!
        assertEquals(boundary.text.length, retry.text.length)
        assertFalse(buffer.accepts(boundary))
        assertTrue(buffer.accepts(retry))
        buffer.closeAndSnapshot()
        assertFalse(buffer.accepts(retry))
    }

    @Test
    fun `single-message drain retains unflushed thinking without reviving a finished tool`() {
        val buffer = ThinkingStreamBuffer("thinking_1")
        buffer.append("shown")
        val shown = buffer.takeIfDue(0)!!
        val running = AssistantBlock(id = "tool", kind = "tool_use", content = "latest output", toolStatus = ToolBlockStatus.RUNNING)
        val canonical = listOf(
            AssistantBlock(id = "thinking_1", kind = "thinking", content = "old"),
            running.copy(content = "final output", toolStatus = ToolBlockStatus.CANCELLED),
        )
        val overlay = StreamingDelta("reply body", shown.applyTo(emptyList()) + running, false)
        buffer.append(" received tail") // Still inside the 200 ms publication window.
        assertNull(buffer.takeIfDue(1))
        val drained = buffer.closeAndSnapshot()!!
        val enriched = resolveThinkingStreamSnapshot(null, overlay, StreamingDelta("", canonical, false), drained)!!
        val merged = InFlightToolHeal.mergeOverlayBlocks(canonical, enriched.toolBlocks)
        assertEquals("reply body", enriched.content)
        assertFalse(enriched.isAwaitingModelResponse)
        assertEquals(listOf("thinking_1", "tool"), merged.map { it.id })
        assertEquals("shown received tail", merged[0].content)
        assertEquals(ToolBlockStatus.CANCELLED, merged[1].toolStatus)
        assertFalse(buffer.accepts(shown))
    }

    @Test
    fun `drain before first overlay publication preserves canonical content and completed tools`() {
        val buffer = ThinkingStreamBuffer("thinking_1")
        val completed = AssistantBlock(id = "earlier_tool", kind = "tool_use", toolStatus = ToolBlockStatus.SUCCESS)
        val canonical = ChatMessage(id = "assistant", role = "assistant", content = "earlier reply", toolBlocks = listOf(completed))
        buffer.append("first received chunk")
        val snapshot = buffer.closeAndSnapshot()!!
        val fallback = StreamingDelta(canonical.content, canonical.toolBlocks, canonical.isAwaitingModelResponse)
        val enriched = resolveThinkingStreamSnapshot(null, null, fallback, snapshot)!!
        val merged = InFlightToolHeal.mergeOverlayBlocks(canonical.toolBlocks, enriched.toolBlocks)
        assertEquals("earlier reply", enriched.content)
        assertSame(completed, merged[0])
        assertEquals(listOf("earlier_tool", "thinking_1"), merged.map { it.id })
        assertEquals("first received chunk", merged.last().content)
        assertTrue(buffer.isClosed)
    }

    @Test
    fun `thinking tick and stop keep newer pending reply and tool progress through a longer text throttle`() = runTest {
        val buffer = ThinkingStreamBuffer("thinking_1")
        buffer.append("thought")
        val first = buffer.takeIfDue(0)!!
        val oldTool = AssistantBlock(id = "tool", kind = "tool_use", content = "old progress", toolStatus = ToolBlockStatus.RUNNING)
        val newTool = oldTool.copy(content = "new progress") // Same status: no structural fast-path.
        val canonical = StreamingDelta("earlier", listOf(oldTool), false)
        val published = StreamingDelta("a".repeat(2_000), first.applyTo(emptyList()) + oldTool, false)
        val newestBody = published.content + " newer reply"
        var pending: StreamingDelta? = StreamingDelta(newestBody, first.applyTo(emptyList()) + newTool, false)
        val ticks = mutableListOf<StreamingDelta>()
        backgroundScope.launch {
            buffer.publishPending({ testScheduler.currentTime }) { snapshot ->
                // The real worker uses this resolver before updateAssistantMessage;
                // a >=2000-character reply can still be held by its 500 ms gate.
                pending = resolveThinkingStreamSnapshot(pending, published, canonical, snapshot)
                ticks += pending!!
            }
        }
        advanceTimeBy(50)
        buffer.append(" more")
        runCurrent()
        advanceTimeBy(150)
        runCurrent()
        assertEquals(1, ticks.size)
        assertEquals(newestBody, ticks.single().content)
        assertEquals("new progress", ticks.single().toolBlocks.last().content)
        assertEquals(listOf("thinking_1", "tool"), ticks.single().toolBlocks.map { it.id })
        assertFalse(ticks.single().isAwaitingModelResponse)

        buffer.append("\n終🚀")
        runCurrent()
        val stopped = buffer.closeAndSnapshot()!!
        val finalized = canonical.copy(toolBlocks = listOf(oldTool.copy(toolStatus = ToolBlockStatus.CANCELLED)))
        val drained = resolveThinkingStreamSnapshot(pending, published, finalized, stopped)!!
        val blocks = InFlightToolHeal.mergeOverlayBlocks(finalized.toolBlocks, drained.toolBlocks)
        assertEquals(newestBody, drained.content)
        assertEquals("thought more\n終🚀", blocks.first().content)
        assertEquals("new progress", blocks.last().content)
        assertEquals(ToolBlockStatus.CANCELLED, blocks.last().toolStatus)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("closed worker cannot overwrite the final drain", 1, ticks.size)
    }

    @Test
    fun `empty pending content is authoritative and preserves its awaiting flag`() {
        val published = StreamingDelta("stale visible text", emptyList(), false)
        val pending = StreamingDelta("", emptyList(), true)
        val thinking = ThinkingStreamBuffer.Snapshot("thinking_1", "latest thought")
        val resolved = resolveThinkingStreamSnapshot(pending, published, null, thinking)!!
        assertEquals("", resolved.content)
        assertTrue(resolved.isAwaitingModelResponse)
        assertEquals("latest thought", resolved.toolBlocks.single().content)
    }

    @Test
    fun `snapshot resolution falls back through published and canonical without inventing a message`() {
        val published = StreamingDelta("published", emptyList(), false)
        val canonical = StreamingDelta("canonical", emptyList(), true)
        assertSame(published, resolveThinkingStreamSnapshot(null, published, canonical, null))
        assertSame(canonical, resolveThinkingStreamSnapshot(null, null, canonical, null))
        assertNull(resolveThinkingStreamSnapshot(null, null, null, ThinkingStreamBuffer.Snapshot("thinking_1", "orphan")))
    }

    @Test
    fun `stop during retry rollback cannot restore discarded reasoning`() = runTest {
        val buffer = ThinkingStreamBuffer("thinking_1")
        val previous = AssistantBlock(id = "previous_tool", kind = "tool_use", toolStatus = ToolBlockStatus.SUCCESS)
        buffer.append("failed reasoning")
        val queued = buffer.snapshot(0)!!
        buffer.append(" unpublished tail")
        var published = StreamingDelta("reply already read", queued.applyTo(listOf(previous)), true)
        val rollbackPublished = CompletableDeferred<Unit>()
        val resumeAfterMain = CompletableDeferred<Unit>()
        val retry = launch {
            buffer.resetBeforeRetry {
                // Model the rollback Main publication, then the suspended return
                // to the collector where Stop can cancel before IO resumes.
                published = published.copy(toolBlocks = listOf(previous))
                rollbackPublished.complete(Unit)
                resumeAfterMain.await()
            }
            buffer.append("next attempt")
        }
        rollbackPublished.await()
        assertFalse(buffer.accepts(queued))
        val stopped = buffer.closeAndSnapshot()
        assertNull("Stop cannot recover the discarded generation", stopped)
        val drained = resolveThinkingStreamSnapshot(null, published, null, stopped)!!
        retry.cancelAndJoin()
        assertEquals("reply already read", drained.content)
        assertTrue(drained.isAwaitingModelResponse)
        assertEquals(listOf(previous), drained.toolBlocks)
        assertEquals("", buffer.toString())
        assertTrue(buffer.isClosed)
    }

    @Test
    fun `publisher failure reaches an active outer handler with the complete tail`() = runTest {
        val buffer = ThinkingStreamBuffer("thinking_1")
        val failure = IOException("publisher callback failed")
        var worker: Job? = null
        var drained: String? = null
        try {
            withThinkingStreamAttempt<Unit> {
                try {
                    worker = launch {
                        buffer.publishPending({ testScheduler.currentTime }) { throw failure }
                    }
                    buffer.append("received")
                    buffer.append(" complete tail")
                    awaitCancellation()
                } finally {
                    worker?.cancel()
                }
            }
            throw AssertionError("publisher failure was swallowed")
        } catch (actual: IOException) {
            assertTrue("outer error handler must remain active", coroutineContext.isActive)
            assertEquals(failure.javaClass, actual.javaClass)
            assertEquals(failure.message, actual.message)
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            var original: Throwable? = actual
            while (original != null && seen.add(original) && original !== failure) original = original.cause
            assertSame("original publisher exception must reach the handler", failure, original)
            kotlinx.coroutines.delay(1) // A cancelled outer job would fail this recovery suspension.
            drained = buffer.closeAndSnapshot()!!.text
        }
        assertEquals("received complete tail", drained)
        assertTrue(worker!!.isCompleted)
        assertTrue(coroutineContext.isActive)
    }

    @Test
    fun `clean attempt completion cancels its waiting publisher before returning`() = runTest {
        val buffer = ThinkingStreamBuffer("thinking_1")
        val workerStarted = CompletableDeferred<Unit>()
        var worker: Job? = null
        withTimeout(1_000) {
            withThinkingStreamAttempt {
                try {
                    worker = launch {
                        workerStarted.complete(Unit)
                        buffer.publishPending({ testScheduler.currentTime }) {
                            throw AssertionError("empty attempt must not publish")
                        }
                    }
                    workerStarted.await()
                } finally {
                    worker?.cancel()
                }
            }
        }
        assertTrue(worker!!.isCompleted)
        assertFalse("the attempt scope must not discard retry state", buffer.isClosed)
        assertTrue(coroutineContext.isActive)
    }

    @Test
    fun `external cancellation remains cancellation and preserves the synchronous stop tail`() = runTest {
        val buffer = ThinkingStreamBuffer("thinking_1")
        val stop = CancellationException("explicit Stop")
        val attemptStarted = CompletableDeferred<Unit>()
        var worker: Job? = null
        var sawCancellation = false
        var sawFailure = false
        val turn = launch {
            try {
                withThinkingStreamAttempt {
                    try {
                        buffer.append("received before Stop")
                        worker = launch { buffer.publishPending({ testScheduler.currentTime }) {} }
                        attemptStarted.complete(Unit)
                        awaitCancellation()
                    } finally {
                        worker?.cancel()
                    }
                }
            } catch (cancelled: CancellationException) {
                sawCancellation = true
                assertFalse(coroutineContext.isActive)
                assertTrue("inactive outer turn must propagate Stop", isRealStreamCancellation(cancelled))
                // Recovery may insert copies; the actual cause-free Stop object
                // must still be the origin, not a provider failure with equal text.
                val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
                var original: Throwable? = cancelled
                while (original != null && seen.add(original) && original !== stop) original = original.cause
                assertSame("explicit Stop origin must survive recovery", stop, original)
                assertNull("explicit Stop origin must remain cause-free", original!!.cause)
                throw cancelled
            } catch (failure: Exception) {
                sawFailure = true
            }
        }
        attemptStarted.await()
        turn.cancel(stop)
        turn.join()
        assertTrue(sawCancellation)
        assertFalse(sawFailure)
        assertTrue(worker!!.isCompleted)
        assertEquals("received before Stop", buffer.closeAndSnapshot()!!.text)
        assertTrue(coroutineContext.isActive)
    }

    @Test
    fun `causeful provider cancellation remains recoverable in the active outer handler`() = runTest {
        val buffer = ThinkingStreamBuffer("thinking_1")
        val failure = com.openminis.app.data.model.LLMError.TransientError("provider stream failed")
        val reported = CancellationException("provider cancelled its producer", failure)
        var recovered = false
        try {
            withThinkingStreamAttempt<Unit> {
                buffer.append("received before provider error")
                kotlinx.coroutines.yield() // Exercise the suspended scope-return recovery path.
                throw reported
            }
            throw AssertionError("provider cancellation was swallowed")
        } catch (actual: CancellationException) {
            assertTrue("provider failure must not cancel the outer turn", coroutineContext.isActive)
            assertFalse("active causeful provider error must reach recovery", isRealStreamCancellation(actual))
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            var original: Throwable? = actual
            while (original != null && seen.add(original) && original !== reported) original = original.cause
            assertSame("provider cancellation must survive recovery", reported, original)
            assertSame("real provider failure remains available for unwrapping", failure, original!!.cause)
            kotlinx.coroutines.delay(1)
            assertEquals("received before provider error", buffer.closeAndSnapshot()!!.text)
            recovered = true
        }
        assertTrue(recovered)
        assertTrue(coroutineContext.isActive)
    }
}
