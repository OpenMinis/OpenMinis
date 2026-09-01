package com.openminis.app.sandbox.offload

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import com.openminis.app.accessibility.AccessibilityRecoveryManager
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.accessibility.NodeRegistry
import com.openminis.app.accessibility.RestrictedSettingsManager
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * `android-a11y-cli` — UI-layer automation surface backed by
 * [MinisAccessibilityService]. Mirrors the design doc
 * `docs/.../android-accessibility-cli-design.md`.
 *
 * Output envelope `{ ok, data }` / `{ ok:false, error:{code,message} }`
 * matches the rest of `android-*` so `--quiet` strips it cleanly.
 */
class AccessibilityOffloadHandler(private val context: Context) : NativeOffloadHandler {

    companion object {
        private const val TAG = "A11yOffload"
        private const val TOOL = "android-a11y-cli"

        private const val TOP_HELP = """android-a11y-cli — UI-layer automation via Android AccessibilityService.

Usage:
  android-a11y-cli <group> <subcommand> [flags]

Groups:
  ui        dump | find | info | node | screenshot
  tap       node | xy | text | id
  input     text | clear | key
  scroll    node | xy | to-text
  gesture   swipe | pinch | path
  wait      appear | disappear | stable | activity
  event     watch | once
  notify    watch | once
  dialog    detect | dismiss
  extract   text | list | form
  service   status | ping

Output: JSON envelope { ok, data | error: { code, message } }.
Use --compact to emit on a single line; --quiet to strip the envelope.

First-run: enable "Minis" under Settings → Accessibility, then `service ping`.
"""
        private const val SERVICE_HELP = "service status | ping\n"
        private const val UI_HELP = """ui dump | find | info | node | screenshot

  ui screenshot                Capture a system-wide screenshot via
                               AccessibilityService.takeScreenshot (API 30+).
    --scale <float>            Scale factor applied to the captured bitmap
                               (default 0.5; e.g. 1.0 = native, 0.25 = quarter).
    --display <int>            Display id to capture (default 0 = default).
    --inline | -b              Return base64 PNG inline instead of writing
                               to a file. Otherwise the PNG is written to
                               <externalFilesDir>/a11y_screenshots/<ts>.png
                               and the path is returned.
"""
        private const val TAP_HELP = "tap node <id> | tap xy <x> <y> | tap text <s> | tap id <res>\n"
        private const val INPUT_HELP = "input text <s> [--node id] [--clear|--append] | input clear | input key BACK|HOME|RECENTS|NOTIFICATIONS\n"
        private const val SCROLL_HELP = "scroll node <id> [--direction up|down|left|right] [--times n] | scroll xy <x> <y> | scroll to-text <s>\n"
        private const val GESTURE_HELP = "gesture swipe <x1> <y1> <x2> <y2> | gesture pinch <cx> <cy> [--scale f] | gesture path \"x,y:x,y\"\n"
        private const val WAIT_HELP = "wait appear | disappear | stable | activity\n"
        private const val EVENT_HELP = "event watch [--type T] [--package P] [--duration ms] | event once [--type T] [--timeout ms]\n"
        private const val NOTIFY_HELP = "notify watch [--package P] [--text-contains S] [--duration ms] | notify once [--timeout ms]\n"
        private const val DIALOG_HELP = "dialog detect | dialog dismiss [--confirm|--deny|--button TEXT]\n"
        private const val EXTRACT_HELP = "extract text | list [--auto-scroll] | form\n"
    }

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(
            request.argv.drop(1),
            booleanFlags = setOf(
                "help", "h", "long", "double", "clear", "append",
                "annotate", "compact", "inline", "b",
                "clickable", "editable", "scrollable", "checked", "enabled",
                "visible-only", "auto-scroll", "auto-dismiss", "auto-confirm",
                "confirm", "deny", "children", "ancestors",
            ),
        )
        if (args.hasFlag("h", "help") || args.positional.isEmpty()) {
            return NativeOffloadResult(if (args.positional.isEmpty()) 2 else 0, TOP_HELP)
        }
        val sub = args.positional[0]
        // T330: tri-state agent gate via OffloadPermissionManager. `service`
        // and `--version` are diagnostic and pass through so the agent (or
        // a curious developer) can still verify the service runs even when
        // the agent surface is blocked.
        if (sub != "service" && sub != "--version") {
            if (!OffloadGate.allow("a11y_cli", "android-a11y-cli", request)) {
                return err(
                    args,
                    "PERMISSION_DENIED",
                    "Agent is not allowed to use android-a11y-cli. Open Settings → Permissions → Integrations to change.",
                )
            }
        }
        // [T-android-a11y-force-stop-recovery] A force-stop (system Settings'
        // "Force stop", or an OEM "clear" button) makes the framework strip our
        // component out of Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES. It
        // never self-heals, so without this every subsequent a11y call just
        // returns SERVICE_NOT_RUNNING and the user has no idea the grant is the
        // problem. Detect it here — the single point every real operation
        // passes through — and offer a repair (one-tap via Shizuku when
        // available, otherwise a trip to Settings).
        //
        // `service` / `--version` are exempt for the same reason they bypass
        // the gate above: they are how you DIAGNOSE this state, and prompting
        // from `service status` would be circular.
        //
        // runBlocking matches the established pattern for permission gates in
        // the sibling handlers (Calendar / Contacts / BrowserUse) — offload
        // handlers are invoked off the main thread on a sandbox worker, and
        // `handle` is not a suspend fun. The prompt self-cancels after
        // AccessibilityRecoveryManager.PROMPT_TIMEOUT_MS so this can never
        // block the worker indefinitely.
        if (sub != "service" && sub != "--version") {
            val usable = kotlinx.coroutines.runBlocking {
                AccessibilityRecoveryManager.ensureGrantOrPrompt(context)
            }
            if (!usable) {
                // [T-android-restricted-settings] Distinguish the two reasons
                // the grant can be missing. If the OS has flagged this install
                // (sideloaded from a downloaded APK), telling the user to
                // "re-enable it under Settings → Accessibility" sends them to a
                // toggle that is greyed out and cannot be moved — the actual
                // blocker is the restricted-settings gate, so name it.
                val restricted = RestrictedSettingsManager.isRestricted(context)
                return err(
                    args,
                    "SERVICE_NOT_RUNNING",
                    if (restricted) {
                        "Android is blocking the accessibility toggle for this install " +
                            "(\"Restricted setting\" — applies to apps installed from a " +
                            "downloaded APK). Allow it via App info → ⋮ → Allow restricted " +
                            "settings, then enable Minis under Settings → Accessibility. " +
                            "Settings → Permissions → System Permissions has a one-tap fix " +
                            "when Shizuku is available."
                    } else {
                        "Accessibility permission was revoked (this happens after a force-stop). " +
                            "Re-enable Minis under Settings → Accessibility, or use Settings → " +
                            "Permissions → Integrations to repair it with Shizuku."
                    },
                    exit = 77,
                )
            }
        }
        // [T-bg-overlay phase 2 fix] Surface a11y sub-action progress to
        // SessionActivityTracker.currentToolStatus so the background
        // overlay capsule + FGS notification show what the agent is
        // actually doing — "tap by-text Login" instead of the static
        // "Running: a11y_cli". Computed before dispatch so even fast
        // sub-commands (status / ping) get a one-line update before they
        // return. Does NOT touch the per-message tool name / running
        // flag — those stay owned by ChatViewModel so the overlay's
        // tool-icon and indeterminate progress are not affected.
        com.openminis.app.service.SessionActivityTracker.updateToolStatus(
            statusForSubAction(sub, args.positional.getOrNull(1), args),
        )
        return try {
            when (sub) {
                "ui"        -> uiSub(args)
                "tap"       -> tapSub(args)
                "input"     -> inputSub(args)
                "scroll"    -> scrollSub(args)
                "gesture"   -> gestureSub(args)
                "wait"      -> waitSub(args)
                "event"     -> eventSub(args)
                "notify"    -> notifySub(args)
                "dialog"    -> dialogSub(args)
                "extract"   -> extractSub(args)
                "service"   -> serviceSub(args)
                "--version" -> NativeOffloadResult(0, "android-a11y-cli 0.1\n")
                else        -> NativeOffloadResult(2, "$TOOL: unknown subcommand '$sub'\n$TOP_HELP")
            }
        } catch (e: NotRunning) {
            err(args, "SERVICE_NOT_RUNNING",
                e.message ?: "Accessibility service is not running. Enable Minis under Settings → Accessibility.",
                exit = 77)
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "uncaught: ${e.javaClass.simpleName} ${e.message}")
            err(args, "INTERNAL", "${e.javaClass.simpleName}: ${e.message ?: ""}")
        }
    }

    // ── service ──────────────────────────────────────────────────────────

    private fun serviceSub(args: OffloadArgs): NativeOffloadResult {
        return when (args.positional.getOrNull(1)) {
            null -> NativeOffloadResult(2, SERVICE_HELP)
            "status" -> {
                val svc = MinisAccessibilityService.getInstance()
                val data = JSONObject()
                    .put("running", svc != null)
                    .put("serviceName", MinisAccessibilityService.SERVICE_ID)
                    .put("capabilities", JSONArray().apply {
                        put("retrieveWindowContent"); put("performGestures"); put("watchEvents")
                    })
                    .put("androidVersion", android.os.Build.VERSION.SDK_INT)
                    // [T-android-restricted-settings] `service status` is the
                    // documented way to diagnose why the service won't start,
                    // so report the OS-level block explicitly — otherwise the
                    // only visible fact is running=false, which looks
                    // identical to "the user never enabled it".
                    .put("restrictedSettings", RestrictedSettingsManager.isRestricted(context))
                ok(args, data)
            }
            "ping" -> {
                if (MinisAccessibilityService.getInstance() != null)
                    NativeOffloadResult(0, "✓ Accessibility service is running\n")
                else if (RestrictedSettingsManager.isRestricted(context))
                    NativeOffloadResult(
                        77,
                        "✗ Accessibility service is not running — Android has flagged this " +
                            "install as \"Restricted setting\" and the toggle is greyed out. " +
                            "Allow it via App info → ⋮ → Allow restricted settings first.\n",
                    )
                else
                    NativeOffloadResult(77, "✗ Accessibility service is not running — go to Settings → Accessibility → Minis to enable\n")
            }
            else -> NativeOffloadResult(2, "$TOOL service: unknown action\n$SERVICE_HELP")
        }
    }

    // ── ui ───────────────────────────────────────────────────────────────

    private fun uiSub(args: OffloadArgs): NativeOffloadResult {
        return when (args.positional.getOrNull(1)) {
            null         -> NativeOffloadResult(2, UI_HELP)
            "dump"       -> uiDump(args)
            "find"       -> uiFind(args)
            "info"       -> uiInfo(args)
            "node"       -> uiNode(args)
            "screenshot" -> uiScreenshot(args)
            else         -> NativeOffloadResult(2, "$TOOL ui: unknown action\n$UI_HELP")
        }
    }

    private fun uiDump(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val maxDepth = args.getInt("depth") ?: 10
        val compact = args.hasFlag("compact")
        val visibleOnly = args.getBool("visible-only") ?: true
        val arr = JSONArray()
        for (root in svc.rootNodes()) {
            walkNode(svc.nodeRegistry, root, 0, maxDepth, visibleOnly, compact, arr)
        }
        return ok(args, JSONObject().put("count", arr.length()).put("nodes", arr))
    }

    private fun walkNode(
        registry: NodeRegistry, node: AccessibilityNodeInfo?,
        depth: Int, maxDepth: Int, visibleOnly: Boolean, compact: Boolean, out: JSONArray,
    ) {
        if (node == null || depth > maxDepth) return
        if (!visibleOnly || node.isVisibleToUser) out.put(nodeToJson(registry, node, depth, compact))
        for (i in 0 until node.childCount) {
            walkNode(registry, node.getChild(i), depth + 1, maxDepth, visibleOnly, compact, out)
        }
    }

    private fun nodeToJson(registry: NodeRegistry, n: AccessibilityNodeInfo, depth: Int, compact: Boolean): JSONObject {
        val id = registry.put(n)
        val rect = Rect(); n.getBoundsInScreen(rect)
        val text = n.text?.toString()
        val desc = n.contentDescription?.toString()
        val obj = JSONObject().put("nodeId", id)
        if (compact) {
            if (!text.isNullOrEmpty()) obj.put("text", text)
            if (!desc.isNullOrEmpty()) obj.put("contentDesc", desc)
            obj.put("clickable", n.isClickable)
            obj.put("center", JSONObject().put("x", rect.centerX()).put("y", rect.centerY()))
        } else {
            obj.put("className", n.className?.toString() ?: "")
            obj.put("text", text ?: "")
            obj.put("contentDesc", desc ?: "")
            obj.put("resourceId", n.viewIdResourceName ?: "")
            obj.put("packageName", n.packageName?.toString() ?: "")
            obj.put("bounds", JSONObject()
                .put("left", rect.left).put("top", rect.top)
                .put("right", rect.right).put("bottom", rect.bottom))
            obj.put("center", JSONObject().put("x", rect.centerX()).put("y", rect.centerY()))
            obj.put("clickable", n.isClickable)
            obj.put("longClickable", n.isLongClickable)
            obj.put("scrollable", n.isScrollable)
            obj.put("editable", n.isEditable)
            obj.put("checkable", n.isCheckable)
            obj.put("checked", n.isChecked)
            obj.put("focusable", n.isFocusable)
            obj.put("focused", n.isFocused)
            obj.put("selected", n.isSelected)
            obj.put("enabled", n.isEnabled)
            obj.put("visible", n.isVisibleToUser)
            obj.put("depth", depth)
            obj.put("childCount", n.childCount)
        }
        return obj
    }

    private fun uiFind(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val limit = args.getInt("limit") ?: 50
        val depth = args.getInt("depth") ?: 30
        val matches = ArrayList<AccessibilityNodeInfo>()
        for (root in svc.rootNodes()) {
            findMatching(root, args, depth, 0, matches, limit)
            if (matches.size >= limit) break
        }
        val index = args.getInt("index")
        val arr = JSONArray()
        if (index != null) {
            matches.getOrNull(index)?.let { arr.put(nodeToJson(svc.nodeRegistry, it, 0, compact = false)) }
        } else {
            for (m in matches) arr.put(nodeToJson(svc.nodeRegistry, m, 0, compact = false))
        }
        return ok(args, JSONObject().put("count", arr.length()).put("data", arr))
    }

    private fun findMatching(
        node: AccessibilityNodeInfo?, args: OffloadArgs,
        maxDepth: Int, depth: Int,
        out: MutableList<AccessibilityNodeInfo>, limit: Int,
    ) {
        if (node == null || depth > maxDepth || out.size >= limit) return
        if (matchesPredicate(node, args)) out.add(node)
        for (i in 0 until node.childCount) {
            findMatching(node.getChild(i), args, maxDepth, depth + 1, out, limit)
        }
    }

    private fun matchesPredicate(n: AccessibilityNodeInfo, args: OffloadArgs): Boolean {
        args.get("text")?.let { if (n.text?.toString() != it) return false }
        args.get("text-contains")?.let { if (n.text?.toString()?.contains(it) != true) return false }
        args.get("desc")?.let { if (n.contentDescription?.toString() != it) return false }
        args.get("desc-contains")?.let { if (n.contentDescription?.toString()?.contains(it) != true) return false }
        args.get("id")?.let { if (n.viewIdResourceName != it) return false }
        args.get("class")?.let { if (n.className?.toString() != it) return false }
        args.get("package")?.let { if (n.packageName?.toString() != it) return false }
        if (args.hasFlag("clickable") && !n.isClickable) return false
        if (args.hasFlag("editable") && !n.isEditable) return false
        if (args.hasFlag("scrollable") && !n.isScrollable) return false
        if (args.hasFlag("checked") && !n.isChecked) return false
        if (args.hasFlag("enabled") && !n.isEnabled) return false
        return true
    }

    private fun uiInfo(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val (pkg, cls) = svc.foregroundPackage()
        val windows = svc.windowInfos()
        val winArr = JSONArray()
        for (w in windows) {
            winArr.put(JSONObject().apply { for ((k, v) in w) put(k, v ?: JSONObject.NULL) })
        }
        return ok(args, JSONObject()
            .put("packageName", pkg ?: "")
            .put("activityName", cls ?: "")
            .put("windowCount", windows.size)
            .put("windows", winArr))
    }

    private fun uiNode(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val nodeId = args.positional.getOrNull(2)
            ?: return NativeOffloadResult(2, "$TOOL ui node: missing <nodeId>\n")
        val n = svc.nodeRegistry.get(nodeId)
            ?: return err(args, "NODE_NOT_FOUND", "no live node with id=$nodeId; re-run `ui dump`")
        val data = nodeToJson(svc.nodeRegistry, n, 0, compact = false)
        if (args.hasFlag("children")) {
            val ch = JSONArray()
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { ch.put(nodeToJson(svc.nodeRegistry, it, 1, compact = false)) }
            }
            data.put("children", ch)
        }
        if (args.hasFlag("ancestors")) {
            val anc = JSONArray()
            var cur = n.parent
            while (cur != null) { anc.put(nodeToJson(svc.nodeRegistry, cur, 0, compact = true)); cur = cur.parent }
            data.put("ancestors", anc)
        }
        return ok(args, data)
    }

    private fun uiScreenshot(args: OffloadArgs): NativeOffloadResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return err(args, "NOT_SUPPORTED",
                "ui screenshot requires Android 11 (API 30); use `android-shizuku-cli exec screencap` instead.")
        }
        val svc = svcOrThrow()
        val scale = (args.getDouble("scale") ?: 0.5).toFloat().coerceIn(0.05f, 1.0f)
        val displayId = args.getInt("display") ?: Display.DEFAULT_DISPLAY
        val inline = args.hasFlag("inline", "b")

        val shot = svc.captureScreenshot(displayId)
        val raw = shot.bitmap
            ?: return err(args, shot.errorCode ?: "TAKE_SCREENSHOT_FAILED",
                shot.errorMessage ?: "takeScreenshot failed")

        val origW = raw.width
        val origH = raw.height
        val scaled: Bitmap = if (scale == 1.0f) raw else {
            val w = (origW * scale).toInt().coerceAtLeast(1)
            val h = (origH * scale).toInt().coerceAtLeast(1)
            val s = Bitmap.createScaledBitmap(raw, w, h, true)
            if (s !== raw) raw.recycle()
            s
        }

        val baos = java.io.ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 100, baos)
        val outW = scaled.width
        val outH = scaled.height
        scaled.recycle()
        val pngBytes = baos.toByteArray()

        val data = JSONObject()
            .put("displayId", displayId)
            .put("originalWidth", origW)
            .put("originalHeight", origH)
            .put("scaledWidth", outW)
            .put("scaledHeight", outH)
            .put("scale", scale.toDouble())
            .put("sizeBytes", pngBytes.size)

        if (inline) {
            data.put("encoding", "png+base64")
            data.put("base64", android.util.Base64.encodeToString(pngBytes, android.util.Base64.NO_WRAP))
            return ok(args, data)
        }

        val dir = (context.getExternalFilesDir("a11y_screenshots") ?: java.io.File(context.cacheDir, "a11y_screenshots"))
        if (!dir.exists()) dir.mkdirs()
        val file = java.io.File(dir, "a11y_${System.currentTimeMillis()}.png")
        return try {
            java.io.FileOutputStream(file).use { it.write(pngBytes) }
            data.put("path", file.absolutePath)
            ok(args, data)
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "screenshot write failed: ${t.message}")
            err(args, "WRITE_FAILED", "failed to write PNG: ${t.javaClass.simpleName}: ${t.message ?: ""}")
        }
    }

    // ── tap ──────────────────────────────────────────────────────────────

    private fun tapSub(args: OffloadArgs): NativeOffloadResult {
        return when (args.positional.getOrNull(1)) {
            null   -> NativeOffloadResult(2, TAP_HELP)
            "node" -> tapNode(args)
            "xy"   -> tapXY(args)
            "text" -> tapText(args)
            "id"   -> tapId(args)
            else   -> NativeOffloadResult(2, "$TOOL tap: unknown action\n$TAP_HELP")
        }
    }

    private fun tapNode(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val nodeId = args.positional.getOrNull(2)
            ?: return NativeOffloadResult(2, "$TOOL tap node: missing <nodeId>\n")
        val n = svc.nodeRegistry.get(nodeId)
            ?: return err(args, "NODE_NOT_FOUND", "no live node with id=$nodeId")
        val action = if (args.hasFlag("long")) AccessibilityNodeInfo.ACTION_LONG_CLICK
                     else AccessibilityNodeInfo.ACTION_CLICK
        if (!n.isClickable && !args.hasFlag("long")) {
            val r = Rect(); n.getBoundsInScreen(r)
            return tapXYRaw(svc, r.centerX(), r.centerY(), args)
        }
        return if (n.performAction(action))
            ok(args, JSONObject().put("nodeId", nodeId).put("action", "click"))
        else err(args, "ACTION_FAILED", "performAction($action) returned false")
    }

    private fun tapXY(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val x = args.positional.getOrNull(2)?.toIntOrNull()
            ?: return NativeOffloadResult(2, "$TOOL tap xy: missing <x>\n")
        val y = args.positional.getOrNull(3)?.toIntOrNull()
            ?: return NativeOffloadResult(2, "$TOOL tap xy: missing <y>\n")
        return tapXYRaw(svc, x, y, args)
    }

    private fun tapXYRaw(svc: MinisAccessibilityService, x: Int, y: Int, args: OffloadArgs): NativeOffloadResult {
        val duration = args.getLong("duration") ?: if (args.hasFlag("long")) 1000L else 50L
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            lineTo(x.toFloat() + 0.1f, y.toFloat() + 0.1f)
        }
        val gestureOk = svc.dispatchSimpleGesture(path, 0L, duration)
        if (args.hasFlag("double")) {
            Thread.sleep(80)
            svc.dispatchSimpleGesture(path, 0L, duration)
        }
        return if (gestureOk) ok(args, JSONObject().put("x", x).put("y", y).put("action", "tap"))
        else err(args, "GESTURE_FAILED", "dispatchGesture returned cancelled / timed out")
    }

    private fun tapText(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val text = args.positional.getOrNull(2)
            ?: return NativeOffloadResult(2, "$TOOL tap text: missing <text>\n")
        val contains = args.hasFlag("contains")
        val index = args.getInt("index") ?: 0
        val matches = ArrayList<AccessibilityNodeInfo>()
        for (root in svc.rootNodes()) findByTextOrDesc(root, text, contains, 30, 0, matches)
        val n = matches.getOrNull(index)
            ?: return err(args, "NODE_NOT_FOUND",
                "no node with text${if (contains) " containing " else "="}\"$text\" (matches: ${matches.size})")
        if (n.isClickable) n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        else {
            val r = Rect(); n.getBoundsInScreen(r)
            tapXYRaw(svc, r.centerX(), r.centerY(), args)
        }
        return ok(args, JSONObject().put("text", text).put("action", "click"))
    }

    private fun findByTextOrDesc(
        node: AccessibilityNodeInfo?, target: String, contains: Boolean,
        maxDepth: Int, depth: Int, out: MutableList<AccessibilityNodeInfo>,
    ) {
        if (node == null || depth > maxDepth) return
        val t = node.text?.toString()
        val d = node.contentDescription?.toString()
        val match = if (contains)
            (t != null && t.contains(target)) || (d != null && d.contains(target))
        else
            t == target || d == target
        if (match) out.add(node)
        for (i in 0 until node.childCount) findByTextOrDesc(node.getChild(i), target, contains, maxDepth, depth + 1, out)
    }

    private fun tapId(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val rid = args.positional.getOrNull(2)
            ?: return NativeOffloadResult(2, "$TOOL tap id: missing <resourceId>\n")
        val matches = ArrayList<AccessibilityNodeInfo>()
        for (root in svc.rootNodes()) findByResourceId(root, rid, 30, 0, matches)
        val n = matches.firstOrNull()
            ?: return err(args, "NODE_NOT_FOUND", "no node with resource-id=$rid")
        return if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            ok(args, JSONObject().put("resourceId", rid).put("action", "click"))
        else err(args, "ACTION_FAILED", "performAction(CLICK) returned false")
    }

    private fun findByResourceId(
        node: AccessibilityNodeInfo?, rid: String,
        maxDepth: Int, depth: Int, out: MutableList<AccessibilityNodeInfo>,
    ) {
        if (node == null || depth > maxDepth) return
        if (node.viewIdResourceName == rid) out.add(node)
        for (i in 0 until node.childCount) findByResourceId(node.getChild(i), rid, maxDepth, depth + 1, out)
    }

    // ── input ────────────────────────────────────────────────────────────

    private fun inputSub(args: OffloadArgs): NativeOffloadResult {
        return when (args.positional.getOrNull(1)) {
            null    -> NativeOffloadResult(2, INPUT_HELP)
            "text"  -> inputText(args)
            "clear" -> inputClear(args)
            "key"   -> inputKey(args)
            else    -> NativeOffloadResult(2, "$TOOL input: unknown action\n$INPUT_HELP")
        }
    }

    private fun inputText(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val text = args.positional.getOrNull(2)
            ?: return NativeOffloadResult(2, "$TOOL input text: missing <text>\n")
        val node = resolveTargetEditable(svc, args)
            ?: return err(args, "NODE_NOT_FOUND", "no editable focus and no --node specified")
        val finalText = when {
            args.hasFlag("clear")  -> text
            args.hasFlag("append") -> (node.text?.toString() ?: "") + text
            else                   -> text
        }
        return if (svc.setNodeText(node, finalText))
            ok(args, JSONObject().put("text", finalText).put("action", "setText"))
        else err(args, "ACTION_FAILED", "ACTION_SET_TEXT failed (node may not be editable)")
    }

    private fun inputClear(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val node = resolveTargetEditable(svc, args)
            ?: return err(args, "NODE_NOT_FOUND", "no editable focus and no --node specified")
        return if (svc.setNodeText(node, ""))
            ok(args, JSONObject().put("action", "clear"))
        else err(args, "ACTION_FAILED", "ACTION_SET_TEXT('') failed")
    }

    private fun inputKey(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val keyName = args.positional.getOrNull(2)
            ?: return NativeOffloadResult(2, "$TOOL input key: missing <keycode>\n")
        return when (keyName.uppercase()) {
            "BACK" -> { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); ok(args, JSONObject().put("key", "BACK")) }
            "HOME" -> { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME); ok(args, JSONObject().put("key", "HOME")) }
            "RECENTS" -> { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS); ok(args, JSONObject().put("key", "RECENTS")) }
            "NOTIFICATIONS" -> { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS); ok(args, JSONObject().put("key", "NOTIFICATIONS")) }
            else -> err(args, "INVALID_ARGS",
                "key '$keyName' not supported via accessibility (try BACK/HOME/RECENTS/NOTIFICATIONS; for ENTER/DPAD use shizuku-cli `input keyevent`)")
        }
    }

    private fun resolveTargetEditable(svc: MinisAccessibilityService, args: OffloadArgs): AccessibilityNodeInfo? {
        args.get("node")?.let { id -> return svc.nodeRegistry.get(id) }
        for (root in svc.rootNodes()) {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null) return focused
        }
        return null
    }

    // ── scroll ───────────────────────────────────────────────────────────

    private fun scrollSub(args: OffloadArgs): NativeOffloadResult {
        return when (args.positional.getOrNull(1)) {
            null      -> NativeOffloadResult(2, SCROLL_HELP)
            "node"    -> scrollNode(args)
            "xy"      -> scrollXY(args)
            "to-text" -> scrollToText(args)
            else      -> NativeOffloadResult(2, "$TOOL scroll: unknown action\n$SCROLL_HELP")
        }
    }

    private fun scrollNode(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val nodeId = args.positional.getOrNull(2)
            ?: return NativeOffloadResult(2, "$TOOL scroll node: missing <nodeId>\n")
        val n = svc.nodeRegistry.get(nodeId)
            ?: return err(args, "NODE_NOT_FOUND", "no live node with id=$nodeId")
        val direction = args.get("direction") ?: "down"
        val times = args.getInt("times") ?: 1
        val action = when (direction) {
            "down", "right" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            "up", "left"    -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else -> return err(args, "INVALID_ARGS", "--direction must be up|down|left|right")
        }
        var done = 0
        repeat(times) { if (n.performAction(action)) done++ }
        return ok(args, JSONObject().put("scrolled", done).put("direction", direction))
    }

    private fun scrollXY(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val x = args.positional.getOrNull(2)?.toIntOrNull()
            ?: return NativeOffloadResult(2, "$TOOL scroll xy: missing <x>\n")
        val y = args.positional.getOrNull(3)?.toIntOrNull()
            ?: return NativeOffloadResult(2, "$TOOL scroll xy: missing <y>\n")
        val direction = args.get("direction") ?: "down"
        val distance = args.getInt("distance") ?: 500
        val duration = args.getLong("duration") ?: 300L
        val (dx, dy) = when (direction) {
            "down"  -> 0 to -distance
            "up"    -> 0 to distance
            "left"  -> distance to 0
            "right" -> -distance to 0
            else -> return err(args, "INVALID_ARGS", "--direction must be up|down|left|right")
        }
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()); lineTo((x + dx).toFloat(), (y + dy).toFloat()) }
        return if (svc.dispatchSimpleGesture(path, 0L, duration))
            ok(args, JSONObject().put("direction", direction).put("distance", distance))
        else err(args, "GESTURE_FAILED", "scroll gesture cancelled")
    }

    private fun scrollToText(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val text = args.positional.getOrNull(2)
            ?: return NativeOffloadResult(2, "$TOOL scroll to-text: missing <text>\n")
        val maxScrolls = args.getInt("max-scrolls") ?: 10
        val direction = args.get("direction") ?: "down"
        val containerId = args.get("container")
        val container: AccessibilityNodeInfo? = containerId?.let { svc.nodeRegistry.get(it) }
        val action = when (direction) {
            "down", "right" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            "up", "left"    -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else -> return err(args, "INVALID_ARGS", "--direction must be up|down|left|right")
        }
        repeat(maxScrolls) {
            val matches = ArrayList<AccessibilityNodeInfo>()
            for (root in svc.rootNodes()) findByTextOrDesc(root, text, contains = true, 30, 0, matches)
            val hit = matches.firstOrNull()
            if (hit != null) return ok(args, JSONObject()
                .put("found", true)
                .put("node", nodeToJson(svc.nodeRegistry, hit, 0, compact = true)))
            val scrolled = container?.performAction(action) ?: run {
                var any = false
                for (root in svc.rootNodes()) {
                    val s = firstScrollable(root, 30, 0)
                    if (s != null) { any = s.performAction(action); break }
                }
                any
            }
            if (!scrolled) return ok(args, JSONObject().put("found", false).put("reason", "scroll_action_rejected"))
            Thread.sleep(400)
        }
        return ok(args, JSONObject().put("found", false))
    }

    private fun firstScrollable(node: AccessibilityNodeInfo?, maxDepth: Int, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > maxDepth) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) firstScrollable(node.getChild(i), maxDepth, depth + 1)?.let { return it }
        return null
    }

    // ── gesture ──────────────────────────────────────────────────────────

    private fun gestureSub(args: OffloadArgs): NativeOffloadResult {
        return when (args.positional.getOrNull(1)) {
            null    -> NativeOffloadResult(2, GESTURE_HELP)
            "swipe" -> gestureSwipe(args)
            "pinch" -> gesturePinch(args)
            "path"  -> gesturePath(args)
            else    -> NativeOffloadResult(2, "$TOOL gesture: unknown action\n$GESTURE_HELP")
        }
    }

    private fun gestureSwipe(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val x1 = args.positional.getOrNull(2)?.toIntOrNull() ?: return NativeOffloadResult(2, "$TOOL gesture swipe: missing <x1>\n")
        val y1 = args.positional.getOrNull(3)?.toIntOrNull() ?: return NativeOffloadResult(2, "$TOOL gesture swipe: missing <y1>\n")
        val x2 = args.positional.getOrNull(4)?.toIntOrNull() ?: return NativeOffloadResult(2, "$TOOL gesture swipe: missing <x2>\n")
        val y2 = args.positional.getOrNull(5)?.toIntOrNull() ?: return NativeOffloadResult(2, "$TOOL gesture swipe: missing <y2>\n")
        val duration = args.getLong("duration") ?: 300L
        val path = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
        return if (svc.dispatchSimpleGesture(path, 0L, duration))
            ok(args, JSONObject().put("from", JSONArray().put(x1).put(y1)).put("to", JSONArray().put(x2).put(y2)))
        else err(args, "GESTURE_FAILED", "swipe cancelled")
    }

    private fun gesturePinch(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val cx = args.positional.getOrNull(2)?.toFloatOrNull() ?: return NativeOffloadResult(2, "$TOOL gesture pinch: missing <cx>\n")
        val cy = args.positional.getOrNull(3)?.toFloatOrNull() ?: return NativeOffloadResult(2, "$TOOL gesture pinch: missing <cy>\n")
        val scale = args.getDouble("scale")?.toFloat() ?: 0.5f
        val duration = args.getLong("duration") ?: 300L
        return if (svc.dispatchPinch(cx, cy, scale, duration))
            ok(args, JSONObject().put("center", JSONArray().put(cx).put(cy)).put("scale", scale))
        else err(args, "GESTURE_FAILED", "pinch cancelled")
    }

    private fun gesturePath(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val pointsStr = args.positional.getOrNull(2)
            ?: return NativeOffloadResult(2, "$TOOL gesture path: missing <points>\n")
        val pts = pointsStr.split(":").map {
            val xy = it.split(","); if (xy.size != 2) return err(args, "INVALID_ARGS", "bad points: $it")
            xy[0].toFloat() to xy[1].toFloat()
        }
        if (pts.size < 2) return err(args, "INVALID_ARGS", "need >= 2 points")
        val path = Path().apply {
            moveTo(pts[0].first, pts[0].second)
            for (i in 1 until pts.size) lineTo(pts[i].first, pts[i].second)
        }
        val duration = args.getLong("duration") ?: (pts.size * 100L)
        return if (svc.dispatchSimpleGesture(path, 0L, duration))
            ok(args, JSONObject().put("points", pts.size))
        else err(args, "GESTURE_FAILED", "path cancelled")
    }

    // ── wait ─────────────────────────────────────────────────────────────

    private fun waitSub(args: OffloadArgs): NativeOffloadResult {
        return when (args.positional.getOrNull(1)) {
            null         -> NativeOffloadResult(2, WAIT_HELP)
            "appear"     -> waitAppear(args, present = true)
            "disappear"  -> waitAppear(args, present = false)
            "stable"     -> waitStable(args)
            "activity"   -> waitActivity(args)
            else         -> NativeOffloadResult(2, "$TOOL wait: unknown action\n$WAIT_HELP")
        }
    }

    private fun waitAppear(args: OffloadArgs, present: Boolean): NativeOffloadResult {
        val svc = svcOrThrow()
        val timeout = args.getLong("timeout") ?: 10_000L
        val deadline = System.currentTimeMillis() + timeout
        val targetText = args.get("text") ?: args.get("text-contains")
        val containsOnly = args.get("text-contains") != null
        val targetId = args.get("id")
        val targetClass = args.get("class")
        while (System.currentTimeMillis() < deadline) {
            for (root in svc.rootNodes()) {
                val matches = ArrayList<AccessibilityNodeInfo>()
                if (targetText != null) findByTextOrDesc(root, targetText, contains = containsOnly, 30, 0, matches)
                if (targetId != null) findByResourceId(root, targetId, 30, 0, matches)
                if (targetClass != null) findByClassName(root, targetClass, 30, 0, matches)
                val found = matches.isNotEmpty()
                if (found == present) {
                    return if (present) ok(args, JSONObject().put("found", true)
                        .put("node", nodeToJson(svc.nodeRegistry, matches.first(), 0, compact = true)))
                    else ok(args, JSONObject().put("disappeared", true))
                }
            }
            Thread.sleep(200)
        }
        return ok(args, JSONObject().put("found", false).put("timedOut", true))
    }

    private fun findByClassName(node: AccessibilityNodeInfo?, cls: String, maxDepth: Int, depth: Int, out: MutableList<AccessibilityNodeInfo>) {
        if (node == null || depth > maxDepth) return
        if (node.className?.toString() == cls) out.add(node)
        for (i in 0 until node.childCount) findByClassName(node.getChild(i), cls, maxDepth, depth + 1, out)
    }

    private fun waitStable(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val interval = args.getLong("interval") ?: 200L
        val stableDuration = args.getLong("duration") ?: 500L
        val timeout = args.getLong("timeout") ?: 8000L
        val start = System.currentTimeMillis()
        val deadline = start + timeout
        var lastSig = treeSignature(svc)
        var stableSince = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(interval)
            val sig = treeSignature(svc)
            if (sig != lastSig) { lastSig = sig; stableSince = System.currentTimeMillis() }
            else if (System.currentTimeMillis() - stableSince >= stableDuration) {
                return ok(args, JSONObject().put("stable", true).put("waitedMs", System.currentTimeMillis() - start))
            }
        }
        return ok(args, JSONObject().put("stable", false).put("timedOut", true))
    }

    private fun treeSignature(svc: MinisAccessibilityService): Int {
        var h = 0
        for (root in svc.rootNodes()) {
            h = h * 31 + (root.text?.hashCode() ?: 0)
            h = h * 31 + root.childCount
            h = h * 31 + root.windowId
        }
        val (pkg, cls) = svc.foregroundPackage()
        h = h * 31 + (pkg?.hashCode() ?: 0)
        h = h * 31 + (cls?.hashCode() ?: 0)
        return h
    }

    private fun waitActivity(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val timeout = args.getLong("timeout") ?: 10_000L
        val pkg = args.get("package")
        val act = args.get("activity")
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            val (p, c) = svc.foregroundPackage()
            val pkgOk = pkg == null || p == pkg
            val actOk = act == null || c == act || (act.startsWith(".") && (c?.endsWith(act) == true))
            if (pkgOk && actOk) return ok(args, JSONObject().put("packageName", p ?: "").put("activityName", c ?: ""))
            Thread.sleep(200)
        }
        return ok(args, JSONObject().put("timedOut", true))
    }

    // ── event / notify / dialog ──────────────────────────────────────────

    private fun eventSub(args: OffloadArgs): NativeOffloadResult {
        return when (args.positional.getOrNull(1)) {
            null    -> NativeOffloadResult(2, EVENT_HELP)
            "watch" -> eventWatch(args, once = false)
            "once"  -> eventWatch(args, once = true)
            else    -> NativeOffloadResult(2, "$TOOL event: unknown action\n$EVENT_HELP")
        }
    }

    private fun eventWatch(args: OffloadArgs, once: Boolean): NativeOffloadResult {
        val svc = svcOrThrow()
        val duration = if (once) (args.getLong("timeout") ?: 30_000L)
                       else (args.getLong("duration") ?: 30_000L)
        val typeFilter = args.get("type")?.split(",")?.map(String::trim)?.toSet()
        val pkgFilter = args.get("package")
        val textContains = args.get("text-contains")
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + duration.coerceAtMost(120_000L)
        val collected = java.util.concurrent.ConcurrentLinkedQueue<MinisAccessibilityService.RecordedEvent>()
        val listener: (MinisAccessibilityService.RecordedEvent) -> Unit = { collected.offer(it) }
        svc.addEventListener(listener)
        try {
            while (System.currentTimeMillis() < deadline) {
                while (true) {
                    val ev = collected.poll() ?: break
                    val typeName = ev.type.lowercase().removePrefix("typeview").removePrefix("type")
                    if (typeFilter != null && typeFilter.none { typeName.contains(it.lowercase()) }) continue
                    if (pkgFilter != null && ev.packageName != pkgFilter) continue
                    if (textContains != null && (ev.text?.contains(textContains) != true)) continue
                    val obj = JSONObject()
                        .put("type", typeName)
                        .put("packageName", ev.packageName ?: "")
                        .put("className", ev.className ?: "")
                        .put("text", ev.text ?: "")
                        .put("timestamp", ev.timestamp)
                    sb.append(obj.toString()).append('\n')
                    if (once) return NativeOffloadResult(0, sb.toString())
                }
                Thread.sleep(50)
            }
        } finally {
            svc.removeEventListener(listener)
        }
        return NativeOffloadResult(0, sb.toString())
    }

    private fun notifySub(args: OffloadArgs): NativeOffloadResult {
        val sub = args.positional.getOrNull(1)
        if (sub != "watch" && sub != "once") {
            return NativeOffloadResult(2, "$TOOL notify: unknown action\n$NOTIFY_HELP")
        }
        // Reuse eventWatch with forced type filter.
        val newArgv = mutableListOf("event", sub)
        args.get("package")?.let { newArgv += listOf("--package", it) }
        args.get("text-contains")?.let { newArgv += listOf("--text-contains", it) }
        args.get("duration")?.let { newArgv += listOf("--duration", it) }
        args.get("timeout")?.let { newArgv += listOf("--timeout", it) }
        newArgv += listOf("--type", "notification_state_changed")
        if (args.hasFlag("compact")) newArgv += "--compact"
        return eventWatch(OffloadArgs(newArgv), once = sub == "once")
    }

    private fun dialogSub(args: OffloadArgs): NativeOffloadResult {
        return when (args.positional.getOrNull(1)) {
            null      -> NativeOffloadResult(2, DIALOG_HELP)
            "detect"  -> dialogDetect(args)
            "dismiss" -> dialogDismiss(args)
            "watch"   -> err(args, "NOT_IMPLEMENTED", "dialog watch: not implemented in v1; poll `dialog detect` instead")
            else      -> NativeOffloadResult(2, "$TOOL dialog: unknown action\n$DIALOG_HELP")
        }
    }

    private fun dialogDetect(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        var dialog: AccessibilityNodeInfo? = null
        for (root in svc.rootNodes()) {
            dialog = findFirstByClassNameContains(root, "Dialog", 30, 0)
            if (dialog != null) break
        }
        if (dialog == null) return ok(args, JSONObject().put("hasDialog", false))
        val buttons = JSONArray()
        collectClickableTexts(dialog, 10, 0, buttons, svc.nodeRegistry)
        val msg = StringBuilder()
        collectText(dialog, 5, 0, msg)
        return ok(args, JSONObject()
            .put("hasDialog", true)
            .put("type", "app_dialog")
            .put("title", "")
            .put("message", msg.toString().trim())
            .put("buttons", buttons))
    }

    private fun findFirstByClassNameContains(node: AccessibilityNodeInfo?, frag: String, maxDepth: Int, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > maxDepth) return null
        if (node.className?.toString()?.contains(frag) == true) return node
        for (i in 0 until node.childCount) findFirstByClassNameContains(node.getChild(i), frag, maxDepth, depth + 1)?.let { return it }
        return null
    }

    private fun collectClickableTexts(node: AccessibilityNodeInfo?, maxDepth: Int, depth: Int, out: JSONArray, registry: NodeRegistry) {
        if (node == null || depth > maxDepth) return
        val t = node.text?.toString()
        if (node.isClickable && !t.isNullOrEmpty()) {
            out.put(JSONObject().put("text", t).put("nodeId", registry.put(node)))
        }
        for (i in 0 until node.childCount) collectClickableTexts(node.getChild(i), maxDepth, depth + 1, out, registry)
    }

    private fun collectText(node: AccessibilityNodeInfo?, maxDepth: Int, depth: Int, out: StringBuilder) {
        if (node == null || depth > maxDepth) return
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.append(it).append(' ') }
        for (i in 0 until node.childCount) collectText(node.getChild(i), maxDepth, depth + 1, out)
    }

    private fun dialogDismiss(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val confirm = args.hasFlag("confirm")
        val deny = args.hasFlag("deny")
        val explicit = args.get("button")
        val candidates = when {
            explicit != null -> listOf(explicit)
            confirm -> listOf("确认", "确定", "允许", "好", "是", "OK", "Allow", "Confirm", "Yes")
            deny    -> listOf("拒绝", "取消", "否", "不允许", "Cancel", "Deny", "No")
            else    -> listOf("取消", "关闭", "否", "Cancel", "Close")
        }
        for (label in candidates) {
            val matches = ArrayList<AccessibilityNodeInfo>()
            for (root in svc.rootNodes()) findByTextOrDesc(root, label, contains = false, 30, 0, matches)
            val btn = matches.firstOrNull { it.isClickable } ?: matches.firstOrNull()
            if (btn != null) {
                val clicked = if (btn.isClickable) btn.performAction(AccessibilityNodeInfo.ACTION_CLICK) else {
                    val r = Rect(); btn.getBoundsInScreen(r)
                    svc.dispatchSimpleGesture(
                        Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()); lineTo(r.exactCenterX() + 0.1f, r.exactCenterY() + 0.1f) },
                        0L, 50L
                    )
                }
                if (clicked) return ok(args, JSONObject().put("dismissed", true).put("button", label))
            }
        }
        return err(args, "NODE_NOT_FOUND", "no matching dismiss button found (looked for: ${candidates.joinToString()})")
    }

    // ── extract ──────────────────────────────────────────────────────────

    private fun extractSub(args: OffloadArgs): NativeOffloadResult {
        return when (args.positional.getOrNull(1)) {
            null    -> NativeOffloadResult(2, EXTRACT_HELP)
            "text"  -> extractText(args)
            "list"  -> extractList(args)
            "table" -> err(args, "NOT_IMPLEMENTED", "extract table: not implemented in v1")
            "form"  -> extractForm(args)
            else    -> NativeOffloadResult(2, "$TOOL extract: unknown action\n$EXTRACT_HELP")
        }
    }

    private fun extractText(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val nodeId = args.get("node")
        val sep = args.get("separator") ?: "\n"
        val roots = if (nodeId != null) listOfNotNull(svc.nodeRegistry.get(nodeId)) else svc.rootNodes()
        if (roots.isEmpty()) return err(args, "NODE_NOT_FOUND", "no roots / node=$nodeId")
        val arr = JSONArray()
        val sb = StringBuilder()
        for (root in roots) collectVisibleText(root, 50, 0, arr, sb, sep)
        return if (args.get("format") == "json")
            ok(args, JSONObject().put("count", arr.length()).put("data", arr))
        else NativeOffloadResult(0, sb.toString())
    }

    private fun collectVisibleText(node: AccessibilityNodeInfo?, maxDepth: Int, depth: Int, arr: JSONArray, sb: StringBuilder, sep: String) {
        if (node == null || depth > maxDepth) return
        if (node.isVisibleToUser) {
            val t = node.text?.toString()
            if (!t.isNullOrBlank()) {
                val r = Rect(); node.getBoundsInScreen(r)
                arr.put(JSONObject().put("text", t).put("bounds", JSONObject()
                    .put("left", r.left).put("top", r.top).put("right", r.right).put("bottom", r.bottom)))
                sb.append(t).append(sep)
            }
        }
        for (i in 0 until node.childCount) collectVisibleText(node.getChild(i), maxDepth, depth + 1, arr, sb, sep)
    }

    private fun extractList(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val nodeId = args.get("node")
        val maxItems = args.getInt("max-items") ?: 100
        val autoScroll = args.hasFlag("auto-scroll")
        val container: AccessibilityNodeInfo = (nodeId?.let { svc.nodeRegistry.get(it) }) ?: run {
            var found: AccessibilityNodeInfo? = null
            for (root in svc.rootNodes()) { found = firstScrollable(root, 30, 0); if (found != null) break }
            found ?: return err(args, "NODE_NOT_FOUND", "no scrollable container; pass --node")
        }
        val items = LinkedHashMap<String, JSONObject>()
        var iter = 0
        do {
            for (i in 0 until container.childCount) {
                val child = container.getChild(i) ?: continue
                val sb = StringBuilder()
                collectText(child, 5, 0, sb)
                val text = sb.toString().trim()
                if (text.isNotEmpty()) {
                    items.putIfAbsent(text, JSONObject().put("text", text).put("nodeId", svc.nodeRegistry.put(child)))
                }
                if (items.size >= maxItems) break
            }
            if (!autoScroll || items.size >= maxItems) break
            val scrolled = container.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            if (!scrolled) break
            Thread.sleep(400)
            iter++
        } while (iter < 30)
        val arr = JSONArray()
        for (v in items.values) arr.put(v)
        return ok(args, JSONObject().put("count", arr.length()).put("data", arr))
    }

    private fun extractForm(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val nodeId = args.get("node")
        val roots = if (nodeId != null) listOfNotNull(svc.nodeRegistry.get(nodeId)) else svc.rootNodes()
        val arr = JSONArray()
        for (root in roots) collectFormFields(root, 30, 0, arr, svc.nodeRegistry)
        return ok(args, JSONObject().put("count", arr.length()).put("data", arr))
    }

    private fun collectFormFields(node: AccessibilityNodeInfo?, maxDepth: Int, depth: Int, arr: JSONArray, registry: NodeRegistry) {
        if (node == null || depth > maxDepth) return
        if (node.isEditable || node.isCheckable) {
            val obj = JSONObject().put("nodeId", registry.put(node))
                .put("type", node.className?.toString() ?: "")
                .put("label", node.contentDescription?.toString() ?: node.hintText?.toString() ?: "")
            if (node.isEditable) obj.put("value", node.text?.toString() ?: "")
            if (node.isCheckable) obj.put("checked", node.isChecked)
            arr.put(obj)
        }
        for (i in 0 until node.childCount) collectFormFields(node.getChild(i), maxDepth, depth + 1, arr, registry)
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private class NotRunning(msg: String) : RuntimeException(msg)

    private fun svcOrThrow(): MinisAccessibilityService =
        MinisAccessibilityService.getInstance()
            ?: throw NotRunning("Accessibility service is not running. Enable Minis under Settings → Accessibility.")

    private fun ok(args: OffloadArgs, data: Any): NativeOffloadResult {
        val body = JSONObject().put("ok", true).put("data", data).toString()
        return NativeOffloadResult(0, OffloadOutput.formatBody(body, args) + "\n")
    }

    private fun err(args: OffloadArgs, code: String, message: String, exit: Int = 1): NativeOffloadResult {
        val body = JSONObject().put("ok", false).put("error",
            JSONObject().put("code", code).put("message", message)).toString()
        return NativeOffloadResult(exit, OffloadOutput.formatBody(body, args) + "\n")
    }

    /**
     * [T-bg-overlay phase 2 fix] Human-readable sub-action status surfaced
     * to the background overlay + FGS notification while the a11y CLI is
     * running. Format mirrors the CLI's own subcommand vocabulary so a
     * developer reading logs sees the same thing the agent ran.
     *
     * Examples:
     *   ui dump           → "a11y_cli: dumping UI tree"
     *   tap by-text Login → "a11y_cli: tap \"Login\""
     *   input set --text X → "a11y_cli: input text"
     *   scroll forward    → "a11y_cli: scroll forward"
     *   event watch       → "a11y_cli: watching events"
     */
    private fun statusForSubAction(sub: String, verb: String?, args: OffloadArgs): String {
        val v = verb ?: ""
        val target = args.positional.getOrNull(2)?.takeIf { it.isNotBlank() }
        return when (sub) {
            "ui" -> when (v) {
                "dump"       -> "a11y_cli: dumping UI tree"
                "find"       -> "a11y_cli: finding ${target ?: "element"}"
                "info"       -> "a11y_cli: reading UI info"
                "node"       -> "a11y_cli: reading node ${target ?: ""}".trim()
                "screenshot" -> "a11y_cli: capturing screenshot"
                else         -> "a11y_cli: ui"
            }
            "tap" -> when (v) {
                "by-text"     -> "a11y_cli: tap \"${target ?: ""}\""
                "by-id"       -> "a11y_cli: tap #${target ?: ""}"
                "by-node"     -> "a11y_cli: tap node ${target ?: ""}"
                "at"          -> "a11y_cli: tap at ($target)"
                else          -> "a11y_cli: tap"
            }
            "input" -> when (v) {
                "set"   -> "a11y_cli: input text"
                "clear" -> "a11y_cli: clearing input"
                else    -> "a11y_cli: input"
            }
            "scroll" -> when (v) {
                "forward", "backward", "up", "down" -> "a11y_cli: scroll $v"
                "to-text"                            -> "a11y_cli: scrolling to \"${target ?: ""}\""
                else                                 -> "a11y_cli: scroll"
            }
            "gesture"  -> "a11y_cli: gesture${v.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""}"
            "wait"     -> "a11y_cli: waiting for ${v.ifBlank { "condition" }}"
            "event"    -> if (v == "watch") "a11y_cli: watching events" else "a11y_cli: event $v"
            "notify"   -> if (v == "watch") "a11y_cli: watching notifications" else "a11y_cli: notify $v"
            "dialog"   -> "a11y_cli: dialog $v"
            "extract"  -> "a11y_cli: extracting ${v.ifBlank { "text" }}"
            "service"  -> "a11y_cli: service $v"
            else       -> "Running: a11y_cli $sub"
        }
    }

}
