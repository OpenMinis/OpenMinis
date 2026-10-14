package com.openminis.app.accessibility

import android.graphics.Path
import com.openminis.app.logging.AppLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Abstraction over the concrete a11y execution surface so [SkillReplayEngine]
 * can be unit-tested with a fake. The production impl delegates to the same
 * APIs the `android-a11y-cli` offload handler uses — no new service surface.
 */
interface A11yActor {
    /** Bounded text snapshot of the foreground screen (ui dump equivalent). */
    fun dumpScreen(): String

    /** Gesture tap at absolute screen coordinates. Returns success. */
    fun tap(x: Int, y: Int): Boolean

    /** Tap the first node matching text or contentDescription. False if none. */
    fun tapByText(text: String): Boolean

    /** Write [text] into the focused editable node. */
    fun typeText(text: String): Boolean

    /** Swipe between two points (duration ms). */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean

    /** Global BACK. */
    fun back(): Boolean

    /** Launch an app by package name. */
    fun openApp(packageName: String): Boolean

    /** Sleep — real impl Thread.sleep, fake injects no delay. */
    fun sleep(ms: Long)
}

/**
 * Production [A11yActor] delegating straight to [MinisAccessibilityService]
 * (the same entry points [com.openminis.app.sandbox.offload.AccessibilityOffloadHandler]
 * uses for `tap xy`, `tap text`, `input text`, `gesture swipe`, `input key BACK`,
 * `ui dump`):
 *
 * - dump/tapByText/typeText → [MinisAccessibilityService.rootNodes] +
 *   node actions via [com.openminis.app.accessibility.AccessibilityQueryGuard]
 * - tap/swipe → [MinisAccessibilityService.dispatchSimpleGesture]
 * - back → [AccessibilityService.performGlobalAction(GLOBAL_ACTION_BACK)]
 * - openApp → `android-shizuku-cli launch <pkg>` executed in the PRoot GUEST
 *   via [com.openminis.app.sandbox.ExecutionCoordinator] — the same route
 *   `UiActionTool.open_app` takes since the host-shell migration (see
 *   mcp/server/McpToolDispatcher.executeInGuest: stock Android has no
 *   /bin/bash on the host and the CLI stubs live in the guest rootfs, so a
 *   host ProcessBuilder could never work on a real device).
 */
class ServiceA11yActor(private val svc: MinisAccessibilityService) : A11yActor {

    override fun dumpScreen(): String = AccessibilityQueryGuard.query("") {
        buildString {
            for (root in svc.rootNodes()) walk(root, 0, MAX_DUMP_DEPTH)
        }
    }

    private fun StringBuilder.walk(node: android.view.accessibility.AccessibilityNodeInfo?, depth: Int, max: Int) {
        if (node == null || depth > max) return
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        append("  ".repeat(depth))
        append("[")
        append(node.viewIdResourceName ?: "")
        append("] text=")
        append(node.text?.toString() ?: "")
        append(" desc=")
        append(node.contentDescription?.toString() ?: "")
        append(" cls=")
        append(node.className?.toString() ?: "")
        append(" bounds=")
        append(rect.left).append(",").append(rect.top)
        append("-").append(rect.right).append(",").append(rect.bottom)
        append(" clickable=").append(node.isClickable)
        append(" editable=").append(node.isEditable)
        append("\n")
        for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1, max)
    }

    override fun tap(x: Int, y: Int): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            lineTo(x.toFloat() + 0.1f, y.toFloat() + 0.1f)
        }
        return svc.dispatchSimpleGesture(path, 0L, 50L)
    }

    override fun tapByText(text: String): Boolean {
        val node = findNode(text) ?: return false
        if (node.isClickable) {
            val ok = AccessibilityQueryGuard.query(false) { node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) }
            if (ok) return true
        }
        val r = android.graphics.Rect()
        node.getBoundsInScreen(r)
        return tap(r.centerX(), r.centerY())
    }

    override fun typeText(text: String): Boolean {
        for (root in svc.rootNodes()) {
            val editable = findEditable(root, 0) ?: continue
            return svc.setNodeText(editable, text)
        }
        return false
    }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        return svc.dispatchSimpleGesture(path, 0L, durationMs)
    }

    override fun back(): Boolean =
        svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)

    override fun openApp(packageName: String): Boolean = runCatching {
        val cmd = "android-shizuku-cli launch '" + packageName.replace("'", "'\\''") + "'"
        // [T-a11y-host-shell] Was ProcessBuilder("/bin/bash","-c",cmd) on the
        // host — stock Android has no /bin/bash host-side and the CLI stub is
        // installed in the guest rootfs, so the recorded skill's open_app step
        // silently failed on every real device. Execute through the same
        // guest path McpToolDispatcher migrated to
        // (executeInGuest → ExecutionCoordinator.execute): the offload stub
        // bridges to ShizukuOffloadHandler in-process, and a missing/denied
        // Shizuku surfaces as a typed error with non-zero exit instead of an
        // IOException swallowed by getOrDefault(false).
        val result = kotlinx.coroutines.runBlocking {
            com.openminis.app.sandbox.ExecutionCoordinator.execute(
                sessionId = SKILL_SESSION_ID,
                command = cmd,
                timeout = LAUNCH_TIMEOUT_MS,
            )
        }
        AppLogger.info(TAG, "openApp $packageName → exit=${result.exitCode} ${result.output.take(120)}")
        result.exitCode == 0
    }.getOrDefault(false)

    override fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }

    private fun findNode(text: String): android.view.accessibility.AccessibilityNodeInfo? {
        for (root in svc.rootNodes()) {
            findMatch(root, text, 0)?.let { return it }
        }
        return null
    }

    private fun findMatch(
        node: android.view.accessibility.AccessibilityNodeInfo?,
        target: String,
        depth: Int,
    ): android.view.accessibility.AccessibilityNodeInfo? {
        if (node == null || depth > MAX_DUMP_DEPTH) return null
        if (node.text?.toString() == target || node.contentDescription?.toString() == target) return node
        for (i in 0 until node.childCount) {
            findMatch(node.getChild(i), target, depth + 1)?.let { return it }
        }
        return null
    }

    private fun findEditable(
        node: android.view.accessibility.AccessibilityNodeInfo?,
        depth: Int,
    ): android.view.accessibility.AccessibilityNodeInfo? {
        if (node == null || depth > MAX_DUMP_DEPTH) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            findEditable(node.getChild(i), depth + 1)?.let { return it }
        }
        return null
    }

    companion object {
        private const val TAG = "A11yReplay"
        private const val MAX_DUMP_DEPTH = 30

        /**
         * Fixed internal lane for skill-replay guest commands. Skill replay is
         * not driven from a chat session (UiSkillTool hands us only the a11y
         * service), so — like the mcp server's "mcp-server" lane — it owns a
         * stable id; ExecutionCoordinator materializes its bind dirs on demand.
         */
        private const val SKILL_SESSION_ID = "a11y-skill-replay"
        private const val LAUNCH_TIMEOUT_MS = 15_000L
    }
}

/**
 * Replays a recorded a11y skill against the live screen.
 *
 * Per step:
 *  1. popup pre-check: dump once; dismiss up to [MAX_POPUP_DISMISS] dialogs
 *     whose labels hit the close-word list (zh + en);
 *  2. loading-word wait: while the dump shows a loading spinner label, retry
 *     up to [LOADING_RETRIES] × [LOADING_RETRY_MS];
 *  3. label-first resolution: for TAP steps with a label (text / viewId /
 *     contentDescription), re-find the node in the fresh dump; on a hit tap
 *     there AND write the new coordinates back into the store
 *     (坐标自愈); on a miss fall back to the recorded coordinates;
 *  4. inter-step pacing from the recorded atMs deltas, clamped to
 *     [MIN_STEP_GAP_MS]..[MAX_STEP_GAP_MS].
 *
 * Any step failure aborts with a structured [ReplayResult] and bumps the
 * skill's failStreak (via [store]); a full run clears it and refreshes LRU.
 */
class SkillReplayEngine(
    private val actor: A11yActor,
    private val store: A11ySkillStore?,
) {

    data class ReplayResult(
        val success: Boolean,
        val stepIndex: Int?,
        val reason: String?,
        val dumpExcerpt: String?,
        val stepsExecuted: Int,
        val popupsDismissed: Int,
    ) {
        override fun toString(): String = buildString {
            append(if (success) "✓ replay ok" else "✗ replay failed")
            append(" steps=$stepsExecuted popups=$popupsDismissed")
            if (!success) {
                append(" step=").append(stepIndex)
                append(" reason=").append(reason)
                dumpExcerpt?.let { append("\ndump:\n").append(it) }
            }
        }
    }

    fun replay(skill: A11ySkillStore.Skill): ReplayResult {
        if (skill.steps.isEmpty()) {
            return ReplayResult(false, null, "skill has no steps", null, 0, 0)
        }
        if (skill.steps.size > MAX_STEPS) {
            return ReplayResult(false, null, "too many steps: ${skill.steps.size} > $MAX_STEPS", null, 0, 0)
        }
        var popups = 0
        var executed = 0
        var prevAt = skill.steps.firstOrNull()?.atMs ?: 0L
        for ((index, step) in skill.steps.withIndex()) {
            // 1) inter-step pacing from the recorded rhythm
            if (index > 0) {
                val gap = (step.atMs - prevAt).coerceIn(MIN_STEP_GAP_MS, MAX_STEP_GAP_MS)
                actor.sleep(gap)
            }
            prevAt = step.atMs

            // 2) one fresh dump drives popup pre-check, loading wait and
            //    label resolution (extra dumps only after an actual dismissal)
            var dump = runCatching { actor.dumpScreen() }.getOrElse { "" }
            val (popped, afterPopups) = dismissPopups(dump)
            popups += popped
            dump = afterPopups
            val waitOut = waitForLoading(dump)
            if (waitOut != null) {
                return replayEnd(skill, false, index, "loading still present after $LOADING_RETRIES retries", waitOut, executed, popups)
            }

            // 3) the step itself
            val ok = when (step.kind) {
                "TAP" -> runTap(skill, index, step, dump)
                "INPUT" -> runInput(step)
                "WINDOW" -> true // recorded window transition — nothing to do
                else -> false
            }
            if (!ok) {
                return replayEnd(
                    skill, false, index,
                    "step $index (${step.kind}) failed — label=${step.text ?: step.viewId ?: step.contentDescription ?: "-"} xy=${step.resolvedXy ?: step.xy ?: "-"}",
                    dumpExcerpt(dump), executed, popups,
                )
            }
            executed++
        }
        store?.touch(skill.id)
        return replayEnd(skill, true, null, null, null, executed, popups)
    }

    private fun replayEnd(
        skill: A11ySkillStore.Skill, success: Boolean, stepIndex: Int?,
        reason: String?, dump: String?, executed: Int, popups: Int,
    ): ReplayResult {
        if (success) store?.touch(skill.id) else store?.recordFailure(skill.id)
        return ReplayResult(success, stepIndex, reason, dump, executed, popups)
    }

    private fun runTap(skill: A11ySkillStore.Skill, index: Int, step: A11ySkillStore.Step, dump: String): Boolean {
        val label = step.text ?: step.viewId ?: step.contentDescription
        // label-first re-resolution
        if (!label.isNullOrBlank() && dump.isNotBlank() && findCenter(dump, label) != null) {
            val (x, y) = findCenter(dump, label)!!
            if (actor.tap(x, y)) {
                // 坐标自愈: persist the freshly resolved coordinates
                store?.updateResolvedXy(skill.id, index, x, y)
                return true
            }
        }
        // fallback: recorded coordinates (self-healed first, then raw)
        val xy = step.resolvedXy ?: step.xy
        if (xy != null) {
            val parts = xy.split(',')
            val x = parts.getOrNull(0)?.trim()?.toIntOrNull()
            val y = parts.getOrNull(1)?.trim()?.toIntOrNull()
            if (x != null && y != null) return actor.tap(x, y)
        }
        // last resort: tap by text without dump confirmation
        if (!label.isNullOrBlank()) return actor.tapByText(label)
        return false
    }

    private fun runInput(step: A11ySkillStore.Step): Boolean {
        val text = step.text ?: return false
        return actor.typeText(text)
    }

    // ── popup + loading handling ─────────────────────────────────────────

    /**
     * Lines carrying a close-word → tap the label, at most [MAX_POPUP_DISMISS].
     * Starts from the caller's dump and only re-dumps after an actual
     * dismissal; returns (dismissed count, freshest dump).
     */
    private fun dismissPopups(initialDump: String): Pair<Int, String> {
        var dismissed = 0
        var dump = initialDump
        while (dismissed < MAX_POPUP_DISMISS) {
            val target = findPopupLabel(dump) ?: break
            if (!actor.tapByText(target)) break
            dismissed++
            actor.sleep(POPUP_SETTLE_MS)
            dump = runCatching { actor.dumpScreen() }.getOrElse { "" }
        }
        return dismissed to dump
    }

    private fun findPopupLabel(dump: String): String? {
        for (line in dump.lineSequence()) {
            for (word in POPUP_WORDS) {
                if (line.contains("text=$word") || line.contains("desc=$word")) return word
            }
        }
        return null
    }

    /** Returns the last dump (as evidence) when loading words persist, else null. */
    private fun waitForLoading(firstDump: String): String? {
        var dump = firstDump
        var tries = 0
        while (hasLoadingWord(dump) && tries < LOADING_RETRIES) {
            actor.sleep(LOADING_RETRY_MS)
            dump = runCatching { actor.dumpScreen() }.getOrElse { "" }
            tries++
        }
        return if (hasLoadingWord(dump)) dump else null
    }

    private fun hasLoadingWord(dump: String): Boolean = LOADING_WORDS.any { dump.contains("text=$it") || dump.contains("desc=$it") }

    private fun findCenter(dump: String, label: String): Pair<Int, Int>? {
        val regex = Regex("text=\\Q$label\\E|desc=\\Q$label\\E")
        for (line in dump.lineSequence()) {
            if (!regex.containsMatchIn(line)) continue
            val m = Regex("bounds=(-?\\d+),(-?\\d+)-(-?\\d+),(-?\\d+)").find(line) ?: continue
            val (l, t, r, b) = m.destructured
            val left = l.toInt(); val top = t.toInt(); val right = r.toInt(); val bottom = b.toInt()
            return (left + right) / 2 to (top + bottom) / 2
        }
        return null
    }

    private fun dumpExcerpt(dump: String): String? =
        dump.lineSequence().take(40).joinToString("\n").take(1500).ifBlank { null }

    companion object {
        const val MAX_STEPS = 40
        const val MIN_STEP_GAP_MS = 200L
        const val MAX_STEP_GAP_MS = 3000L
        const val LOADING_RETRIES = 5
        const val LOADING_RETRY_MS = 900L
        const val MAX_POPUP_DISMISS = 2
        const val POPUP_SETTLE_MS = 400L

        val POPUP_WORDS = listOf(
            "我知道了", "以后再说", "跳过", "暂不", "拒绝", "不允许", "关闭", "取消",
            "Got it", "Skip", "Not now", "Cancel", "Allow later", "Close",
        )
        val LOADING_WORDS = listOf("加载中", "正在加载", "请稍候", "Loading")

        /** Parse `text=…` / `desc=…` out of a dump line for popup detection. */
        fun dumpLabels(dump: String): List<String> =
            Regex("(?:text|desc)=([^\\n]*)").findAll(dump).map { it.groupValues[1].trim() }.toList()

        internal fun summarizeJson(dump: String): String =
            runCatching {
                val o = JSONObject(dump)
                val arr = o.optJSONArray("nodes") ?: JSONArray()
                (0 until minOf(arr.length(), 40)).joinToString("\n") { i ->
                    val n = arr.optJSONObject(i) ?: return@joinToString ""
                    "text=${n.optString("text")} desc=${n.optString("contentDesc")} center=${n.optJSONObject("center")}"
                }
            }.getOrDefault("")
    }
}
