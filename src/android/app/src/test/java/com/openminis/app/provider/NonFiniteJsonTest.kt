package com.openminis.app.provider

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-nonfinite-json] Android's `org.json` parser accepts `1e999` /
 * `Infinity` / `NaN` as `Double`s that its writer refuses to serialize, so a
 * single poisoned tool argument broke every `ToolUse.input.toString()`
 * downstream (resident-window sizing, persistence, request bodies). These
 * tests pin the scrubber that removes such values at the parse boundaries.
 *
 * Test-classpath caveat: json.org (the `org.json:json` artifact used for
 * unit tests) refuses to *construct* non-finite values — `put` throws via
 * `testValidity`, and its parser downgrades `1e999` to a finite `BigDecimal`
 * — while the Android runtime's parser happily produces `Infinity`. The
 * Android parse result is therefore injected through reflection on json.org's
 * internal map/list fields. That couples the helper to json.org internals
 * (field names `map` / `myArrayList`), which is acceptable for a test-only
 * shim; if json.org renames them the shim fails loudly and gets updated.
 */
class NonFiniteJsonTest {

    /** Build a JSONObject holding values json.org would refuse to `put`. */
    private fun poisonedObject(vararg entries: Pair<String, Any>): JSONObject {
        val obj = JSONObject()
        val field = JSONObject::class.java.getDeclaredField("map")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(obj) as MutableMap<String, Any>
        for ((k, v) in entries) map[k] = v
        return obj
    }

    /** Build a JSONArray holding values json.org would refuse to `put`. */
    private fun poisonedArray(vararg values: Any): JSONArray {
        val arr = JSONArray()
        val field = JSONArray::class.java.getDeclaredField("myArrayList")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val list = field.get(arr) as MutableList<Any>
        for (v in values) list.add(v)
        return arr
    }

    @Test
    fun `non-finite double entries are dropped, finite siblings survive`() {
        val obj = poisonedObject(
            "offset" to Double.POSITIVE_INFINITY,
            "limit" to java.lang.Double.NaN,
            "temperature" to java.lang.Double.NEGATIVE_INFINITY,
            "count" to 1.5,
        )
        NonFiniteJson.scrub(obj)
        assertFalse("Infinity entry must be dropped", obj.has("offset"))
        assertFalse("NaN entry must be dropped", obj.has("limit"))
        assertFalse("-Infinity entry must be dropped", obj.has("temperature"))
        assertTrue(obj.has("count"))
        assertEquals(1.5, obj.optDouble("count"), 0.0)
        // The whole point: the scrubbed object must round-trip JSON.
        assertTrue(obj.toString().isNotEmpty())
    }

    @Test
    fun `non-finite float entries are dropped too`() {
        val obj = poisonedObject("f" to Float.POSITIVE_INFINITY, "ok" to 2L)
        NonFiniteJson.scrub(obj)
        assertFalse(obj.has("f"))
        assertEquals(2L, obj.optLong("ok"))
    }

    @Test
    fun `nested objects and arrays are scrubbed`() {
        val nested = poisonedObject("inner" to Double.NaN, "keep" to "v")
        val arr = poisonedArray(1.0, Double.POSITIVE_INFINITY, "tail")
        val outer = poisonedObject(
            "nested" to nested,
            "arr" to arr,
            "top" to Double.NaN,
        )
        NonFiniteJson.scrub(outer)
        assertFalse(outer.has("top"))
        assertTrue(outer.has("nested"))
        assertFalse(nested.has("inner"))
        assertTrue(nested.has("keep"))
        assertEquals(2, arr.length())
        assertEquals(1.0, arr.opt(0))
        assertEquals("tail", arr.opt(1))
    }

    @Test
    fun `array elements are dropped in place keeping order`() {
        val arr = poisonedArray("head", Double.NaN, 3L, Double.POSITIVE_INFINITY, "end")
        NonFiniteJson.scrub(arr)
        assertEquals(3, arr.length())
        assertEquals("head", arr.opt(0))
        assertEquals(3L, arr.opt(1))
        assertEquals("end", arr.opt(2))
    }

    @Test
    fun `finite big numbers survive the scrub`() {
        // json.org parses 1e999 as a finite BigDecimal; Android parses it as
        // Infinity. The scrubber must only drop non-finite Double/Float —
        // dropping a finite BigDecimal would be data loss, and it serializes
        // fine ("1E+999").
        val obj = NonFiniteJson.parseOrNull("{\"huge\":1e999,\"small\":7}")!!
        assertTrue("finite arbitrary-precision number must survive", obj.has("huge"))
        assertTrue(obj.has("small"))
        assertTrue(obj.toString().contains("1E+999"))
    }

    @Test
    fun `clean objects pass through unchanged`() {
        val raw = "{\"a\":1,\"b\":\"x\",\"c\":null,\"d\":[1,2],\"e\":{\"f\":true}}"
        val before = JSONObject(raw).toString()
        val after = NonFiniteJson.scrub(JSONObject(raw)).toString()
        assertEquals(before, after)
    }

    @Test
    fun `parseOrNull returns null on malformed input`() {
        assertNull(NonFiniteJson.parseOrNull("{oops"))
        assertNull(NonFiniteJson.parseOrNull(""))
    }

    @Test
    fun `parseOrNull parses and scrubs valid input`() {
        val obj = NonFiniteJson.parseOrNull("{\"a\":1.5,\"b\":[2,3]}")!!
        assertEquals(1.5, obj.optDouble("a"), 0.0)
        assertEquals(2, obj.optJSONArray("b").length())
    }

    @Test
    fun `parse throws on malformed input like the constructor it replaces`() {
        var threw = false
        try {
            NonFiniteJson.parse("{oops")
        } catch (_: JSONException) {
            threw = true
        }
        assertTrue(threw)
    }
}
