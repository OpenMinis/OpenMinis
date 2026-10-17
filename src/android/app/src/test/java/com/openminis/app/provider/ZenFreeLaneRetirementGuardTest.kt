package com.openminis.app.provider

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-zen-free-lane-retired] The keyless OpenCode Zen lane must not come back by
 * accident.
 *
 * Measured 2026-10-17: every non-official client is refused with
 * `403 FreeTierError` on every model, whatever headers it sends, while the
 * official 2.0.32 CLI passes on a credential it never stores on disk. The app
 * therefore can no longer drive that lane by imitating a client identity, and
 * shipping the imitation again would only restore a provider that fails on
 * every request. This guard is the tombstone: the symbols below were the whole
 * lane, and the retired instance id may exist in exactly one place — the
 * upgrade pass that removes it.
 */
class ZenFreeLaneRetirementGuardTest {

    @Test
    fun `no production file carries the retired free-lane machinery`() {
        val sources = ProductionSources.allKotlinFiles()
        assertTrue("production sources not found", sources.isNotEmpty())
        val banned = listOf(
            "ZenDisguise",       // forged CLI UA / session / project headers
            "ZenFreeLaneHealth", // auto-blacklist of ids on 401/403 evidence
            "isZenFree",         // key == "public" provider gate
            "zenBudgetTokens",   // free-lane max_completion_tokens ladder
            "bundledZenModels",  // seeded free-id list
            "zenVisibleModels",  // catalogue filter for the keyless instance
            "ZEN_BUNDLED_ENDPOINT",
        )
        val offenders = sources.mapNotNull { file ->
            val text = file.readText()
            banned.filter { it in text }
                .takeIf { it.isNotEmpty() }
                ?.let { "${file.name} -> $it" }
        }
        assertTrue("retired free-lane code is back: $offenders", offenders.isEmpty())
    }

    @Test
    fun `the seeded zen instance is only mentioned by the retirement pass`() {
        val sources = ProductionSources.allKotlinFiles()
        val mentions = sources.sumOf { file ->
            Regex(Regex.escape("builtin-opencode-zen")).findAll(file.readText()).count()
        }
        assertEquals(
            "the retired instance id must exist in exactly one place: the upgrade pass that deletes it",
            1,
            mentions,
        )
    }

    @Test
    fun `the provider store retires instead of seeding`() {
        val repo = ProductionSources.read("data/repository/ProviderRepository.kt")
        assertTrue(repo, repo.contains("retireBuiltInZenProvider()"))
        assertTrue(repo, repo.contains("LEGACY_ZEN_BUILTIN_ID"))
        assertFalse(repo, repo.contains("""saveApiKey(instance.id, "public")"""))
        assertFalse(repo, repo.contains("OpenCode Zen (Free)"))
    }
}
