package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.RoutingStrategy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-titlegen-group-order] + [T-titlegen-retry-not-retrying] Title generation
 * tries the WHOLE title group, then the chat's own model/group, then the
 * default group, then the rest — and moves on when one model fails. iOS
 * parity: 987f1f70d + 26ccf9cb4.
 *
 * Reported: one rate-limited title model left chats on "New Chat". Android
 * reached one model per auto attempt; the title group's other members were
 * never asked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TitleCandidatesTest {

    private fun entry(id: String, modelId: String = id, outs: List<String>? = null, hidden: Boolean = false) =
        ModelEntry(
            providerInstanceId = "inst",
            baseModel = LLMModel(id = modelId, displayName = modelId, provider = "p", outputModalities = outs),
            isHidden = hidden,
            uuid = id,
        )

    private val a = entry("A"); private val b = entry("B"); private val c = entry("C")
    private val p1 = entry("P1"); private val p2 = entry("P2")
    private val d1 = entry("D1"); private val x = entry("X")
    private val all = listOf(a, b, c, p1, p2, d1, x)
    private fun byId(id: String) = all.firstOrNull { it.id == id }

    private fun group(id: String, vararg members: ModelEntry, strategy: RoutingStrategy = RoutingStrategy.fallback) =
        ModelGroup(id = id, name = id, memberEntryIds = members.map { it.id }.toMutableList(), strategy = strategy)

    private val sub = group("sub", a, b, c)
    private val prim = group("prim", p1, p2)
    private val def = group("def", d1, p1)
    private val groups = listOf(sub, prim, def)

    private fun order(
        subGroup: ModelGroup? = sub,
        primary: TitleCandidates.PrimarySource? = TitleCandidates.PrimarySource.Group("prim", null),
        sessionModelId: String? = null,
        defaultGroup: ModelGroup? = def,
        unavailable: Set<String> = emptySet(),
        pool: List<ModelEntry> = all,
    ) = TitleCandidates.order(
        sessionId = "s1",
        subGroup = subGroup,
        primary = primary,
        sessionModelId = sessionModelId,
        defaultPrimaryGroup = defaultGroup,
        pool = pool,
        group = { id -> groups.firstOrNull { it.id == id } },
        entry = ::byId,
        availableMembers = { g -> g.memberEntryIds.mapNotNull(::byId).filter { it.id !in unavailable } },
        isAvailable = { it.id !in unavailable && !it.isHidden },
    ).map { it.id }

    // ---- ordering ---------------------------------------------------------

    @Test
    fun `the whole title group comes first, then the chat's group, the default group, then the rest`() {
        assertEquals(listOf("A", "B", "C", "P1", "P2", "D1", "X"), order())
    }

    @Test
    fun `the reported case - title model A rate-limited, B and C are next, not the primary tier`() {
        val o = order()
        assertEquals(listOf("B", "C"), o.subList(1, 3))
    }

    @Test
    fun `an unavailable member is skipped in place, order kept`() {
        assertEquals(listOf("A", "C", "P1", "P2", "D1", "X"), order(unavailable = setOf("B")))
    }

    @Test
    fun `a group walk starts at the last-used member and wraps, like the agent loop`() {
        val o = order(subGroup = null, primary = TitleCandidates.PrimarySource.Group("prim", "P2"), defaultGroup = null)
        assertEquals(listOf("P2", "P1"), o.take(2))
    }

    @Test
    fun `loadBalance starts at the session-hash member and wraps`() {
        val lb = group("lb", a, b, c, strategy = RoutingStrategy.loadBalance)
        val walked = TitleCandidates.expandGroup(lb, listOf(a, b, c), "s1").map { it.id }
        val start = Math.floorMod("s1".hashCode(), 3)
        assertEquals(List(3) { listOf("A", "B", "C")[(start + it) % 3] }, walked)
    }

    @Test
    fun `a pinned entry is the chat's tier`() {
        val o = order(subGroup = null, primary = TitleCandidates.PrimarySource.Entry("X"), defaultGroup = null)
        assertEquals("X", o.first())
    }

    @Test
    fun `the session row's model id is used only when nothing else resolved`() {
        assertEquals("C", order(subGroup = null, primary = null, sessionModelId = "C", defaultGroup = null).first())
        assertEquals("A", order(primary = null, sessionModelId = "C").first())
    }

    @Test
    fun `non-chat and hidden models never become candidates`() {
        val tts = entry("T", modelId = "speech-tts-1")
        val img = entry("I", outs = listOf("image"))
        val hid = entry("H", hidden = true)
        val o = order(subGroup = null, primary = null, defaultGroup = null, pool = listOf(tts, img, hid, x))
        assertEquals(listOf("X"), o)
    }

    @Test
    fun `binding JSON parses both shapes and rejects junk`() {
        assertEquals(
            TitleCandidates.PrimarySource.Group("g", "e"),
            TitleCandidates.PrimarySource.parse("""{"type":"group","groupId":"g","lastEntryId":"e"}"""),
        )
        assertEquals(TitleCandidates.PrimarySource.Entry("e"), TitleCandidates.PrimarySource.parse("""{"type":"entry","entryId":"e"}"""))
        assertNull(TitleCandidates.PrimarySource.parse(null))
        assertNull(TitleCandidates.PrimarySource.parse("not json"))
    }

    // ---- the walk ---------------------------------------------------------

    @Test
    fun `a failing model moves on to the next, with the pause between tries`() = runTest {
        val asked = mutableListOf<String>()
        val r = TitleCandidates.walk(listOf(a, b, c), origin = "t", pauseMs = 1_500) { e ->
            asked += e.id
            when (e.id) {
                "A" -> throw RuntimeException("429 rate limited")
                "B" -> null // empty title
                else -> "title"
            }
        }
        assertEquals("title", r)
        assertEquals(listOf("A", "B", "C"), asked)
        assertEquals("two pauses before the third try", 3_000L, currentTime)
    }

    @Test
    fun `the walk is capped and returns null when every try fails`() = runTest {
        val many = (1..10).map { entry("E$it") }
        var tries = 0
        val r = TitleCandidates.walk(many, origin = "t") { tries++; null }
        assertNull(r)
        assertEquals(TitleCandidates.MAX_TRIES, tries)
    }

    @Test
    fun `the walk stops when the chat got a title meanwhile`() = runTest {
        var tries = 0
        val r = TitleCandidates.walk(listOf(a, b, c), origin = "t", shouldStop = { tries >= 1 }) { tries++; null }
        assertNull(r)
        assertEquals(1, tries)
    }

    // [T-android-titlegen-stream-error-walk] Providers report a mid-stream
    // error by cancelling their producer with the LLMError as the cause.
    @Test
    fun `a provider's mid-stream error moves on to the next model`() = runTest {
        val asked = mutableListOf<String>()
        val r = TitleCandidates.walk(listOf(a, b), origin = "t") { e ->
            asked += e.id
            if (e.id == "A") {
                // The real shape: a channelFlow whose producer cancels itself.
                kotlinx.coroutines.flow.channelFlow<String> {
                    cancel("Stream error", RuntimeException("inline SSE error 429"))
                }.collect { }
                "unreachable"
            } else "title"
        }
        assertEquals("title", r)
        assertEquals(listOf("A", "B"), asked)
    }

    @Test
    fun `a real cancellation still ends the walk`() = runTest {
        val asked = mutableListOf<String>()
        val job = launch {
            TitleCandidates.walk(listOf(a, b), origin = "t") { e ->
                asked += e.id
                // The caller goes away — even with a cause attached.
                coroutineContext.cancel(kotlinx.coroutines.CancellationException("left", RuntimeException("x")))
                kotlinx.coroutines.yield()
                "title"
            }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(listOf("A"), asked)

        var thrown: Throwable? = null
        try {
            TitleCandidates.walk(listOf(a, b), origin = "t") { throw kotlinx.coroutines.CancellationException("plain") }
        } catch (t: Throwable) { thrown = t }
        assertTrue(thrown is kotlinx.coroutines.CancellationException)
    }

    // ---- wiring -----------------------------------------------------------

    @Test
    fun `both title paths use the shared order and walk`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("TitleCandidates.forSession(providerRepository, titleSid, primarySource, currentModel?.id)"))
        assertTrue(vm.contains("pauseMs = TitleCandidates.AUTO_PAUSE_MS,"))
        // The first-message fallback only after the whole walk failed, and no
        // further automatic attempts after it.
        val gaveUp = vm.substringAfter("// [T-titlegen-retry-not-retrying] Every candidate failed")
            .substringBefore("} catch (e: Exception) {")
        assertTrue(gaveUp.contains("titleGenerationAttempts = TITLE_MAX_ATTEMPTS"))
        assertTrue(gaveUp.contains("applyFallbackTitleFromFirstMessage(\"all title candidates failed\")"))
        assertTrue("the single-model resolver is gone", !vm.contains("fun resolveTitleProvider("))

        val list = ProductionSources.read("ui/sessions/SessionListViewModel.kt")
        assertTrue(list.contains("com.openminis.app.ui.chat.TitleCandidates.forSession("))
        assertTrue(list.contains("PrimarySource.parse(session.modelBinding)"))
        assertTrue(list.contains("com.openminis.app.ui.chat.TitleCandidates.walk(candidates, origin = origin)"))
    }
}
