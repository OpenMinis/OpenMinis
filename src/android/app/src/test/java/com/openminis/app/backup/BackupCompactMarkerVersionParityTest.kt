package com.openminis.app.backup

import com.openminis.app.ProductionSources
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.db.MessageEntity
import java.io.File
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-platform field parity for the two chat record kinds that
 * BackupIosWireShapeTest (72ec83b96) does NOT cover: messages and compact
 * markers. Guards the same invariant 72ec83b96 fixed for sessions — "every key
 * iOS's synthesized Codable requires is on the wire" — plus the marker
 * `version` round trip on Android itself.
 *
 * Why a separate parser from BackupIosWireShapeTest: that test's property regex
 * skips `var x: T = default` lines. Swift's SYNTHESIZED Decodable ignores
 * property defaults and still requires the key for any non-optional property,
 * so those lines are exactly the ones that break a decode. iOS
 * `CompactMarker.version: Int = 1` is such a property:
 *
 *   - Android's markerRecord never writes `version`, so on an iPhone every
 *     Android compact-marker line fails `decode(CompactMarker.self)` and is
 *     dropped silently by readJSONL's `try?`.
 *   - Android's BackupRecordMapper.compactMarker never reads `version`, so a
 *     v2 marker (both platforms write v2 today: iOS
 *     AIChatViewModel+Compaction `version: 2`, Android ChatViewModel
 *     `version = 2` with `firstKeptSortOrder = Int.MAX_VALUE`) restores as v1
 *     and is resolved through the legacy sort-order chain — on an
 *     Android -> Android restore too.
 *
 * Proposed fix: markerRecord `put("version", JsonPrimitive(c.version))`;
 * compactMarker `version = c.int("version") ?: 1`.
 *
 * The tests marked BUG below fail on current code by design.
 */
class BackupCompactMarkerVersionParityTest {

    private data class SwiftProp(val name: String, val optional: Boolean)

    private val repoRoot: File by lazy {
        var dir: File? = ProductionSources.mainRoot()
        while (dir != null && !File(dir, "src/ios").isDirectory) dir = dir.parentFile
        requireNotNull(dir) { "repo root with src/ios not found" }
    }
    private val chatStore by lazy { File(repoRoot, "src/ios/Agent/Chat/ChatStore.swift").readText() }

    /**
     * Stored properties of a Swift struct, INCLUDING `var x: T = default`.
     * Computed properties (`{` on the line) are excluded.
     */
    private fun storedProps(header: String): List<SwiftProp> {
        val start = chatStore.indexOf(header)
        require(start >= 0) { "declaration not found: $header" }
        var depth = 0
        var i = chatStore.indexOf('{', start)
        val bodyStart = i
        while (i < chatStore.length) {
            when (chatStore[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) break }
            }
            i++
        }
        val body = chatStore.substring(bodyStart + 1, i)
        val prop = Regex("""^    (?:let|var) (\w+): ([^=/{\n]+?)\s*(?:=[^{]*)?(?://.*)?$""")
        return body.lines().mapNotNull { line ->
            if (line.contains("{")) return@mapNotNull null
            val m = prop.find(line) ?: return@mapNotNull null
            SwiftProp(m.groupValues[1], m.groupValues[2].trim().endsWith("?"))
        }
    }

    /** Keys a synthesized Decodable requires: every non-optional stored property. */
    private fun requiredKeys(header: String): Set<String> =
        storedProps(header).filterNot { it.optional }.map { it.name }.toSet()

    private fun hasCustomDecoder(typeName: String): Boolean =
        Regex("""extension $typeName\b""").containsMatchIn(chatStore) ||
            chatStore.substringAfter("struct $typeName:").substringBefore("\n}\n").contains("init(from decoder")

    private val v2Marker = CompactMarkerEntity(
        id = "C-2", sessionId = "S-1", summary = "sum", firstKeptSortOrder = Int.MAX_VALUE,
        compactedCount = 12, createdAt = 1_758_000_000_000, uiBoundarySortOrder = null,
        boundaryMessageId = null, firstKeptMessageId = null, lastCompactedMessageId = "M-12",
        version = 2,
    )

    private val message = MessageEntity(
        id = "M-1", sessionId = "S-1", role = "assistant",
        partsJson = """[{"type":"text","text":"hi"}]""",
        createdAt = 1_758_000_000_000, sortOrder = 3,
    )

    // -- parser sanity ---------------------------------------------------------

    @Test
    fun `the Swift declarations parse, including defaulted vars`() {
        val marker = storedProps("struct CompactMarker: ").map { it.name }
        assertTrue("CompactMarker.version must be seen: $marker", "version" in marker)
        val raw = storedProps("struct RawMessage: ").map { it.name }
        assertTrue("RawMessage.sortOrder must be seen: $raw", "sortOrder" in raw)
        assertTrue("RawMessage.streamInterruptCount must be seen: $raw", "streamInterruptCount" in raw)
        // The requirement below is only true for SYNTHESIZED Codable.
        assertTrue("CompactMarker grew a custom decoder; revisit this test", !hasCustomDecoder("CompactMarker"))
    }

    // -- iOS decode parity -------------------------------------------------------

    @Test
    fun `BUG - a compact marker carries every key iOS's synthesized Codable requires`() {
        val rec = BackupExporter.markerRecord(v2Marker).jsonObject
        val missing = requiredKeys("struct CompactMarker: ").filter { k -> rec[k] == null || rec[k] is JsonNull }
        assertTrue(
            "Android markerRecord omits required iOS CompactMarker keys $missing — every marker " +
                "line fails decode on iPhone and is dropped by readJSONL's try?",
            missing.isEmpty(),
        )
    }

    @Test
    fun `a message carries every key iOS's synthesized Codable requires`() {
        val rec = BackupExporter.messageRecord(message).jsonObject
        val missing = requiredKeys("struct RawMessage: ").filter { k -> rec[k] == null || rec[k] is JsonNull }
        assertTrue("messageRecord omits required iOS RawMessage keys $missing", missing.isEmpty())
    }

    @Test
    fun `message keys are camelCase Swift property names, never snake_case`() {
        val iosNames = storedProps("struct RawMessage: ").map { it.name }.toSet()
        val rec = BackupExporter.messageRecord(
            message.copy(modelId = "m", modelDisplayName = "M", providerType = "openAI", providerInstanceId = "P"),
        ).jsonObject
        val foreign = rec.keys.filter { it !in iosNames }
        assertTrue("keys iOS does not know: $foreign", foreign.isEmpty())
    }

    // -- Android round trip ----------------------------------------------------

    @Test
    fun `BUG - a v2 marker keeps version 2 through Android export then import`() {
        val back = BackupRecordMapper.compactMarker(BackupExporter.markerRecord(v2Marker).jsonObject)
        assertEquals(
            "v2 marker restored as v${back?.version} — it would resolve through the legacy " +
                "firstKeptSortOrder (=Int.MAX_VALUE) chain instead of lastCompactedMessageId",
            2, back?.version,
        )
    }

    @Test
    fun `BUG - an iOS v2 marker line restores as version 2 on Android`() {
        // What iOS's synthesized encoder writes for a v2 marker.
        val ios = Json.parseToJsonElement(
            """{"id":"C-9","sessionId":"S-1","summary":"s","firstKeptSortOrder":41,""" +
                """"compactedCount":40,"createdAt":"2026-09-20T10:00:00Z","uiBoundarySortOrder":41,""" +
                """"lastCompactedMessageId":"M-40","version":2}""",
        ).jsonObject
        assertEquals(2, ios["version"]?.jsonPrimitive?.intOrNull)
        assertEquals(2, BackupRecordMapper.compactMarker(ios)?.version)
    }

    @Test
    fun `a marker line with no version still restores as v1 (older packages)`() {
        val old = JsonObject(
            BackupExporter.markerRecord(v2Marker.copy(version = 1)).jsonObject.filterKeys { it != "version" },
        )
        assertEquals(1, BackupRecordMapper.compactMarker(old)?.version)
    }
}
