package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-android-group-resolve-skip-uncredentialed] Pins the selection rule used by
 * `ChatViewModel.resolveProviderFromGroup` + `ProviderRepository.availableMemberEntries`
 * (mirrors iOS `ModelGroupRouter.resolve` / `availableEntryIds`).
 *
 * Same shape as ModelGroupReorderTest: the repository needs Context + Room +
 * EncryptedSharedPreferences, so this exercises the pure decision rule those
 * methods implement — filter the members down to the usable ones FIRST, then
 * apply the routing strategy. That ordering is the whole contract.
 *
 * The regression being pinned: selection used to take the first *enabled*
 * member and only then check its credential, abandoning the entire group when
 * that one member had none. A group of otherwise-usable models therefore
 * resolved to nothing, and the caller fell through to the new-chat default
 * chain — which picks by "most recently added provider" and so ran the session
 * on a model that was not in the group the user had selected.
 */
class GroupResolveCredentialFilterTest {

    /** Minimal stand-in for the (entry, instance) pair the real filter walks. */
    private data class Member(
        val entryId: String,
        val hidden: Boolean = false,
        val providerEnabled: Boolean = true,
        /** hasAnyCredential: API key OR manual bearer OR stored OAuth token. */
        val credentialed: Boolean = true,
    )

    /** The exact filter implemented in ProviderRepository.availableMemberEntries. */
    private fun available(members: List<Member>): List<String> =
        members.filter { !it.hidden && it.providerEnabled && it.credentialed }
            .map { it.entryId }

    /** The exact selection implemented in ChatViewModel.resolveProviderFromGroup. */
    private fun resolve(
        members: List<Member>,
        preferredEntryId: String? = null,
        loadBalance: Boolean = false,
        sessionId: String = "",
    ): String? {
        val avail = available(members)
        if (avail.isEmpty()) return null
        return avail.firstOrNull { it == preferredEntryId }
            ?: if (loadBalance) avail[Math.floorMod(sessionId.hashCode(), avail.size)]
            else avail.first()
    }

    // -- The reported bug ---------------------------------------------------

    @Test
    fun `uncredentialed head member is skipped, not fatal to the whole group`() {
        // Exactly the field configuration: the first three members sit on one
        // OAuth provider that is signed out; the next two are usable.
        val group = listOf(
            Member("anthropic/claude-sonnet-5", credentialed = false),
            Member("anthropic/claude-sonnet-4-6", credentialed = false),
            Member("anthropic/claude-opus-5", credentialed = false),
            Member("openai/gpt-5.6-terra"),
            Member("openai/gpt-5.6-sol"),
        )
        // Previously: null (whole group abandoned) → caller fell through to the
        // new-chat default chain and picked an unrelated model.
        assertEquals("openai/gpt-5.6-terra", resolve(group))
    }

    @Test
    fun `group resolves to null only when EVERY member is unusable`() {
        val group = listOf(
            Member("a/1", credentialed = false),
            Member("b/2", providerEnabled = false),
            Member("c/3", hidden = true),
        )
        assertNull(resolve(group))
    }

    // -- Filter dimensions --------------------------------------------------

    @Test
    fun `hidden entries are filtered, matching iOS`() {
        val group = listOf(Member("a/1", hidden = true), Member("b/2"))
        assertEquals("b/2", resolve(group))
    }

    @Test
    fun `disabled providers are still filtered`() {
        val group = listOf(Member("a/1", providerEnabled = false), Member("b/2"))
        assertEquals("b/2", resolve(group))
    }

    @Test
    fun `filtering preserves declaration order so primary stays first member`() {
        val group = listOf(
            Member("a/1", credentialed = false),
            Member("b/2"),
            Member("c/3"),
        )
        assertEquals(listOf("b/2", "c/3"), available(group))
        assertEquals("b/2", resolve(group))
    }

    @Test
    fun `a fully usable group is unaffected`() {
        val group = listOf(Member("a/1"), Member("b/2"))
        assertEquals("a/1", resolve(group))
    }

    // -- preferredEntryId (prior session binding) ---------------------------

    @Test
    fun `preferred entry is honored when still available`() {
        val group = listOf(Member("a/1"), Member("b/2"), Member("c/3"))
        assertEquals("c/3", resolve(group, preferredEntryId = "c/3"))
    }

    @Test
    fun `preferred entry that lost its credential falls back to first available`() {
        val group = listOf(
            Member("a/1", credentialed = false),
            Member("b/2"),
            Member("c/3", credentialed = false),
        )
        // "c/3" was the user's pick last time but is no longer usable; the
        // session must still open rather than dead-end.
        assertEquals("b/2", resolve(group, preferredEntryId = "c/3"))
    }

    // -- Routing strategy ---------------------------------------------------

    @Test
    fun `loadBalance picks within the FILTERED list, never an unusable member`() {
        val group = listOf(
            Member("dead/1", credentialed = false),
            Member("ok/1"),
            Member("ok/2"),
        )
        val usable = setOf("ok/1", "ok/2")
        // Whatever the session id hashes to, it must land on a usable member.
        for (sid in listOf("", "s1", "s2", "session-abc", "😀")) {
            val picked = resolve(group, loadBalance = true, sessionId = sid)
            assert(picked in usable) { "loadBalance picked $picked for sid=$sid" }
        }
    }

    @Test
    fun `loadBalance is stable for a given session id`() {
        val group = listOf(Member("a/1"), Member("b/2"), Member("c/3"))
        val first = resolve(group, loadBalance = true, sessionId = "session-42")
        repeat(5) {
            // Re-entering a session must not reshuffle its model.
            assertEquals(first, resolve(group, loadBalance = true, sessionId = "session-42"))
        }
    }

    @Test
    fun `loadBalance spreads distinct sessions across members`() {
        val group = listOf(Member("a/1"), Member("b/2"), Member("c/3"))
        val picked = (0 until 60).map {
            resolve(group, loadBalance = true, sessionId = "session-$it")
        }.toSet()
        // Not a distribution assertion — just that it isn't degenerate, which
        // is precisely what the old code did by ignoring strategy entirely.
        assert(picked.size > 1) { "loadBalance collapsed to a single member: $picked" }
    }

    @Test
    fun `fallback strategy always takes the first available member`() {
        val group = listOf(Member("a/1", credentialed = false), Member("b/2"), Member("c/3"))
        for (sid in listOf("", "s1", "session-xyz")) {
            assertEquals("b/2", resolve(group, loadBalance = false, sessionId = sid))
        }
    }

    @Test
    fun `negative hash codes do not crash or index out of bounds`() {
        val group = listOf(Member("a/1"), Member("b/2"))
        // "polygenelubricants" is the classic negative-hashCode string; plain
        // % would yield a negative index here, hence Math.floorMod.
        val picked = resolve(group, loadBalance = true, sessionId = "polygenelubricants")
        assert(picked == "a/1" || picked == "b/2") { "unexpected pick: $picked" }
    }
}
