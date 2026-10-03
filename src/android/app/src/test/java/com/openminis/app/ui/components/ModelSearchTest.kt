package com.openminis.app.ui.components

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-picker-search-relevance] [T-picker-search-debounce] [T-picker-search-cap]
 * Model-picker search, iOS parity with ModelSearchScorerTests (30fb01839):
 * tiers, bonuses never crossing a tier, token boundaries, multi-field best
 * score — plus the Android section search (provider-name hits, cap across
 * sections) and the wiring that keeps it off the per-keystroke path.
 */
class ModelSearchTest {

    private fun score(t: String, q: String) = ModelSearch.score(t, q)

    // ---- tiers --------------------------------------------------------------

    @Test
    fun `tiers rank exact over prefix over token prefix over substring over subsequence`() {
        val exact = score("claude", "claude")
        val prefix = score("claude-3", "claude")
        val token = score("anthropic/claude-3", "claude")
        val sub = score("xclaudex", "claude")
        val subseq = score("c-l-a-u-d-e", "claude")
        assertEquals(ModelSearch.EXACT, exact)
        assertTrue(exact > prefix && prefix > token && token > sub && sub > subseq && subseq > 0)
    }

    @Test
    fun `the GH-272 case - a prefix hit outranks a subsequence hit`() {
        assertTrue(score("claude-3-5-sonnet", "claude") > score("cl-a-u-de-x", "claude"))
    }

    @Test
    fun `no match is zero`() {
        assertEquals(0, score("gpt-5", "claude"))
    }

    @Test
    fun `the length bonus orders within a tier and never crosses into the next`() {
        assertTrue(score("claude-3", "claude") > score("claude-3-5-sonnet-20241022", "claude"))
        val longest = score("claude" + "x".repeat(500), "claude")
        assertTrue(longest >= ModelSearch.PREFIX && longest < ModelSearch.EXACT)
        val shortestToken = score("a/claude", "claude")
        assertTrue(shortestToken < ModelSearch.PREFIX)
        val shortestSub = score("xclaude", "claude")
        assertTrue(shortestSub < ModelSearch.TOKEN_PREFIX)
    }

    @Test
    fun `every id separator counts as a word boundary`() {
        for (sep in listOf("-", "/", ".", "_", ":", " ")) {
            val s = score("claude${sep}sonnet", "sonnet")
            assertTrue("sep '$sep' scored $s", s in ModelSearch.TOKEN_PREFIX until ModelSearch.PREFIX)
        }
    }

    @Test
    fun `matching is case-insensitive on the target`() {
        assertEquals(ModelSearch.EXACT, score("Claude", "claude"))
        assertEquals("claude", ModelSearch.normalize("  Claude "))
    }

    @Test
    fun `best score takes the best field, an exact id wins`() {
        assertEquals(ModelSearch.EXACT, ModelSearch.bestScore(listOf("Sonnet (latest)", "claude"), "claude"))
    }

    // ---- ranking and sections ----------------------------------------------

    private fun entry(id: String, name: String = id) =
        ModelEntry(providerInstanceId = "p", baseModel = LLMModel(id = id, displayName = name, provider = "p"), uuid = id)

    @Test
    fun `rank drops non-matches and puts the best first, ties keep incoming order`() {
        val list = listOf(entry("c-l-a-u-d-e"), entry("claude-3-5-sonnet"), entry("claude-3"), entry("gpt-5"), entry("claude-4"))
        assertEquals(listOf("claude-3", "claude-4", "claude-3-5-sonnet", "c-l-a-u-d-e"), ModelSearch.rank(list, "claude").map { it.id })
    }

    @Test
    fun `a provider-name hit shows that provider's whole list`() {
        val r = ModelSearch.search(
            listOf("host.example.com" to listOf(entry("/models/Qwen3.8-27B.gguf")), "Other" to listOf(entry("gpt-5"))),
            label = { it },
            query = "wsen",
        )
        assertEquals(listOf("host.example.com"), r.sections.map { it.key })
        assertEquals(1, r.sections.single().entries.size)
    }

    @Test
    fun `the cap applies across sections and keeps the most relevant rows`() {
        val big = (1..7000).map { entry("model-$it") } + entry("model")
        val r = ModelSearch.search(listOf("agg" to big, "b" to listOf(entry("model-x"))), label = { it }, query = "model")
        assertEquals(ModelSearch.MAX_RESULTS, r.shown)
        assertEquals(7002, r.totalMatches)
        assertTrue(r.truncated)
        assertEquals("the exact hit survives truncation", "model", r.sections.first().entries.first().id)
        assertEquals("the cap is total, not per section", listOf("agg"), r.sections.map { it.key })
    }

    @Test
    fun `an empty query returns every section unchanged and uncapped`() {
        val big = (1..500).map { entry("m$it") }
        val r = ModelSearch.search(listOf("a" to big, "empty" to emptyList()), label = { it }, query = "")
        assertEquals(500, r.shown)
        assertFalse(r.truncated)
        assertEquals(listOf("a"), r.sections.map { it.key })
    }

    @Test
    fun `debounce and cap are the iOS values`() {
        assertEquals(120L, ModelSearch.DEBOUNCE_MS)
        assertEquals(150, ModelSearch.MAX_RESULTS)
    }

    // ---- wiring ---------------------------------------------------------------

    @Test
    fun `the chat picker searches the debounced query off the main thread, row by row`() {
        val src = ProductionSources.read("ui/chat/ChatModelPickerSheet.kt")
        assertTrue(src.contains("val query = com.openminis.app.ui.components.rememberDebouncedQuery(searchText)"))
        assertTrue(src.contains("withContext(Dispatchers.Default) {\n            com.openminis.app.ui.components.ModelSearch.search(baseSections, ::sectionLabel, query)"))
        assertTrue("expanded rows are lazy items", src.contains("itemsIndexed(entries, key = { _, e -> \"entry_\${instance.id}_\${e.id}\" })"))
        assertTrue(src.contains("R.string.model_picker_search_capped"))
        assertFalse("the Bool fuzzy filter is gone", src.contains("private fun fuzzyMatch("))
    }

    @Test
    fun `the entry picker takes a remembered result and renders rows lazily`() {
        val picker = ProductionSources.read("ui/components/ModelEntryPicker.kt")
        assertTrue(picker.contains("val result = sections ?: modelEntryPickerSearch("))
        assertTrue(picker.contains("itemsIndexed(entries, key = { _, e -> \"entry_\${instance.id}_\${e.id}\" })"))
        for (caller in listOf("ui/settings/AddModelsToGroupScreen.kt", "ui/settings/AgentLoopAddSheets.kt")) {
            val c = ProductionSources.read(caller)
            assertTrue(caller, c.contains("rememberModelEntryPickerSections("))
            assertTrue(caller, c.contains("sections = searchSections,"))
        }
    }

    @Test
    fun `the voice picker uses the same scorer and debounce`() {
        val src = ProductionSources.read("ui/components/UnifiedModelPickerSheet.kt")
        assertTrue(src.contains("val query = rememberDebouncedQuery(searchText)"))
        assertTrue(src.contains("ModelSearch.rank(sectionEntries, query)"))
    }
}
