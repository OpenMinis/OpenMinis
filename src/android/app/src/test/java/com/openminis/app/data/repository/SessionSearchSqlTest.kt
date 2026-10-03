package com.openminis.app.data.repository

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * [T-android-search-visible-only] The session-search SQL, run for real against
 * SQLite (port of iOS SearchHitLineTests.swift [3]). Only visible fields may
 * make a session a result; each result carries its NEWEST visible hit.
 */
class SessionSearchSqlTest {

    private lateinit var db: Connection
    private var t0 = 1000L

    @Before fun open() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use {
            it.execute("CREATE TABLE sessions (id TEXT, title TEXT, model_id TEXT, created_at INTEGER, updated_at INTEGER)")
            it.execute("CREATE TABLE messages (session_id TEXT, sort_order INTEGER, parts_json TEXT)")
        }
        session("text", "Plain chat", listOf(listOf(text("hello")), listOf(text("switch to GPT-5 please")), listOf(text("ok"))))
        session("toolid", "iPad split view port", listOf(listOf(toolUse("call_x9GpTq2", """{"command":"ls"}""")), listOf(toolResult("call_x9GpTq2", "done"))))
        session("sig", "Backup plan review", listOf(listOf(toolUse("t1", """{"command":"pwd"}""", sig = "CiQB0e2Kbgpt7Hx"))))
        session("input", "Shell work", listOf(listOf(toolUse("t2", """{"command":"grep -r gpt-5 src"}"""))))
        session("output", "Model list", listOf(listOf(toolResult("t3", "gpt-5.1-codex\nclaude"))))
        session("title", "GPT pricing notes", listOf(listOf(text("nothing here"))))
        session("reminder", "Runtime", listOf(listOf(text("<system-reminder>model: gpt-5</system-reminder>"))))
        session("newest", "Two hits", listOf(listOf(text("old GPT line")), listOf(text("unrelated")), listOf(text("new GPT line")), listOf(text("tail"))))
    }

    @After fun close() = db.close()

    private fun text(v: String) = """{"type":"text","value":${JSONObject.quote(v)}}"""
    private fun toolUse(id: String, input: String, sig: String? = null): String {
        val o = JSONObject().put("toolUseId", id).put("name", "shell_execute").put("input", input)
        if (sig != null) o.put("thoughtSignature", sig)
        return """{"type":"toolUse","value":$o}"""
    }
    private fun toolResult(id: String, output: String) =
        """{"type":"toolResult","value":${JSONObject().put("toolUseId", id).put("output", output).put("success", true)}}"""

    private fun session(id: String, title: String, messages: List<List<String>>) {
        t0 += 1
        db.prepareStatement("INSERT INTO sessions VALUES (?, ?, 'm', ?, ?)").use {
            it.setString(1, id); it.setString(2, title); it.setLong(3, t0); it.setLong(4, t0); it.execute()
        }
        messages.forEachIndexed { i, parts ->
            db.prepareStatement("INSERT INTO messages VALUES (?, ?, ?)").use {
                it.setString(1, id); it.setInt(2, i); it.setString(3, "[" + parts.joinToString(",") + "]"); it.execute()
            }
        }
    }

    /** (session id, hit_parts_json) rows, in query order. */
    private fun run(sql: String, q: String): List<Pair<String, String?>> =
        db.prepareStatement(sql).use { st ->
            st.setString(1, "%$q%"); st.setString(2, q)
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getString("id") to rs.getString("hit_parts_json")) }
            }
        }

    @Test fun `old raw-JSON query reproduces the false positives`() {
        val legacy = run(SessionSearch.LEGACY_SEARCH_SQL, "GPT").map { it.first }.toSet()
        assertTrue("tool-call id session was a result", "toolid" in legacy)
        assertTrue("thought-signature session was a result", "sig" in legacy)
    }

    @Test fun `visible-field query keeps only real hits`() {
        val rows = run(SessionSearch.VISIBLE_SEARCH_SQL, "GPT")
        val ids = rows.map { it.first }.toSet()
        assertFalse("a hit only in a tool-call id is not a result", "toolid" in ids)
        assertFalse("a hit only in a thought signature is not a result", "sig" in ids)
        assertTrue(ids.containsAll(listOf("text", "input", "output", "title", "newest")))
        assertNull("a title-only result carries no message", rows.first { it.first == "title" }.second)
        assertTrue("the NEWEST visible hit is returned", rows.first { it.first == "newest" }.second!!.contains("new GPT line"))
        // A reminder hit passes SQL; searchSnippet finds no visible line and the repository drops it.
        assertTrue("reminder" in ids)
        assertNull(SessionSearch.searchSnippet(rows.first { it.first == "reminder" }.second!!, "GPT"))
        assertEquals("newest session first", "newest", rows.first().first)
    }

    @Test fun `LIKE is ASCII case-insensitive like the app`() {
        assertEquals(
            run(SessionSearch.VISIBLE_SEARCH_SQL, "GPT").map { it.first }.toSet(),
            run(SessionSearch.VISIBLE_SEARCH_SQL, "gpt").map { it.first }.toSet(),
        )
    }

    @Test fun `older-match look-back uses the same visible rule, newest first`() {
        val rows = db.prepareStatement(SessionSearch.olderMatchesSql(visibleOnly = true)).use { st ->
            st.setString(1, "%GPT%"); st.setString(2, "newest"); st.setString(3, "GPT")
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("parts_json")) } }
        }
        assertEquals(2, rows.size)
        assertTrue(rows[0].contains("new GPT line"))
        assertTrue(rows[1].contains("old GPT line"))
    }

    // ── [T-android-search-blob-cap] ────────────────────────────────────────

    @Test fun `an oversized hit row comes back as a window around the hit, not whole`() {
        val filler = "x".repeat(SessionSearch.MAX_HIT_JSON_CHARS)
        session("huge", "Big tool output", listOf(listOf(toolResult("t9", filler + "\nfound the NEEDLE here\n" + filler))))
        for (sql in listOf(SessionSearch.VISIBLE_SEARCH_SQL, SessionSearch.LEGACY_SEARCH_SQL)) {
            val hit = run(sql, "needle").single { it.first == "huge" }.second!!
            assertTrue(hit.startsWith(SessionSearch.WINDOW_MARK))
            assertTrue("capped: ${hit.length}", hit.length <= SessionSearch.WINDOW_MARK.length + SessionSearch.HIT_WINDOW_CHARS)
            assertTrue(hit.contains("NEEDLE"))
            // Not JSON any more: the marked window is read as its raw line.
            assertEquals(true, SessionSearch.searchSnippet(hit, "needle")?.contains("NEEDLE"))
        }
    }

    @Test fun `rows under the cap still come back whole`() {
        val rows = run(SessionSearch.VISIBLE_SEARCH_SQL, "GPT")
        val hit = rows.single { it.first == "newest" }.second!!
        assertEquals(org.json.JSONArray(hit).length(), 1)
    }

    @Test fun `the look-back pass caps oversized rows too`() {
        val filler = "y".repeat(SessionSearch.MAX_HIT_JSON_CHARS)
        session("huge2", "Log", listOf(listOf(text("GPT " + filler)), listOf(text("tail"))))
        val rows = db.prepareStatement(SessionSearch.olderMatchesSql(visibleOnly = true)).use { st ->
            st.setString(1, "%GPT%"); st.setString(2, "huge2"); st.setString(3, "GPT")
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("parts_json")) } }
        }
        assertEquals(1, rows.size)
        assertTrue(rows[0].startsWith(SessionSearch.WINDOW_MARK) && rows[0].contains("GPT"))
        assertTrue(rows[0].length <= SessionSearch.WINDOW_MARK.length + SessionSearch.HIT_WINDOW_CHARS)
    }
}
