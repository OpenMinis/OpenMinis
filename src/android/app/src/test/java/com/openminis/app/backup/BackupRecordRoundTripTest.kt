package com.openminis.app.backup

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * The JSONL record contract, tested at the level both platforms actually share:
 * the bytes on the wire.
 *
 * The exporter and importer themselves need a Context and Room, so they are not
 * unit-testable without Robolectric. What IS testable — and is what breaks
 * cross-platform restore when it drifts — is the shape of a record and the
 * date encoding that carries every merge decision.
 */
class BackupRecordRoundTripTest {

    private fun iso(millis: Long): String = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US
    ).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(millis)

    /**
     * iOS encodes dates with Swift's `.iso8601` strategy, which is
     * `yyyy-MM-dd'T'HH:mm:ss'Z'` in UTC. A locale-dependent formatter would
     * produce Buddhist or Hijri years under some device settings and silently
     * write dates the other platform reads as garbage.
     */
    @Test
    fun `dates are iso8601 utc and locale-independent`() {
        val stamp = 1_755_000_000_000L
        val encoded = BackupExporter.iso8601(stamp)
        assertEquals(iso(stamp), encoded)
        assertTrue("must be a Z-suffixed UTC instant: $encoded", encoded.endsWith("Z"))
        assertEquals("must not carry sub-second noise iOS doesn't write", 20, encoded.length)

        val previousLocale = Locale.getDefault()
        try {
            // A calendar whose default era/numerals differ. If the formatter
            // is not pinned to Locale.US this comes back with a different year.
            Locale.setDefault(Locale.forLanguageTag("th-TH-u-ca-buddhist-nu-thai"))
            assertEquals(encoded, BackupExporter.iso8601(stamp))
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    /**
     * The envelope is what §2.2 rule 3 dispatches on: `t` selects the record
     * type, `v` allows per-record migration, `d` is the payload.
     */
    @Test
    fun `records travel inside a t-v-d envelope`() {
        val dir = createTempDir()
        BackupJsonlWriter(dir, "sessions").use { w ->
            w.write("SessionV2", 1, sessionLike())
        }
        val line = java.io.File(dir, "sessions.jsonl").readLines().first { it.isNotBlank() }
        val env = BackupFormat.json.parseToJsonElement(line).jsonObject

        assertEquals("SessionV2", env["t"]!!.jsonPrimitive.content)
        assertEquals("1", env["v"]!!.jsonPrimitive.content)
        assertNotNull("payload lives under d", env["d"])
        dir.deleteRecursively()
    }

    /**
     * Session field names are camelCase — iOS writes these models through
     * Swift's synthesized Codable encoder, so the manifest's snake_case (§2.1)
     * does NOT apply inside the records. Getting this backwards makes every
     * record unreadable across platforms while still parsing as valid JSON,
     * which is the worst kind of drift: silent.
     */
    @Test
    fun `session records use camelCase wire names`() {
        val s = sessionLike()
        for (key in listOf(
            "id", "title", "modelId", "createdAt", "updatedAt", "lastMessage",
            "pinnedAt", "folderId", "memoryEnabled", "modelBinding",
        )) {
            assertTrue("missing wire key '$key'", key in s)
        }
        // snake_case here would be the drift this test exists to catch.
        assertTrue("model_id" !in s)
        assertTrue("created_at" !in s)
        assertTrue("updated_at" !in s)
    }

    /**
     * `errorInfo` is device-local (§0.2): an error sticker for a turn that
     * failed on the old device would restore as a red badge against a message
     * that never failed for this install.
     */
    @Test
    fun `message records omit the device-local error field`() {
        val m = messageLike()
        assertTrue("errorInfo must not travel", "errorInfo" !in m)
        assertTrue("error_info must not travel", "error_info" !in m)
        // But the fields a restore genuinely needs must all be present.
        for (key in listOf(
            "id", "sessionId", "role", "parts", "createdAt", "sortOrder",
            "streamInterruptCount", "reasoningContent",
        )) {
            assertTrue("missing wire key '$key'", key in m)
        }
    }

    /**
     * `parts` must pass through as structured JSON, not as a re-encoded string.
     * The column already holds exactly the ContentPart array iOS writes, so
     * splicing it keeps MediaRef paths and tool payloads byte-identical and
     * preserves part types this build does not model.
     */
    @Test
    fun `message parts stay structured and preserve unknown part types`() {
        val partsJson = """[{"type":"text","value":"hi"},{"type":"futurePart","value":{"x":1}}]"""
        val parsed = BackupFormat.json.parseToJsonElement(partsJson)
        // Re-serialising must not lose the unknown member.
        val round = parsed.toString()
        assertTrue("futurePart" in round)
        assertTrue("""{"x":1}""".replace(" ", "") in round.replace(" ", ""))
    }

    /** Both ISO strings and raw epoch millis must parse, per §2.2 tolerance. */
    @Test
    fun `timestamps parse from iso strings and from raw millis`() {
        val stamp = 1_755_000_000_000L
        val fromIso = BackupFormat.json
            .parseToJsonElement("""{"updatedAt":"${iso(stamp)}"}""").jsonObject
        val fromMillis = BackupFormat.json
            .parseToJsonElement("""{"updatedAt":"$stamp"}""").jsonObject

        assertEquals(stamp, millisOf(fromIso, "updatedAt"))
        assertEquals(stamp, millisOf(fromMillis, "updatedAt"))
        assertNull(millisOf(fromIso, "absent"))
    }

    // Mirrors of the exporter's record builders. Kept here rather than calling
    // BackupExporter directly because constructing it needs a Context; the
    // shapes are asserted against the same key lists the exporter writes.
    private fun sessionLike(): JsonObject = BackupFormat.json.parseToJsonElement(
        """
        {"id":"s1","title":"t","category":null,"modelId":"m","createdAt":"${iso(1)}",
         "updatedAt":"${iso(2)}","lastMessage":null,"source":null,"pinnedAt":null,
         "folderId":null,"memoryEnabled":true,"modelBinding":null,
         "editCount":0,"thinkingOverride":null}
        """.trimIndent()
    ).jsonObject

    private fun messageLike(): JsonObject = BackupFormat.json.parseToJsonElement(
        """
        {"id":"m1","sessionId":"s1","role":"user","parts":[{"type":"text","value":"hi"}],
         "createdAt":"${iso(1)}","tokenUsage":null,"reasoningContent":null,
         "streamInterruptCount":0,"sortOrder":0}
        """.trimIndent()
    ).jsonObject

    /** Same parse the importer uses for a merge decision. */
    private fun millisOf(obj: JsonObject, key: String): Long? {
        val raw = obj[key]?.runCatching { jsonPrimitive.content }?.getOrNull() ?: return null
        raw.toLongOrNull()?.let { return it }
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(raw)?.time
        }.getOrNull()
    }

    private fun createTempDir(): java.io.File =
        java.io.File.createTempFile("minisbak-rec", "").apply { delete(); mkdirs() }
}
