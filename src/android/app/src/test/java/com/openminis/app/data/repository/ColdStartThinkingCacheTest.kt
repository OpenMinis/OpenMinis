package com.openminis.app.data.repository

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GH#306] Cold start must warm the custom-thinking-rule cache.
 *
 * The reported symptom: a custom thinking rule works right after you save it, then
 * silently stops applying after the app is killed and restarted — the rule is still
 * on disk and still shown in Settings, but requests carry the built-in wire format.
 *
 * The mechanism is a RACE, which is why it was hard to pin down (on a Pixel 6 the
 * synchronous callers won 3/3 cold starts, so the bug did not surface there):
 *
 *   ensureConfigLoaded()  early-returns at the top when `_configLoaded` is true, and
 *                         only calls loadAllThinkingRulesIntoCache() AFTER its
 *                         synchronized block.
 *   the init coroutine    sets `_configLoaded = true` asynchronously.
 *
 *   loader wins the race -> every later ensureConfigLoaded() early-returns -> the warm
 *                           is never reached -> custom rules silently fall back.
 *   a caller wins        -> takes the synchronized path -> falls through to the warm.
 *
 * A JVM unit test cannot drive Android's async init, so this is a source-fact check on
 * the invariant that closes the window: the branch that SETS the flag must also warm
 * the cache. If someone later removes that call, this fails instead of the bug
 * reappearing as an intermittent field report.
 */
class ColdStartThinkingCacheTest {

    private val src: String by lazy {
        val f = File("src/main/java/com/openminis/app/data/repository/ProviderRepository.kt")
        assertTrue("missing ${f.absolutePath}", f.exists())
        f.readText()
    }

    /** The `init { loadScope.launch { … } }` body, where the async adoption happens. */
    private val initBlock: String by lazy {
        src.substringAfter("    init {").substringBefore("\n    private fun saveConfig(")
    }

    @Test
    fun `the async loader warms the thinking-rule cache after adopting the config`() {
        assertTrue(
            "the init coroutine must call loadAllThinkingRulesIntoCache(), or a cold " +
                "start where the loader wins the race leaves the cache empty",
            initBlock.contains("loadAllThinkingRulesIntoCache()"),
        )
    }

    @Test
    fun `the warm is guarded so it only runs when this coroutine adopted the config`() {
        // If a writer won the race, ensureConfigLoaded() already warmed the cache and a
        // second full DB reload would be pure work on the cold-start path.
        assertTrue(initBlock.contains("adoptedByAsyncLoader = true"))
        assertTrue(initBlock.contains("if (adoptedByAsyncLoader) loadAllThinkingRulesIntoCache()"))
    }

    @Test
    fun `ensureConfigLoaded still warms the cache on the path it owns`() {
        // The belt-and-braces half: the fix ADDS a warm, it must not move one.
        val fn = src.substringAfter("internal fun ensureConfigLoaded()")
            .substringBefore("\n    private fun ")
        assertTrue(
            "ensureConfigLoaded must keep its own loadAllThinkingRulesIntoCache()",
            fn.contains("loadAllThinkingRulesIntoCache()"),
        )
    }
}
