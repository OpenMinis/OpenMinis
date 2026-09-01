package com.openminis.app.backup

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.BufferedOutputStream
import java.io.File

/**
 * Append-only JSONL writer with automatic shard rollover (§2), mirroring
 * `src/ios/Agent/Backup/BackupJSONLWriter.swift`.
 *
 * Every record is one self-contained line, so the importer can parse
 * line-by-line and skip a bad record without losing the file (§2.2 rule 3).
 * Writes go straight to a stream — nothing accumulates in memory, which is
 * what lets a 100k-message export stay flat.
 *
 * @param baseName `messages` → `messages.jsonl`, then `messages-0002.jsonl`.
 */
class BackupJsonlWriter(
    private val directory: File,
    private val baseName: String,
    private val maxShardBytes: Int = BackupFormat.MAX_SHARD_BYTES,
) : AutoCloseable {

    private var stream: BufferedOutputStream? = null
    private var currentBytes = 0
    private var shardIndex = 1

    var writtenRecords = 0
        private set
    var totalBytes = 0L
        private set
    val shardPaths = mutableListOf<String>()

    /** True when nothing was ever written, so the caller can avoid emitting an empty category. */
    val isEmpty: Boolean get() = writtenRecords == 0

    /**
     * Write one record wrapped in the §2.2 rule-3 envelope: `t` dispatches on
     * the way in, `v` allows per-record migration.
     */
    fun write(type: String, version: Int = 1, payload: JsonElement) {
        val envelope = buildJsonObject {
            put("t", JsonPrimitive(type))
            put("v", JsonPrimitive(version))
            put("d", payload)
        }
        val line = (BackupFormat.json.encodeToString(JsonObject.serializer(), envelope) + "\n")
            .toByteArray(Charsets.UTF_8)
        append(line)
        writtenRecords += 1
    }

    private fun append(data: ByteArray) {
        if (stream == null || currentBytes + data.size > maxShardBytes) rollover()
        val out = stream ?: throw BackupException("no open shard for $baseName")
        out.write(data)
        currentBytes += data.size
        totalBytes += data.size
    }

    private fun rollover() {
        close()
        val name = if (shardIndex == 1) "$baseName.jsonl"
        else String.format("%s-%04d.jsonl", baseName, shardIndex)
        directory.mkdirs()
        stream = BufferedOutputStream(File(directory, name).outputStream())
        currentBytes = 0
        shardIndex += 1
        shardPaths.add(name)
    }

    override fun close() {
        stream?.flush()
        stream?.close()
        stream = null
    }
}
