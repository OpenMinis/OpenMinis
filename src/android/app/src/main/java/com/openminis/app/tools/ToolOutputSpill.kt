package com.openminis.app.tools

import android.content.Context
import java.io.File

/**
 * Spill oversized tool results out of the LLM context into the session
 * workspace. Inspired by long-running Android agents that otherwise freeze
 * Compose and blow the context window when `shell_execute` dumps megabytes.
 *
 * The UI still shows a short tail; the model gets a preview plus a guest
 * path it can `file_read` if it actually needs more.
 *
 * [T-tool-output-single-mechanism] This is THE oversized-output mechanism:
 * it resolves the spill directory PER SESSION through
 * `SessionWorkspace.hostDir(filesDir, sessionId, "workspace")`, i.e. the HOST
 * path the app process can actually write, and hands the model the matching
 * GUEST path. [ToolOutputPolicy] used to carry a second, parallel copy of this
 * logic writing to `ToolOutputPolicy.workspaceRoot = "/var/minis/workspace/…"`
 * — a GUEST path the app process cannot write, whose write therefore always
 * failed, after which `apply()` returned the text UNCAPPED (defeating the whole
 * limit). That duplicate is gone; ToolOutputPolicy now redacts and delegates
 * here, so one threshold, one preview format and one working path remain.
 */
object ToolOutputSpill {
    /** The single oversized-output threshold (chars). */
    const val LIMIT = 16_000
    const val HEAD = 6_000
    const val TAIL = 4_000

    fun maybeSpill(
        context: Context,
        sessionId: String,
        toolName: String,
        toolId: String,
        output: String,
    ): String {
        if (output.length <= LIMIT) return output
        // Oversized and no session to hang the spill file on: still capped, the
        // model just gets no path to page through.
        if (sessionId.isBlank()) return cappedPreview(toolName, output)
        val safeId = SAFE_ID_RE.replace(toolId, "_").ifBlank { "tool" }
        val dir = File(
            com.openminis.app.sandbox.SessionWorkspace.hostDir(context.filesDir, sessionId, "workspace"),
            "tool-spill",
        )
        if (!dir.mkdirs() && !dir.isDirectory) {
            // [T-tool-output-single-mechanism] CAPPED even when the spill cannot
            // happen. Returning the raw text here (the old behaviour of the
            // duplicated mechanism) is what let a megabyte tool result reach
            // the model intact.
            return cappedPreview(toolName, output)
        }
        val file = File(dir, "$safeId.txt")
        return try {
            file.writeText(output)
            formatPreview(
                toolName = toolName,
                guestPath = "/var/minis/workspace/tool-spill/${file.name}",
                output = output,
            )
        } catch (_: Exception) {
            cappedPreview(toolName, output)
        }
    }

    /**
     * Bounded preview with NO spill file: the model is told the truth (the full
     * output is gone) instead of silently receiving an uncapped dump.
     */
    fun cappedPreview(toolName: String, output: String): String =
        formatPreview(toolName, guestPath = null, output = output)

    /**
     * [guestPath] is null when the full text could NOT be persisted, in which
     * case the preview says so instead of pointing at a file that does not exist.
     */
    fun formatPreview(toolName: String, guestPath: String?, output: String): String {
        // Surrogate-safe slicing (ToolOutputPolicy.safeSubstring): take/takeLast
        // could cut a emoji in half and poison the message with a lone surrogate.
        val head = ToolOutputPolicy.safeSubstring(output, 0, HEAD)
        val tail = if (output.length > HEAD + TAIL)
            ToolOutputPolicy.safeSubstring(output, output.length - TAIL, output.length) else ""
        return buildString {
            append("[tool-output-spill] $toolName produced ${output.length} chars. ")
            if (guestPath != null) {
                append("Full output saved to $guestPath — use file_read if you need more.\n")
                append("Open in app: minis://workspace/tool-spill/${guestPath.substringAfterLast('/')}\n\n")
            } else {
                append("Full output could not be saved to the session workspace, ")
                append("so only this bounded preview is available.\n\n")
            }
            append(head)
            if (tail.isNotEmpty()) {
                append("\n\n…(${output.length - head.length - tail.length} chars omitted)…\n\n")
                append(tail)
            }
        }
    }

    /** [T-hoisted-regex] Constant pattern, compiled once instead of per call. */
    private val SAFE_ID_RE = Regex("[^A-Za-z0-9._-]")

    private val GUEST_PATH = Regex("/var/minis/workspace/tool-spill/([A-Za-z0-9._-]+)")

    fun parseGuestPath(content: String): String? {
        val m = GUEST_PATH.find(content) ?: return null
        return "/var/minis/workspace/tool-spill/${m.groupValues[1]}"
    }

    fun hostFile(context: Context, sessionId: String, guestPath: String): File? {
        val name = parseGuestPath(guestPath)?.substringAfterLast('/') ?: return null
        if (sessionId.isBlank() || name.isBlank()) return null
        val file = File(
            com.openminis.app.sandbox.SessionWorkspace.hostDir(context.filesDir, sessionId, "workspace"),
            "tool-spill/$name",
        )
        return file.takeIf { it.isFile }
    }
}
