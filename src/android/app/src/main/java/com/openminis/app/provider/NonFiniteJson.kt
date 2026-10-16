package com.openminis.app.provider

import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-android-nonfinite-json] Android's `org.json` parser and writer disagree
 * about non-finite numbers, and the gap is a crash class.
 *
 * `JSONTokener` parses numeric literals with `Double.valueOf` and no
 * finiteness check, so model-emitted JSON like `{"offset": 1e999}` — or a
 * bare `Infinity` / `NaN` word, which `Double.valueOf` also accepts — parses
 * successfully into a `Double` the writer then refuses to serialize:
 * `JSONObject.numberToString` throws `JSONException("Forbidden numeric
 * value: Infinity")`, and the no-arg `JSONObject.toString()` catches that
 * and returns **null**, which Kotlin callers treat as a non-null platform
 * `String` and promptly NPE on. Either way every `ToolUse.input.toString()`
 * downstream (resident-window sizing, token estimation, persistence,
 * request bodies) is a latent bomb: one poisoned tool argument breaks the
 * turn, and a poisoned row breaks every later session load.
 *
 * The fix is structural: scrub non-finite numbers at every boundary where
 * externally-controlled text becomes `org.json` — provider tool-argument
 * parses, persisted-history restore, JSON repair, stream-trace replay, MCP
 * responses. A value that cannot round-trip JSON never enters the object
 * graph, so every existing `toString()` site is safe without touching it.
 *
 * Semantics: an entry whose value is a non-finite `Double`/`Float` is
 * REMOVED (object key dropped, array element dropped). Tools see the key as
 * absent and apply their own defaults — the same outcome as the model never
 * sending it. Finite numbers of other types (`Long`, `BigDecimal` from
 * json.org's arbitrary-precision fallback) are kept untouched: they
 * serialize fine and dropping them would be data loss.
 *
 * Kept free of Android types so it is plain-JVM testable. Note for tests:
 * json.org (the unit-test classpath) refuses to *construct* non-finite
 * values via `put` and parses `1e999` as a finite `BigDecimal`, so tests
 * inject the Android-parse result through reflection — see
 * `NonFiniteJsonTest`.
 */
object NonFiniteJson {

    /** `true` for a `Double`/`Float` value that JSON cannot serialize. */
    fun isNonFiniteNumber(value: Any?): Boolean = when (value) {
        is Double -> value.isNaN() || value.isInfinite()
        is Float -> value.isNaN() || value.isInfinite()
        else -> false
    }

    /**
     * Remove non-finite numeric entries from [obj], in place, recursing into
     * nested objects and arrays. Returns the same instance for chaining.
     */
    fun scrub(obj: JSONObject): JSONObject {
        // Collect first, remove after: mutating nameValuePairs while
        // iterating its key set is a ConcurrentModificationException.
        val doomed = ArrayList<String>(4)
        for (key in obj.keys()) {
            val v = obj.opt(key) ?: continue
            if (isNonFiniteNumber(v)) {
                doomed.add(key)
                continue
            }
            if (v is JSONObject) scrub(v) else if (v is JSONArray) scrub(v)
        }
        for (k in doomed) obj.remove(k)
        return obj
    }

    /** Array variant of [scrub]; drops the element, keeping order of the rest. */
    fun scrub(arr: JSONArray): JSONArray {
        // Backwards so removals never shift an index we have not visited.
        for (i in arr.length() - 1 downTo 0) {
            val v = arr.opt(i) ?: continue
            if (isNonFiniteNumber(v)) {
                arr.remove(i)
                continue
            }
            if (v is JSONObject) scrub(v) else if (v is JSONArray) scrub(v)
        }
        return arr
    }

    /**
     * Parse [raw] and scrub it. Throws `JSONException` on malformed input,
     * exactly like the `JSONObject(String)` constructor it replaces.
     */
    fun parse(raw: String): JSONObject = scrub(JSONObject(raw))

    /**
     * Parse-and-scrub that never throws: `null` on malformed input. Drop-in
     * for the `try { JSONObject(x) } catch { … }` shapes at the provider
     * parse boundaries.
     */
    fun parseOrNull(raw: String): JSONObject? = try {
        scrub(JSONObject(raw))
    } catch (_: Exception) {
        null
    }
}
