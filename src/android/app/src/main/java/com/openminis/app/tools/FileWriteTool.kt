package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.ContextOffload
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONObject

object FileWriteTool {
    const val NAME = "file_write"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        // [T-android-file-write-large-guidance] The large-file paragraph is the
        // load-bearing half of this description. Every byte of `content` is
        // generated as tool arguments and streamed, so a single huge call is
        // both slow and a common way for a request to die mid-flight — and the
        // model has no way to know that from the schema alone. Ported from iOS
        // AIChatViewModel+ToolDefinitions.swift.
        description = "Write content to a file on the Linux filesystem. Faster than shell_execute for writing files. Creates the file if it doesn't exist. Use append mode to add to existing files.\n\nIMPORTANT for large files (roughly >8KB): do NOT emit it as one call. Every byte of `content` is generated and streamed as tool arguments, so one huge call is slow and frequently dies mid-request. Instead either (a) write the first chunk, then extend it with further calls using append: true, or (b) when the content is repetitive or computable (SVG charts, generated tables, boilerplate), write a short script and run it with shell_execute — generating 2KB of code that emits 100KB beats transcribing 100KB.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Create Python statistics script', 'Write configuration file'). Use the same language as the user."),
            "path" to AgentToolParam("string", "Absolute Linux path to write (e.g. /root/test.txt)"),
            "content" to AgentToolParam("string", "The text content to write to the file. For large content prefer several appending calls over one huge one — see the tool description."),
            "append" to AgentToolParam("boolean", "If true, append to existing file instead of overwriting (default: false)"),
            "create_dirs" to AgentToolParam("boolean", "If true, create parent directories if they don't exist (default: false)"),
        ),
        required = listOf("tool_title", "path", "content"),
        propertyOrdering = listOf("tool_title", "path", "content", "append", "create_dirs"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val path = args.optString("path", "")
            val content = args.optString("content", "")
            val append = args.optBoolean("append", false)
            val createDirs = args.optBoolean("create_dirs", false)
            val toolTitle = args.optString("tool_title", NAME)

            if (path.isBlank()) {
                return ToolExecutionResult("Error: 'path' is required", false, toolTitle = toolTitle)
            }

            // [T-offload-placeholder-write-guard] (GH#374) Refuse before opening
            // anything: the offload pass rewrites a historical file_write's
            // `content` to the "[CONTEXT OFFLOADED] …" stub, so a call re-issued
            // from that rewritten history would overwrite the real file with the
            // ~130-char reference and report success. Mirrors iOS
            // AIChatViewModel.offloadPlaceholderRefusal, and the
            // truncated-write refusal in ChatViewModel's dispatch loop: for a
            // write, a wrong artifact is strictly worse than none.
            if (ContextOffload.isOffloadPlaceholder(content)) {
                AppLogger.warning(
                    "FileWrite",
                    "REFUSED offload-placeholder write path=$path bytes=${content.length}",
                )
                return ToolExecutionResult(
                    ContextOffload.placeholderWriteRefusal("content", path),
                    false, toolTitle = toolTitle,
                )
            }

            // T219: read-only mount guard. Reject before opening so we don't
            // half-create files inside a Locked external mount and surface a
            // friendly hint pointing the user at Settings. Mirrors iOS
            // MountedFolderCoordinator.isLinuxPathUnderReadOnlyMount used by
            // AIChatViewModel.fileWrite (AIChatViewModel.swift:8333-8341).
            if (PRootKernel.isLinuxPathUnderReadOnlyMount(path)) {
                return ToolExecutionResult(
                    "Error: $path is inside a read-only mounted folder and cannot be modified. " +
                        "Toggle writability in Settings → Mount External Folders if this is a mistake.",
                    false, toolTitle = toolTitle,
                )
            }

            // T123: per-session resolver so /var/minis/workspace/...,
            // /var/minis/attachments/..., /var/minis/offloads/...,
            // /var/minis/browser/... land in this session's host dir
            // rather than the global bind-mount map (which is overwritten
            // every time another session boots its shell, last-writer-wins).
            val file = PRootKernel.resolveSessionHostPath(sessionId, path, context)
                ?: return ToolExecutionResult("Error: Cannot resolve path: $path", false, toolTitle = toolTitle)

            // Validate UTF-8
            try {
                content.toByteArray(Charsets.UTF_8)
            } catch (e: Exception) {
                return ToolExecutionResult("Error: Content is not valid UTF-8", false, toolTitle = toolTitle)
            }

            // T123: mirror iOS AIChatViewModel L8339 — auto-create the
            // parent dir whenever it doesn't exist, regardless of the
            // create_dirs flag. Per-session subdirs (workspace, etc.) are
            // materialized lazily, so a fresh session writing into
            // /var/minis/workspace/foo/bar.md would otherwise hit "Parent
            // directory does not exist" on the very first call.
            val parent = file.parentFile
            if (parent != null && (createDirs || !parent.exists())) {
                parent.mkdirs()
            }

            if (append) {
                file.appendText(content)
            } else {
                file.writeText(content)
            }

            val bytes = file.length()
            // Diagnose "write reported success but nothing on disk" (Android 10
            // legacy-storage FUSE shadow writes): confirm the file is actually
            // there with the expected size right after writing. A mounted-folder
            // write that silently no-ops shows exists=false / size mismatch here.
            if (path.startsWith("/var/minis/mounts/")) {
                val landed = file.exists() && file.length() == bytes
                com.openminis.app.logging.AppLogger.info(
                    "FileWrite",
                    "mount write path=$path host=${file.absolutePath} bytes=$bytes " +
                        "exists=${file.exists()} landedOk=$landed",
                )
                if (!landed) {
                    com.openminis.app.logging.AppLogger.warning(
                        "FileWrite",
                        "mount write to $path reported success but did NOT persist to " +
                            "${file.absolutePath} — likely missing WRITE_EXTERNAL_STORAGE / " +
                            "shadowed FUSE view on this device",
                    )
                }
            }
            ToolExecutionResult("Wrote to $path ($bytes bytes)", true, toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("Error writing file: ${e.message}", false)
        }
    }
}
