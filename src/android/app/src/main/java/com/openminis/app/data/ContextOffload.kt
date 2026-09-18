package com.openminis.app.data

import android.content.Context
import com.openminis.app.logging.AppLogger
import java.io.File

/**
 * Per-session offload storage helpers — write large tool outputs to disk so
 * the model can `file_read` them later while we replace the in-history copy
 * with a tiny `[CONTEXT OFFLOADED] ... <linux-path>` stub.
 *
 * Mirrors iOS `AIChatViewModel.minisOffloadsPersistentDir(for:)`,
 * `offloadContextContent(_:toolId:toolName:ext:)`, and
 * `offloadContextImage(_:toolId:mimeType:)` (AIChatViewModel.swift:6964 +
 * 7170 + 7188). Same path layout — `.../offloads/tools/<name>_<id>.<ext>` —
 * so file_read paths round-trip across platforms when an Android-offloaded
 * session is opened on iOS (or vice versa) via cloud sync.
 *
 * Linux-visible mount: `/var/minis/offloads/tools/<file>`. The host base
 * `filesDir/minis-sessions/<sid>/offloads` is bind-mounted into the
 * sandbox by [com.openminis.app.sandbox.PRootKernel.perSessionSubdirs]
 * (which already includes the "offloads" subdir — no kernel changes
 * required for this feature).
 */
object ContextOffload {
    /** Linux-side mount point — keep in lock-step with iOS `minisOffloadsLinuxDir`. */
    const val LINUX_OFFLOADS_DIR = "/var/minis/offloads"

    /** Sentinel prefix on stub strings — the agent loop checks this to skip
     *  re-offloading parts that have already been processed. Mirrors iOS. */
    const val OFFLOADED_PREFIX = "[CONTEXT OFFLOADED]"

    /**
     * [GH#352] Text this large must leave the prompt even if it sits in the
     * protected last-4-messages window. Token-threshold offload never sees
     * the latest tool result (that's the protected tail), and the char/3.5
     * estimator under-counts base64 by ~2.5x, so a 1MB PNG-as-text (~960k
     * real tokens) was sent raw and 400'd the session forever.
     */
    const val HARD_OFFLOAD_CHARS = 32_768

    fun isForceOffloadContent(content: String): Boolean =
        content.length >= HARD_OFFLOAD_CHARS && !isOffloadReadback(content)

    /**
     * Host-side persistent dir for [sessionId]'s tool offloads. Lazily
     * created on first write — callers should call [ensureToolsDir] before
     * writing.
     */
    fun toolsDir(context: Context, sessionId: String): File =
        File(context.filesDir, "minis-sessions/$sessionId/offloads/tools")

    private fun ensureToolsDir(context: Context, sessionId: String): File {
        val dir = toolsDir(context, sessionId)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Take the last 12 chars of [toolId] as a short, locally-unique suffix
     * for the on-disk filename. Anthropic IDs are `toolu_01…` (constant
     * 8-char prefix), so the trailing 12 chars are still distinguishing.
     * Mirrors iOS `shortToolId(_:)`.
     */
    private fun shortToolId(toolId: String): String =
        if (toolId.length <= 12) toolId else toolId.takeLast(12)

    private fun sanitize(name: String): String =
        name.ifEmpty { "tool" }.replace('/', '_')

    /**
     * Write tool text content to disk and return the Linux-visible path
     * the model can later pass to `file_read`. Returns the empty string
     * on any I/O failure — caller should still update the in-history part
     * with a stub so the model isn't left holding the original bytes.
     */
    fun offloadContent(
        context: Context,
        sessionId: String,
        content: String,
        toolId: String,
        toolName: String,
        ext: String = "txt",
    ): String {
        val dir = ensureToolsDir(context, sessionId)
        val fileName = "${sanitize(toolName)}_${shortToolId(toolId)}.$ext"
        val file = File(dir, fileName)
        return try {
            file.writeText(content)
            "$LINUX_OFFLOADS_DIR/tools/$fileName"
        } catch (e: Exception) {
            AppLogger.warning(TAG, "offloadContent failed: ${e.message}")
            ""
        }
    }

    /**
     * Write tool image bytes to disk and return the Linux-visible path.
     * Extension derived from MIME type — falls through to `.bin` for
     * unrecognised types so the file_read path still resolves something
     * the model can preview.
     */
    fun offloadImage(
        context: Context,
        sessionId: String,
        bytes: ByteArray,
        toolId: String,
        mimeType: String,
    ): String {
        val ext = when (mimeType) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            else -> "bin"
        }
        val dir = ensureToolsDir(context, sessionId)
        val fileName = "image_${shortToolId(toolId)}.$ext"
        val file = File(dir, fileName)
        return try {
            file.writeBytes(bytes)
            "$LINUX_OFFLOADS_DIR/tools/$fileName"
        } catch (e: Exception) {
            AppLogger.warning(TAG, "offloadImage failed: ${e.message}")
            ""
        }
    }

    /**
     * Build the in-history stub that replaces an offloaded part. Format
     * is identical to iOS so a session opened on either platform shows
     * the same `[CONTEXT OFFLOADED] …` text where the real bytes used
     * to be.
     */
    fun stub(approxTokens: Int, byteCount: Int, linuxPath: String): String =
        "$OFFLOADED_PREFIX Content (~$approxTokens tokens, $byteCount bytes) saved to: $linuxPath\n" +
            "Use file_read tool to retrieve if needed."

    /**
     * [GH#343] True when [content] is an offload stub that has come back into the
     * history — either raw, or wrapped in the "[<path> | <n> bytes | <m> lines |
     * showing a-b of c]" header that FileReadTool prepends to every result.
     *
     * The wrapper is exactly why a plain `content.startsWith(OFFLOADED_PREFIX)`
     * check is not enough: reading an offloaded file back yields
     * "[/var/minis/offloads/tools/x.txt | 12345 bytes | …]\n[CONTEXT OFFLOADED] …",
     * so the candidate scan saw a brand-new large result every turn and offloaded
     * the stub again — relocating the same context to a new file forever.
     */
    fun isOffloadReadback(content: String): Boolean {
        val markerAt = content.indexOf(OFFLOADED_PREFIX)
        if (markerAt < 0) return false
        if (markerAt == 0) return true
        if (markerAt > READBACK_HEADER_MAX) return false
        return content.substring(0, markerAt).contains(LINUX_OFFLOADS_DIR)
    }

    /** Longest "[<path> | <n> bytes | <m> lines | showing a-b of c]" header we tolerate. */
    private const val READBACK_HEADER_MAX = 512

    private const val TAG = "ContextOffload"
}
