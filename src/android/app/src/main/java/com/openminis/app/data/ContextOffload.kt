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
     * [T-offload-stub-system-reminder] (GH#374) Tag carried by the current stub
     * format. The pruned-argument notice is wrapped in a `<system-reminder>`
     * envelope so the model reads it as a meta-instruction about a REMOVED
     * argument instead of as content it may copy into its next call. The legacy
     * plain-text form read like content ("Content (~N tokens…) saved to: …"),
     * which is exactly why a model re-issuing its own earlier `file_write`
     * pasted the placeholder back in. Mirrors iOS `offloadedStubNoticeTag`.
     */
    const val SYSTEM_NOTICE_TAG = "[Minis System Notice]"

    /**
     * Envelope opener for the current stub format. Kept separate from
     * [SYSTEM_NOTICE_TAG] so detection can require BOTH — a bare
     * `<system-reminder>` is legitimately emitted by other subsystems (the
     * persona reminder), and must not be mistaken for an offload stub.
     * Mirrors iOS `offloadedStubEnvelope`.
     */
    const val SYSTEM_REMINDER_OPEN = "<system-reminder>"

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
     * [T-offload-stub-system-reminder] (GH#374) The notice handed to the model in
     * place of a `file_write` / `file_edit` payload moved to disk. Byte-identical
     * to iOS `offloadedStubNotice` so a session opened on either platform reads
     * the same text.
     *
     * Used for a pruned tool ARGUMENT specifically. [stub] still covers tool
     * RESULTS and image parts, which the model reads as observations rather than
     * as arguments it might resend.
     */
    fun prunedArgumentNotice(approxTokens: Int, byteCount: Int, linuxPath: String): String =
        "$SYSTEM_REMINDER_OPEN\n" +
            "$SYSTEM_NOTICE_TAG The original content (~$approxTokens tokens, " +
            "$byteCount bytes) of this tool argument has been pruned to save context " +
            "and offloaded to: $linuxPath\n\n" +
            "CRITICAL: This is a placeholder notice, NOT actual file content. NEVER pass " +
            "this placeholder or reuse it in subsequent file_write or file_edit calls. " +
            "If you need the original content, call file_read on the offloaded path first.\n" +
            "</system-reminder>"

    /**
     * [T-offload-placeholder-write-guard] (GH#374) True when [value] is an
     * offload placeholder rather than real content.
     *
     * The offload pass rewrites a historical `file_write`'s `content` argument
     * to [stub] to reclaim its tokens, and that rewritten call is what the model
     * sees from then on. A call re-issued from it — a retry, a resumed pending
     * call, the model copying its own earlier call — therefore carries this
     * ~130-character reference where the file body used to be, and the write
     * tools cannot tell the difference: the arguments are complete and
     * well-formed, so the placeholder lands on disk over the user's real file
     * and is reported as a success whose byte count matches the placeholder.
     *
     * Leading whitespace is trimmed so a payload a provider prefixed with a
     * newline is still caught, and the test is a PREFIX so genuine content that
     * merely mentions the marker mid-body still writes.
     */
    fun isOffloadPlaceholder(value: String): Boolean {
        val trimmed = value.trimStart()
        // Current format: the <system-reminder> envelope AND the notice tag.
        if (trimmed.startsWith(SYSTEM_REMINDER_OPEN) && trimmed.contains(SYSTEM_NOTICE_TAG)) {
            return true
        }
        // Legacy format. NOT transitional: it is still present in every session
        // persisted before this change, and in history synced from a peer on an
        // older build, so these keep arriving for as long as such histories do.
        return trimmed.startsWith(OFFLOADED_PREFIX)
    }

    /**
     * The model-facing refusal for [isOffloadPlaceholder]. [field] names the
     * argument at fault so the model knows which one to re-send.
     */
    fun placeholderWriteRefusal(field: String, path: String): String =
        "Error: '$field' is an offload placeholder, not real " +
            "content — the original text was moved out of context to free tokens and only " +
            "this reference remains in the conversation. Nothing was written to $path; the " +
            "file is unchanged. Use file_read on the path named inside the placeholder to " +
            "recover the real content, then re-issue this write with it."

    /**
     * [T-android-offload-readback] (GH#343) If [content] is a `file_read` of a
     * file we previously offloaded, return that file's path; else null.
     *
     * The problem this solves: the offload scan skips a part that already
     * `startsWith(OFFLOADED_PREFIX)`, but when the agent reads the offloaded
     * file back, `FileReadTool` prepends its own header —
     * `[<path> | <n> bytes | <m> lines | showing a-b of c]` — so the result
     * starts with `[/var/minis/offloads/…`, the prefix check misses, and the
     * content is offloaded AGAIN into a second file holding identical bytes.
     *
     * Note the loop is bounded, contrary to the original report: the stub that
     * replaces it is ~130 chars, well under the scan's 500-char floor, so the
     * copy is not itself re-offloaded. The cost is a redundant file per
     * readback, not unbounded growth.
     *
     * Returning the PATH rather than a boolean is what lets the caller re-stub
     * against the file that already exists instead of writing a duplicate —
     * and, unlike "skip anything read from the offloads dir", it keeps the
     * content compressible, so deliberately re-reading a big offloaded file to
     * analyse it in pieces does not pin those bytes in context for the rest of
     * the session.
     *
     * Only the caller can say whether this came from `file_read`; that is
     * checked there via [AgentContentPart.ToolResult.name], which is already
     * populated on both the live and the rehydrated path.
     */
    fun offloadReadbackPath(content: String): String? {
        if (!content.startsWith("[")) return null
        val close = content.indexOf(']')
        if (close <= 1) return null
        val header = content.substring(1, close)
        // Header shape is `<path> | <n> bytes | …`; the path is the first field.
        val path = header.substringBefore('|').trim()
        if (path.isEmpty()) return null
        // Anchor on the directory boundary so a sibling like
        // `/var/minis/offloads-backup/x` cannot match.
        if (path != LINUX_OFFLOADS_DIR && !path.startsWith("$LINUX_OFFLOADS_DIR/")) return null
        return path
    }

    private const val TAG = "ContextOffload"
}
