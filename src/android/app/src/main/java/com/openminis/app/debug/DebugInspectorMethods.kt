package com.openminis.app.debug

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.Locale

/**
 * Compose-Semantics + View hierarchy introspection for the debug RPC server.
 *
 * Mirrors the iOS DebugViewInspector contract (viewTree / search / inspect /
 * highlight) but takes an explicit shallow approach: Compose nodes come from
 * the public SemanticsNode API rather than a UIKit-style reflection walk.
 * That's enough for test harnesses to find buttons by text and verify
 * geometry, and means we don't need to mirror iOS's class-name strings.
 *
 * Addresses are System.identityHashCode hex strings stamped per snapshot —
 * inspect/highlight need a fresh viewTree (or search) call before they can
 * resolve a node, since hashes don't survive recomposition.
 */
object DebugInspectorMethods {

    // Snapshot of nodes by address, populated at viewTree/search time so
    // subsequent inspect/highlight can resolve back to a live View or
    // SemanticsNode without re-walking the tree. Cleared on each fresh walk.
    private val snapshot = mutableMapOf<String, NodeRef>()

    private sealed class NodeRef {
        class V(val view: WeakReference<View>) : NodeRef()
        class S(val node: SemanticsNode) : NodeRef()
    }

    /**
     * Safe-read for SemanticsConfiguration: contains() + indexed get(),
     * since `getOrNull` is not in the public API of this version.
     */
    private fun <T> SemanticsConfiguration.readOrNull(key: SemanticsPropertyKey<T>): T? {
        return if (this.contains(key)) this[key] else null
    }

    suspend fun viewTree(activity: Activity, maxDepth: Int): JSONArray =
        runOnMain {
            snapshot.clear()
            val result = JSONArray()
            val root = activity.window?.decorView ?: return@runOnMain result
            result.put(buildViewNode(root, depth = 0, maxDepth = maxDepth))
            result
        }

    suspend fun search(activity: Activity, keyword: String, scope: String): JSONArray =
        runOnMain {
            snapshot.clear()
            val matches = JSONArray()
            val root = activity.window?.decorView ?: return@runOnMain matches
            // [T-android-turkish-dotted-i] Keyword matching is a comparison,
            // not display — fold locale-invariantly on BOTH sides so a Turkish/
            // Azeri device default can't strand the dotted/dotless i.
            walkAndMatch(root, keyword.lowercase(Locale.ROOT), scope, matches)
            matches
        }

    suspend fun inspect(activity: Activity, address: String): JSONObject? = runOnMain {
        when (val ref = snapshot[address]) {
            is NodeRef.V -> ref.view.get()?.let { describeView(it, deep = true) }
            is NodeRef.S -> describeSemantics(ref.node, deep = true)
            null -> null
        }
    }

    suspend fun highlight(activity: Activity, address: String, color: String, durationSec: Double): Boolean =
        runOnMain {
            val rect = when (val ref = snapshot[address]) {
                is NodeRef.V -> ref.view.get()?.let { viewBounds(it) }
                is NodeRef.S -> {
                    val r = ref.node.boundsInWindow
                    intArrayOf(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
                }
                null -> null
            } ?: return@runOnMain false
            showOverlay(activity, rect, parseColor(color), (durationSec * 1000).toLong().coerceIn(100L, 10_000L))
            true
        }

    // ── View walk ───────────────────────────────────────────────────────────

    private fun buildViewNode(view: View, depth: Int, maxDepth: Int): JSONObject {
        val node = JSONObject()
        val address = stamp(view)
        node.put("address", address)
        node.put("type", view.javaClass.simpleName)
        node.put("bounds", boundsJson(viewBounds(view)))
        if (view is android.widget.TextView) {
            view.text?.toString()?.takeIf { it.isNotEmpty() }?.let { node.put("text", it) }
        }
        view.contentDescription?.toString()?.takeIf { it.isNotEmpty() }?.let { node.put("description", it) }
        if (isComposeHost(view)) {
            // Append the merged Compose semantics tree (one entry per logical
            // a11y node, much fewer than the underlying View tree).
            semanticsRootOf(view)?.let { root ->
                val sem = buildSemanticsNode(root, depth = depth + 1, maxDepth = maxDepth)
                if (sem != null) {
                    val list = JSONArray().apply { put(sem) }
                    node.put("compose", list)
                }
            }
        }
        if (view is ViewGroup && depth < maxDepth) {
            val children = JSONArray()
            for (i in 0 until view.childCount) {
                children.put(buildViewNode(view.getChildAt(i), depth + 1, maxDepth))
            }
            if (children.length() > 0) node.put("children", children)
        }
        return node
    }

    private fun buildSemanticsNode(node: SemanticsNode, depth: Int, maxDepth: Int): JSONObject? {
        val address = stamp(node)
        val obj = JSONObject()
        obj.put("address", address)
        obj.put("type", "SemanticsNode")
        val text = node.config.readOrNull(SemanticsProperties.Text)
            ?.joinToString(" ") { it.text }
        if (!text.isNullOrEmpty()) obj.put("text", text)
        node.config.readOrNull(SemanticsProperties.ContentDescription)
            ?.joinToString(" ")?.takeIf { it.isNotEmpty() }
            ?.let { obj.put("description", it) }
        node.config.readOrNull(SemanticsProperties.Role)?.toString()
            ?.let { obj.put("role", it) }
        node.config.readOrNull(SemanticsProperties.TestTag)
            ?.let { obj.put("testTag", it) }
        val r = node.boundsInWindow
        obj.put("bounds", boundsJson(intArrayOf(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())))
        if (depth < maxDepth) {
            val kids = JSONArray()
            for (child in node.children) {
                buildSemanticsNode(child, depth + 1, maxDepth)?.let(kids::put)
            }
            if (kids.length() > 0) obj.put("children", kids)
        }
        return obj
    }

    private fun walkAndMatch(view: View, keyword: String, scope: String, out: JSONArray) {
        if (matches(view, keyword, scope)) {
            out.put(describeView(view, deep = false))
        }
        if (isComposeHost(view)) {
            semanticsRootOf(view)?.let { walkSemantics(it, keyword, scope, out) }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                walkAndMatch(view.getChildAt(i), keyword, scope, out)
            }
        }
    }

    private fun walkSemantics(node: SemanticsNode, keyword: String, scope: String, out: JSONArray) {
        if (matchesSemantics(node, keyword, scope)) {
            out.put(describeSemantics(node, deep = false))
        }
        for (child in node.children) walkSemantics(child, keyword, scope, out)
    }

    private fun matches(view: View, kw: String, scope: String): Boolean {
        // Locale.ROOT everywhere: these feed comparison against the folded
        // keyword (see search()), not display text.
        when (scope) {
            "type" -> return view.javaClass.simpleName.lowercase(Locale.ROOT).contains(kw)
            "text" -> {
                if (view is android.widget.TextView) {
                    if (view.text?.toString()?.lowercase(Locale.ROOT)?.contains(kw) == true) return true
                }
                if (view.contentDescription?.toString()?.lowercase(Locale.ROOT)?.contains(kw) == true) return true
                return false
            }
        }
        // "all"
        if (view.javaClass.simpleName.lowercase(Locale.ROOT).contains(kw)) return true
        if (view is android.widget.TextView && view.text?.toString()?.lowercase(Locale.ROOT)?.contains(kw) == true) return true
        if (view.contentDescription?.toString()?.lowercase(Locale.ROOT)?.contains(kw) == true) return true
        return false
    }

    private fun matchesSemantics(node: SemanticsNode, kw: String, scope: String): Boolean {
        val text = node.config.readOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }?.lowercase(Locale.ROOT)
        val desc = node.config.readOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")?.lowercase(Locale.ROOT)
        val tag = node.config.readOrNull(SemanticsProperties.TestTag)?.lowercase(Locale.ROOT)
        val role = node.config.readOrNull(SemanticsProperties.Role)?.toString()?.lowercase(Locale.ROOT)
        return when (scope) {
            "type" -> role?.contains(kw) == true || tag?.contains(kw) == true
            "text" -> text?.contains(kw) == true || desc?.contains(kw) == true
            else -> text?.contains(kw) == true || desc?.contains(kw) == true ||
                tag?.contains(kw) == true || role?.contains(kw) == true
        }
    }

    private fun describeView(view: View, deep: Boolean): JSONObject {
        val obj = JSONObject()
        obj.put("address", stamp(view))
        obj.put("type", view.javaClass.simpleName)
        obj.put("bounds", boundsJson(viewBounds(view)))
        if (view is android.widget.TextView) {
            view.text?.toString()?.takeIf { it.isNotEmpty() }?.let { obj.put("text", it) }
        }
        view.contentDescription?.toString()?.takeIf { it.isNotEmpty() }?.let { obj.put("description", it) }
        obj.put("visible", view.visibility == View.VISIBLE)
        obj.put("enabled", view.isEnabled)
        if (deep) {
            obj.put("alpha", view.alpha.toDouble())
            obj.put("class", view.javaClass.name)
            view.id.takeIf { it != View.NO_ID }?.let {
                obj.put("idHex", String.format("0x%08x", it))
                runCatching { obj.put("idName", view.resources.getResourceEntryName(it)) }
            }
        }
        return obj
    }

    private fun describeSemantics(node: SemanticsNode, deep: Boolean): JSONObject {
        val obj = JSONObject()
        obj.put("address", stamp(node))
        obj.put("type", "SemanticsNode")
        val text = node.config.readOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
        if (!text.isNullOrEmpty()) obj.put("text", text)
        node.config.readOrNull(SemanticsProperties.ContentDescription)
            ?.joinToString(" ")?.takeIf { it.isNotEmpty() }
            ?.let { obj.put("description", it) }
        node.config.readOrNull(SemanticsProperties.Role)?.toString()?.let { obj.put("role", it) }
        node.config.readOrNull(SemanticsProperties.TestTag)?.let { obj.put("testTag", it) }
        val r = node.boundsInWindow
        obj.put("bounds", boundsJson(intArrayOf(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())))
        if (deep) {
            val actions = JSONArray()
            // Common actions surface as keys we can probe explicitly.
            if (node.config.contains(SemanticsActions.OnClick)) actions.put("onClick")
            if (node.config.contains(SemanticsActions.OnLongClick)) actions.put("onLongClick")
            if (node.config.contains(SemanticsActions.SetText)) actions.put("setText")
            if (node.config.contains(SemanticsActions.ScrollBy)) actions.put("scrollBy")
            if (actions.length() > 0) obj.put("actions", actions)
            node.config.readOrNull(SemanticsProperties.EditableText)?.text?.takeIf { it.isNotEmpty() }
                ?.let { obj.put("editableText", it) }
            node.config.readOrNull(SemanticsProperties.StateDescription)?.let { obj.put("state", it) }
        }
        return obj
    }

    // ── Compose Semantics access ────────────────────────────────────────────

    /** True for AbstractComposeView / AndroidComposeView instances. */
    private fun isComposeHost(view: View): Boolean {
        var cls: Class<*>? = view.javaClass
        while (cls != null) {
            val name = cls.name
            if (name == "androidx.compose.ui.platform.AbstractComposeView" ||
                name == "androidx.compose.ui.platform.AndroidComposeView") return true
            cls = cls.superclass
        }
        return false
    }

    /** Walk up to find the AndroidComposeView and reflect into its semanticsOwner. */
    private fun semanticsRootOf(host: View): SemanticsNode? {
        // AbstractComposeView wraps AndroidComposeView as its only child.
        val owner = findAndroidComposeViewSemanticsOwner(host) ?: return null
        return runCatching {
            // SemanticsOwner has unmergedRootSemanticsNode (kotlin property).
            val m = owner.javaClass.getMethod("getUnmergedRootSemanticsNode")
            m.invoke(owner) as? SemanticsNode
        }.getOrNull()
    }

    private fun findAndroidComposeViewSemanticsOwner(view: View): Any? {
        // Direct AndroidComposeView
        if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") {
            return runCatching {
                val m = view.javaClass.getMethod("getSemanticsOwner")
                m.invoke(view)
            }.getOrNull()
        }
        // AbstractComposeView with a single AndroidComposeView child
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findAndroidComposeViewSemanticsOwner(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    // ── Address bookkeeping ─────────────────────────────────────────────────

    private fun stamp(view: View): String {
        val a = "0x" + Integer.toHexString(System.identityHashCode(view))
        snapshot[a] = NodeRef.V(WeakReference(view))
        return a
    }

    private fun stamp(node: SemanticsNode): String {
        val a = "0x" + Integer.toHexString(System.identityHashCode(node))
        snapshot[a] = NodeRef.S(node)
        return a
    }

    private fun viewBounds(view: View): IntArray {
        val pos = IntArray(2)
        view.getLocationOnScreen(pos)
        return intArrayOf(pos[0], pos[1], pos[0] + view.width, pos[1] + view.height)
    }

    private fun boundsJson(rect: IntArray): JSONObject {
        return JSONObject()
            .put("left", rect[0]).put("top", rect[1])
            .put("right", rect[2]).put("bottom", rect[3])
            .put("width", rect[2] - rect[0]).put("height", rect[3] - rect[1])
    }

    // ── Highlight overlay ───────────────────────────────────────────────────

    private fun showOverlay(activity: Activity, rect: IntArray, color: Int, durationMs: Long) {
        val root = activity.window.decorView as? ViewGroup ?: return
        // Convert screen coords to decorView-relative.
        val decorPos = IntArray(2)
        root.getLocationOnScreen(decorPos)
        val left = (rect[0] - decorPos[0]).coerceAtLeast(0)
        val top = (rect[1] - decorPos[1]).coerceAtLeast(0)
        val width = (rect[2] - rect[0]).coerceAtLeast(1)
        val height = (rect[3] - rect[1]).coerceAtLeast(1)

        val overlay = View(activity).apply {
            background = GradientDrawable().apply {
                setStroke(6, color)
                setColor(color and 0x00FFFFFF or 0x33000000.toInt())
            }
        }
        val lp = FrameLayout.LayoutParams(width, height).apply {
            this.leftMargin = left
            this.topMargin = top
            gravity = Gravity.START or Gravity.TOP
        }
        root.addView(overlay, lp)
        Handler(Looper.getMainLooper()).postDelayed({
            (overlay.parent as? ViewGroup)?.removeView(overlay)
        }, durationMs)
    }

    // RPC token → ARGB mapping: compare locale-invariantly (protocol token).
    private fun parseColor(name: String): Int = when (name.lowercase(Locale.ROOT)) {
        "red" -> Color.RED
        "green" -> Color.GREEN
        "blue" -> Color.BLUE
        "yellow" -> Color.YELLOW
        "purple" -> Color.parseColor("#8E24AA")
        "orange" -> Color.parseColor("#FF9800")
        else -> runCatching { Color.parseColor(name) }.getOrDefault(Color.RED)
    }

    private suspend fun <T> runOnMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { block() }
    }
}
