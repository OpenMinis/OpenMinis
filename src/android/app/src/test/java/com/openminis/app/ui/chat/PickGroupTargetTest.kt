package com.openminis.app.ui.chat

import com.openminis.app.data.ModelCapability
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.RoutingStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-capability-route-no-vm-mutation] Pins the PURE core of group resolution.
 *
 * GroupResolveCredentialFilterTest had to re-implement the rule as a shadow
 * copy because the real one lived inside a ChatViewModel extension (needs
 * Context + Room + EncryptedSharedPreferences). pickGroupTarget is the real
 * implementation, extracted top-level: these tests exercise the production
 * function directly — filter/strategy/preferred/stability contracts that
 * resolveGroupTargetEntry (thin shell) and the per-turn capability detour in
 * ChatViewModelSendExt both depend on.
 */
class PickGroupTargetTest {

    private fun entry(id: String, vararg inputs: String) = ModelEntry(
        providerInstanceId = "p-$id",
        baseModel = LLMModel(
            id = id,
            displayName = id,
            provider = "test",
            inputModalities = inputs.toList(),
        ),
        uuid = id,
    )

    private fun group(strategy: RoutingStrategy) = ModelGroup(
        name = "g",
        strategy = strategy,
    )

    // -- preferred entry binding -------------------------------------------

    @Test
    fun `preferred entry still available is honored`() {
        val a = entry("a", "text")
        val b = entry("b", "text")
        val picked = pickGroupTarget(group(RoutingStrategy.fallback), listOf(a, b), "b", emptySet(), "s1")
        assertEquals("b", picked?.first?.id)
    }

    @Test
    fun `preferred entry unavailable falls through to strategy`() {
        // The binding points at a member that is now hidden/disabled — the
        // session must still proceed on the degraded group, not die.
        val a = entry("a", "text")
        val b = entry("b", "text")
        val picked = pickGroupTarget(group(RoutingStrategy.fallback), listOf(a, b), "gone", emptySet(), "s1")
        assertEquals("a", picked?.first?.id)
    }

    // -- routing strategy ----------------------------------------------------

    @Test
    fun `fallback strategy picks the first available member`() {
        val a = entry("a", "text")
        val b = entry("b", "text")
        val picked = pickGroupTarget(group(RoutingStrategy.fallback), listOf(a, b), null, emptySet(), "s1")
        assertEquals("a", picked?.first?.id)
    }

    @Test
    fun `loadBalance is stable for one session key`() {
        // Re-entering a chat must never reshuffle its model: the same key
        // resolves to the same member across calls.
        val members = (0 until 5).map { entry("m$it", "text") }
        val first = pickGroupTarget(group(RoutingStrategy.loadBalance), members, null, emptySet(), "session-42")
        val second = pickGroupTarget(group(RoutingStrategy.loadBalance), members, null, emptySet(), "session-42")
        assertEquals(first?.first?.id, second?.first?.id)
    }

    @Test
    fun `loadBalance spreads distinct sessions across members`() {
        // With 4 members and 40 distinct sessions, a single-member outcome
        // would mean the hash collapsed to one bucket — the spread is the
        // point of load balancing.
        val members = (0 until 4).map { entry("m$it", "text") }
        val g = group(RoutingStrategy.loadBalance)
        val distinct = (0 until 40).map { s ->
            pickGroupTarget(g, members, null, emptySet(), "session-$s")?.first?.id
        }.toSet()
        assertEquals(true, distinct.size > 1)
    }

    @Test
    fun `loadBalance hash indexes into the FILTERED pool not the full group`() {
        // The index must be taken modulo the available list, so a degraded
        // group (members dropped) still lands on a real member.
        val members = listOf(entry("a", "text"), entry("b", "text"))
        val picked = pickGroupTarget(group(RoutingStrategy.loadBalance), members, null, emptySet(), "whatever")
        assertEquals(true, picked?.first?.id in setOf("a", "b"))
    }

    // -- capability routing ---------------------------------------------------

    @Test
    fun `image need routes to the vision member`() {
        val text = entry("text", "text")
        val vision = entry("vision", "text", "image_input")
        val picked = pickGroupTarget(
            group(RoutingStrategy.fallback),
            listOf(text, vision),
            null,
            setOf(ModelCapability.IMAGE_INPUT),
            "s1",
        )
        assertEquals("vision", picked?.first?.id)
    }

    @Test
    fun `image need with no capable member keeps the pool and reports why`() {
        val a = entry("a", "text")
        val b = entry("b", "text")
        val picked = pickGroupTarget(
            group(RoutingStrategy.fallback),
            listOf(a, b),
            null,
            setOf(ModelCapability.IMAGE_INPUT),
            "s1",
        )
        assertEquals("a", picked?.first?.id)
        // The reason is surfaced to SessionActivityTracker by callers.
        assertEquals(true, picked?.second?.contains("no group member has") == true)
    }

    @Test
    fun `no need yields a null reason`() {
        val a = entry("a", "text")
        val picked = pickGroupTarget(group(RoutingStrategy.fallback), listOf(a), null, emptySet(), "s1")
        assertNull(picked?.second)
    }

    // -- empty pool ------------------------------------------------------------

    @Test
    fun `empty pool resolves to null`() {
        val picked = pickGroupTarget(group(RoutingStrategy.fallback), emptyList(), null, emptySet(), "s1")
        assertNull(picked)
    }

    @Test
    fun `empty pool with a preferred entry still resolves to null`() {
        val picked = pickGroupTarget(group(RoutingStrategy.loadBalance), emptyList(), "a", emptySet(), "s1")
        assertNull(picked)
    }
}
