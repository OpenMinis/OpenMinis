package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-android-title-failover] Pins the pure ordering/dedup contract of the
 * title-model failover chain (TitleFailoverChain.plan):
 *  - sub-group order is preserved exactly;
 *  - a duplicate instance+model key is dropped (first occurrence wins);
 *  - the primary provider is appended once at the tail;
 *  - a primary that is also a sub-group member is NOT tried twice.
 */
class TitleFailoverChainTest {

    private fun sub(key: String) = key to "sub:$key"
    private fun primary(key: String) = key to "primary:$key"

    @Test
    fun `empty subs and no primary yields empty chain`() {
        assertEquals(emptyList<String>(), TitleFailoverChain.plan(emptyList(), null))
    }

    @Test
    fun `sub group order is preserved`() {
        val plan = TitleFailoverChain.plan(
            listOf(sub("a:1"), sub("b:2"), sub("c:3")),
            null,
        )
        assertEquals(listOf("sub:a:1", "sub:b:2", "sub:c:3"), plan)
    }

    @Test
    fun `duplicate sub keys are dropped first occurrence wins`() {
        val plan = TitleFailoverChain.plan(
            listOf(sub("a:1"), sub("a:1"), sub("b:2")),
            null,
        )
        assertEquals(listOf("sub:a:1", "sub:b:2"), plan)
    }

    @Test
    fun `primary is appended at the tail`() {
        val plan = TitleFailoverChain.plan(
            listOf(sub("a:1")),
            primary("z:9"),
        )
        assertEquals(listOf("sub:a:1", "primary:z:9"), plan)
    }

    @Test
    fun `primary that is also a sub member is not tried twice`() {
        val plan = TitleFailoverChain.plan(
            listOf(sub("a:1"), sub("b:2")),
            primary("a:1"),
        )
        assertEquals(listOf("sub:a:1", "sub:b:2"), plan)
    }

    @Test
    fun `primary alone still forms a single-candidate chain`() {
        val plan = TitleFailoverChain.plan(emptyList(), primary("z:9"))
        assertEquals(listOf("primary:z:9"), plan)
    }

    @Test
    fun `same model on different instances are distinct candidates`() {
        // a:1 and b:1 share the modelId but live on different provider
        // instances (e.g. the same deepseek model via two relays) — both
        // stay in the chain.
        val plan = TitleFailoverChain.plan(
            listOf(sub("a:1"), sub("b:1")),
            null,
        )
        assertEquals(listOf("sub:a:1", "sub:b:1"), plan)
    }
}
