package com.openminis.app.config

import com.openminis.app.config.collections.ModelsCollection
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Model ids with dots in config paths.
 *
 * History: [T-android-config-model-id-dot] (440465f7b / 2bb448166, port of iOS
 * eaf30cda5) escaped dots as `~d` so the registry's `limit = 3` splitter would
 * not cut the id. That worked but was undiscoverable (OpenMinis#390). Since
 * [T-config-path-dotted-id] (iOS cef38f13b) the registry takes everything
 * between the first and last dot as the id, so paths carry the RAW id — see
 * ConfigPathSplitTest. What remains here is the legacy escape: builds from
 * 09-19 until #390 wrote `~d` paths into audit keys and scripts, and those must
 * keep resolving, through the REAL ModelsCollection decoder and lookup rule.
 */
class ModelConfigPathDotTest {

    /** The pre-#390 encoder, kept only to produce legacy inputs. */
    private fun legacyEncode(id: String): String = id.replace("~", "~~").replace(".", "~d")

    private fun decode(segment: String) = ModelsCollection.decodeLegacySegment(segment)

    // ---- Legacy decode --------------------------------------------------------

    @Test
    fun `legacy paths round-trip across the id shapes users actually type`() {
        val ids = listOf(
            "UUID/gpt-4.1", "UUID/my.model.v2", "UUID/deepseek-flash", "UUID/a.b.c.d",
            "UUID/~", "UUID/~~", "UUID/~d", "UUID/.leading", "UUID/trailing.",
            "UUID/mixed.~d.value",
        )
        for (id in ids) assertEquals("round-trip failed for $id", id, decode(legacyEncode(id)))
    }

    @Test
    fun `ids without dots or tildes decode to themselves`() {
        for (id in listOf("AAAA-BBBB/gpt-4o", "UUID/claude-opus-4-8", "x/y")) assertEquals(id, decode(id))
    }

    @Test
    fun `the old escape was injective`() {
        val a = "u/a.b"      // a real dot
        val b = "u/a~db"     // literal "~d" the user typed
        assertNotEquals(legacyEncode(a), legacyEncode(b))
        assertEquals(a, decode(legacyEncode(a)))
        assertEquals(b, decode(legacyEncode(b)))
    }

    @Test
    fun `a literal tilde-d-tilde round-trips, which two-pass decoding gets wrong`() {
        val tricky = "u/~d~"
        assertEquals(tricky, decode(legacyEncode(tricky)))
        val twoPass = legacyEncode(tricky).replace("~d", ".").replace("~~", "~")
        assertNotEquals("two-pass decode must be wrong here", tricky, twoPass)
    }

    // ---- Lookup rule: raw first, legacy second ----------------------------------

    private val real = setOf("U/mimo-v2.6-pro", "U/kimi-k3", "U/lit~deral", "U/lit.eral")
    private fun find(id: String) = ModelsCollection.lookupIn(id) { c -> c.takeIf { it in real } }

    @Test
    fun `the raw id is found as is`() {
        assertEquals("U/mimo-v2.6-pro", find("U/mimo-v2.6-pro"))
        assertEquals("U/kimi-k3", find("U/kimi-k3"))
    }

    @Test
    fun `the legacy escaped spelling still finds its entry`() {
        assertEquals("U/mimo-v2.6-pro", find("U/mimo-v2~d6-pro"))
    }

    @Test
    fun `raw wins, so an id that literally contains ~d is not decoded into another`() {
        // "U/lit~deral" would decode to "U/lit.eral", which ALSO exists.
        assertEquals("U/lit~deral", find("U/lit~deral"))
    }

    @Test
    fun `an unknown id finds nothing, raw or decoded`() {
        assertNull(find("U/nope"))
        assertNull(find("U/no~dpe"))
    }

    // ---- Source facts -------------------------------------------------------------

    private val src by lazy {
        File("src/main/java/com/openminis/app/config/collections/ModelsCollection.kt").readText()
    }

    @Test
    fun `paths carry the raw id and the encoder is gone`() {
        assertTrue(src.contains("path = \"models.\$id.displayName\""))
        assertTrue("no escaping on the way into a path any more", !src.contains("pathSegment"))
    }

    @Test
    fun `the decoder is a single scan, not chained replaces`() {
        val fn = src.substringAfter("internal fun decodeLegacySegment").substringBefore("internal fun <T : Any> lookupIn")
        assertTrue("must scan with an index", fn.contains("while (i < segment.length)"))
        assertTrue(!fn.contains(".replace(\"~d\"") && !fn.contains(".replace(\"~~\""))
    }

    @Test
    fun `every entry point resolves through lookup, and entry() is an exact match`() {
        assertTrue(src.contains("private fun lookup(idOrSegment: String): ModelEntry? = lookupIn(idOrSegment, ::entry)"))
        val entryFn = src.substringAfter("private fun entry(id: String)").substringBefore("private fun mutate")
        assertTrue("entry() compares raw ids only", entryFn.contains("it.id == id") && !entryFn.contains("decode"))
        val mutate = src.substringAfter("private fun mutate(").substringBefore("private fun providerInstanceId(")
        assertTrue(mutate.contains("lookup(id)"))
    }
}
