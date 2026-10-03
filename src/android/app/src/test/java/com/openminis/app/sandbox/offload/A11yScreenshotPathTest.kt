package com.openminis.app.sandbox.offload

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-a11y-screenshot-mount] `ui screenshot` must land somewhere the
 * agent can actually read.
 *
 * Reported by a user driving a11y automation: every capture came back with a
 * path `read_image` answered "File not found" for, so visual analysis stopped
 * dead. The cause was not a permission setting — it was that
 * `getExternalFilesDir("a11y_screenshots")` is bind-mounted **nowhere**.
 * `ExecutionCoordinator.buildSessionBindMounts` exposes only
 * `/var/minis/{attachments,offloads,workspace,browser}` (per session) and
 * `/var/minis/{memory,skills,shared,mcp-servers}` (global), so the host path
 * the CLI returned fell through `PRootKernel.resolveHostPath`'s rootfs
 * fallback to a file that never existed. The misleading "not found" (rather
 * than "cannot resolve") is why this read as a flaky IO error.
 *
 * These are source-text assertions in the style of `ShellExecutionStrategyTest`:
 * the handler needs a live AccessibilityService and a booted PRoot to run, so
 * the contract is pinned where it can be checked without either.
 */
class A11yScreenshotPathTest {

    private val handlerSrc: String by lazy {
        val f = File("src/main/java/com/openminis/app/sandbox/offload/AccessibilityOffloadHandler.kt")
        assertTrue("missing ${f.absolutePath}", f.exists())
        f.readText()
    }

    /** The handler source with comment lines removed — prose must not satisfy a code assertion. */
    private val handlerCode: String by lazy {
        handlerSrc.lineSequence()
            .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
            .joinToString("\n")
    }

    /** Just the body of `uiScreenshot`, comments stripped. */
    private val screenshotBody: String by lazy {
        handlerCode.substringAfter("private fun uiScreenshot(").substringBefore("\n    private fun ")
    }

    @Test
    fun `the screenshot never writes to an unmounted private directory`() {
        // The exact call that caused the bug. Its presence in a comment is
        // fine (and deliberate — it documents the regression), but it must not
        // survive in code.
        assertFalse(
            "getExternalFilesDir is mounted nowhere; the agent cannot read it back",
            handlerCode.contains("getExternalFilesDir"),
        )
        assertFalse(
            "and the cacheDir fallback was the same bug with a different path",
            screenshotBody.contains("cacheDir"),
        )
    }

    @Test
    fun `the screenshot lands under the attachments mount`() {
        assertTrue(
            "must target /var/minis/attachments",
            handlerCode.contains("""VAR_MINIS_ATTACHMENTS = "/var/minis/attachments""""),
        )
        assertTrue(
            "under a screenshots subdir, to stay clear of uploads/ and spillover/",
            handlerCode.contains("""A11Y_SHOT_SUBDIR = "screenshots""""),
        )
        assertTrue(
            "and the written directory must be built from both",
            screenshotBody.contains("File(attachmentsHostDir, A11Y_SHOT_SUBDIR)"),
        )
    }

    @Test
    fun `the owning session resolves its own attachments directory`() {
        // PRootKernel.bindMounts is process-global and ExecutionCoordinator
        // overwrites it whenever ANY session boots a shell, so resolveHostPath
        // alone is last-writer-wins: with two active chats the capture would
        // land in the wrong one — unreadable here, and leaked there.
        assertTrue(
            "must resolve against the calling session first",
            screenshotBody.contains("PRootKernel.resolveSessionHostPath(it, VAR_MINIS_ATTACHMENTS, context)"),
        )
        assertTrue(
            "falling back to the global map only when there is no session",
            screenshotBody.contains("?: PRootKernel.resolveHostPath(VAR_MINIS_ATTACHMENTS)"),
        )
    }

    @Test
    fun `the session id is threaded from handle to the screenshot`() {
        // Without this the resolver above has nothing to scope to.
        // [T-android-a11y-helper-mount] ...mapped to the session whose
        // attachments dir the guest has mounted (a helper's parent), so the
        // request id still flows in but through ExecutionCoordinator.
        assertTrue(
            "handle must pass the request's session id into ui",
            Regex("""uiSub\(\s*args,\s*request\.sessionId\?\.let \{\s*com\.openminis\.app\.sandbox\.ExecutionCoordinator\.mountedSessionIdFor\(it\)""")
                .containsMatchIn(handlerCode),
        )
        assertTrue(
            "uiSub must accept it",
            handlerCode.contains("private fun uiSub(args: OffloadArgs, sessionId: String?)"),
        )
        assertTrue(
            "and forward it to the screenshot action",
            handlerCode.contains("""uiScreenshot(args, sessionId)"""),
        )
        assertTrue(
            "which must declare it",
            handlerCode.contains("private fun uiScreenshot(args: OffloadArgs, sessionId: String?)"),
        )
    }

    @Test
    fun `the reply carries a guest path, a minis url and the host path`() {
        assertTrue(
            "path must be the guest path, not the host one the agent cannot open",
            screenshotBody.contains("""data.put("path", linuxPath)"""),
        )
        assertTrue(
            "minis_url lets the model render it inline and feed read_image directly",
            screenshotBody.contains("""data.put("minis_url", "minis://attachments/${'$'}A11Y_SHOT_SUBDIR/${'$'}filename")"""),
        )
        assertTrue(
            "host_path is retained so the old value is still available to callers that want it",
            screenshotBody.contains("""data.put("host_path", file.absolutePath)"""),
        )
    }

    @Test
    fun `an unresolvable mount fails loudly instead of writing somewhere unreadable`() {
        // The old `?: cacheDir` chain reported success for a file nothing could
        // read. Failing with a named code and pointing at --inline is the
        // project's CLI convention: errors guide the caller forward.
        assertTrue(
            "must return a specific error code",
            screenshotBody.contains("NO_ATTACHMENTS_MOUNT"),
        )
        assertTrue(
            "and name the flag that still works without a mount",
            screenshotBody.contains("--inline"),
        )
    }

    @Test
    fun `the help text documents the new location`() {
        // This string is the only documentation the model sees for the tool;
        // leaving the old path there would keep pointing it at a dead file.
        assertTrue(
            "help must state the guest path",
            handlerSrc.contains("/var/minis/attachments/screenshots/a11y_<ts>.png"),
        )
        assertFalse(
            "and must not still advertise the unreadable one",
            handlerSrc.contains("<externalFilesDir>/a11y_screenshots"),
        )
    }

    @Test
    fun `the inline path still bypasses the filesystem`() {
        // --inline is the escape hatch for calls with no mount at all, so it
        // must keep returning base64 before any directory resolution happens.
        val beforeResolve = screenshotBody.substringBefore("attachmentsHostDir")
        assertTrue(
            "inline must short-circuit ahead of the mount lookup",
            beforeResolve.contains("""data.put("encoding", "png+base64")"""),
        )
        assertTrue(beforeResolve.contains("return ok(args, data)"))
    }
}
