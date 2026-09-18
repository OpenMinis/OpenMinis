package com.openminis.app.service

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.openminis.app.MinisApp
import com.openminis.app.R
import kotlin.math.abs

/**
 * T-bg-overlay phase 2: floating tool-status overlay.
 *
 * Adds a small draggable capsule via [WindowManager] using
 * TYPE_APPLICATION_OVERLAY (requires SYSTEM_ALERT_WINDOW). The capsule
 * shows the Minis launcher logo, the running tool's label, and a short
 * status — same data the FGS notification reads
 * (`SessionActivityTracker.currentToolName` / `currentToolStatus`).
 *
 * T-bg-overlay-polish: the per-tool icon was replaced with the Minis
 * launcher icon clipped to a circle, a thin stroke ring rotates around
 * the logo while a tool is running, and a small success/error glyph
 * sits next to the tool label once a tool finishes.
 *
 * Owned by [AgentForegroundService]; created on service start, destroyed
 * on service stop. Visibility is driven by the service's collector:
 *
 *   - Minis foreground OR no tool running OR user toggle OFF OR no perm → hidden
 *   - otherwise → shown, content updated on each tool-status change
 *
 * Position persists in [com.openminis.app.data.repository.BackgroundSettingsRepository]
 * across process restarts; first run defaults to the bottom-left corner
 * (10 dp from each edge). Tap dismisses to bring Minis back to the
 * foreground; drag moves the capsule.
 */
class ToolOverlayController(private val context: Context) {

    companion object {
        private const val TAG = "ToolOverlayController"
        private const val DRAG_SLOP_DP = 8f
        private const val LOGO_SIZE_DP = 26
        // [T-android-overlay-ring-tight] Ring view matches the logo
        // box exactly (inset = 0) so the rotating stroke hugs the
        // logo's visible edge. Earlier inset of 2dp produced a visible
        // gap between the ring and the launcher disc on small overlay
        // sizes; sitting at the edge eliminates that gap while still
        // keeping the stroke inside the logo bounding box (stroke is
        // centered, so half of it sits inside the box edge).
        private const val RING_INSET_DP = 0
        private const val RING_STROKE_DP = 1
        private const val STATUS_ICON_SIZE_DP = 12
        private const val EDGE_PADDING_DP = 10
        // [T-android-overlay-dimensions-stable] Pin capsule height so
        // it doesn't jitter across states (spinner / completed / reply
        // / error). 56dp comfortably covers the 26dp logo + 6dp top/bot
        // padding + a two-row text column (12sp label + 10sp status or
        // reply, both single-line). Reply excerpt is single-line w/
        // ellipsize so it never grows the row.
        private const val CAPSULE_HEIGHT_DP = 44
        // [T-android-overlay-fixed-half-width] User-facing requirement:
        // the capsule must be a fixed half-screen-wide pill — no jitter
        // as content cycles through spinner / completed / reply / error.
        // 0.50 of displayMetrics.widthPixels, floored at
        // CAPSULE_WIDTH_FLOOR_DP for tiny screens. Applied to the
        // WindowManager.LayoutParams.width AND to the text-row maxWidth
        // so labels ellipsize within the same envelope instead of
        // pushing the box outward.
        private const val CAPSULE_WIDTH_FRACTION = 0.50f
        private const val CAPSULE_WIDTH_FLOOR_DP = 180
        // [T-android-overlay-landscape-width-rotation-drift] Upper bound on
        // the capsule width. In landscape `widthPixels` is large, so the
        // 0.50 fraction alone produced a pill that visually spanned most of
        // the screen. Cap at min(screenWidth*0.70, 400dp): the 400dp ceiling
        // keeps the pill compact on wide/landscape displays, while the 0.70
        // factor still lets very narrow screens use a sensible share.
        private const val CAPSULE_WIDTH_CAP_FRACTION = 0.70f
        private const val CAPSULE_WIDTH_CAP_DP = 400
        private val RING_COLOR = Color.argb(230, 120, 200, 255)
        private val SUCCESS_COLOR = Color.parseColor("#4CAF50")
        private val FAILURE_COLOR = Color.parseColor("#E53935")
    }

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundRepo =
        (context.applicationContext as MinisApp).backgroundSettingsRepository

    private var view: View? = null
    private var logoView: ImageView? = null
    private var ringView: RotatingRingView? = null
    private var ringAnimator: ObjectAnimator? = null
    private var labelView: TextView? = null
    private var statusView: TextView? = null
    private var replyView: TextView? = null
    private var statusIconView: StatusGlyphView? = null
    private var closeView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    // [T-android-overlay-reply-status-34599] Session ID associated with
    // the current overlay capsule. The whole-capsule tap builds a
    // `minis://session/<id>` deep-link to land back in the right chat;
    // null falls through to "just bring MainActivity forward" so we
    // never strand the user when no session id was published.
    private var pendingSessionId: String? = null
    /**
     * [T-android-overlay-reply-status-34599] Called when the user
     * dismisses the overlay (X button or tap-to-open). Lets the
     * service-level observer wipe the lingered completion state so the
     * AND-gate flips `shouldShow` to false on the next emission. Wired
     * by [AgentForegroundService] right after construction.
     */
    var onDismissByUser: (() -> Unit)? = null

    @Volatile
    var isShown: Boolean = false
        private set

    fun hasOverlayPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    /**
     * Show or update the overlay. Safe to call repeatedly — content
     * updates if already shown, no-ops on permission denial.
     *
     * @param isRunning true while the tool is actively executing
     *        (drives the rotating ring + hides the success/error glyph).
     * @param outcome typed outcome of the most recently finished tool.
     *        Only consulted when [isRunning] = false:
     *          - Success → green ✓
     *          - Error / Timeout → red ✗
     *          - Cancelled / Unknown → glyph hidden (we don't have a
     *            confident signal to render).
     *        [T-overlay-glyph-typed-outcome] Replaces the previous
     *        [looksLikeFailure] text heuristic which always rendered
     *        green because it scanned the stale "Running: foo" status.
     */
    fun show(
        toolName: String?,
        statusText: String,
        isRunning: Boolean = true,
        outcome: ToolOutcome = ToolOutcome.Unknown,
        replyExcerpt: String? = null,
        targetSessionId: String? = null,
        toolTitle: String? = null,
    ) {
        Log.d(
            TAG,
            "show() toolName=$toolName toolTitle=${toolTitle?.take(40)} " +
                "status=${statusText.take(40)} " +
                "running=$isRunning outcome=$outcome reply=${replyExcerpt?.take(20)} " +
                "session=$targetSessionId perm=${hasOverlayPermission()}",
        )
        if (!hasOverlayPermission()) {
            if (isShown) hide()
            return
        }
        mainHandler.post {
            try {
                pendingSessionId = targetSessionId
                if (view == null) {
                    Log.d(TAG, "show() attach() — view was null")
                    attach()
                }
                updateContent(toolName, statusText, isRunning, outcome, replyExcerpt, toolTitle)
            } catch (e: Throwable) {
                Log.w(TAG, "show failed: ${e.message}", e)
            }
        }
    }

    fun hide() {
        mainHandler.post {
            val v = view ?: return@post
            try {
                ringAnimator?.cancel()
                ringAnimator = null
                windowManager.removeView(v)
            } catch (e: Throwable) {
                Log.w(TAG, "removeView failed: ${e.message}")
            }
            view = null
            logoView = null
            ringView = null
            labelView = null
            statusView = null
            replyView = null
            statusIconView = null
            closeView = null
            layoutParams = null
            isShown = false
        }
    }

    private fun attach() {
        val container = buildView()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            // [T-android-overlay-fixed-half-width] Pin width to half the
            // screen (floored on tiny screens) so the capsule no longer
            // expands/shrinks with content. Previously WRAP_CONTENT,
            // which let the box jitter as labels swapped between
            // states. Text rows inside ellipsize within this envelope —
            // see updateContent maxWidth assignment.
            fixedCapsuleWidthPx(),
            // [T-android-overlay-dimensions-stable] Fixed height so the
            // capsule doesn't jitter as content cycles through spinner /
            // completed / reply / error states.
            dpToPx(CAPSULE_HEIGHT_DP),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val savedX = backgroundRepo.getOverlayX()
            val savedY = backgroundRepo.getOverlayY()
            if (savedX >= 0 && savedY >= 0) {
                x = savedX
                y = savedY
            } else {
                // [T-bg-overlay-polish] First-paint default: bottom-left,
                // 10 dp from the left edge and 10 dp above the nav-bar
                // region. We don't have an accurate nav-bar height before
                // attach, so use displayMetrics.heightPixels and trust
                // FLAG_LAYOUT_NO_LIMITS to keep us on-screen.
                val metrics = context.resources.displayMetrics
                val padding = dpToPx(EDGE_PADDING_DP)
                val approxOverlayHeight = dpToPx(LOGO_SIZE_DP + 16)
                val approxNavBar = dpToPx(48)
                x = padding
                y = metrics.heightPixels - approxOverlayHeight - approxNavBar - padding
            }
        }
        layoutParams = params
        attachTouchListener(container, params)
        windowManager.addView(container, params)
        view = container
        isShown = true
    }

    private fun buildView(): View {
        val density = context.resources.displayMetrics.density
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            // Right padding is larger than the symmetric 6dp baseline so
            // the trailing X close button clears the pill's rounded corner.
            // With CAPSULE_HEIGHT_DP=44 the corner radius is 22dp; a 6dp
            // right inset put the X glyph's top/bottom corners behind the
            // curve. ~14dp gives the 14dp glyph full clearance.
            setPadding(dpToPx(6), dpToPx(6), dpToPx(14), dpToPx(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                // [T-android-overlay-pill-shape] Corner radius tracks
                // half the capsule height so the shape stays a true pill
                // regardless of CAPSULE_HEIGHT_DP tuning.
                cornerRadius = (CAPSULE_HEIGHT_DP / 2f) * density
                setColor(Color.argb(230, 28, 28, 30))
                setStroke(dpToPx(1), Color.argb(40, 255, 255, 255))
            }
            elevation = 8f * density
            gravity = Gravity.CENTER_VERTICAL
            // [T-android-overlay-fixed-half-width] The capsule width is
            // now pinned at the WindowManager layer (see attach() →
            // params.width = fixedCapsuleWidthPx), so this LinearLayout
            // measures against the parent's fixed-size envelope rather
            // than its own intrinsic content. Keeping a small
            // minimumWidth as a defensive floor in case a future
            // refactor returns the WM container to WRAP_CONTENT.
            minimumWidth = dpToPx(120)
        }

        // [T-bg-overlay-polish] Logo + rotating ring stacked in a
        // FrameLayout. The logo is clipped to a circle via
        // ViewOutlineProvider; the ring is a custom View drawing a thin
        // arc that we rotate with an ObjectAnimator.
        val logoSize = dpToPx(LOGO_SIZE_DP)
        val ringInset = dpToPx(RING_INSET_DP)
        // Ring view matches the logo box; with a centered stroke this
        // puts the ring perimeter right at the logo's visible edge so
        // the spinner appears to hug the icon rather than float inside.
        val ringTotal = (logoSize - ringInset * 2).coerceAtLeast(dpToPx(8))
        val logoStack = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(logoSize, logoSize).apply {
                gravity = Gravity.CENTER_VERTICAL
                rightMargin = dpToPx(6)
            }
        }

        val logo = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(logoSize, logoSize).apply {
                gravity = Gravity.CENTER
            }
            setImageResource(R.mipmap.ic_launcher)
            scaleType = ImageView.ScaleType.CENTER_CROP
            // Clip to a circle. `clipToOutline = true` + a circular
            // outline provider is the standard API-21+ recipe and avoids
            // having to allocate a BitmapShader for the launcher.
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    outline.setOval(0, 0, v.width, v.height)
                }
            }
            clipToOutline = true
        }
        logoView = logo
        logoStack.addView(logo)

        val ring = RotatingRingView(
            context,
            strokeWidthPx = dpToPx(RING_STROKE_DP).toFloat(),
            ringColor = RING_COLOR,
        ).apply {
            layoutParams = FrameLayout.LayoutParams(ringTotal, ringTotal).apply {
                gravity = Gravity.CENTER
            }
            visibility = View.GONE
        }
        ringView = ring
        logoStack.addView(ring)

        container.addView(logoStack)

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // weight=1 + width=0 makes this column eat all horizontal
            // space between the logo and the trailing X button. Without
            // this, short labels (e.g. "Done") let the column shrink
            // and the X is dragged inward; weight pins X to the right
            // edge regardless of text length.
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply { gravity = Gravity.CENTER_VERTICAL }
        }

        val labelRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val label = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            text = ""
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        labelView = label
        labelRow.addView(label)

        val statusIcon = StatusGlyphView(context).apply {
            val s = dpToPx(STATUS_ICON_SIZE_DP)
            layoutParams = LinearLayout.LayoutParams(s, s).apply {
                leftMargin = dpToPx(4)
                gravity = Gravity.CENTER_VERTICAL
            }
            visibility = View.GONE
        }
        statusIconView = statusIcon
        labelRow.addView(statusIcon)

        textCol.addView(labelRow)

        val status = TextView(context).apply {
            setTextColor(Color.argb(200, 220, 220, 220))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            text = ""
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        statusView = status
        textCol.addView(status)

        // [T-android-overlay-reply-status-34599] Third row: the last
        // assistant reply excerpt. Hidden by default — only made
        // visible when the agent loop has completed and we have a
        // non-blank excerpt to show. Color is a touch softer than the
        // status row so the two rows are visually distinguishable.
        val reply = TextView(context).apply {
            setTextColor(Color.argb(235, 200, 220, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            text = ""
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        replyView = reply
        textCol.addView(reply)

        container.addView(textCol)

        // [T-android-overlay-reply-status-34599] Trailing X button to
        // let the user dismiss the overlay without having to wait for
        // a foreground transition. Sized small so it doesn't dominate
        // the capsule; tap area widened by padding. Lives outside the
        // drag listener (separate setOnClickListener) so tapping it
        // doesn't initiate a drag.
        val closeBtn = CloseGlyphView(context).apply {
            val s = dpToPx(14)
            layoutParams = LinearLayout.LayoutParams(s, s).apply {
                leftMargin = dpToPx(10)
                gravity = Gravity.CENTER_VERTICAL
            }
            setOnClickListener { onUserDismiss() }
            contentDescription = context.getString(R.string.overlay_dismiss)
        }
        closeView = closeBtn
        container.addView(closeBtn)

        return container
    }

    private fun updateContent(
        toolName: String?,
        statusText: String,
        isRunning: Boolean,
        outcome: ToolOutcome,
        replyExcerpt: String?,
        toolTitle: String?,
    ) {
        // [T-android-overlay-tool-title] Prefer model-supplied tool_title
        // (e.g. "Open Baidu home page", "Take screenshot of current page") over the
        // static per-tool label ("Browser"/"Shell"). Falls back to the
        // per-tool label when title is null/blank — preserves prior look
        // when the model didn't supply a title.
        // [T-android-overlay-no-idle] After a tool completes, SessionActivityTracker
        // resets currentToolStatus to "Idle" and toolName to null — flowing that
        // through verbatim would render a noisy "Minis / Idle" capsule that
        // tells the user nothing. When not running and we have neither a
        // toolTitle nor a real toolName, hide both the label and the status
        // row so only the reply excerpt (if any) plus glyph remain.
        val hasLabelText = !toolTitle.isNullOrBlank() || toolName != null
        val showStatusRow = isRunning || (hasLabelText && !statusText.equals("Idle", ignoreCase = true))

        // [T-android-overlay-finalize item 3] Post-completion fallback so
        // the capsule is never just a bare status glyph. Precedence (when
        // !isRunning):
        //   1. reply excerpt available → handled in the reply row below;
        //      label still shows toolTitle/toolName for context.
        //   2. toolTitle present, no reply → label "$toolTitle <outcome>"
        //   3. nothing → label shows generic localized outcome word.
        val hasReplyExcerpt = !isRunning && !replyExcerpt.isNullOrBlank()
        val completionWord: String? = if (!isRunning) when (outcome) {
            ToolOutcome.Success -> context.getString(R.string.overlay_completion_completed)
            ToolOutcome.Error -> context.getString(R.string.overlay_completion_failed)
            ToolOutcome.Timeout -> context.getString(R.string.overlay_completion_timeout)
            ToolOutcome.Cancelled -> context.getString(R.string.overlay_completion_cancelled)
            ToolOutcome.Unknown -> null
        } else null

        labelView?.let { lv ->
            val labelText: String? = when {
                hasLabelText && isRunning ->
                    if (!toolTitle.isNullOrBlank()) toolTitle else toolDisplayLabel(toolName)
                hasLabelText && hasReplyExcerpt ->
                    if (!toolTitle.isNullOrBlank()) toolTitle else toolDisplayLabel(toolName)
                hasLabelText && completionWord != null -> {
                    val head = if (!toolTitle.isNullOrBlank()) toolTitle else toolDisplayLabel(toolName)
                    "$head — $completionWord"
                }
                hasLabelText -> if (!toolTitle.isNullOrBlank()) toolTitle else toolDisplayLabel(toolName)
                // No label-source AND no reply → still show *something* text-shaped.
                !hasReplyExcerpt && completionWord != null -> completionWord
                else -> null
            }
            if (labelText != null) {
                lv.text = labelText
                lv.visibility = View.VISIBLE
            } else {
                lv.text = ""
                lv.visibility = View.GONE
            }
        }
        statusView?.let { sv ->
            if (showStatusRow) {
                sv.text = statusText
                sv.visibility = View.VISIBLE
            } else {
                sv.text = ""
                sv.visibility = View.GONE
            }
        }
        // [T-android-overlay-fixed-half-width] maxWidth tracks the same
        // fixed half-screen envelope as the WM container, minus the
        // logo + paddings + trailing close button so text ellipsizes
        // before it can push the (now-fixed-width) box. Approx subtract:
        // 6+26+6 (left pad + logo + logo-right-margin) + 14+10 (close +
        // close-left-margin) + 14 (right pad clearing the pill corner) ≈ 76dp.
        val maxW = (fixedCapsuleWidthPx() - dpToPx(76)).coerceAtLeast(dpToPx(80))
        labelView?.maxWidth = maxW
        statusView?.maxWidth = maxW
        replyView?.maxWidth = maxW

        // [T-android-overlay-reply-status-34599] Reply excerpt visible
        // only when the run is no longer streaming AND we have non-blank
        // text. While `isRunning=true` the row stays hidden so the
        // capsule isn't taller than necessary during an active tool.
        val replyTv = replyView
        if (replyTv != null) {
            if (!isRunning && !replyExcerpt.isNullOrBlank()) {
                replyTv.text = replyExcerpt
                replyTv.visibility = View.VISIBLE
            } else {
                replyTv.visibility = View.GONE
            }
        }

        // [T-android-overlay-reply-status-34599] The X close button is
        // useful only once the user has something to dismiss — while a
        // tool is mid-stream the overlay self-tears-down when the tool
        // finishes (no completion state yet to linger on), and the X
        // shouldn't double as a "cancel the run" affordance.
        closeView?.visibility = if (isRunning) View.GONE else View.VISIBLE

        val ring = ringView
        if (ring != null) {
            if (isRunning) {
                if (ring.visibility != View.VISIBLE) ring.visibility = View.VISIBLE
                if (ringAnimator?.isStarted != true) startRingAnimation(ring)
            } else {
                if (ring.visibility != View.GONE) ring.visibility = View.GONE
                ringAnimator?.cancel()
                ringAnimator = null
            }
        }

        val glyph = statusIconView
        if (glyph != null) {
            // [T-overlay-glyph-typed-outcome] Render the glyph from the
            // typed outcome rather than scanning the (stale) status
            // string. Cancelled / Unknown hide the glyph entirely so we
            // don't render a guess.
            if (isRunning) {
                glyph.visibility = View.GONE
            } else {
                when (outcome) {
                    ToolOutcome.Success -> {
                        glyph.setState(StatusGlyphView.State.Success, SUCCESS_COLOR)
                        glyph.visibility = View.VISIBLE
                    }
                    ToolOutcome.Error, ToolOutcome.Timeout -> {
                        glyph.setState(StatusGlyphView.State.Error, FAILURE_COLOR)
                        glyph.visibility = View.VISIBLE
                    }
                    ToolOutcome.Cancelled, ToolOutcome.Unknown -> {
                        glyph.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun startRingAnimation(target: View) {
        ringAnimator?.cancel()
        ringAnimator = ObjectAnimator.ofFloat(target, View.ROTATION, 0f, 360f).apply {
            duration = 1100L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = android.view.animation.LinearInterpolator()
            start()
        }
    }

    private fun attachTouchListener(target: View, params: WindowManager.LayoutParams) {
        val slopPx = (DRAG_SLOP_DP * context.resources.displayMetrics.density).toInt()
        var downX = 0f
        var downY = 0f
        var startParamX = 0
        var startParamY = 0
        var dragging = false
        target.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startParamX = params.x
                    startParamY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downX).toInt()
                    val dy = (ev.rawY - downY).toInt()
                    if (!dragging && (abs(dx) > slopPx || abs(dy) > slopPx)) dragging = true
                    if (dragging) {
                        params.x = startParamX + dx
                        params.y = startParamY + dy
                        try {
                            windowManager.updateViewLayout(target, params)
                        } catch (e: Throwable) {
                            Log.w(TAG, "updateViewLayout failed: ${e.message}")
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        backgroundRepo.setOverlayPosition(params.x, params.y)
                    } else {
                        onTap()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun onTap() {
        try {
            val sid = pendingSessionId
            val launchIntent = Intent(
                context,
                Class.forName("com.openminis.app.MainActivity"),
            ).apply {
                // [T-android-overlay-reply-status-34599] When we have a
                // tracked session, route the tap through the existing
                // `minis://session/<id>` deep-link so MainActivity's
                // DeepLinkHandler navigates to that chat. When sid is
                // null (e.g. completion observed before any session was
                // pushed), fall back to plain "bring to front".
                if (!sid.isNullOrBlank()) {
                    data = Uri.parse("minis://session/$sid")
                }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(launchIntent)
        } catch (e: Throwable) {
            Log.w(TAG, "tap-to-foreground failed: ${e.message}")
        }
        onUserDismiss()
    }

    /**
     * [T-android-overlay-reply-status-34599] Shared dismissal path used
     * by both the X button and the tap-to-open-chat tap. Tears down
     * the window immediately for snappy feedback, then nudges the
     * service-level observer to clear lingered completion state so
     * `shouldShow` flips to false on the next emission and we don't
     * accidentally re-show during a future tool turn that has the
     * same outcome.
     */
    private fun onUserDismiss() {
        try { onDismissByUser?.invoke() } catch (_: Throwable) {}
        hide()
    }

    /**
     * [T-android-overlay-fixed-half-width] Resolve the fixed capsule
     * width: half of the current display width, floored at
     * CAPSULE_WIDTH_FLOOR_DP. Read live each time so a config change
     * (orientation, multi-window resize) picks up the new metrics on
     * the next attach. Existing attached views keep their original
     * width — orientation change recreates the overlay anyway.
     */
    private fun fixedCapsuleWidthPx(): Int {
        val dm = context.resources.displayMetrics
        // [T-android-overlay-landscape-width-rotation-drift] Cap the width at
        // min(screenWidth*0.70, 400dp) so landscape no longer stretches the
        // pill across the screen, then floor for tiny screens.
        val cap = minOf(
            (dm.widthPixels * CAPSULE_WIDTH_CAP_FRACTION).toInt(),
            dpToPx(CAPSULE_WIDTH_CAP_DP),
        )
        return (dm.widthPixels * CAPSULE_WIDTH_FRACTION).toInt()
            .coerceAtMost(cap)
            .coerceAtLeast(dpToPx(CAPSULE_WIDTH_FLOOR_DP))
    }

    /**
     * [T-android-overlay-landscape-width-rotation-drift] Re-clamp the
     * attached capsule into the current screen bounds after a
     * configuration change (orientation flip, multi-window resize). The
     * overlay is a WindowManager view — it is NOT recreated on rotation —
     * so a position saved while in landscape can land off-screen (or fully
     * out of view) once the device returns to portrait. Recompute the
     * width for the new metrics, clamp x/y so the capsule stays fully
     * on-screen, push the new layout, and persist the corrected position.
     *
     * No-op when nothing is attached. Owned by [AgentForegroundService],
     * which forwards its own onConfigurationChanged here.
     */
    fun onConfigurationChanged() {
        mainHandler.post {
            val v = view ?: return@post
            val params = layoutParams ?: return@post
            val dm = context.resources.displayMetrics
            val width = fixedCapsuleWidthPx()
            val height = dpToPx(CAPSULE_HEIGHT_DP)
            val maxX = (dm.widthPixels - width).coerceAtLeast(0)
            val maxY = (dm.heightPixels - height).coerceAtLeast(0)
            val clampedX = params.x.coerceIn(0, maxX)
            val clampedY = params.y.coerceIn(0, maxY)
            if (params.width == width && params.x == clampedX && params.y == clampedY) return@post
            params.width = width
            params.x = clampedX
            params.y = clampedY
            try {
                windowManager.updateViewLayout(v, params)
                backgroundRepo.setOverlayPosition(clampedX, clampedY)
            } catch (e: Throwable) {
                Log.w(TAG, "onConfigurationChanged relayout failed: ${e.message}")
            }
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * context.resources.displayMetrics.density + 0.5f).toInt()

    /**
     * Mirrors [AgentForegroundService.toolDisplayLabel] but trims the
     * "Minis is using " prefix — the overlay capsule is tight, so we just
     * show the tool kind ("Shell", "Browser", …).
     */
    private fun toolDisplayLabel(toolName: String?): String = when (toolName) {
        null -> "Minis"
        "shell_execute" -> "Shell"
        "task_output" -> "Task"
        "file_read" -> "File"
        "file_write" -> "Editor"
        "file_edit" -> "Edit"
        "browser_use" -> "Browser"
        "read_image" -> "Image"
        "memory_write", "memory_get" -> "Memory"
        "web_search" -> "Search"
        else -> toolName
    }

    /**
     * Custom view drawing a single thin arc segment that spans ~180° of
     * the ring (a half circle). Rotating the view itself (via
     * ObjectAnimator on View.ROTATION) gives the spinning effect without
     * per-frame invalidation.
     */
    private class RotatingRingView(
        context: Context,
        private val strokeWidthPx: Float,
        ringColor: Int,
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = ringColor
            strokeWidth = strokeWidthPx
        }
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            val inset = strokeWidthPx / 2f
            rect.set(inset, inset, width - inset, height - inset)
            // Draw a 180° arc (half circle) starting at the top. The
            // 180° gap is what makes the spinning visually obvious.
            canvas.drawArc(rect, -90f, 180f, false, paint)
        }
    }

    /**
     * [T-android-overlay-reply-status-34599] Tiny close-X glyph used
     * as the manual-dismiss button at the right edge of the capsule.
     * Drawn via Path for parity with [StatusGlyphView] (no new vector
     * drawable resource needed) and uses a lighter color so the user
     * reads it as a chrome control rather than a status indicator.
     */
    private class CloseGlyphView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = Color.argb(220, 200, 200, 200)
        }
        private val path = Path()
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            paint.strokeWidth = (w * 0.14f).coerceAtLeast(2f)
            path.reset()
            path.moveTo(w * 0.25f, h * 0.25f)
            path.lineTo(w * 0.75f, h * 0.75f)
            path.moveTo(w * 0.75f, h * 0.25f)
            path.lineTo(w * 0.25f, h * 0.75f)
            canvas.drawPath(path, paint)
        }
    }

    /**
     * Lightweight ✓ / ✗ glyph rendered via Path so we don't need to
     * ship a new vector drawable resource. Color is provided by
     * [setState] so callers can swap success/failure tinting.
     */
    private class StatusGlyphView(context: Context) : View(context) {

        enum class State { Success, Error }

        private var state: State = State.Success
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.parseColor("#4CAF50")
        }
        private val path = Path()

        fun setState(newState: State, color: Int) {
            state = newState
            paint.color = color
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            paint.strokeWidth = (w * 0.16f).coerceAtLeast(2f)
            path.reset()
            when (state) {
                State.Success -> {
                    path.moveTo(w * 0.18f, h * 0.52f)
                    path.lineTo(w * 0.42f, h * 0.74f)
                    path.lineTo(w * 0.82f, h * 0.30f)
                }
                State.Error -> {
                    path.moveTo(w * 0.22f, h * 0.22f)
                    path.lineTo(w * 0.78f, h * 0.78f)
                    path.moveTo(w * 0.78f, h * 0.22f)
                    path.lineTo(w * 0.22f, h * 0.78f)
                }
            }
            canvas.drawPath(path, paint)
        }
    }
}
