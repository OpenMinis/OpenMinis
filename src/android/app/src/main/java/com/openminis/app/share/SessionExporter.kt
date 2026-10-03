package com.openminis.app.share

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * [T-android-session-multi-export] Exports several sessions into ONE file, the
 * way the session list's multi-select "Export" does on iOS
 * (ContentView.exportSessions / streamExportAsJSON / streamExportAsPlainText).
 *
 * Same output as iOS, so an export reads the same whichever device made it:
 *  - JSON: an array of sessions, each `{id, modelId, createdAt, updatedAt,
 *    title?, category?, messages: [...]}` with ISO-8601 dates; a message is
 *    `{role, parts, createdAt, tokenUsage?, reasoning?}` and its parts are
 *    cleaned (`text`, `tool_use{name, description?, input?}`,
 *    `tool_result{tool, success, output?}` with output capped at 2000 chars,
 *    `media` as a note).
 *  - Plain text: per session a `# title / Model / Created` header, then
 *    `[User]|[Minis] HH:mm` blocks separated by `---`; sessions separated by a
 *    line of 60 `=`.
 *  - Both start each session at its first user message, skip rows that are
 *    only tool results, and skip a session with no user message at all.
 *  - The payload is zipped (a single entry); if zipping fails the raw
 *    payload is shared instead, as iOS falls back.
 *  - File name: the session title (60 chars, `/` and `:` replaced) for one
 *    session, `minis-sessions-<n>` for several.
 *
 * Not ported: iOS writes a tool result's `snapshot` text/duration; Android's
 * ToolResult has no snapshot.
 *
 * Messages are read in pages of [BATCH_SIZE] and written as they arrive, so
 * memory stays bounded however long the sessions are (the T-export-optimize
 * contract of [ChatExporter]).
 */
object SessionExporter {

    const val BATCH_SIZE = 50
    private const val TOOL_OUTPUT_MAX = 2000
    private const val LOG_CATEGORY = "SessionExporter"

    enum class Format(val ext: String) { JSON("json"), PLAIN_TEXT("txt") }

    /** iOS ExportSummary, plus the image/video split Android's summary strings show. */
    data class Summary(
        val totalMessages: Int = 0,
        val images: Int = 0,
        val videos: Int = 0,
        val otherAttachments: Int = 0,
        val earliest: Long? = null,
        val latest: Long? = null,
    ) {
        val attachmentCount: Int get() = images + videos + otherAttachments
    }

    data class Result(
        val uri: Uri,
        val summary: Summary,
        val format: Format,
        val sessionCount: Int,
        val fileSizeBytes: Long,
        val fileName: String,
    )

    /** One page of a session's rows, oldest first; empty when exhausted. */
    fun interface PageLoader {
        suspend fun load(sessionId: String, offset: Int, limit: Int): List<MessageEntity>
    }

    /** iOS: the session title for one session, `minis-sessions-<n>` for several. */
    fun baseName(sessions: List<ChatSessionEntity>): String {
        val single = sessions.singleOrNull()
        if (single != null) {
            val title = single.title?.take(60)?.replace("/", "-")?.replace(":", "-")
            if (!title.isNullOrBlank()) return title
        }
        return "minis-sessions-${sessions.size}"
    }

    suspend fun exportToZip(
        context: Context,
        sessions: List<ChatSessionEntity>,
        repository: ChatRepository,
        format: Format,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result = withContext(Dispatchers.IO) {
        require(sessions.isNotEmpty()) { "nothing to export" }
        val workRoot = File(File(context.cacheDir, "export-staging"), "multi-${UUID.randomUUID()}")
        val payloadDir = File(workRoot, "payload")
        if (!payloadDir.mkdirs() && !payloadDir.isDirectory) {
            throw IllegalStateException("export-staging mkdir failed: ${payloadDir.absolutePath}")
        }
        try {
            val base = baseName(sessions)
            val payloadName = "$base.${format.ext}"
            val payload = File(payloadDir, payloadName)
            val loader = PageLoader { sid, offset, limit -> repository.loadMessagePageRaw(sid, offset, limit) }
            // Total up front (a count query per session), so progress reads
            // "n / total" rather than growing with every page as on iOS.
            val total = sessions.sumOf { repository.messageCount(it.id) }
            onProgress(0, total)
            val summary = BufferedWriter(OutputStreamWriter(FileOutputStream(payload), Charsets.UTF_8)).use { w ->
                when (format) {
                    Format.JSON -> writeJson(sessions, loader, w, total, onProgress)
                    Format.PLAIN_TEXT -> writePlainText(sessions, loader, w, total, onProgress)
                }
            }

            // Hand out from cacheDir/shared (declared in file_provider_paths).
            val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
            val zip = File(sharedDir, "$base.zip")
            val out = try {
                if (zip.exists()) zip.delete()
                ZipOutputStream(FileOutputStream(zip).buffered()).use { zos ->
                    zos.putNextEntry(ZipEntry(payloadName))
                    FileInputStream(payload).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
                zip
            } catch (t: Throwable) {
                // iOS falls back to the raw payload when zipping fails.
                AppLogger.warning(LOG_CATEGORY, "zip failed, sharing the raw payload: ${t.message}")
                runCatching { zip.delete() }
                File(sharedDir, payloadName).also { raw ->
                    if (raw.exists()) raw.delete()
                    payload.copyTo(raw)
                }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
            AppLogger.info(
                LOG_CATEGORY,
                "export ok: ${out.name} sessions=${sessions.size} messages=${summary.totalMessages} bytes=${out.length()}",
            )
            Result(uri, summary, format, sessions.size, out.length(), out.name)
        } finally {
            runCatching { workRoot.deleteRecursively() }
        }
    }

    // ── Writers (pure: a loader in, text out; unit-tested) ───────────────────

    /** Rows of [sessionId] from its first user message on, page by page. */
    private suspend fun forEachRowFromFirstUser(
        sessionId: String,
        loader: PageLoader,
        block: suspend (MessageEntity) -> Unit,
    ) {
        var offset = 0
        var started = false
        while (true) {
            val page = loader.load(sessionId, offset, BATCH_SIZE)
            if (page.isEmpty()) break
            for (row in page) {
                if (!started && row.role != "user") continue
                started = true
                block(row)
            }
            offset += page.size
            if (page.size < BATCH_SIZE) break
        }
    }

    /** Whether a session has a user message at all (iOS skips it otherwise). */
    private suspend fun hasUserMessage(sessionId: String, loader: PageLoader): Boolean {
        var offset = 0
        while (true) {
            val page = loader.load(sessionId, offset, BATCH_SIZE)
            if (page.any { it.role == "user" }) return true
            if (page.size < BATCH_SIZE) return false
            offset += page.size
        }
    }

    suspend fun writeJson(
        sessions: List<ChatSessionEntity>,
        loader: PageLoader,
        out: Writer,
        total: Int = 0,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Summary {
        var summary = Summary()
        var done = 0
        out.write("[\n")
        var firstSession = true
        for (session in sessions) {
            if (!hasUserMessage(session.id, loader)) continue
            if (!firstSession) out.write(",\n")
            firstSession = false
            val header = JSONObject().apply {
                put("id", session.id)
                put("modelId", session.modelId)
                put("createdAt", iso(session.createdAt))
                put("updatedAt", iso(session.updatedAt))
                session.title?.let { put("title", it) }
                session.category?.let { put("category", it) }
            }
            // Open the session object, then stream its messages array.
            out.write(header.toString(2).trimEnd().removeSuffix("}"))
            out.write(",\n  \"messages\": [\n")
            var firstMsg = true
            var sinceProgress = 0
            forEachRowFromFirstUser(session.id, loader) { row ->
                done += 1
                val parts = parseParts(row.partsJson)
                if (isToolResultOnly(parts)) return@forEachRowFromFirstUser
                val clean = JSONArray()
                for (p in parts) {
                    val v = p.optJSONObject("value")
                    when (p.optString("type")) {
                        "text" -> clean.put(JSONObject().put("type", "text").put("text", p.optString("value")))
                        "toolUse" -> clean.put(
                            JSONObject().put("type", "tool_use").put("name", v?.optString("name").orEmpty()).apply {
                                v?.optString("description")?.takeIf { it.isNotEmpty() }?.let { put("description", it) }
                                v?.optString("input")?.takeIf { it.isNotEmpty() }?.let { put("input", it) }
                            },
                        )
                        "toolResult" -> clean.put(
                            JSONObject().put("type", "tool_result")
                                .put("tool", v?.optString("toolUseId").orEmpty())
                                .put("success", v?.optBoolean("success", true) ?: true)
                                .apply {
                                    val output = v?.optString("output").orEmpty()
                                    if (output.isNotEmpty()) {
                                        put(
                                            "output",
                                            if (output.length > TOOL_OUTPUT_MAX) output.take(TOOL_OUTPUT_MAX) + "\n…[truncated]" else output,
                                        )
                                    }
                                },
                        )
                        "mediaRef" -> {
                            clean.put(JSONObject().put("type", "media").put("note", "image/file attachment"))
                            summary = summary.countMedia(v?.optString("mimeType").orEmpty())
                        }
                    }
                }
                if (clean.length() == 0) return@forEachRowFromFirstUser
                val msg = JSONObject().apply {
                    put("role", row.role)
                    put("parts", clean)
                    put("createdAt", iso(row.createdAt))
                    row.tokenUsage?.let { raw -> runCatching { JSONObject(raw) }.getOrNull()?.let { put("tokenUsage", it) } }
                    row.reasoningContent?.takeIf { it.isNotEmpty() }?.let { put("reasoning", it) }
                }
                if (!firstMsg) out.write(",\n")
                firstMsg = false
                out.write(msg.toString(2))
                summary = summary.countMessage(row.createdAt)
                if (++sinceProgress >= BATCH_SIZE) {
                    sinceProgress = 0
                    out.flush()
                    onProgress(done, maxOf(total, done))
                }
            }
            out.write("\n  ]\n}")
            onProgress(done, maxOf(total, done))
        }
        out.write("\n]\n")
        return summary
    }

    suspend fun writePlainText(
        sessions: List<ChatSessionEntity>,
        loader: PageLoader,
        out: Writer,
        total: Int = 0,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Summary {
        val dateFmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        var summary = Summary()
        var done = 0
        sessions.forEachIndexed { i, session ->
            if (i > 0) out.write("\n\n" + "=".repeat(60) + "\n\n")
            out.write("# ${session.title ?: "Untitled"}\n")
            out.write("Model: ${session.modelId}\n")
            out.write("Created: ${dateFmt.format(Date(session.createdAt))}\n")
            out.write("-".repeat(40) + "\n")
            var sinceProgress = 0
            forEachRowFromFirstUser(session.id, loader) { row ->
                done += 1
                val parts = parseParts(row.partsJson)
                if (isToolResultOnly(parts)) return@forEachRowFromFirstUser
                val lines = mutableListOf<String>()
                for (p in parts) {
                    val v = p.optJSONObject("value")
                    when (p.optString("type")) {
                        "text" -> p.optString("value").takeIf { it.isNotEmpty() }?.let { lines.add(it) }
                        "toolUse" -> lines.add(
                            "[Tool: ${v?.optString("description")?.takeIf { it.isNotEmpty() } ?: v?.optString("name").orEmpty()}]",
                        )
                        // Android keeps no result snapshot, so this is iOS's no-snapshot branch.
                        "toolResult" -> lines.add("[Result: ${if (v?.optBoolean("success", true) != false) "ok" else "failed"}]")
                        "mediaRef" -> {
                            lines.add("[Attachment]")
                            summary = summary.countMedia(v?.optString("mimeType").orEmpty())
                        }
                    }
                }
                val body = lines.joinToString("\n")
                if (body.isNotEmpty()) {
                    val role = if (row.role == "user") "User" else "Minis"
                    out.write("\n[$role] ${timeFmt.format(Date(row.createdAt))}\n$body\n---\n")
                    summary = summary.countMessage(row.createdAt)
                }
                if (++sinceProgress >= BATCH_SIZE) {
                    sinceProgress = 0
                    out.flush()
                    onProgress(done, maxOf(total, done))
                }
            }
            onProgress(done, maxOf(total, done))
        }
        return summary
    }

    private fun parseParts(partsJson: String): List<JSONObject> = try {
        val arr = JSONArray(partsJson)
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    } catch (_: Exception) {
        // A row that is not a parts array is treated as plain text, as the
        // rest of the app does.
        listOf(JSONObject().put("type", "text").put("value", partsJson))
    }

    /** iOS `isToolResultOnly`: every part is a tool result. */
    private fun isToolResultOnly(parts: List<JSONObject>): Boolean =
        parts.isNotEmpty() && parts.all { it.optString("type") == "toolResult" }

    private fun Summary.countMessage(at: Long) = copy(
        totalMessages = totalMessages + 1,
        earliest = if (earliest == null || at < earliest) at else earliest,
        latest = if (latest == null || at > latest) at else latest,
    )

    private fun Summary.countMedia(mime: String) = when {
        mime.startsWith("image/") -> copy(images = images + 1)
        mime.startsWith("video/") -> copy(videos = videos + 1)
        else -> copy(otherAttachments = otherAttachments + 1)
    }

    private fun iso(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(ms))
}
