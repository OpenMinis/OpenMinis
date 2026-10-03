package com.openminis.app.backup

import com.openminis.app.backup.BackupRecordMapper.Decoded
import com.openminis.app.backup.BackupRecordMapper.millis
import com.openminis.app.backup.BackupRecordMapper.str
import com.openminis.app.backup.BackupRecordMapper.unwrapNested
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.db.FolderEntity
import com.openminis.app.data.db.MessageEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The record <-> entity mapping and merge rules behind a restore, exercised
 * through the REAL exporter builders and the REAL importer mapping — the
 * error-prone part of backup: a wrong default or merge comparison silently
 * drops or overwrites user data, and the restore still reports success.
 */
class BackupRecordMapperTest {

    private fun json(text: String): JsonObject = BackupFormat.json.parseToJsonElement(text).jsonObject

    // Whole seconds: the wire format is second-precision ISO-8601 (see the
    // truncation test below), so fixtures that round-trip exactly use them.
    private val t0 = 1_758_000_000_000L
    private fun at(sec: Long) = t0 + sec * 1000

    private val session = ChatSessionEntity(
        id = "S-1", title = "Trip", modelId = "claude-sonnet-5",
        createdAt = at(0), updatedAt = at(60), category = "travel",
        lastMessage = "ok", modelBinding = "group:g1", source = "share",
        memoryEnabled = 0, pinnedAt = at(30), editCount = 4,
        thinkingOverride = "high", folderId = "F-1",
        parentSessionId = "P-1", parentToolUseId = "toolu_1",
    )

    private fun sessionOut(e: ChatSessionEntity = session) =
        BackupExporter.sessionRecord(e).jsonObject

    private fun applied(d: Decoded<ChatSessionEntity>): ChatSessionEntity {
        assertTrue("expected Apply, got $d", d is Decoded.Apply)
        return (d as Decoded.Apply).entity
    }

    // -- Sessions: round trip ---------------------------------------------------

    @Test
    fun `a session survives export then import field for field`() {
        assertEquals(session, applied(BackupRecordMapper.session(sessionOut(), existing = null)))
    }

    @Test
    fun `a legacy flat Android session (1_13 and older) imports identically`() {
        val nested = sessionOut()
        val flat = JsonObject(nested["session"]!!.jsonObject + nested.filterKeys { it != "session" })
        assertEquals(session, applied(BackupRecordMapper.session(flat, null)))
    }

    @Test
    fun `an iPhone session record imports, with Android-only fields defaulted`() {
        // Shape of a real iOS line: nested, no lastMessage / editCount / thinkingOverride.
        val ios = json(
            """{"memoryEnabled":false,"modelBinding":"group:g1","session":{
              "id":"S-2","title":"From iPhone","modelId":"gpt-6-sol",
              "createdAt":"2025-09-16T05:20:00Z","updatedAt":"2025-09-16T05:21:00Z",
              "folderId":"F-9","pinnedAt":null,"lastSyncedAt":"2025-09-16T05:22:00Z",
              "remoteDeviceId":null}}"""
        )
        val e = applied(BackupRecordMapper.session(ios, null))
        assertEquals("S-2", e.id)
        assertEquals("F-9", e.folderId)
        assertEquals("the wrapper's memoryEnabled applies", 0, e.memoryEnabled)
        assertEquals("group:g1", e.modelBinding)
        assertEquals(0, e.editCount)
        assertNull(e.thinkingOverride)
        assertNull("iOS does not send it; the importer rebuilds it from messages", e.lastMessage)
    }

    @Test
    fun `inner fields win over wrapper fields of the same name`() {
        val rec = json("""{"title":"outer","session":{"id":"S","title":"inner","updatedAt":"1"}}""")
        assertEquals("inner", applied(BackupRecordMapper.session(rec, null)).title)
    }

    // -- Sessions: defaults -------------------------------------------------------

    @Test
    fun `memory stays ON unless the record explicitly says false`() {
        fun mem(v: String?) = applied(
            BackupRecordMapper.session(
                json("""{"id":"S","updatedAt":"1"${v?.let { ""","memoryEnabled":$it""" } ?: ""}}"""), null,
            ),
        ).memoryEnabled
        assertEquals("absent -> on", 1, mem(null))
        assertEquals("null -> on", 1, mem("null"))
        assertEquals(1, mem("true"))
        assertEquals(0, mem("false"))
    }

    @Test
    fun `a missing modelId keeps the local one rather than blanking it`() {
        val local = session.copy(updatedAt = at(10))
        val rec = json("""{"id":"S-1","updatedAt":"${BackupExporter.iso8601(at(20))}"}""")
        assertEquals("claude-sonnet-5", applied(BackupRecordMapper.session(rec, local)).modelId)
        assertEquals("", applied(BackupRecordMapper.session(rec, null)).modelId)
    }

    @Test
    fun `a missing createdAt falls back to updatedAt`() {
        val rec = json("""{"id":"S","updatedAt":"${BackupExporter.iso8601(at(5))}"}""")
        assertEquals(at(5), applied(BackupRecordMapper.session(rec, null)).createdAt)
    }

    @Test
    fun `a record with no id anywhere is unreadable`() {
        assertEquals(Decoded.Unreadable, BackupRecordMapper.session(json("""{"session":{"title":"x"}}"""), null))
        assertEquals(Decoded.Unreadable, BackupRecordMapper.session(json("""{"id":null}"""), null))
    }

    // -- Merge ------------------------------------------------------------------

    @Test
    fun `merge - newer backup wins, equal or older keeps the local row`() {
        val rec = sessionOut() // updatedAt = at(60)
        assertEquals(Decoded.Stale("S-1"), BackupRecordMapper.session(rec, session.copy(updatedAt = at(60))))
        assertEquals(Decoded.Stale("S-1"), BackupRecordMapper.session(rec, session.copy(updatedAt = at(61))))
        val newer = BackupRecordMapper.session(rec, session.copy(updatedAt = at(59)))
        assertTrue(newer is Decoded.Apply && !newer.isNew)
        val fresh = BackupRecordMapper.session(rec, null)
        assertTrue(fresh is Decoded.Apply && fresh.isNew)
    }

    @Test
    fun `re-running the same restore changes nothing`() {
        val first = applied(BackupRecordMapper.session(sessionOut(), null))
        assertEquals(Decoded.Stale("S-1"), BackupRecordMapper.session(sessionOut(), first))
    }

    @Test
    fun `a record without updatedAt never overwrites an existing row`() {
        val rec = json("""{"id":"S-1","title":"stamp-less"}""")
        assertEquals(Decoded.Stale("S-1"), BackupRecordMapper.session(rec, session))
    }

    @Test
    fun `sub-second local edits are not clobbered by the same state exported to whole seconds`() {
        // The wire truncates to seconds, so a backup of the CURRENT state reads
        // as slightly older than the local row — and must lose, not win.
        val local = session.copy(updatedAt = at(60) + 750)
        assertEquals(Decoded.Stale("S-1"), BackupRecordMapper.session(sessionOut(local), local))
    }

    @Test
    fun `incomingWins is strict`() {
        assertTrue(BackupRecordMapper.incomingWins(null, 0))
        assertTrue(BackupRecordMapper.incomingWins(10, 11))
        assertTrue(!BackupRecordMapper.incomingWins(10, 10))
        assertTrue(!BackupRecordMapper.incomingWins(10, 9))
    }

    // -- Folders ----------------------------------------------------------------

    private val folder = FolderEntity(
        id = "F-1", name = "Work", icon = "briefcase", color = "blue",
        origin = FolderEntity.ORIGIN_AI, sortIndex = 3, pinnedAt = at(1),
        description = "Office stuff", createdAt = at(0), updatedAt = at(2),
    )

    @Test
    fun `a folder survives export then import field for field`() {
        val d = BackupRecordMapper.folder(BackupExporter.folderRecord(folder).jsonObject, null)
        assertEquals(folder, (d as Decoded.Apply).entity)
    }

    @Test
    fun `an iPhone folder keeps its description, sent as desc`() {
        val ios = json(
            """{"id":"F-2","name":"Home","origin":"manual","sortIndex":0,"desc":"Family",
              "createdAt":"2025-09-16T05:20:00Z","updatedAt":"2025-09-16T05:21:00Z"}"""
        )
        assertEquals("Family", (BackupRecordMapper.folder(ios, null) as Decoded.Apply).entity.description)
    }

    @Test
    fun `folder defaults - manual origin, empty name, index 0`() {
        val e = (BackupRecordMapper.folder(json("""{"id":"F","updatedAt":"1"}"""), null) as Decoded.Apply).entity
        assertEquals(FolderEntity.ORIGIN_MANUAL, e.origin)
        assertEquals("", e.name)
        assertEquals(0, e.sortIndex)
    }

    @Test
    fun `an older backup does not undo a local folder rename`() {
        val rec = BackupExporter.folderRecord(folder).jsonObject
        assertEquals(Decoded.Stale("F-1"), BackupRecordMapper.folder(rec, folder.copy(name = "Renamed", updatedAt = at(9))))
    }

    // -- Messages -----------------------------------------------------------------

    private val message = MessageEntity(
        id = "M-1", sessionId = "S-1", role = "assistant",
        partsJson = """[{"type":"text","text":"hi"},{"type":"future_part","blob":{"a":[1,2]}}]""",
        createdAt = at(3), tokenUsage = """{"input":10,"output":5}""", sortOrder = 7,
        reasoningContent = "thought", streamInterruptCount = 1, updatedAt = at(3),
        errorInfo = null, modelId = "gpt-6-sol", modelDisplayName = "GPT-6 Sol",
        providerType = "openai", providerInstanceId = "inst-1",
    )

    private fun parsed(jsonText: String) = BackupFormat.json.parseToJsonElement(jsonText)

    @Test
    fun `a message survives export then import, unknown part types included`() {
        val back = BackupRecordMapper.message(BackupExporter.messageRecord(message).jsonObject)!!
        assertEquals(message.copy(partsJson = back.partsJson, tokenUsage = back.tokenUsage), back)
        assertEquals("parts compare as JSON, unknown type kept", parsed(message.partsJson), parsed(back.partsJson))
        assertEquals(parsed(message.tokenUsage!!), parsed(back.tokenUsage!!))
    }

    @Test
    fun `a device-local error never travels`() {
        val withError = message.copy(errorInfo = """{"code":429}""")
        val rec = BackupExporter.messageRecord(withError).jsonObject
        assertTrue("not exported", !rec.containsKey("errorInfo"))
        val injected = JsonObject(rec + ("errorInfo" to parsed("\"boom\"")))
        assertNull("not imported even if present", BackupRecordMapper.message(injected)!!.errorInfo)
    }

    @Test
    fun `message defaults - user role, empty parts, no usage, no attribution`() {
        val m = BackupRecordMapper.message(json("""{"id":"M","sessionId":"S","tokenUsage":null}"""))!!
        assertEquals("user", m.role)
        assertEquals("[]", m.partsJson)
        assertNull(m.tokenUsage)
        assertEquals(0, m.sortOrder)
        assertNull("absent attribution = the Usage page's estimated state", m.modelId)
        assertNull(m.providerInstanceId)
    }

    @Test
    fun `a message without id or sessionId cannot be placed`() {
        assertNull(BackupRecordMapper.message(json("""{"sessionId":"S"}""")))
        assertNull(BackupRecordMapper.message(json("""{"id":"M"}""")))
    }

    @Test
    fun `malformed stored parts still export as a valid empty array`() {
        val rec = BackupExporter.messageRecord(message.copy(partsJson = "{not json")).jsonObject
        assertEquals("[]", rec["parts"].toString())
    }

    // -- Compact markers --------------------------------------------------------

    @Test
    fun `a compact marker survives export then import field for field`() {
        val marker = CompactMarkerEntity(
            id = "C-1", sessionId = "S-1", summary = "sum", firstKeptSortOrder = 40,
            compactedCount = 39, createdAt = at(4), uiBoundarySortOrder = 38,
            boundaryMessageId = "M-38", firstKeptMessageId = "M-40", lastCompactedMessageId = "M-39",
        )
        assertEquals(marker, BackupRecordMapper.compactMarker(BackupExporter.markerRecord(marker).jsonObject))
        assertNull(BackupRecordMapper.compactMarker(json("""{"id":"C"}""")))
    }

    // -- Value parsing -----------------------------------------------------------

    @Test
    fun `timestamps - every accepted spelling lands on the same instant`() {
        val ms = at(0)
        for (raw in listOf(
            "\"${BackupExporter.iso8601(ms)}\"",
            "\"2025-09-16T13:20:00+08:00\"",
            "\"$ms\"",
            "$ms",
        )) {
            assertEquals(raw, ms, json("""{"t":$raw}""").millis("t"))
        }
    }

    @Test
    fun `timestamps - fractional seconds keep their milliseconds`() {
        assertEquals(at(0) + 456, json("""{"t":"2025-09-16T05:20:00.456Z"}""").millis("t"))
        assertEquals(at(0) + 456, json("""{"t":"2025-09-16T13:20:00.456+08:00"}""").millis("t"))
    }

    @Test
    fun `timestamps - absent, null and garbage are null, never epoch 0`() {
        assertNull(json("""{}""").millis("t"))
        assertNull(json("""{"t":null}""").millis("t"))
        assertNull(json("""{"t":"yesterday"}""").millis("t"))
    }

    @Test
    fun `whole-second export truncates sub-second precision`() {
        assertEquals("2025-09-16T05:20:00Z", BackupExporter.iso8601(at(0) + 999))
    }

    @Test
    fun `str treats JSON null as absent and refuses objects`() {
        val o = json("""{"a":null,"b":"null","c":3,"d":{"x":1}}""")
        assertNull(o.str("a"))
        assertEquals("the string \"null\" is a real value", "null", o.str("b"))
        assertEquals("3", o.str("c"))
        assertNull(o.str("d"))
    }

    @Test
    fun `unwrapNested leaves a flat record untouched`() {
        val flat = json("""{"id":"S","session":"not-an-object"}""")
        assertEquals(flat, flat.unwrapNested("session"))
    }
}
