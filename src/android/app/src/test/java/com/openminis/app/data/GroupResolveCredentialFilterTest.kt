package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // -- [M07] A disabled provider is unreachable through a group -----------
    //
    // GitHub #34 / Android #564, reported as "禁用不彻底": turning a provider OFF
    // in Settings did not stop chat calling it, because three runtime-resolution
    // sites in ChatViewModel walked `group.memberEntryIds` directly and resolved
    // through `instance(...)` with no `isEnabled` check. The multi-step fallback
    // chain WAS checking it, which is why the bug only showed when the disabled
    // provider sat at the HEAD of a group and produced the first call — the
    // fallback loop was never reached (244d17009).
    //
    // The filter tests above already cover `providerEnabled` as one dimension
    // among several. What follows states the stronger property the report
    // demands: a disabled provider's entry must never be the answer, under ANY
    // routing strategy, preference, or position in the member list.

    @Test
    fun `a disabled head member is never the first call`() {
        // The exact reported configuration: the model the user thought they had
        // turned off is the group's primary, so it produced the very first
        // request and the fallback chain never ran.
        val group = listOf(
            Member("off/flagship", providerEnabled = false),
            Member("on/backup"),
        )
        assertEquals("on/backup", resolve(group))
        assertEquals(listOf("on/backup"), available(group))
    }

    @Test
    fun `a disabled entry is unreachable from every position and strategy`() {
        // Position-independent and strategy-independent, because the three fixed
        // call sites differ in both: primary-pick, load-balance, and the
        // single-attempt fallback walker.
        for (position in 0..2) {
            val members = MutableList(3) { i -> Member("on/$i") }
            members[position] = Member("off/$position", providerEnabled = false)
            val avail = available(members)
            assertTrue(
                "the disabled member leaked at position $position: $avail",
                avail.none { it.startsWith("off/") },
            )
            for (sid in listOf("", "s1", "session-42", "polygenelubricants")) {
                for (lb in listOf(false, true)) {
                    val picked = resolve(members, loadBalance = lb, sessionId = sid)
                    assertTrue(
                        "picked $picked (disabled at $position, lb=$lb, sid=$sid)",
                        picked != null && !picked.startsWith("off/"),
                    )
                }
            }
        }
    }

    @Test
    fun `a disabled provider cannot be reached even as the session's stored preference`() {
        // The subtle path: a session bound to that entry BEFORE the provider was
        // disabled. `preferredEntryId` must be filtered like any other candidate,
        // or reopening an old chat calls the provider the user switched off.
        val group = listOf(
            Member("off/remembered", providerEnabled = false),
            Member("on/live"),
        )
        assertEquals("on/live", resolve(group, preferredEntryId = "off/remembered"))
    }

    @Test
    fun `a group whose every member is behind a disabled provider resolves to null`() {
        // Null is the correct answer, not "use it anyway": the caller then shows
        // "no available models" rather than sending to a provider the user
        // disabled. The routing-must-not-silently-substitute half of the same
        // report is covered by `uncredentialed head member is skipped`.
        val group = listOf(
            Member("off/1", providerEnabled = false),
            Member("off/2", providerEnabled = false),
        )
        assertNull(resolve(group))
        assertNull(resolve(group, loadBalance = true, sessionId = "s"))
        assertNull(resolve(group, preferredEntryId = "off/1"))
    }

    @Test
    fun `disabled and uncredentialed are independent reasons to skip`() {
        // Both filters must hold at once. A provider that is enabled but signed
        // out, and one that is credentialed but disabled, are each unusable — the
        // first fails auth, the second was switched off on purpose.
        val group = listOf(
            Member("enabled-but-signed-out", credentialed = false),
            Member("credentialed-but-disabled", providerEnabled = false),
            Member("usable"),
        )
        assertEquals(listOf("usable"), available(group))
    }

    // -- [M07] The runtime sites must use the credential-aware filter --------
    //
    // The rule above is only enforced if every routing site calls it. Two
    // functions exist on purpose and answer different questions —
    // `enabledMemberEntries` ("is the provider switched on", right for the
    // settings UI) and `availableMemberEntries` ("can it serve a request right
    // now", required for routing) — so a routing site reaching for the weaker one
    // is a silent regression. Source facts, because ProviderRepository needs
    // Context + Room + EncryptedSharedPreferences.

    private fun src(path: String) = com.openminis.app.ProductionSources.read(path)

    @Test
    fun `both filters exist and the routing one checks all three conditions`() {
        val repoSrc = src("data/repository/ProviderRepository.kt")
        assertTrue(repoSrc.contains("fun enabledMemberEntries(group: ModelGroup)"))
        assertTrue(repoSrc.contains("fun availableMemberEntries(group: ModelGroup)"))

        val routing = repoSrc.substringAfter("fun availableMemberEntries(group: ModelGroup)")
            .substringBefore("fun resolveTitleSubEntry()")
        assertTrue("must drop hidden entries", routing.contains("entry.isHidden"))
        assertTrue("must drop disabled providers", routing.contains("!instance.isEnabled"))
        assertTrue("must drop uncredentialed providers", routing.contains("!hasAnyCredential(instance)"))
        // Order preserved — the filter removes candidates, it never reorders, or
        // "primary = first member" stops holding.
        assertTrue(
            "must walk memberEntryIds in declaration order",
            routing.contains("group.memberEntryIds.mapNotNull"),
        )
    }

    @Test
    fun `the request-path resolvers all go through availableMemberEntries`() {
        // Every site that picks a model to SEND with. `firstEnabledMemberEntry`
        // here would reintroduce the report for a signed-out provider, and a raw
        // `memberEntryIds` walk would reintroduce #34 itself.
        val sites = mapOf(
            "ui/chat/ChatViewModel.kt" to "availableMemberEntries(group)",
            "agent/jobs/ModelTierResolver.kt" to "availableMemberEntries(",
            "speech/correction/CorrectionStrategy.kt" to "availableMemberEntries(",
            "scheduled/ScheduledAgentRunner.kt" to "availableMemberEntries(",
        )
        for ((path, needle) in sites) {
            assertTrue("$path must resolve through the credential-aware filter", src(path).contains(needle))
        }
        // Title generation is a request too — it was one of the three fixed sites.
        assertTrue(
            "resolveTitleSubEntry must use the routing filter, not the UI one",
            src("data/repository/ProviderRepository.kt").contains(
                "return availableMemberEntries(group).firstOrNull()",
            ),
        )
    }

    @Test
    fun `the settings UI has its own predicate so it can dim rather than hide`() {
        // 244d17009's other half: the member stays visible in Settings, marked
        // unreachable. Without `isEntryProviderEnabled` the UI would have to
        // either hide it (the user cannot tell why their group shrank) or lie.
        val repoSrc = src("data/repository/ProviderRepository.kt")
        assertTrue(repoSrc.contains("fun isEntryProviderEnabled(entryId: String)"))
        val fn = repoSrc.substringAfter("fun isEntryProviderEnabled(entryId: String)")
            .substringBefore("fun replaceEntries(")
        assertTrue("it must read isEnabled", fn.contains("instance.isEnabled"))
        assertTrue(
            "an orphaned entry id must answer false, not crash",
            fn.contains("?: return false"),
        )
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
