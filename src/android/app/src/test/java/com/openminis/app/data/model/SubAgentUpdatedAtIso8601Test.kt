package com.openminis.app.data.model

import com.openminis.app.backup.Iso8601MillisSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Regression coverage for the real-device crash captured in Minis.Restore on a
 * Pixel 4a:
 *
 * ```
 * [Restore] provider_config.json unreadable: Failed to parse literal
 * "2026-09-06T11:41:21Z" as a long value at element: $.updatedAt
 * [Restore] providers: imported=0 updated=0 skipped=0 unreadable=1 declared=20
 * ```
 *
 * iOS `SubAgentDefinition.updatedAt` is a `Date`, encoded as an ISO-8601 string
 * by the backup exporter. Android's `SubAgentDefinition.updatedAt` used a bare
 * `Long`, so decoding a genuine iOS-produced `provider_config.json` threw and —
 * because kotlinx.serialization aborts the whole document on one bad element —
 * took every one of the 20 declared entries in that file down with it
 * (imported=0), not just the one sub agent. Fixed by applying the same
 * [Iso8601MillisSerializer] pattern already used for `ProviderInstance.createdAt`.
 */
class SubAgentUpdatedAtIso8601Test {

    private val json = Json { ignoreUnknownKeys = true }

    private fun utcFormatter() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /** Exact reproduction of the failing wire payload from the crash log. */
    @Test
    fun `decodes the real ISO8601 string payload that previously crashed the whole file`() {
        val wire = """
            {"id":"builtin.general","name":"General Sub Agent","description":"Sub Agent",
             "sortOrder":0,"updatedAt":"2026-09-06T11:41:21Z"}
        """.trimIndent()

        val def = json.decodeFromString(SubAgentDefinition.serializer(), wire)

        val expectedMillis = utcFormatter().parse("2026-09-06T11:41:21Z")!!.time
        assertEquals(expectedMillis, def.updatedAt)
    }

    /** Legacy Android-written packages wrote updatedAt as a bare numeric epoch. */
    @Test
    fun `decodes the legacy plain numeric epoch millis payload`() {
        val epochMillis = 1_757_159_281_000L // 2025-09-06T11:41:21Z-ish epoch
        val wire = """
            {"id":"custom-1","name":"Researcher","description":"desc",
             "sortOrder":1,"updatedAt":$epochMillis}
        """.trimIndent()

        val def = json.decodeFromString(SubAgentDefinition.serializer(), wire)

        assertEquals(epochMillis, def.updatedAt)
    }

    /**
     * The same instant, encoded once as a string and once as a number, must
     * decode to the identical epoch-millis value — the compatibility shim must
     * not introduce a timezone or truncation skew between the two wire forms.
     */
    @Test
    fun `ISO8601 string and equivalent numeric epoch decode to the same value`() {
        val instantMillis = utcFormatter().parse("2026-01-15T03:04:05Z")!!.time

        val stringWire = """
            {"id":"a","name":"A","description":"d","sortOrder":0,
             "updatedAt":"2026-01-15T03:04:05Z"}
        """.trimIndent()
        val numericWire = """
            {"id":"a","name":"A","description":"d","sortOrder":0,
             "updatedAt":$instantMillis}
        """.trimIndent()

        val fromString = json.decodeFromString(SubAgentDefinition.serializer(), stringWire)
        val fromNumeric = json.decodeFromString(SubAgentDefinition.serializer(), numericWire)

        assertEquals(instantMillis, fromString.updatedAt)
        assertEquals(fromNumeric.updatedAt, fromString.updatedAt)
    }

    /** Round-trip: encoding always produces the ISO-8601 wire form (matches iOS). */
    @Test
    fun `encoding a SubAgentDefinition writes updatedAt as an ISO8601 string`() {
        val millis = utcFormatter().parse("2026-03-01T00:00:00Z")!!.time
        val def = SubAgentDefinition(
            id = "x",
            name = "X",
            description = "d",
            sortOrder = 0,
            updatedAt = millis,
        )

        val encoded = json.encodeToString(SubAgentDefinition.serializer(), def)

        assertTrue("expected an ISO-8601 string on the wire, got: $encoded", "\"2026-03-01T00:00:00Z\"" in encoded)

        // And it round-trips back to the exact same millis value.
        val decoded = json.decodeFromString(SubAgentDefinition.serializer(), encoded)
        assertEquals(millis, decoded.updatedAt)
    }

    /**
     * A full provider_config-shaped roster array — mirrors how the field
     * actually appears embedded inside the larger backup document, so this
     * catches the "aborts the whole document" failure mode directly, not just
     * the single-object decode.
     */
    @Test
    fun `a roster array containing an ISO8601 updatedAt decodes entirely`() {
        val wire = """
            [
              {"id":"builtin.general","name":"General Sub Agent","description":"Sub Agent",
               "sortOrder":0,"updatedAt":"2026-09-06T11:41:21Z"},
              {"id":"custom-1","name":"Researcher","description":"desc",
               "sortOrder":1,"updatedAt":1700000000000}
            ]
        """.trimIndent()

        val roster = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(SubAgentDefinition.serializer()),
            wire,
        )

        assertEquals(2, roster.size)
        assertEquals("builtin.general", roster[0].id)
        assertEquals("custom-1", roster[1].id)
        assertEquals(1_700_000_000_000L, roster[1].updatedAt)
    }
}
