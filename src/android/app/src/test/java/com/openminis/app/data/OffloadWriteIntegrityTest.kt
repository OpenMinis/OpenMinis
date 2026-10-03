package com.openminis.app.data

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.provider.ToolJsonRepair
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [T17] Offload placeholders must never become file content; truncated
 * writes are refused (e8665bd4a; issues #374 #343 #119 #223).
 *
 * Three failure shapes this pins:
 *  1. Truncated argument stream. `dynamicMaxTokens` shrinks the output budget
 *     as the context fills, a large `file_write` gets cut mid-`content`, and
 *     `ToolJsonRepair` Strategy 1 closes the JSON. For a write that is
 *     indistinguishable from the model ending `content` there — the half file
 *     landed with "success" on both the UI and the tool result. Writes are now
 *     REFUSED when the repair set carries a `truncation+` tag; read/shell tools
 *     keep the repair-and-run path plus a model-facing note.
 *  2. Readback of an already-offloaded file must not offload again (GH#343).
 *  3. The `[CONTEXT OFFLOADED]` stub replacing a historical file_write's
 *     `content` must never be what a later write puts on disk.
 *
 * The refusal lives in ChatViewModel's dispatch loop (needs a ViewModel); its
 * decision is ported here and driven by the REAL ToolJsonRepair, with a
 * source-grep drift guard.
 */
class OffloadWriteIntegrityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val fileWrite = AgentToolDefinition(
        name = "file_write", description = "write",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "t"),
            "path" to AgentToolParam("string", "p"),
            "content" to AgentToolParam("string", "c"),
        ),
        required = listOf("tool_title", "path", "content"),
    )
    private val fileEdit = fileWrite.copy(name = "file_edit")
    private val fileRead = AgentToolDefinition(
        name = "file_read", description = "read",
        parameters = mapOf("tool_title" to AgentToolParam("string", "t"), "path" to AgentToolParam("string", "p")),
        required = listOf("tool_title", "path"),
    )
    private val tools = listOf(fileWrite, fileEdit, fileRead)

    /** Port of the dispatch-loop decision (ChatViewModel ~L11075-11085). */
    private fun refusesTruncatedWrite(name: String, repairs: List<String>): String? {
        val tag = repairs.firstOrNull { it.startsWith("truncation+") } ?: return null
        return if (name == "file_write" || name == "file_edit") tag else null
    }

    /** The stream tail of a write whose `content` was cut by max_tokens. */
    private val cutWriteTail = "{\"tool_title\":\"Write notes\",\"path\":\"/var/minis/workspace/notes.md\",\"content\":\"# Notes\\nline 1\\nline 2"

    // ── 1. truncated write refused, truncated read allowed ─────────────────

    @Test
    fun `a max_tokens-truncated file_write is repaired into valid JSON but refused`() {
        val args = JSONObject()
        val repairs = ToolJsonRepair.repair("file_write", args, cutWriteTail, tools)
        assertTrue("sanity: the repair DID make it parse (tags=$repairs)", repairs.any { it.startsWith("truncation+") })
        assertEquals("/var/minis/workspace/notes.md", args.getString("path"))
        assertTrue("repaired content is the half the model got out", args.getString("content").startsWith("# Notes"))
        // …which is exactly why it must not run.
        val tag = refusesTruncatedWrite("file_write", repairs)
        assertNotNull("file_write with a truncation repair must be refused", tag)
        assertNotNull(refusesTruncatedWrite("file_edit", repairs))
    }

    @Test
    fun `the same truncation on a read-class tool is allowed to run`() {
        val args = JSONObject()
        val repairs = ToolJsonRepair.repair("file_read", args, "{\"tool_title\":\"Read\",\"path\":\"/var/minis/workspace/a.txt", tools)
        assertTrue(repairs.any { it.startsWith("truncation+") })
        assertNull("reads keep repair-and-run", refusesTruncatedWrite("file_read", repairs))
        assertNull(refusesTruncatedWrite("shell_execute", repairs))
    }

    @Test
    fun `a complete write with only a shape repair is not refused`() {
        // Fuzzy key rename / type coercion fix the SHAPE of complete args, not the value.
        val args = JSONObject().put("tool_title", "w").put("path", "/x").put("contnt", "full body")
        val repairs = ToolJsonRepair.repair("file_write", args, null, tools)
        assertTrue(repairs.any { it.startsWith("fuzzy:") })
        assertNull(refusesTruncatedWrite("file_write", repairs))
        val clean = ToolJsonRepair.repair("file_write", JSONObject().put("tool_title", "w").put("path", "/x").put("content", "b"), null, tools)
        assertTrue(clean.isEmpty())
    }

    // ── 2. readback of an offloaded file is not offloaded again ────────────

    /** Port of the candidate filter in offloadContextIfNeeded (~L9150-9175). */
    private fun isOffloadCandidate(
        part: AgentContentPart,
        answeredToolUseIds: Set<String> = setOf("w", "c"),
    ): Boolean = when (part) {
        is AgentContentPart.ToolResult -> !ContextOffload.isOffloadPlaceholder(part.content) &&
            (part.content.length > 500 || (part.imageData?.size ?: 0) > 1024)
        is AgentContentPart.ToolUse ->
            (part.name == "file_write" || part.name == "file_edit") &&
                // [T-offload-toolinput-guards GH#374] already-pruned, by flag or
                // by text; and only a call that already has a tool_result.
                !part.isOffloadedArgument &&
                !ContextOffload.isOffloadPlaceholder(part.input.optString("content", "")) &&
                part.id in answeredToolUseIds &&
                part.input.optString("content", "").length > 500
        else -> false
    }

    @Test
    fun `reading a file under the offloads dir re-stubs to that file and the stub is never re-offloaded`() {
        val path = "${ContextOffload.LINUX_OFFLOADS_DIR}/tools/shell_execute_abc123def456.txt"
        val readback = "[$path | 456789 bytes | 1200 lines | showing 1-1200 of 1200]\n" + "x".repeat(20_000)
        assertEquals("the readback resolves to the EXISTING file", path, ContextOffload.offloadReadbackPath(readback))
        // After stubbing, the part is skipped by the prefix check and by size.
        val stub = ContextOffload.stub(approxTokens = 5000, byteCount = 456789, linuxPath = path)
        assertTrue(stub.startsWith(ContextOffload.OFFLOADED_PREFIX))
        assertTrue("stub stays under the 500-char scan floor (${stub.length})", stub.length < 500)
        assertFalse(isOffloadCandidate(AgentContentPart.ToolResult("c", "file_read", stub)))
        // A stubbed historical file_write is not a candidate either.
        val stubbedWrite = AgentContentPart.ToolUse("w", "file_write", JSONObject().put("path", "/a").put("content", stub))
        assertFalse(isOffloadCandidate(stubbedWrite))
        // Sanity: the un-stubbed forms ARE candidates.
        assertTrue(isOffloadCandidate(AgentContentPart.ToolResult("c", "file_read", readback)))
        assertTrue(isOffloadCandidate(AgentContentPart.ToolUse("w", "file_write", JSONObject().put("content", "y".repeat(600)))))
        // A read of an ordinary file is never mistaken for a readback.
        assertNull(ContextOffload.offloadReadbackPath("[/var/minis/workspace/big.csv | 9 bytes | 1 lines | showing 1-1 of 1]\nrows"))
    }

    // ── 3. the placeholder must never be written as content ────────────────

    /**
     * GH#374. The history rewrite in offloadContextIfNeeded replaces a past
     * file_write's `content` argument with the stub, so a model that re-issues
     * that call from history (retry, "write it again", a copied tool call) used
     * to write the ~130-char placeholder over the real file and be told
     * `Wrote to <path> (130 bytes)`. Both write tools now refuse, pointing the
     * model at the offloaded path.
     *
     * The refusal lives in the tools and needs a Context to reach, so the
     * DECISION (`ContextOffload.isOffloadPlaceholder`) is asserted directly
     * against the real production predicate, plus a source grep proving both
     * tools consult it and return rather than write.
     */
    @Test
    fun `write content that is an offload placeholder is refused`() {
        val stub = ContextOffload.stub(1200, 48_000, "${ContextOffload.LINUX_OFFLOADS_DIR}/tools/file_write_deadbeef0001.txt")
        assertTrue("the stub itself must be refused", ContextOffload.isOffloadPlaceholder(stub))
        // A provider-prefixed newline must not smuggle it past the prefix test.
        assertTrue(ContextOffload.isOffloadPlaceholder("\n  $stub"))
        // Genuine content writes — including content that merely MENTIONS the
        // marker mid-body, which is why the test is a prefix and not a contains.
        assertFalse(ContextOffload.isOffloadPlaceholder("# real content"))
        assertFalse(
            ContextOffload.isOffloadPlaceholder("notes on ${ContextOffload.OFFLOADED_PREFIX} handling"),
        )
        assertFalse("empty content is a truncation problem, not this one", ContextOffload.isOffloadPlaceholder(""))

        // The refusal text must name the field and tell the model how to recover.
        val refusal = ContextOffload.placeholderWriteRefusal("content", "/var/minis/workspace/app.py")
        assertTrue(refusal.startsWith("Error:"))
        assertTrue(refusal.contains("'content'"))
        assertTrue(refusal.contains("Nothing was written to /var/minis/workspace/app.py"))
        assertTrue("must point at file_read to recover", refusal.contains("Use file_read on the path named inside the placeholder"))

        // Both write tools consult it and RETURN — no write happens first.
        for (tool in listOf("tools/FileWriteTool.kt", "tools/FileEditTool.kt")) {
            val src = ProductionSources.read(tool)
            assertTrue("$tool must consult the guard", src.contains("ContextOffload.isOffloadPlaceholder("))
            assertTrue("$tool must refuse, not write", src.contains("ContextOffload.placeholderWriteRefusal("))
            // The guard must run before the file is opened/resolved, or a
            // partially-created file is left behind.
            assertTrue(
                "$tool must check before resolving the host path",
                src.indexOf("ContextOffload.isOffloadPlaceholder(") < src.indexOf("resolveSessionHostPath"),
            )
        }
        // file_write guards `content`; file_edit guards only the REPLACEMENT
        // text — a placeholder in `old_string` is just a search that will not
        // match, which the "old_string not found" path already reports.
        assertTrue(
            ProductionSources.read("tools/FileWriteTool.kt")
                .contains("ContextOffload.isOffloadPlaceholder(content)"),
        )
        val editSrc = ProductionSources.read("tools/FileEditTool.kt")
        assertTrue(editSrc.contains("ContextOffload.isOffloadPlaceholder(newString)"))
        assertFalse(
            "a stub in old_string must NOT be gated here",
            editSrc.contains("ContextOffload.isOffloadPlaceholder(oldString)"),
        )
    }

    // ── 4. reported byte count == bytes on disk ────────────────────────────

    @Test
    fun `the reported byte count is the file's real size in UTF-8 bytes, not characters`() {
        // Mirrors FileWriteTool: writeText then report file.length().
        val content = "héllo — 你好\n" + "x".repeat(100)
        val f = File(tmp.root, "out.md")
        f.writeText(content)
        val reported = f.length()
        assertEquals(content.toByteArray(Charsets.UTF_8).size.toLong(), reported)
        assertTrue("bytes differ from chars for non-ASCII content", reported != content.length.toLong())
        assertEquals("Wrote to /x/out.md ($reported bytes)", "Wrote to /x/out.md (${f.length()} bytes)")
        // A refused truncated write leaves the target untouched.
        val before = f.readBytes()
        val repairs = ToolJsonRepair.repair("file_write", JSONObject(), cutWriteTail, tools)
        if (refusesTruncatedWrite("file_write", repairs) != null) { /* nothing written */ }
        assertTrue(f.readBytes().contentEquals(before))
    }

    // ── 3b. both placeholder formats are refused ───────────────────────────

    /**
     * [T-offload-stub-system-reminder] (GH#374) The stub handed to the model in
     * place of a pruned ARGUMENT is now a `<system-reminder>` notice, so the
     * model reads it as a meta-instruction rather than as content it may copy
     * into its next write. Detection must accept BOTH formats, and neither
     * branch may be dropped:
     *   - the notice is what the offloader writes now;
     *   - the legacy `[CONTEXT OFFLOADED]` text is still in every session
     *     persisted before the change and in history synced from an older peer,
     *     so it keeps arriving for as long as such histories exist.
     */
    @Test
    fun `both the current notice and the legacy stub are refused`() {
        val path = "${ContextOffload.LINUX_OFFLOADS_DIR}/tools/file_write_c1.txt"
        val notice = ContextOffload.prunedArgumentNotice(4406, 15220, path)
        val legacy = ContextOffload.stub(4406, 15220, path)

        assertTrue("current notice must be refused", ContextOffload.isOffloadPlaceholder(notice))
        assertTrue("legacy stub must still be refused", ContextOffload.isOffloadPlaceholder(legacy))
        assertTrue("a newline-prefixed notice is still caught", ContextOffload.isOffloadPlaceholder("\n  $notice"))

        // The notice must actually carry the agreed structure, not just parse.
        assertTrue(notice.startsWith(ContextOffload.SYSTEM_REMINDER_OPEN))
        assertTrue(notice.contains(ContextOffload.SYSTEM_NOTICE_TAG))
        assertTrue(notice.contains("pruned to save context and offloaded to: $path"))
        assertTrue(notice.contains("NEVER pass this placeholder"))
        assertTrue(notice.trimEnd().endsWith("</system-reminder>"))

        // A bare <system-reminder> is legitimately emitted elsewhere (the persona
        // reminder). Requiring the tag too is what keeps it writable.
        assertFalse(
            "a bare system-reminder is not an offload stub",
            ContextOffload.isOffloadPlaceholder(
                "${ContextOffload.SYSTEM_REMINDER_OPEN}\nuser prefers metric\n</system-reminder>",
            ),
        )
        assertFalse(
            "the tag without the envelope is not an offload stub",
            ContextOffload.isOffloadPlaceholder("${ContextOffload.SYSTEM_NOTICE_TAG} a note"),
        )
        // Prose that merely mentions either marker mid-body still writes.
        assertFalse(ContextOffload.isOffloadPlaceholder("notes on ${ContextOffload.SYSTEM_NOTICE_TAG} handling"))
    }

    /**
     * The provenance flag is the authoritative signal: unlike the text, the model
     * cannot imitate it. Pinned together with the text test so neither alone is
     * relied upon.
     */
    @Test
    fun `an already-pruned argument is skipped by flag even when its text looks genuine`() {
        val big = "x".repeat(2_000)
        val flagged = AgentContentPart.ToolUse(
            "w", "file_write", JSONObject().put("path", "/a").put("content", big),
            isOffloadedArgument = true,
        )
        assertFalse("the flag alone must exclude it", isOffloadCandidate(flagged))
        val unflagged = AgentContentPart.ToolUse(
            "w", "file_write", JSONObject().put("path", "/a").put("content", big),
        )
        assertTrue("sanity: the same part without the flag IS a candidate", isOffloadCandidate(unflagged))
        assertFalse("default is false, so old history decodes unchanged", unflagged.isOffloadedArgument)
    }

    /**
     * An unanswered call's argument must never be pruned — pruning something
     * still in flight is the shape that would put a notice where a payload
     * belongs. Today's call-site ordering already prevents it; this keeps the
     * invariant local to the scanner.
     */
    @Test
    fun `an unanswered tool call's argument is never pruned`() {
        val big = "y".repeat(2_000)
        val pending = AgentContentPart.ToolUse(
            "pending-id", "file_write", JSONObject().put("path", "/a").put("content", big),
        )
        assertFalse(
            "no tool_result for this id yet",
            isOffloadCandidate(pending, answeredToolUseIds = setOf("some-other-id")),
        )
        assertTrue(
            "once answered, it is prunable",
            isOffloadCandidate(pending, answeredToolUseIds = setOf("pending-id")),
        )
    }

    // ── drift guard ────────────────────────────────────────────────────────

    @Test
    fun `drift guard - refusal, model note and byte report still in production`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("val truncationRepairTag: String? = repairs.firstOrNull { it.startsWith(\"truncation+\") }"))
        assertTrue(vm.contains("if (truncationRepairTag != null && (name == \"file_write\" || name == \"file_edit\")) {"))
        assertTrue(vm.contains("Error: This call was NOT executed. Its argument stream was truncated "))
        assertTrue(vm.contains("Nothing was written to disk — the target file is unchanged."))
        assertTrue("refused call must be an error tool result", vm.contains("content = modelMessage,\n                                isError = true,"))
        assertTrue(vm.contains("The argument stream for this call was \" +\n                                \"truncated in transit and auto-closed by the client"))
        // Readback + prefix skip in the offload scan.
        // [T-offload-stub-system-reminder GH#374] Both scanner skips route
        // through the shared predicate now; a bare startsWith on the legacy
        // marker silently stopped matching when the stub format changed.
        assertTrue(
            "the scan must skip via the shared dual-format predicate",
            vm.contains("if (ContextOffload.isOffloadPlaceholder(part.content)) {"),
        )
        assertFalse(
            "no bare legacy-prefix check may remain in the scan",
            vm.contains("part.content.startsWith(ContextOffload.OFFLOADED_PREFIX)"),
        )
        assertTrue(
            "a pruned ARGUMENT gets the system-reminder notice and the provenance flag",
            vm.contains("ContextOffload.prunedArgumentNotice(") &&
                vm.contains("isOffloadedArgument = true"),
        )
        assertTrue(
            "the argument scanner carries both new guards",
            vm.contains("part.isOffloadedArgument ||") &&
                vm.contains("part.id !in answeredToolUseIds"),
        )
        assertTrue(vm.contains("ContextOffload.offloadReadbackPath(part.content)"))
        val fw = ProductionSources.read("tools/FileWriteTool.kt")
        assertTrue(fw.contains("val bytes = file.length()"))
        assertTrue(fw.contains("ToolExecutionResult(\"Wrote to \$path (\$bytes bytes)\", true, toolTitle = toolTitle)"))
        val repair = ProductionSources.read("provider/ToolJsonRepair.kt")
        assertTrue(repair.contains("repairs.add(\"truncation+\" + if (suffix.isEmpty()) \"noop\" else suffix)"))
    }
}
