package com.openminis.app.ui.settings

import com.openminis.app.data.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-token-attribution-snapshot] The rule that decides whether a usage number
 * is presented as a measurement or as a guess.
 *
 * ## Why this is worth pinning
 *
 * The original bug was not a wrong sum — the token counts were always right.
 * It was a wrong LABEL: usage was attributed by joining `sessions.model_id`, a
 * mutable column with no history, so a session's entire past silently moved to
 * whatever model it currently pointed at. Nothing on screen distinguished a
 * re-attributed total from a real one, which is why it survived two rounds of
 * fixes to the same screen.
 *
 * So the classification is the part that must not regress. Collapsing any two
 * of these states back into one restores exactly the ambiguity that hid the
 * bug.
 */
class UsageAttributionTest {

    // ── A: measured ──────────────────────────────────────────────────────

    @Test
    fun `snapshot that resolves in config is measured`() {
        assertEquals(
            Attribution.MEASURED,
            classifyAttribution(modelId = "grok-4.5", hasSnapshot = true, resolvesInConfig = true),
        )
    }

    // ── B: estimated (pre-migration rows) ────────────────────────────────

    /**
     * The fallback path. `COALESCE(m.model_id, s.model_id)` yields the
     * session's CURRENT model for old rows — usable, but only as an estimate,
     * because that column may have been rewritten any number of times since.
     */
    @Test
    fun `row without a snapshot is estimated even when the id resolves`() {
        assertEquals(
            Attribution.ESTIMATED,
            classifyAttribution(modelId = "grok-4.5", hasSnapshot = false, resolvesInConfig = true),
        )
    }

    /** Not knowing the model AND not having a snapshot is still just estimated-unknown, not measured. */
    @Test
    fun `row without a snapshot whose id does not resolve is still estimated`() {
        assertEquals(
            Attribution.ESTIMATED,
            classifyAttribution(modelId = "ghost", hasSnapshot = false, resolvesInConfig = false),
        )
    }

    // ── C: orphaned rows ─────────────────────────────────────────────────

    /**
     * GH#168 kept these rows rather than dropping them (the tokens were really
     * billed). They have no session to infer from, so they must land in their
     * own bucket instead of borrowing another model's identity.
     */
    @Test
    fun `null model id is an unknown session`() {
        assertEquals(
            Attribution.UNKNOWN_SESSION,
            classifyAttribution(modelId = null, hasSnapshot = false, resolvesInConfig = false),
        )
    }

    // ── D: the case this feature exists for ──────────────────────────────

    /**
     * Provider deleted after the fact. Before the snapshot, a CUSTOM model here
     * had no remaining source for its name — deleting the instance also drops
     * its modelEntries — so it degraded to a bare id in an "Unknown" bucket.
     * With a snapshot it stays a real, named row.
     */
    @Test
    fun `snapshot whose provider was deleted is still measured`() {
        assertEquals(
            Attribution.MEASURED_REMOVED,
            classifyAttribution(modelId = "my-private-model", hasSnapshot = true, resolvesInConfig = false),
        )
    }

    /**
     * An orphaned row that DOES carry a snapshot must stay measured.
     *
     * Worth pinning because the branch order decides it: `COALESCE` returns the
     * snapshot id even when the session row is gone, so `modelId` is non-null
     * and the UNKNOWN_SESSION arm is correctly skipped. Reordering the `when`
     * to test `hasSnapshot` after a session-existence check would silently
     * downgrade perfectly good data to "unknown".
     */
    @Test
    fun `orphaned row that has a snapshot is still measured`() {
        assertEquals(
            Attribution.MEASURED,
            classifyAttribution(modelId = "grok-4.5", hasSnapshot = true, resolvesInConfig = true),
        )
        // …and the same row keeps its identity when the provider is gone too.
        assertEquals(
            Attribution.MEASURED_REMOVED,
            classifyAttribution(modelId = "grok-4.5", hasSnapshot = true, resolvesInConfig = false),
        )
    }

    /** All four states are distinct — collapsing any two reintroduces the ambiguity. */
    @Test
    fun `the four states are mutually exclusive`() {
        val seen = setOf(
            classifyAttribution("m", hasSnapshot = true, resolvesInConfig = true),
            classifyAttribution("m", hasSnapshot = false, resolvesInConfig = true),
            classifyAttribution(null, hasSnapshot = false, resolvesInConfig = false),
            classifyAttribution("m", hasSnapshot = true, resolvesInConfig = false),
        )
        assertEquals(4, seen.size)
    }

    // ── Provider grouping ────────────────────────────────────────────────

    /**
     * Snapshots store the ProviderType rawValue, not a display name. Display
     * names are localized and were historically inconsistent — iOS ended up
     * rendering "Google", "Gemini" and "Google Gemini" as three sections for
     * one provider.
     */
    @Test
    fun `provider rawValue maps to its display name`() {
        assertEquals(ProviderType.openAI.displayName, providerDisplayName("openAI"))
        assertEquals(ProviderType.gemini.displayName, providerDisplayName("gemini"))
        assertEquals(ProviderType.anthropic.displayName, providerDisplayName("anthropic"))
    }

    /**
     * A rawValue this build no longer knows (provider type removed in a later
     * version, or a package synced from a newer build) degrades to the raw
     * string. Showing something odd beats dropping the row's tokens.
     */
    @Test
    fun `unknown provider rawValue degrades to the raw string`() {
        assertEquals("someFutureProvider", providerDisplayName("someFutureProvider"))
        assertEquals("", providerDisplayName(""))
    }
}
