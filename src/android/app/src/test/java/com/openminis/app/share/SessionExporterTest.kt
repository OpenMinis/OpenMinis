package com.openminis.app.share

import com.openminis.app.ProductionSources
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.MessageEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter

/**
 * [T-android-session-multi-export] The session list's multi-select Export
 * writes the same file iOS does (ContentView.streamExportAsJSON /
 * streamExportAsPlainText).
 */
class SessionExporterTest {

    private fun session(id: String, title: String?) =
        ChatSessionEntity(id = id, title = title, modelId = "model-x", createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_100_000L)

    private var order = 0
    private fun row(sid: String, role: String, parts: String, at: Long = 1_700_000_000_000L + order * 1000L) =
        MessageEntity(id = "m${order}", sessionId = sid, role = role, partsJson = parts, createdAt = at, sortOrder = order++)

    private fun text(v: String) = """{"type":"text","value":${JSONObject.quote(v)}}"""
    private fun toolUse(name: String, desc: String?) =
        """{"type":"toolUse","value":{"toolUseId":"t1","name":"$name","input":"{\"cmd\":\"ls\"}"${desc?.let { ",\"description\":\"$it\"" } ?: ""}}}"""
    private fun toolResult(output: String, ok: Boolean = true) =
        """{"type":"toolResult","value":{"toolUseId":"t1","output":${JSONObject.quote(output)},"success":$ok}}"""
    private fun media(mime: String) =
        """{"type":"mediaRef","value":{"id":"f","relativePath":"a","mimeType":"$mime"}}"""
    private fun parts(vararg p: String) = "[" + p.joinToString(",") + "]"

    /** s1: a greeting before the first user turn, a tool round trip, an image. s2: no user message at all. */
    private val rows: Map<String, List<MessageEntity>> by lazy {
        mapOf(
            "s1" to listOf(
                row("s1", "assistant", parts(text("Hi, I'm Minis"))),
                row("s1", "user", parts(text("list files"), media("image/png"))),
                row("s1", "assistant", parts(text("Running"), toolUse("shell", "List files"))),
                row("s1", "user", parts(toolResult("a.txt"))),
                row("s1", "assistant", parts(text("Done"), toolResult("x".repeat(2500), ok = false))),
            ),
            "s2" to listOf(row("s2", "assistant", parts(text("orphan greeting")))),
        )
    }

    private val loader = SessionExporter.PageLoader { sid, offset, limit ->
        rows[sid].orEmpty().drop(offset).take(limit)
    }

    @Test
    fun `json matches the iOS shape and skips what iOS skips`() = runBlocking {
        val out = StringWriter()
        val summary = SessionExporter.writeJson(listOf(session("s1", "Files"), session("s2", "Empty")), loader, out)
        val arr = JSONArray(out.toString())

        assertEquals("a session with no user message is skipped", 1, arr.length())
        val s = arr.getJSONObject(0)
        assertEquals("s1", s.getString("id"))
        assertEquals("model-x", s.getString("modelId"))
        assertEquals("Files", s.getString("title"))
        assertTrue("ISO-8601 dates", s.getString("createdAt").matches(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ""")))

        val msgs = s.getJSONArray("messages")
        // greeting before the first user turn and the tool-result-only row are skipped
        assertEquals(3, msgs.length())
        assertEquals("user", msgs.getJSONObject(0).getString("role"))
        val first = msgs.getJSONObject(0).getJSONArray("parts")
        assertEquals("text", first.getJSONObject(0).getString("type"))
        assertEquals("list files", first.getJSONObject(0).getString("text"))
        assertEquals("media", first.getJSONObject(1).getString("type"))

        val tool = msgs.getJSONObject(1).getJSONArray("parts").getJSONObject(1)
        assertEquals("tool_use", tool.getString("type"))
        assertEquals("shell", tool.getString("name"))
        assertEquals("List files", tool.getString("description"))

        val result = msgs.getJSONObject(2).getJSONArray("parts").getJSONObject(1)
        assertEquals("tool_result", result.getString("type"))
        assertFalse(result.getBoolean("success"))
        assertTrue("output capped at 2000 chars", result.getString("output").endsWith("\n…[truncated]"))
        assertEquals(2000 + "\n…[truncated]".length, result.getString("output").length)

        assertEquals(3, summary.totalMessages)
        assertEquals(1, summary.images)
    }

    @Test
    fun `plain text matches the iOS layout`() = runBlocking {
        val out = StringWriter()
        SessionExporter.writePlainText(listOf(session("s1", "Files"), session("s2", null)), loader, out)
        val txt = out.toString()

        assertTrue(txt.startsWith("# Files\nModel: model-x\nCreated: "))
        assertTrue(txt.contains("-".repeat(40) + "\n"))
        assertFalse("nothing before the first user turn", txt.contains("Hi, I'm Minis"))
        assertTrue(txt.contains("[User] "))
        assertTrue(txt.contains("list files\n[Attachment]\n---\n"))
        assertTrue(txt.contains("Running\n[Tool: List files]\n---\n"))
        assertTrue(txt.contains("Done\n[Result: failed]\n---\n"))
        assertFalse("a tool-result-only row is skipped", txt.contains("[Result: ok]"))
        // Sessions are separated by 60 '='; a session with no user message
        // still gets its header, as on iOS.
        assertTrue(txt.contains("\n\n" + "=".repeat(60) + "\n\n# Untitled\n"))
        assertFalse(txt.contains("orphan greeting"))
    }

    @Test
    fun `sessions longer than one page are read in full`() = runBlocking {
        order = 0
        val many = (0 until 130).map { i -> row("big", if (i % 2 == 0) "user" else "assistant", parts(text("m$i"))) }
        val pages = mutableListOf<Int>()
        val paging = SessionExporter.PageLoader { _, offset, limit ->
            pages.add(offset)
            many.drop(offset).take(limit)
        }
        val progress = mutableListOf<Pair<Int, Int>>()
        val summary = SessionExporter.writeJson(listOf(session("big", "Big")), paging, StringWriter(), total = 130) { d, t ->
            progress.add(d to t)
        }
        assertEquals(130, summary.totalMessages)
        assertTrue("paged in batches of ${SessionExporter.BATCH_SIZE}", pages.containsAll(listOf(0, 50, 100)))
        assertEquals(130 to 130, progress.last())
    }

    @Test
    fun `file name follows iOS`() {
        assertEquals("a-b-c", SessionExporter.baseName(listOf(session("1", "a/b:c"))))
        assertEquals(60, SessionExporter.baseName(listOf(session("1", "x".repeat(80)))).length)
        assertEquals("minis-sessions-2", SessionExporter.baseName(listOf(session("1", "A"), session("2", "B"))))
        assertEquals("minis-sessions-1", SessionExporter.baseName(listOf(session("1", null))))
    }

    @Test
    fun `the multi-select Export button is wired to the exporter`() {
        val src = ProductionSources.read("ui/sessions/SessionListScreen.kt")
        assertFalse("the TODO stub is gone", src.contains("onExport = { /* TODO: export */ }"))
        assertTrue(src.contains("com.openminis.app.share.SessionExporter.exportToZip("))
        assertTrue(src.contains("SessionExporter.Format.JSON"))
        assertTrue(src.contains("SessionExporter.Format.PLAIN_TEXT"))
    }
}
