package com.openminis.app.config

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * JSON-serializable union covering every value type a [ConfigField] can
 * hold. Mirrors iOS `ConfigValue` (Shared/Config/ConfigField.swift).
 *
 * The CLI bridge encodes/decodes these as JSON; the confirm dialog
 * renders them via [displayString]; the audit log persists them as JSON
 * text in the `old_value` / `new_value` columns.
 */
sealed class ConfigValue {
    data class Bool(val value: Boolean) : ConfigValue()
    data class Int(val value: kotlin.Int) : ConfigValue()
    data class Double(val value: kotlin.Double) : ConfigValue()
    data class Str(val value: String) : ConfigValue()
    data class Arr(val value: List<ConfigValue>) : ConfigValue()
    data class Obj(val value: Map<String, ConfigValue>) : ConfigValue()
    object Null : ConfigValue()

    /**
     * Compact human-readable rendering used by the confirm dialog and
     * `--help` examples. Long strings are truncated; arrays/objects show
     * their JSON form. Mirrors iOS `displayString`.
     */
    val displayString: String
        get() = when (this) {
            is Bool -> if (value) "true" else "false"
            is Int -> value.toString()
            is Double -> value.toString()
            is Str -> {
                if (value.length > 80) {
                    "\"${value.take(60)}…${value.takeLast(15)}\""
                } else {
                    "\"$value\""
                }
            }
            is Arr, is Obj -> jsonString()
            Null -> "null"
        }

    /**
     * Returns a copy with every value under a [SECRET_KEYS] key masked.
     * `$$VAR` references pass through (they're a pointer, not the secret);
     * any other non-empty literal string becomes `"••• (hidden)"`. Recurses
     * into nested objects/arrays so a secret nested in an add-payload is
     * caught too. Mirrors iOS `ConfigValue.redactingSecrets()` —
     * [T-minis-config-provider-add].
     *
     * Apply this to add-payload / audit-row JSON when the path resolves to a
     * collection that may contain credentials. The real (un-redacted) value
     * still reaches the collection's `add()` so it can persist the secret to
     * the encrypted store; only the displayed / audit-logged copy is masked.
     */
    fun redactingSecrets(): ConfigValue = when (this) {
        is Obj -> {
            val out = LinkedHashMap<String, ConfigValue>(value.size)
            for ((k, v) in value) {
                if (k in SECRET_KEYS && v is Str &&
                    !v.value.startsWith("\$\$") && v.value.isNotEmpty()) {
                    out[k] = Str("••• (hidden)")
                } else {
                    out[k] = v.redactingSecrets()
                }
            }
            Obj(out)
        }
        is Arr -> Arr(value.map { it.redactingSecrets() })
        else -> this
    }

    /** JSON-text representation suitable for storing in audit rows. */
    fun jsonString(): String = toJsonAny().let { any ->
        when (any) {
            JSONObject.NULL -> "null"
            is JSONObject -> any.toString()
            is JSONArray -> any.toString()
            is String -> JSONObject.quote(any)
            else -> any.toString()
        }
    }

    /** Recursively convert to a JSON-compatible Any (JSONObject/JSONArray/primitive/null). */
    private fun toJsonAny(): Any = when (this) {
        is Bool -> value
        is Int -> value
        is Double -> value
        is Str -> value
        is Arr -> JSONArray().also { arr ->
            for (v in value) {
                val a = v.toJsonAny()
                if (a === JSONObject.NULL) arr.put(JSONObject.NULL) else arr.put(a)
            }
        }
        is Obj -> JSONObject().also { obj ->
            for ((k, v) in value) {
                val a = v.toJsonAny()
                obj.put(k, if (a === JSONObject.NULL) JSONObject.NULL else a)
            }
        }
        Null -> JSONObject.NULL
    }

    companion object {
        /**
         * Object keys whose values are credential-sensitive and must be
         * masked in any audit / display / confirm-sheet surface. Mirrors
         * iOS `ConfigValue.secretObjectKeys`. Used by [redactingSecrets].
         */
        val SECRET_KEYS: Set<String> = setOf("apiKey", "oauthToken", "manualOAuthToken")

        /**
         * Parse a JSON string into a ConfigValue. Returns null on malformed JSON.
         *
         * [T-container-network-dns] Null also when text is left over after the
         * first value. org.json reads an UNQUOTED value only up to the first
         * `, : ; = # [ ] { } /`, so `set network.dnsCustomServers
         * 223.5.5.5,8.8.8.8` used to store "223.5.5.5" and drop the rest, an
         * unquoted IPv6 address kept only what came before its first `:`, and
         * an unquoted URL kept "https". A silent partial write is worse than
         * an error, so the whole input must be one value; a quoted string or
         * an unquoted single word still works as before.
         */
        fun decode(json: String): ConfigValue? = try {
            val tokener = JSONTokener(json)
            val value = tokener.nextValue()
            if (tokener.nextClean() != 0.toChar()) null else fromJsonAny(value)
        } catch (_: Throwable) {
            null
        }

        private fun fromJsonAny(any: Any?): ConfigValue = when (any) {
            null, JSONObject.NULL -> Null
            is Boolean -> Bool(any)
            is kotlin.Int -> Int(any)
            is Long -> {
                if (any in kotlin.Int.MIN_VALUE.toLong()..kotlin.Int.MAX_VALUE.toLong()) Int(any.toInt())
                else Double(any.toDouble())
            }
            is kotlin.Double, is Float -> Double((any as Number).toDouble())
            is String -> Str(any)
            is JSONArray -> {
                val out = ArrayList<ConfigValue>(any.length())
                for (i in 0 until any.length()) out.add(fromJsonAny(any.opt(i)))
                Arr(out)
            }
            is JSONObject -> {
                val out = LinkedHashMap<String, ConfigValue>(any.length())
                val keys = any.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    out[k] = fromJsonAny(any.opt(k))
                }
                Obj(out)
            }
            else -> Null
        }
    }
}
