package com.openminis.app.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-android-overlay-completion-survives-stop] Source guards for the two ways
 * the new keep-alive could regress into a pinned foreground service.
 *
 * Making a pending capsule keep the service alive means an un-tapped capsule
 * now costs a foreground service and its wake lock. Two things prevent that
 * from being unbounded, and both are easy to undo by accident:
 *
 *  1. the completion branch arms a timeout that clears the latch, and
 *  2. the `shouldShow` block cancels that timer ONLY when busy — the cancel
 *     used to be unconditional at the top of the block, which was harmless
 *     while the linger was unbounded but would now kill the timer on the very
 *     next emission and restore the pinning behaviour.
 */
class OverlayLingerTimeoutGuardTest {

    private val source: String by lazy {
        val f = File("src/main/java/com/openminis/app/service/AgentForegroundService.kt")
        assertTrue("AgentForegroundService.kt not found (cwd=${File(".").absolutePath})", f.isFile)
        f.readText()
    }

    @Test
    fun `the completion linger is bounded by a timeout`() {
        assertTrue(
            "COMPLETION_LINGER_TIMEOUT_MS must exist — an unbounded linger now pins the FGS",
            source.contains("COMPLETION_LINGER_TIMEOUT_MS"),
        )
        assertTrue(
            "the timeout must be awaited before releasing the latch",
            Regex("""delay\(COMPLETION_LINGER_TIMEOUT_MS\)""").containsMatchIn(source),
        )
    }

    @Test
    fun `the timeout releases the tracker keep-alive`() {
        val job = source.substringAfter("delay(COMPLETION_LINGER_TIMEOUT_MS)").take(400)
        assertTrue(
            "expiring the linger must clear the tracker flag, or the service stays alive forever",
            job.contains("setOverlayCompletionPending(false)"),
        )
    }

    @Test
    fun `the linger timer is only cancelled while busy`() {
        // Pin the guard: inside `if (shouldShow) {`, the cancel must be nested
        // in an isBusy check rather than running unconditionally.
        val block = source.substringAfter("if (shouldShow) {").take(900)
        val cancelIdx = block.indexOf("lingerJob?.cancel()")
        assertTrue("no lingerJob cancel found inside shouldShow", cancelIdx >= 0)
        assertTrue(
            "the cancel inside shouldShow must be guarded by isBusy — an " +
                "unconditional cancel kills the timeout on the next emission",
            block.take(cancelIdx).contains("if (isBusy) {"),
        )
    }

    @Test
    fun `every latch clear is mirrored to a keep-alive release`() {
        // hasCompletionPending is the service's own field; the tracker flag is
        // what keeps the process alive. They must move together, or the service
        // outlives the capsule (leak) — or worse, the capsule outlives the
        // service that draws it (the original bug).
        //
        // Counting occurrences is too blunt: the field DECLARATION initialises
        // to false, and one site releases indirectly through
        // `dismissOverlay()`. Check each assignment's surrounding region for a
        // release instead.
        //
        // The window is deliberately generous and CODE-ONLY. An earlier version
        // scanned a fixed +5 raw lines, which made the invariant hostage to
        // comment length: a correct release documented by a paragraph
        // explaining WHY it is conditional sat outside the window and read as a
        // violation. Comments are stripped and the window widened so this test
        // pins the behaviour rather than the formatting.
        val lines = source.lines()
        val offenders = mutableListOf<String>()
        lines.forEachIndexed { i, line ->
            if (!line.trim().startsWith("hasCompletionPending = false")) return@forEachIndexed
            // Skip the declaration (`private var hasCompletionPending = false`).
            if (line.contains("var hasCompletionPending")) return@forEachIndexed
            val window = lines.subList(
                maxOf(0, i - 6),
                minOf(lines.size, i + 30),
            ).filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") }
                .joinToString("\n")
            val releases = window.contains("setOverlayCompletionPending(false)") ||
                window.contains("dismissOverlay()")
            if (!releases) offenders += "line ${i + 1}: ${line.trim()}"
        }
        assertTrue(
            "these clears of hasCompletionPending do not release the tracker " +
                "keep-alive (directly or via dismissOverlay): $offenders",
            offenders.isEmpty(),
        )
    }
}

