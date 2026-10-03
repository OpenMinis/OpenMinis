package com.openminis.app.data.repository

import com.openminis.app.ProductionSources
import com.openminis.app.data.db.ChatDao
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.db.MessageHeadRow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * [T-android-error-persist-current-turn] GH#263 (iOS fd7ec79a2 / 910708619).
 *
 * A terminal error must be persisted onto the CURRENT turn. The old write
 * stamped the session's newest assistant row, which — when the failing turn had
 * persisted nothing — was the PREVIOUS turn's good reply, so after a reload the
 * banner sat under an answer that had worked.
 *
 * [ChatRepository.persistTurnError] is exercised for real against an in-memory
 * fake of the [ChatDao] methods it uses (a dynamic proxy; any other DAO call
 * fails the test). The ViewModel wiring is asserted against source, since the
 * VM needs a Context/DB/provider to construct.
 */
class ErrorPersistCurrentTurnTest {

    // ── fixtures ──────────────────────────────────────────────────────────

    private val userText = """[{"type":"text","value":"hello"}]"""
    private val toolResult =
        """[{"type":"toolResult","value":{"toolUseId":"t1","name":"shell","output":"ok","success":true,""" +
            """"snapshot":{"type":"text","text":"ok"}}}]"""
    private val assistantText = """[{"type":"text","value":"reply"}]"""
    private val assistantTool =
        """[{"type":"toolUse","value":{"toolUseId":"t1","name":"shell","input":"{}"}}]"""

    private class FakeDao {
        val rows = mutableListOf<MessageEntity>()
        val errorWrites = mutableListOf<Pair<String, String?>>()
        var headReads = 0

        fun add(id: String, role: String, parts: String, errorInfo: String? = null) {
            rows += MessageEntity(
                id = id, sessionId = "s", role = role, partsJson = parts,
                createdAt = 0L, sortOrder = rows.size, errorInfo = errorInfo,
            )
        }

        fun row(id: String) = rows.first { it.id == id }

        val dao: ChatDao = Proxy.newProxyInstance(
            ChatDao::class.java.classLoader, arrayOf(ChatDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "messageHeadNewestFirst" -> {
                    headReads++
                    val offset = args[1] as Int
                    rows.sortedByDescending { it.sortOrder }.getOrNull(offset)
                        ?.let { MessageHeadRow(it.id, it.role, it.partsJson) }
                }
                "updateMessageErrorInfo" -> {
                    val id = args[0] as String
                    val err = args[1] as String?
                    errorWrites += id to err
                    val i = rows.indexOfFirst { it.id == id }
                    if (i >= 0) rows[i] = rows[i].copy(errorInfo = err)
                    Unit
                }
                "nextSortOrder" -> (rows.maxOfOrNull { it.sortOrder } ?: -1) + 1
                "insertMessage" -> { rows += args[0] as MessageEntity; Unit }
                "updateLastAssistantError" -> {
                    val err = args[1] as String?
                    val i = rows.indexOfLast { it.role == "assistant" }
                    if (i >= 0) rows[i] = rows[i].copy(errorInfo = err)
                    Unit
                }
                "toString" -> "FakeDao"
                "hashCode" -> 0
                "equals" -> false
                else -> throw AssertionError("unexpected DAO call ${method.name}")
            }
        } as ChatDao
    }

    // ── the bug: early failure on turn 2 ─────────────────────────────────

    @Test
    fun `an early failure on turn 2 does NOT stamp the previous reply - it gets a carrier`() = runBlocking {
        val f = FakeDao()
        f.add("u1", "user", userText)
        f.add("a1", "assistant", assistantText) // turn 1's good reply
        f.add("u2", "user", userText)           // turn 2 failed before any output
        val write = ChatRepository(f.dao).persistTurnError("s", "HTTP 529 overloaded")

        assertNull("the previous turn's reply must stay clean", f.row("a1").errorInfo)
        assertTrue("expected a carrier, got $write", write is ChatRepository.TurnErrorWrite.Carrier)
        val carrier = f.rows.last()
        assertEquals((write as ChatRepository.TurnErrorWrite.Carrier).messageId, carrier.id)
        assertEquals("assistant", carrier.role)
        assertEquals("[]", carrier.partsJson)
        assertEquals("HTTP 529 overloaded", carrier.errorInfo)
        assertTrue("carrier sits after the turn's user row", carrier.sortOrder > f.row("u2").sortOrder)
        assertEquals("only the newest row needed reading", 1, f.headReads)
    }

    @Test
    fun `a first-turn failure also gets a carrier (was a silent no-op before)`() = runBlocking {
        val f = FakeDao()
        f.add("u1", "user", userText)
        val write = ChatRepository(f.dao).persistTurnError("s", "boom")
        assertTrue(write is ChatRepository.TurnErrorWrite.Carrier)
        assertEquals("boom", f.rows.last().errorInfo)
    }

    @Test
    fun `a failure after tool rounds stamps THIS turn's newest assistant row`() = runBlocking {
        val f = FakeDao()
        f.add("u1", "user", userText)
        f.add("a1", "assistant", assistantText)
        f.add("u2", "user", userText)
        f.add("a2", "assistant", assistantTool)
        f.add("r2", "user", toolResult) // tool-result row does not start a turn
        val write = ChatRepository(f.dao).persistTurnError("s", "boom")
        assertEquals(ChatRepository.TurnErrorWrite.Stamped("a2"), write)
        assertEquals("boom", f.row("a2").errorInfo)
        assertNull(f.row("a1").errorInfo)
        assertEquals("no carrier inserted", 5, f.rows.size)
    }

    @Test
    fun `a mid-reply failure stamps the reply the turn persisted`() = runBlocking {
        val f = FakeDao()
        f.add("u1", "user", userText)
        f.add("a1", "assistant", assistantText)
        val write = ChatRepository(f.dao).persistTurnError("s", "stream dropped")
        assertEquals(ChatRepository.TurnErrorWrite.Stamped("a1"), write)
    }

    @Test
    fun `a second failure of the same turn re-stamps the carrier instead of adding another`() = runBlocking {
        val f = FakeDao()
        f.add("u1", "user", userText)
        f.add("a1", "assistant", assistantText)
        f.add("u2", "user", userText)
        val repo = ChatRepository(f.dao)
        val first = repo.persistTurnError("s", "first") as ChatRepository.TurnErrorWrite.Carrier
        val second = repo.persistTurnError("s", "second")
        assertEquals(ChatRepository.TurnErrorWrite.Stamped(first.messageId), second)
        assertEquals(1, f.rows.count { ChatRepository.isEmptyAssistantCarrier(it.role, it.partsJson) })
        assertEquals("second", f.rows.last().errorInfo)
    }

    @Test
    fun `retry's clear path finds the carrier, and a successful retry leaves it hidden`() = runBlocking {
        val f = FakeDao()
        f.add("u1", "user", userText)
        f.add("a1", "assistant", assistantText)
        f.add("u2", "user", userText)
        val repo = ChatRepository(f.dao)
        val carrier = repo.persistTurnError("s", "boom") as ChatRepository.TurnErrorWrite.Carrier
        // retryLast → clearPersistedLastAssistantError → updateLastAssistantError(sid, null)
        repo.updateLastAssistantError("s", null)
        assertNull("the clear must land on the carrier", f.row(carrier.messageId).errorInfo)
        assertNull(f.row("a1").errorInfo)
        // The retried turn's reply lands after it; a later failure of a NEW
        // turn must still skip both of these.
        f.add("a2", "assistant", assistantText)
        f.add("u3", "user", userText)
        assertTrue(repo.persistTurnError("s", "again") is ChatRepository.TurnErrorWrite.Carrier)
        assertNull(f.row("a2").errorInfo)
    }

    @Test
    fun `an empty session writes nothing`() = runBlocking {
        val f = FakeDao()
        val write = ChatRepository(f.dao).persistTurnError("s", "boom")
        assertTrue(write is ChatRepository.TurnErrorWrite.Skipped)
        assertTrue(f.rows.isEmpty())
    }

    // ── the pure classification ──────────────────────────────────────────

    @Test
    fun `turn-start classification matches iOS currentTurnStartIndex`() {
        assertTrue(ChatRepository.isTurnStartingUserParts(userText))
        assertFalse("tool results continue a turn", ChatRepository.isTurnStartingUserParts(toolResult))
        // The snapshot's nested "type":"text" is inside a toolResult value, not a part.
        assertFalse(ChatRepository.isTurnStartingUserParts(toolResult))
        assertTrue("any non-tool-result part starts a turn",
            ChatRepository.isTurnStartingUserParts(
                "[" + toolResult.removePrefix("[").removeSuffix("]") + """,{"type":"text","value":"x"}]"""))
        assertTrue("image-only user turn starts a turn",
            ChatRepository.isTurnStartingUserParts("""[{"type":"mediaRef","value":{}}]"""))
        assertFalse("empty parts: nothing a user typed (iOS parity)", ChatRepository.isTurnStartingUserParts("[]"))
        assertTrue("unparseable counts as a turn start — never walk past it onto the previous reply",
            ChatRepository.isTurnStartingUserParts("not json"))
    }

    @Test
    fun `pure walk picks the same row as the repository`() {
        fun h(id: String, role: String, parts: String) = MessageHeadRow(id, role, parts)
        assertNull(ChatRepository.currentTurnErrorRowId(listOf(h("u2", "user", userText), h("a1", "assistant", assistantText))))
        assertEquals("a2", ChatRepository.currentTurnErrorRowId(
            listOf(h("r2", "user", toolResult), h("a2", "assistant", assistantTool), h("u2", "user", userText))))
        assertNull(ChatRepository.currentTurnErrorRowId(emptyList()))
    }

    @Test
    fun `only an empty assistant row is a carrier`() {
        assertTrue(ChatRepository.isEmptyAssistantCarrier("assistant", "[]"))
        assertTrue(ChatRepository.isEmptyAssistantCarrier("assistant", " [] "))
        assertFalse(ChatRepository.isEmptyAssistantCarrier("assistant", assistantText))
        assertFalse(ChatRepository.isEmptyAssistantCarrier("user", "[]"))
    }

    // ── ViewModel wiring (source facts) ──────────────────────────────────

    private val vm by lazy { ProductionSources.read("ui/chat/ChatViewModel.kt") }

    @Test
    fun `setInlineError writes through persistTurnError, never the last-assistant UPDATE`() {
        val body = vm.substringAfter("private fun setInlineError(errorText: String) {")
            .substringBefore("private fun setTransientInlineError(")
        assertTrue(body.contains("chatRepository.persistTurnError(sid, safeError)"))
        assertFalse("a WRITE through updateLastAssistantError is the GH#263 bug",
            body.contains("updateLastAssistantError(sid, safeError)"))
        assertFalse("no non-null write anywhere", Regex("updateLastAssistantError\\(sid, (?!null)").containsMatchIn(vm))
    }

    @Test
    fun `the load path shows an error-only carrier and hides a cleared one`() {
        assertTrue(vm.contains(
            "if (entity.role == \"assistant\" && text.isBlank() && blocks.isEmpty() &&\n" +
                "                entity.errorInfo.isNullOrBlank()\n" +
                "            ) return@mapNotNull null"))
    }

    @Test
    fun `every agentHistory rebuild from rows skips carriers`() {
        val rebuilds = Regex("\\.add\\(entity\\.toLLMMessage\\(\\)\\)").findAll(vm).count()
        val guards = Regex("if \\(ChatRepository\\.isEmptyAssistantCarrier\\(entity\\.role, entity\\.partsJson\\)\\) continue")
            .findAll(vm).count()
        assertTrue("expected rebuild sites", rebuilds >= 5)
        assertEquals("each toLLMMessage rebuild needs its carrier guard", rebuilds, guards)
    }
}
