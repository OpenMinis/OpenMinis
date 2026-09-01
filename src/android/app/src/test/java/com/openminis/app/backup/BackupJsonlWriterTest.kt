package com.openminis.app.backup

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Shard rollover and the §2.2 rule-3 envelope.
 *
 * Sharding exists so the importer never has to hold one giant file in memory;
 * the naming is wire format, because the other platform enumerates shards by
 * name (`messages.jsonl`, then `messages-0002.jsonl`).
 */
class BackupJsonlWriterTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File.createTempFile("minisbak-jsonl", "").apply { delete(); mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun payload(id: String) = buildJsonObject { put("id", JsonPrimitive(id)) }

    @Test
    fun `every record is one line carrying its type and version`() {
        BackupJsonlWriter(dir, "messages").use { w ->
            w.write("message", 1, payload("m1"))
            w.write("message", 2, payload("m2"))
        }
        val lines = File(dir, "messages.jsonl").readLines().filter { it.isNotBlank() }
        assertEquals(2, lines.size)
        for (line in lines) {
            val obj = BackupFormat.json.parseToJsonElement(line)
            assertTrue("each line must be self-contained JSON", obj is kotlinx.serialization.json.JsonObject)
        }
        assertTrue(lines[0].contains("\"t\":\"message\""))
        assertTrue(lines[0].contains("\"v\":1"))
        assertTrue(lines[1].contains("\"v\":2"))
    }

    /**
     * The first shard is unsuffixed and later ones are `-0002`, `-0003`… —
     * zero-padded to four digits so lexical order matches numeric order, the
     * same reason the upload parts are `%06d`.
     */
    @Test
    fun `rolls over to a numbered shard once the cap is crossed`() {
        // A tiny cap so a handful of records forces several shards.
        val writer = BackupJsonlWriter(dir, "messages", maxShardBytes = 120)
        writer.use { w ->
            repeat(10) { w.write("message", 1, payload("message-id-$it")) }
        }
        val names = dir.list()!!.sorted()
        assertTrue("first shard must be unsuffixed", "messages.jsonl" in names)
        assertTrue("second shard must be zero-padded", "messages-0002.jsonl" in names)
        assertEquals(names.toList(), writer.shardPaths.sorted())

        // No record may be lost across the rollover.
        val total = names.sumOf { File(dir, it).readLines().count { l -> l.isNotBlank() } }
        assertEquals(10, total)
        assertEquals(10, writer.writtenRecords)
    }

    @Test
    fun `an unused writer reports empty and writes no file`() {
        val writer = BackupJsonlWriter(dir, "voice_corrections")
        writer.close()
        assertTrue(writer.isEmpty)
        assertTrue(
            "an empty category must not leave a stray file in the package",
            dir.list()?.isEmpty() ?: true,
        )
    }

    @Test
    fun `byte counters match what actually landed on disk`() {
        val writer = BackupJsonlWriter(dir, "sessions")
        writer.use { w -> repeat(5) { w.write("session", 1, payload("s$it")) } }
        val onDisk = writer.shardPaths.sumOf { File(dir, it).length() }
        assertEquals(onDisk, writer.totalBytes)
    }

    /** Non-ASCII must survive as UTF-8 — session titles are routinely CJK. */
    @Test
    fun `writes utf8 payloads intact`() {
        BackupJsonlWriter(dir, "sessions").use { w ->
            w.write("session", 1, buildJsonObject { put("title", JsonPrimitive("跨平台备份 — 会话")) })
        }
        val text = File(dir, "sessions.jsonl").readText(Charsets.UTF_8)
        assertTrue(text.contains("跨平台备份 — 会话"))
    }
}
