package com.openminis.app.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONObject
import java.io.ByteArrayOutputStream

object ReadImageTool {
    const val NAME = "read_image"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Read an image file from the Linux filesystem and return it for visual analysis. Supports PNG, JPEG, GIF, WEBP, and other common image formats. Use this to inspect generated charts, downloaded images, screenshots, or any visual output. If you natively support vision the image is returned directly for your analysis; if you do not, it is routed through a configured Vision Group that returns a text description — in that case pass a `prompt` to focus the description on what you actually need (you cannot see the pixels yourself, so this is how you 'ask' about the image). Metadata (dimensions, file size) is always included.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'View generated bar chart', 'Inspect downloaded screenshot'). Use the same language as the user."),
            "path" to AgentToolParam("string", "Linux path (e.g. /var/minis/attachments/chart.png) or minis:// URL (e.g. minis://attachments/chart.png)"),
            "prompt" to AgentToolParam("string", "Optional. A custom instruction describing what you want to understand from the image (e.g. 'transcribe the table text', 'describe the people and their expressions', 'what error message is in this screenshot'). Most useful when you lack native vision and the image is described by a Vision Group — it steers that description toward your question. If omitted, a generic 'describe this image in detail' instruction is used."),
        ),
        required = listOf("tool_title", "path"),
        propertyOrdering = listOf("tool_title", "path", "prompt"),
    )

    /**
     * T178: when the caller knows the owning session, prefer
     * [PRootKernel.resolveSessionHostPath] so per-session subdirs
     * (`/var/minis/{attachments,workspace,offloads,browser}/...`) resolve
     * directly against this session's host dir instead of consulting the
     * global, last-writer-wins `bindMounts` map. Without this, an agent
     * loop in session A that calls `read_image` after session B booted
     * its PRoot reads from session B's host dir — a confirmed leak before this fix.
     *
     * The legacy single-arg overload is preserved for callers that don't
     * know the session id (and falls back to the global map). Mirror iOS
     * `ReadImageTool` which carries `sessionId` through its tool-call
     * pipeline.
     */
    fun execute(argsJson: String, sessionId: String? = null, context: Context? = null): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val rawPath = args.optString("path", "")
            val toolTitle = args.optString("tool_title", NAME)

            if (rawPath.isBlank()) {
                return ToolExecutionResult("Error: 'path' is required", false, toolTitle = toolTitle)
            }

            val path = if (rawPath.startsWith("minis://")) {
                "/var/minis/" + java.net.URLDecoder.decode(rawPath.removePrefix("minis://"), "UTF-8")
            } else rawPath

            val file = if (sessionId != null && context != null) {
                // A null here is the session jail. Falling back to the global
                // bind map would reopen the other session's file.
                PRootKernel.resolveSessionHostPath(sessionId, path, context)
            } else {
                PRootKernel.resolveHostPath(path)
            } ?: return ToolExecutionResult("Error: Cannot resolve path: $path", false, toolTitle = toolTitle)

            if (!file.exists()) {
                return ToolExecutionResult("Error: File not found: $path", false, toolTitle = toolTitle)
            }

            val original = BitmapFactory.decodeFile(file.absolutePath)
                ?: return ToolExecutionResult("Error: Cannot decode image: $path", false, toolTitle = toolTitle)

            val maxEdge = 2000
            val scaled = if (original.width > maxEdge || original.height > maxEdge) {
                val scale = maxEdge.toFloat() / maxOf(original.width, original.height)
                val w = (original.width * scale).toInt()
                val h = (original.height * scale).toInt()
                Bitmap.createScaledBitmap(original, w, h, true)
            } else {
                original
            }

            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            val imageBytes = out.toByteArray()

            if (scaled !== original) scaled.recycle()
            original.recycle()

            val metadata = "[$path | ${original.width}x${original.height} | ${file.length()} bytes]"
            ToolExecutionResult(
                output = metadata,
                success = true,
                imageData = imageBytes,
                imageMimeType = "image/jpeg",
                toolTitle = toolTitle,
                // Surface the source file for inline preview in the tool result UI
                // (mirrors iOS ToolLiveSheet.readImageTool case).
                imageFilePath = file.absolutePath,
            )
        } catch (e: Exception) {
            ToolExecutionResult("Error reading image: ${e.message}", false)
        }
    }
}
