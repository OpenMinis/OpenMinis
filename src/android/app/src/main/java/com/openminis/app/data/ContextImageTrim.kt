package com.openminis.app.data

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import java.util.Locale

/**
 * [GH#323] Keep only the most recent [KEEP_COUNT] images as real bytes in
 * agent history. Older ones become a path placeholder so the model can
 * `read_image` them instead of paying vision/context tokens on every turn.
 *
 * Mirrors iOS `trimOldImagesFromHistory` (`kImageContextKeepCount = 20`).
 * Android previously only had the 25MB *request* ImageBudget, which does
 * not mutate history — old screenshots stayed in `agentHistory` forever.
 *
 * Pure JVM besides [JSONObject] (already used by [AgentContentPart.ToolUse]).
 * Disk snapshot is the caller's job.
 */
object ContextImageTrim {

    const val KEEP_COUNT = 20

    private val MINIS_URL = Regex("""minis://\S+""")

    data class Result(
        val history: List<LLMMessage>,
        val evicted: Int,
        val snapshots: Int,
    )

    fun isPersistentMinisPath(path: String): Boolean {
        if (path.startsWith("/var/minis/workspace/")) return true
        if (path.startsWith("/var/minis/browser/")) return true
        if (path.startsWith("/var/minis/attachments/")) return true
        if (path.startsWith("/var/minis/offloads/")) return true
        if (path.startsWith("minis://workspace/")) return true
        if (path.startsWith("minis://browser/")) return true
        if (path.startsWith("minis://attachments/")) return true
        if (path.startsWith("minis://offloads/")) return true
        return false
    }

    fun originalImagePath(toolName: String, input: JSONObject?, content: String): String? {
        if (toolName == "read_image") {
            val path = input?.optString("path").orEmpty()
            if (path.isNotEmpty()) return path
        }
        if (toolName == "browser_use" || toolName.isEmpty()) {
            MINIS_URL.find(content)?.value?.let { return it }
        }
        return null
    }

    fun placeholder(byteCount: Int, originalPath: String?, snapshotPath: String?): String {
        val size = formatBytes(byteCount)
        return when {
            originalPath != null && snapshotPath != null ->
                "[image omitted to save context — $size — original: $originalPath; snapshot: $snapshotPath, use read_image on snapshot if original is unavailable]"
            originalPath != null ->
                "[image omitted to save context — $size — original: $originalPath, use read_image to view again]"
            snapshotPath != null ->
                "[image omitted to save context — $size — saved to $snapshotPath, use read_image to view again]"
            else ->
                "[image omitted to save context — $size]"
        }
    }

    /**
     * Evict oldest images until at most [keep] image-bearing parts retain bytes.
     * [snapshot] writes volatile bytes and returns the linux path (empty on failure).
     */
    fun trim(
        history: List<LLMMessage>,
        keep: Int = KEEP_COUNT,
        snapshot: (toolId: String, bytes: ByteArray, mimeType: String) -> String,
    ): Result {
        if (keep < 0) return Result(history, 0, 0)
        val toolCallById = HashMap<String, Pair<String, JSONObject>>()
        var totalImages = 0
        for (msg in history) {
            for (part in msg.contentParts) {
                when (part) {
                    is AgentContentPart.ToolUse ->
                        toolCallById[part.id] = part.name to part.input
                    is AgentContentPart.ToolResult ->
                        if (part.imageData != null) totalImages++
                    is AgentContentPart.ImageData -> totalImages++
                    else -> Unit
                }
            }
        }
        if (totalImages <= keep) return Result(history, 0, 0)

        val evictCount = totalImages - keep
        var evicted = 0
        var snapshots = 0
        val out = history.toMutableList()

        for (mi in out.indices) {
            if (evicted >= evictCount) break
            val msg = out[mi]
            val parts = msg.contentParts
            val newParts = parts.toMutableList()
            var mutated = false
            for (pi in parts.indices) {
                if (evicted >= evictCount) break
                when (val part = parts[pi]) {
                    is AgentContentPart.ToolResult -> {
                        val data = part.imageData ?: continue
                        val call = toolCallById[part.id]
                        val effectiveName = part.name.ifEmpty { call?.first.orEmpty() }
                        val originalPath = part.imageLinuxPath
                            ?: originalImagePath(effectiveName, call?.second, part.content)
                        val snapshotPath: String?
                        if (originalPath != null && isPersistentMinisPath(originalPath)) {
                            snapshotPath = null
                        } else {
                            val mime = part.imageMimeType ?: "image/jpeg"
                            val path = snapshot(part.id, data, mime)
                            snapshotPath = path.takeIf { it.isNotEmpty() }
                            if (snapshotPath != null) snapshots++
                        }
                        val note = placeholder(data.size, originalPath, snapshotPath)
                        val newContent = if (part.content.isEmpty()) note else "${part.content}\n\n$note"
                        newParts[pi] = part.copy(
                            content = newContent,
                            imageData = null,
                            imageMimeType = null,
                            imageLinuxPath = part.imageLinuxPath ?: snapshotPath ?: originalPath,
                        )
                        mutated = true
                        evicted++
                    }
                    is AgentContentPart.ImageData -> {
                        val originalPath = part.linuxPath
                        val snapshotPath: String?
                        if (originalPath != null && isPersistentMinisPath(originalPath)) {
                            snapshotPath = null
                        } else {
                            val synthId = "img${mi}_$pi"
                            val path = snapshot(synthId, part.data, part.mimeType)
                            snapshotPath = path.takeIf { it.isNotEmpty() }
                            if (snapshotPath != null) snapshots++
                        }
                        newParts[pi] = AgentContentPart.Text(
                            placeholder(part.data.size, originalPath, snapshotPath),
                        )
                        mutated = true
                        evicted++
                    }
                    else -> Unit
                }
            }
            if (mutated) out[mi] = msg.copy(contentParts = newParts)
        }
        return Result(out, evicted, snapshots)
    }

    fun formatBytes(n: Int): String = when {
        n >= 1_048_576 -> String.format(Locale.US, "%.1f MB", n / 1_048_576.0)
        n >= 1024 -> String.format(Locale.US, "%.1f KB", n / 1024.0)
        else -> "$n bytes"
    }
}
