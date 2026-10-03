package com.openminis.app.service

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import android.widget.Chronometer
import android.widget.TextView
import com.openminis.app.MinisApp
import com.openminis.app.agent.SoulIcon
import com.openminis.app.agent.SoulMetadata
import com.openminis.app.agent.SoulStore
import com.openminis.app.ui.chat.friendlyToolTitleFor
import com.openminis.app.ui.chat.toolAccentColorInt
import com.openminis.app.ui.chat.toolIconResFor
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
 * leads the second row once a tool finishes.
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

        // [T-android-overlay-icon-plate] Inset of a GLYPH inside the identity
        // circle, as a fraction of the circle's box.
        //
        // The previous 18% was derived from the glyph's bounding SQUARE, whose
        // corners do sit inside the circle at that inset. But a tool glyph is
        // not square art: the terminal icon in particular is a wide monitor
        // shape whose flat left/right edges run nearly the full viewport width
        // at mid-height, which is exactly where the circle is widest and the
        // ring is drawn. The result was the glyph's sides crossing the ring's
        // arc. 26% pulls the art well clear of the stroke at every aspect the
        // icon set uses, at the cost of a slightly smaller glyph.
        private const val GLYPH_INSET_FRACTION = 0.26f

        /** Alpha of the tinted plate behind a tool glyph, out of 255. */
        private const val PLATE_ALPHA = 38

        // [T-android-overlay-two-row] Tool glyph on row 2. Slightly larger
        // than the 10sp text beside it so the icon reads at a glance without
        // making the row taller than the text's own line height.
        private const val TOOL_ICON_SIZE_DP = 13
        private const val EDGE_PADDING_DP = 10
        // [T-android-overlay-dimensions-stable] Pin capsule height so
        // it doesn't jitter across states (spinner / completed / reply
        // / error).
        //
        // [T-android-overlay-third-row-clipped] The height must cover the
        // TALLEST state, which is three text rows, not two. The comment here
        // used to reason about "a two-row text column" and justify 56dp, while
        // the constant said 44 — the prose and the number had drifted apart,
        // and the number was the one users saw.
        //
        // [T-android-overlay-two-row] Two rows are stacked in `textCol`: the
        // app/outcome label (12sp) and the tool row (a 13dp glyph beside 10sp
        // text). Both are single-line with ellipsize, so no row ever WRAPS.
        // A third row used to carry the assistant's reply excerpt; it was
        // dropped in the two-row redesign.
        //
        // Budget from MEASURED rows on a 440dpi device (2.75x), never from a
        // nominal line-spacing estimate — an earlier pass computed 54dp from
        // the 1.2x rule and it was genuinely too small on device.
        //
        // [T-android-overlay-two-row] Measured after the capsule went from
        // three rows to two. Both of row 2's variants were measured, since
        // [T-android-overlay-reply-row] gave it different content per state —
        // and it is the COMPLETED state that is the taller of the two, because
        // its label row grows to seat the tick and the clock:
        //
        //     running    label 44px + row2 40px           = 84px
        //     completed  label 49px + row2 39px           = 88px  ← binding
        //     6dp + 6dp padding                           = 33px
        //                                        88 + 33  = 121px ≈ 44.0dp
        //
        // 46dp keeps a couple of dp of slack over that for a font-scale nudge
        // without leaving the two-row capsule visibly hollow — at the previous
        // 60dp it carried ~48px of dead vertical space once the third row was
        // gone. Neither leading glyph is the binding constraint (both measure
        // 36px inside their ~40px row), nor is the 26dp logo.
        private const val CAPSULE_HEIGHT_DP = 46

        /**
         * [T-android-overlay-multitask-pill] Nominal height of the "n of m"
         * chip, used only to derive its corner radius (radius = half height,
         * the same rule the capsule itself follows). The view is WRAP_CONTENT,
         * so this does not size it: 10sp text plus 1dp of vertical padding
         * lands within a dp or two of 16, and any radius at or above half the
         * real height renders as a full pill anyway.
         */
        private const val TASK_PILL_HEIGHT_DP = 16
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
        // the screen.
        //
        // [T-android-overlay-wide-screen-width] The ceiling was 400dp, which
        // still read as "巨长" on landscape phones and tablets. 400dp is about
        // DOUBLE the portrait-phone capsule (a 393dp Pixel gives 196dp, a
        // 411dp Xiaomi gives 206dp), so the pill grew markedly whenever the
        // screen got wider — which is exactly the complaint, even though the
        // cap was technically doing its job.
        //
        // The reference the user gave is the portrait-phone look, so the
        // ceiling is now 240dp: a little roomier than a portrait phone (so a
        // wide screen is not actively *worse* than a narrow one) while
        // staying a compact pill rather than a banner. Measured result:
        //
        //   phone portrait  393dp -> 196dp (50%, unchanged — fraction binds)
        //   phone landscape 851dp -> 240dp (28%, was 400dp/47%)
        //   tablet portrait 800dp -> 240dp (30%, was 400dp/50%)
        //   tablet landscape 1280dp -> 240dp (19%, was 400dp/31%)
        //
        // Portrait phones are untouched: 0.50 of 393dp is 196dp, well under
        // the ceiling, so the only configurations that move are the wide ones
        // this bug is about. The 0.70 fraction is kept for very narrow screens
        // where 240dp could otherwise exceed the display.
        private const val CAPSULE_WIDTH_CAP_FRACTION = 0.70f
        private const val CAPSULE_WIDTH_CAP_DP = 240
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
    // [T-android-overlay-icon-plate] Tinted disc behind the identity/tool
    // glyph; see its construction for why it exists.
    private var plateView: View? = null
    private var ringView: RotatingRingView? = null
    private var ringAnimator: ObjectAnimator? = null
    private var labelView: TextView? = null
    private var statusView: TextView? = null

    /** [T-android-overlay-multitask] Row 1 trailing "n of m" indicator. */
    private var taskIndexView: TextView? = null

    /**
     * [T-android-overlay-multitask-pill] The chip and the clock, which share
     * the capsule's right-hand column. Held together so both can be measured
     * and given the same width, which is what keeps the clock centred under
     * the chip rather than merely near it.
     */
    private var rightColumnViews: List<View> = emptyList()
    // [T-android-overlay-chronometer] Self-driving elapsed clock for the
    // capsule. A Chronometer, not a TextView: the capsule is only repainted
    // when the observed tool state CHANGES, and a long tool call changes
    // nothing for minutes — so any text-based timer here would sit frozen,
    // which is the reported complaint. Chronometer ticks itself on the UI
    // thread and needs no push from the service.
    private var elapsedView: Chronometer? = null
    // [T-android-overlay-two-row] Row 2 is now "tool icon + tool title"
    // instead of a plain status string, so the row needs its own container
    // (to show/hide both children together) and a handle on the icon.
    private var statusRowView: LinearLayout? = null
    // [T-android-overlay-reply-row] Row 2's completion-state leading glyph,
    // shown once the run is over.
    private var replyGlyphView: StatusGlyphView? = null
    // [T-android-overlay-soul-identity] Memoized emoji raster, keyed by
    // (glyph, size) — see emojiBitmap for why this is worth caching.
    private var cachedEmojiBitmap: Pair<Pair<String, Int>, Bitmap>? = null
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
     * @param isRunning true while the turn is still working — either a tool
     *        is executing OR the model is streaming text. Drives the rotating
     *        ring and selects between row 2's working and completed variants.
     * @param isToolRunning true only for the first of those two: an actual
     *        tool is executing. [T-android-overlay-streaming-state] The two
     *        were previously collapsed into one boolean at this boundary, so
     *        the capsule could not tell "running shell_execute" from "the
     *        model is talking", and rendered a stale tool for the latter.
     * @param outcome typed outcome of the most recently finished tool.
     *        Only consulted when [isRunning] = false, where it picks
     *        row 1's outcome word and row 2's leading glyph:
     *          - Success → green ✓
     *          - Error / Timeout → red ✗
     *          - Cancelled / Unknown → glyph hidden (we don't have a
     *            confident signal to render); the outcome word still
     *            carries the result in text.
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
        isToolRunning: Boolean = isRunning,
        isThinking: Boolean = false,
        // [T-android-overlay-multitask] The focused task's own title and its
        // position among the live tasks. Passed in rather than read from the
        // tracker here so the capsule renders one CONSISTENT snapshot: reading
        // them separately is how a title and a bound session id drifted apart
        // on device (10:58:01 — session B's capsule showing A's tool title).
        sessionTitle: String? = null,
        taskIndex: Int = 0,
        taskTotal: Int = 0,
    ) {
        Log.d(
            TAG,
            "show() toolName=$toolName toolTitle=${toolTitle?.take(40)} " +
                "status=${statusText.take(40)} " +
                "running=$isRunning toolRunning=$isToolRunning thinking=$isThinking outcome=$outcome " +
                "reply=${replyExcerpt?.take(20)} " +
                "sessionTitle=${sessionTitle?.take(24)} task=$taskIndex/$taskTotal " +
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
                updateContent(
                    toolName, statusText, isRunning, outcome, replyExcerpt, toolTitle,
                    isToolRunning, isThinking, sessionTitle, taskIndex, taskTotal,
                )
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
            // [T-android-overlay-chronometer] stop() before dropping the
            // reference: a started Chronometer holds a posted tick callback,
            // and the capsule is attached/detached many times per session.
            elapsedView?.stop()
            elapsedView = null
            view = null
            logoView = null
            plateView = null
            ringView = null
            labelView = null
            statusView = null
            statusRowView = null
            replyGlyphView = null
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
            // With CAPSULE_HEIGHT_DP=46 the corner radius is 23dp; a 6dp
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

        // [T-android-overlay-icon-plate] Circular backing plate, BENEATH the
        // icon in the same FrameLayout. Two jobs:
        //
        //  - it gives a transparent glyph or emoji something to sit on, so the
        //    art reads as resting on a disc rather than floating on the
        //    capsule's dark fill;
        //  - being a true circle drawn at the ring's own diameter, it visually
        //    closes the gap between art and ring that made square-ish glyphs
        //    look like they were poking through the stroke.
        //
        // A GradientDrawable OVAL rather than another clipToOutline view: it is
        // one drawable whose colour we re-tint per state, with no extra layer
        // to keep in sync.
        val plate = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(logoSize, logoSize).apply {
                gravity = Gravity.CENTER
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
            }
            visibility = View.GONE
        }
        plateView = plate
        logoStack.addView(plate)

        val logo = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(logoSize, logoSize).apply {
                gravity = Gravity.CENTER
            }
            // [T-android-overlay-dynamic-logo] This is the IDLE / completed
            // rendering. While a tool runs, applyLogoFor() swaps in that
            // tool's glyph; see it for why the scaleType and padding have to
            // change along with the drawable.
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
            // [T-android-overlay-third-row-clipped] The column is
            // WRAP_CONTENT inside a FIXED-height parent and centred
            // vertically, so anything taller than the capsule overflows
            // SYMMETRICALLY — half a row bleeds off the top and half off the
            // bottom. Clipping to the parent's bounds turns that into a clean
            // truncation at the bottom edge instead of a row sliced through
            // its middle.
            //
            // NOTE on provenance: 38b912a27 added this clip (and raised
            // CAPSULE_HEIGHT_DP to 54) believing symmetric three-row overflow
            // was the cause of the reported "half-cut second line". On-device
            // measurement later disproved that — see the elapsed-Chronometer
            // comment below for the actual root cause; the completion capsule
            // was only ever rendering ONE row, so three-row overflow could not
            // have produced the symptom. The clip is kept anyway because it is
            // a genuine safety net for a case the height budget cannot cover:
            // the budget assumes the system font scale, and a user at 1.3x
            // accessibility scaling blows past any fixed dp value.
            clipToPadding = true
            clipChildren = true
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

        // [T-android-overlay-clock-baseline] Row 1 seats the 12sp identity name
        // beside the 10sp clock, so it must align them by TEXT BASELINE. A
        // vertical gravity would centre each child's BOX instead, which is not
        // the same thing at different text sizes and measured 2px out on
        // device. LinearLayout aligns by baseline by default, so the correct
        // move is to set no vertical gravity here at all.
        val labelRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val label = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            text = ""
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        labelView = label
        // [T-android-overlay-elapsed-vertical-wrap] weight=1 with width=0 so a
        // long label ellipsizes rather than widening the row. The original
        // reason was to stop the label starving the clock beside it (which
        // drove the clock to zero width and made it wrap vertically); the clock
        // has since moved to row 2, but the ellipsize-don't-widen behaviour is
        // still what this row wants.
        labelRow.addView(
            label,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )

        // [T-android-overlay-chronometer] Elapsed clock. Hidden until a run
        // supplies a base.
        // [T-android-overlay-task-total-timer] What it measures is the whole
        // task, not the current step: SessionActivityTracker anchors the run
        // start once per task, never per tool call.
        // [T-android-overlay-multitask] It is ADDED to row 2 further down (the
        // index took row 1's trailing slot), so it now sits under the index
        // rather than beside the title. Constructed here, next to the row-1
        // views, only because the label row's sizing lessons below are the
        // same ones it depends on.
        // SessionActivityTracker anchors currentRunStartedAtMs exactly once per
        // task — only on the idle -> active edge (`if (wasRunIdle)`), never
        // per tool call — so this reading accumulates across every tool call
        // and streaming stretch in the turn. Row 1, beside the assistant's
        // name, is where a task-level total belongs; row 2 describes the
        // current step and would have implied a per-step duration it never
        // measured.
        val elapsed = Chronometer(context).apply {
            setTextColor(Color.argb(200, 220, 220, 220))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            // [T-android-overlay-elapsed-vertical-wrap] A Chronometer is a
            // TextView, and an unconstrained TextView squeezed to zero width
            // does not clip — it WRAPS, one character per line. Measured on
            // device: label 297px + icon 33px already exceed the 328px column,
            // leaving nothing for the clock, which then rendered 0px wide and
            // 115px TALL. That single view caused both reported defects — it
            // was the "dashed vertical line" between the tick and the X, and
            // by inflating labelRow to 115px it pushed the status and reply
            // rows to height 0, which is why the completion capsule showed
            // only its title.
            //
            // maxLines=1 is the actual fix: it makes the view's height one
            // line regardless of available width, so it can never grow the
            // row. singleLine is deprecated; maxLines is the current spelling.
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                leftMargin = dpToPx(6)
                // [T-android-overlay-clock-baseline] No vertical gravity here:
                // a per-child gravity overrides the row's baseline alignment
                // for that child, which is the offset this fixes. Both this
                // view and the status text beside it are 10sp, so they would
                // agree either way — but leaving it off keeps them correct if
                // either text size is ever tuned.
            }
            visibility = View.GONE
        }
        elapsedView = elapsed

        // [T-android-overlay-multitask] Row 1's trailing slot is the task
        // index ("\u2460 of 3"), not the clock. The two columns pair by scope:
        // row 1 is the TASK (its title, which of N it is), row 2 is the
        // current STEP (the tool, how long the task has run). Putting the
        // index beside the title is what makes "which of these am I looking
        // at" answerable without reading both rows.
        //
        // Hidden below two tasks: with a single run "\u2460 of 1" is pure noise
        // and costs the title characters it cannot spare.
        val taskIndex = TextView(context).apply {
            setTextColor(Color.argb(200, 220, 220, 220))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            text = ""
            maxLines = 1
            // No ellipsize: this is a handful of digits inside a fixed pill.
            // Truncating "2 of 10" to "2 of…" would be worse than letting the
            // title beside it give up the space, which weight=1 already does.
            // [T-android-overlay-multitask-pill] Outline only — no fill. It
            // echoes the capsule's own edge (1dp hairline, radius tracking half
            // the height) so it reads as a chip drawn ON the capsule; a filled
            // chip sat on top of the body as a second surface and drew more
            // attention than a task counter deserves. The stroke is brighter
            // than the capsule's (70 vs 40 alpha) because it has no fill to
            // separate it from the body behind it.
            val vPad = dpToPx(1)
            val hPad = dpToPx(6)
            setPadding(hPad, vPad, hPad, vPad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = TASK_PILL_HEIGHT_DP / 2f * density
                setColor(Color.TRANSPARENT)
                setStroke(dpToPx(1), Color.argb(70, 255, 255, 255))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dpToPx(6) }
            visibility = View.GONE
        }
        taskIndexView = taskIndex
        labelRow.addView(taskIndex)

        textCol.addView(labelRow)


        // [T-android-overlay-two-row] Second (and final) row: what the turn is
        // doing right now. Its content varies by state:
        //
        //   tool running — the model-supplied tool_title, e.g. "Open Baidu
        //                  home page". Replaces the old generic
        //                  "Running: shell_execute", which named the tool's
        //                  internal identifier rather than the work.
        //   streaming    — a localized "generating" line, for the stretch where
        //                  the model is producing text with no tool in flight.
        //   completed    — a tick glyph, then an excerpt of the assistant's
        //                  last reply. Once the tool has finished, its title is
        //                  stale news and row 1 already carries the outcome;
        //                  the reply is what the user actually wants off-screen.
        //
        // [T-android-overlay-streaming-state] Only the completed variant draws
        // a leading glyph. The two working variants are text-only: the tool's
        // icon lives in the capsule's leading circle (see applyLogoFor), and
        // drawing it again here put the same glyph on the same capsule twice.
        val statusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            // [T-android-overlay-multitask] The clock is back on this row, so
            // this is a 10sp status text beside a 10sp clock. Equal sizes make
            // box-centring and baseline alignment agree, and an explicit
            // gravity keeps the leading glyph (which has no baseline) centred.
            gravity = Gravity.CENTER_VERTICAL
        }

        // [T-android-overlay-reply-row] Completion-state leading glyph. Reuses
        // StatusGlyphView — the same tick/cross the label row draws — so the
        // two rows cannot drift apart in shape or stroke weight.
        val replyGlyph = StatusGlyphView(context).apply {
            val s = dpToPx(TOOL_ICON_SIZE_DP)
            layoutParams = LinearLayout.LayoutParams(s, s).apply {
                rightMargin = dpToPx(4)
                // [T-android-overlay-timer-row2] A View with no text has no
                // baseline for the row to align to, so this one keeps an
                // explicit vertical gravity of its own.
                gravity = Gravity.CENTER_VERTICAL
            }
            visibility = View.GONE
        }
        replyGlyphView = replyGlyph
        statusRow.addView(replyGlyph)

        val status = TextView(context).apply {
            setTextColor(Color.argb(200, 220, 220, 220))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            text = ""
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        statusView = status
        // weight=1 so a long tool_title ellipsizes instead of pushing the row
        // wider than the fixed capsule — the same lesson as the label row.
        statusRow.addView(
            status,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )

        // [T-android-overlay-multitask] The clock moved here from row 1 so the
        // right-hand edge reads index-over-clock. What it measures is
        // unchanged (the whole task, anchored once per run), but it now sits
        // beside the step it is timing rather than beside the identity.
        //
        // Added AFTER the weight=1 status text so the text ellipsizes and the
        // clock keeps its intrinsic width, which is the same ordering row 1
        // already relies on.
        //
        // [T-android-overlay-multitask-pill] The clock is CENTRED on the chip
        // above it, not right-aligned to it: the chip is the wider of the two
        // (it carries a border and 6dp of side padding), so aligning their
        // right edges would leave the clock visibly off-centre beneath it.
        //
        // Both views sit in a column of the same measured width for that to
        // hold at any content: left to wrap-content they only line up by
        // coincidence of today's strings, and "1:02:14" — an hour-long task —
        // is wider than "2 of 2" and would have pushed the clock back out.
        elapsed.gravity = Gravity.CENTER_HORIZONTAL
        statusRow.addView(elapsed)
        taskIndexView?.gravity = Gravity.CENTER_HORIZONTAL
        rightColumnViews = listOf(taskIndex, elapsed)

        statusRowView = statusRow
        textCol.addView(statusRow)

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
        isToolRunning: Boolean,
        isThinking: Boolean,
        // [T-android-overlay-multitask] Threaded through from show() so row 1
        // can name the task and number it. Both travel together — see show().
        sessionTitle: String?,
        taskIndex: Int,
        taskTotal: Int,
    ) {
        // [T-android-overlay-tool-title] The model supplies a per-call
        // tool_title (e.g. "Open Baidu home page", "Take screenshot of current
        // page"); it is what row 2 shows, falling back to a humanized tool name.
        // [T-android-overlay-no-idle] After a tool completes,
        // SessionActivityTracker resets currentToolStatus to "Idle" and
        // toolName to null — flowing that through verbatim would render a noisy
        // "Minis / Idle" capsule that tells the user nothing. When not running
        // and we have neither a toolTitle nor a real toolName, hide the tool
        // row so only the app/outcome line remains.
        val hasToolIdentity = !toolTitle.isNullOrBlank() || toolName != null
        val showStatusRow =
            isRunning || (hasToolIdentity && !statusText.equals("Idle", ignoreCase = true))

        val completionWord: String? = if (!isRunning) when (outcome) {
            ToolOutcome.Success -> context.getString(R.string.overlay_completion_completed)
            ToolOutcome.Error -> context.getString(R.string.overlay_completion_failed)
            ToolOutcome.Timeout -> context.getString(R.string.overlay_completion_timeout)
            ToolOutcome.Cancelled -> context.getString(R.string.overlay_completion_cancelled)
            ToolOutcome.Unknown -> null
        } else null

        // [T-android-overlay-two-row] Row 1 is now the app/outcome line, not a
        // second copy of the tool title. Before this change row 1 showed
        // tool_title and row 2 showed "Running: <tool_name>"; with row 2
        // reworked to carry the tool icon + tool_title, leaving row 1 as-is
        // would have printed the same sentence twice. So the two rows split by
        // scope: row 1 says WHO is working and how it ended, row 2 says WHAT
        // the current call is doing.
        labelView?.let { lv ->
            // On completion the outcome word stands alone rather than as
            // "Minis — <outcome>": row 1 shares its width with the status tick
            // and the elapsed clock, and the localized words are long enough
            // (zh "执行完成" is four full-width characters) that the combined
            // string ellipsized to an unreadable "Minis — 执...". The app
            // identity is already carried by the logo to the left, so dropping
            // it here loses nothing.
            // [T-android-overlay-multitask] Row 1 names the TASK, not the
            // speaker. With one run the Soul name was a reasonable identity
            // line, but it is identical on every capsule, so with several
            // tasks live it cannot tell them apart — which is the whole point
            // of the row now that the capsule pages between them. The app logo
            // to the left still carries identity.
            //
            // [T-android-overlay-soul-identity] The Soul name survives as the
            // fallback for a session with no title yet (a brand-new chat),
            // keeping the row from ever rendering blank.
            //
            // The completion case is unchanged in spirit but no longer
            // replaces the title: the outcome is shown by the leading glyph
            // (see applyLogoFor / StatusGlyphView), so a finished task keeps
            // its name instead of collapsing to a bare "执行完成" that says
            // nothing about WHICH task finished — unreadable once more than
            // one is on screen.
            val identityName = SoulStore.cachedMetadata.value.name
                .ifBlank { SoulMetadata.DEFAULT.name }
            val labelText: String? =
                sessionTitle?.takeIf { it.isNotBlank() } ?: completionWord ?: identityName
            if (labelText != null) {
                lv.text = labelText
                lv.visibility = View.VISIBLE
            } else {
                lv.text = ""
                lv.visibility = View.GONE
            }
        }
        // [T-android-overlay-multitask] The task chip on row 1: "2 of 3", or
        // "2/3" where the word does not belong.
        //
        // Shown only from two tasks up: at one it states the obvious and eats
        // characters the title needs, since this row's width is shared with it.
        //
        // Plain digits, not circled ones (\u2460\u2461\u2462). Those stop at 20, force a
        // fallback nobody can see coming, and render from a different font —
        // often a colour emoji on OEM builds — so they sat at the wrong optical
        // weight beside the clock below them. The chip's own outline is what
        // marks this as an indicator, so the glyph does not have to.
        //
        // The string itself is localized, so a locale can drop the word without
        // any of this logic changing.
        taskIndexView?.let { tv ->
            if (taskTotal >= 2 && taskIndex >= 1) {
                tv.text = context.getString(R.string.overlay_task_index, taskIndex, taskTotal)
                tv.visibility = View.VISIBLE
            } else {
                tv.text = ""
                tv.visibility = View.GONE
            }
        }
        // [T-android-overlay-reply-row] Row 2 carries different content per
        // state — see the row's construction comment for why.
        //
        //   running   → tool icon + tool title, matching the chat stream's
        //               tool pill. Title precedence is the pill's own: the
        //               model-supplied tool_title, else the humanized tool name
        //               (friendlyToolTitleFor). Never the raw "Running: <name>".
        //   completed → outcome tick + the assistant's reply excerpt.
        //
        // The completed branch needs a non-blank excerpt to have anything to
        // say. When the run is over and no excerpt arrived, the row hides
        // rather than falling back to the finished tool's title: row 1 already
        // shows the outcome, and a stale title beneath it reads as if the tool
        // were still going.
        val rowToolName = toolName
        val replyText = replyExcerpt?.takeIf { it.isNotBlank() }
        val showReplyVariant = !isRunning && replyText != null

        // [T-android-overlay-streaming-state] A turn has three working shapes,
        // not two, and the capsule now tells them apart:
        //
        //   tool running  — a named tool is executing. Leading circle shows
        //                   that tool's glyph; row 2 shows its title.
        //   streaming     — the model is producing text with no tool in
        //                   flight. Leading circle shows the Minis mark (there
        //                   is no tool to depict) and row 2 says so.
        //   completed     — handled by showReplyVariant below.
        //
        // Deciding this needs isToolRunning, not isRunning: isRunning is true
        // for BOTH working shapes. And it cannot be inferred from toolName
        // either, because the service passes lastToolName so the finished tool
        // can still be named — during a post-tool streaming stretch that name
        // is still set, which is exactly how a stale tool glyph used to sit on
        // a capsule that was really just talking.
        val showStreamingVariant = isRunning && !isToolRunning

        // [T-android-overlay-dynamic-logo] The leading circle follows the tool
        // that is running RIGHT NOW, and reverts to the Minis mark otherwise —
        // which now includes the streaming stretch, not just completion.
        applyLogoFor(if (isToolRunning) rowToolName else null)

        val rowTitle: String? = when {
            showReplyVariant -> replyText
            // [T-android-live-update-content] Distinguish reasoning from
            // visible output, as the iOS Dynamic Island does.
            showStreamingVariant -> context.getString(
                if (isThinking) R.string.overlay_thinking else R.string.overlay_streaming,
            )
            !showStatusRow -> null
            !toolTitle.isNullOrBlank() -> toolTitle
            rowToolName != null -> friendlyToolTitleFor(rowToolName)
            else -> null
        }
        // [T-overlay-glyph-typed-outcome] Drive the glyph from the typed
        // outcome rather than scanning the (stale) status string. Cancelled /
        // Unknown draw nothing rather than render a guess, so a reply under
        // those outcomes shows as text alone rather than with a misleading
        // tick. Row 1 states the outcome in words either way.
        val replyGlyphState: Pair<StatusGlyphView.State, Int>? =
            if (!showReplyVariant) null else when (outcome) {
                ToolOutcome.Success -> StatusGlyphView.State.Success to SUCCESS_COLOR
                ToolOutcome.Error, ToolOutcome.Timeout ->
                    StatusGlyphView.State.Error to FAILURE_COLOR
                ToolOutcome.Cancelled, ToolOutcome.Unknown -> null
            }

        val showToolRow = rowTitle != null
        statusRowView?.visibility = if (showToolRow) View.VISIBLE else View.GONE
        statusView?.let { sv ->
            if (showToolRow) {
                sv.text = rowTitle
                sv.visibility = View.VISIBLE
            } else {
                sv.text = ""
                sv.visibility = View.GONE
            }
        }
        replyGlyphView?.let { gv ->
            val st = replyGlyphState
            if (showToolRow && st != null) {
                gv.setState(st.first, st.second)
                gv.visibility = View.VISIBLE
            } else {
                gv.visibility = View.GONE
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

        // [T-android-overlay-reply-status-34599] The X close button is
        // useful only once the user has something to dismiss — while a
        // tool is mid-stream the overlay self-tears-down when the tool
        // finishes (no completion state yet to linger on), and the X
        // shouldn't double as a "cancel the run" affordance.
        closeView?.visibility = if (isRunning) View.GONE else View.VISIBLE

        // [T-android-overlay-chronometer] Drive the elapsed clock.
        //
        // Clock domain, and why it differs from the notification: Chronometer
        // interprets `base` as SystemClock.elapsedRealtime(), which is EXACTLY
        // what SessionActivityTracker stamps into currentRunStartedAtMs. So no
        // rebasing here — the opposite of AgentForegroundService, where
        // setWhen() is wall-clock and the same anchor MUST be converted. The
        // asymmetry is deliberate, not an oversight in one of the two.
        //
        // While running: anchor and start, so the capsule keeps counting
        // through a long tool call that pushes no state changes. When stopped:
        // stop() freezes the reading at the run's real duration rather than
        // blanking it, so the completion capsule still answers "how long did
        // that take". Falls back to hiding when there is no anchor (presence
        // only, nothing ever ran) rather than showing a bogus 00:00.
        val elapsed = elapsedView
        if (elapsed != null) {
            val runStart = SessionActivityTracker.currentRunStartedAtMs.value
            if (runStart != null) {
                if (isRunning) {
                    // Re-anchor only when it actually moved; assigning `base`
                    // unconditionally would restart the visible count on every
                    // unrelated state push.
                    if (elapsed.base != runStart) {
                        elapsed.base = runStart
                    }
                    elapsed.visibility = View.VISIBLE
                    elapsed.start()
                } else {
                    // [T-android-overlay-task-total-timer] Completed: freeze the
                    // reading and KEEP it. Back on row 1 the clock no longer
                    // competes with the reply excerpt for width (which is why
                    // it was hidden while it lived on row 2), and a task total
                    // is exactly the kind of summary that stays useful after
                    // the fact — "that took 00:35" is the one number the user
                    // could not have watched accumulate while backgrounded.
                    elapsed.stop()
                    elapsed.visibility = View.VISIBLE
                }
            } else {
                elapsed.stop()
                elapsed.visibility = View.GONE
            }
        }

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

        equaliseRightColumn()
    }

    /**
     * [T-android-overlay-multitask-pill] Gives the chip and the clock a common
     * width so the clock is centred UNDER the chip rather than beside it.
     *
     * Both are WRAP_CONTENT by nature and their strings differ in width ("2 of
     * 2" against "00:15", and an hour-long task reaches "1:02:14"), so left
     * alone they line up only by coincidence of the current content. Measuring
     * both and widening the narrower to match pins the relationship at any
     * content, including the elapsed clock growing a digit mid-run.
     *
     * Runs after every content update because the clock's own width changes as
     * it ticks. Costs two measure passes on two small text views; the capsule
     * already re-lays-out on each of these updates.
     */
    private fun equaliseRightColumn() {
        val views = rightColumnViews.filter { it.visibility == View.VISIBLE }
        // Nothing to align against when the chip is hidden (a single task), and
        // the clock should keep its natural width in that case.
        if (views.size < 2) {
            rightColumnViews.forEach { v ->
                if (v.layoutParams?.width != LinearLayout.LayoutParams.WRAP_CONTENT) {
                    v.layoutParams = (v.layoutParams as LinearLayout.LayoutParams).apply {
                        width = LinearLayout.LayoutParams.WRAP_CONTENT
                    }
                }
            }
            return
        }
        val unspec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        var widest = 0
        for (v in views) {
            val lp = v.layoutParams as LinearLayout.LayoutParams
            // Measure at natural width, not the width a previous pass forced.
            if (lp.width != LinearLayout.LayoutParams.WRAP_CONTENT) {
                lp.width = LinearLayout.LayoutParams.WRAP_CONTENT
                v.layoutParams = lp
            }
            v.measure(unspec, unspec)
            if (v.measuredWidth > widest) widest = v.measuredWidth
        }
        if (widest <= 0) return
        for (v in views) {
            val lp = v.layoutParams as LinearLayout.LayoutParams
            if (lp.width != widest) {
                lp.width = widest
                v.layoutParams = lp
            }
        }
    }

    /**
     * [T-android-overlay-dynamic-logo] Point the leading circle at either the
     * running tool's glyph or the Minis launcher icon.
     *
     * The two drawables need DIFFERENT presentation, which is why this is not
     * a bare setImageResource:
     *
     *  - the launcher icon is a full-bleed bitmap, so it wants CENTER_CROP and
     *    no padding — it is meant to fill the circle edge to edge;
     *  - a tool glyph is line art on a transparent ground. Under CENTER_CROP
     *    the circular clip would shave its extremities (the terminal's bracket,
     *    the globe's rim), so it wants FIT_CENTER plus a little inset to keep
     *    the strokes clear of the clip boundary.
     *
     * Getting this wrong is not a crash, it is a subtly chewed-looking icon,
     * so the two cases are set explicitly rather than sharing a default.
     *
     * The rotating ring is unaffected — it is a sibling in the FrameLayout and
     * keeps spinning around whatever this view shows.
     */
    private fun applyLogoFor(toolName: String?) {
        val iv = logoView ?: return
        if (toolName == null) {
            iv.setPadding(0, 0, 0, 0)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.clearColorFilter()
            applySoulIdentityIcon(iv)
            return
        }
        // [T-android-overlay-icon-plate] Inset so the drawn art stays clear of
        // the ring stroke — see GLYPH_INSET_FRACTION for why 18% was not
        // enough for the wider glyphs.
        val inset = (dpToPx(LOGO_SIZE_DP) * GLYPH_INSET_FRACTION).toInt()
        iv.setPadding(inset, inset, inset, inset)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        val accent = toolAccentColorInt(toolName)
        iv.setImageResource(toolIconResFor(toolName))
        iv.setColorFilter(accent)
        // The plate takes the tool's own accent at low alpha, so a green
        // terminal sits on a faint green disc and a blue globe on a faint blue
        // one. Low alpha rather than a hand-picked pale colour: it composites
        // against the capsule's dark fill, which keeps it muted and tied to the
        // surface instead of reading as a bright sticker laid on top.
        applyPlate(Color.argb(PLATE_ALPHA, Color.red(accent), Color.green(accent), Color.blue(accent)))
    }

    /** [T-android-overlay-icon-plate] Tint the disc, or hide it entirely. */
    private fun applyPlate(color: Int?) {
        val pv = plateView ?: return
        if (color == null) {
            pv.visibility = View.GONE
            return
        }
        (pv.background as? GradientDrawable)?.setColor(color)
        pv.visibility = View.VISIBLE
    }


    /**
     * [T-android-overlay-soul-identity] Paint the identity circle from the
     * user's Soul icon, falling back to the launcher mark.
     *
     * The three branches mirror SoulIconGlyph (SoulSettingsScreen.kt), which is
     * the authoritative renderer for this value — that one is @Composable and
     * cannot be called from a View, so the BRANCHING is duplicated here while
     * the actual decoding stays shared in SoulIcon. Keeping the decode in one
     * place is what stops the two from disagreeing about what a given string
     * means:
     *
     *   data URI  -> SoulIcon.decode() to a Bitmap
     *   non-empty -> an emoji literal, rasterized below
     *   empty     -> unset; keep the launcher icon exactly as before, so a user
     *                who never touched Soul sees no change at all
     *
     * Note this deliberately does NOT reproduce SoulIconGlyph's rounded-rect
     * clip. The capsule's circle is an established part of its own visual
     * language (the rotating ring traces that exact circle), and the outline
     * provider that produces it is applied to this ImageView once at build
     * time. A Soul icon simply takes the place the launcher icon already held.
     */
    private fun applySoulIdentityIcon(iv: ImageView) {
        val icon = SoulStore.cachedMetadata.value.icon
        if (icon.isBlank()) {
            // [T-android-overlay-icon-plate] The launcher mark is an opaque
            // bitmap that fills the circle edge to edge, so a plate behind it
            // would never be seen. Left unchanged, per the brief. Padding and
            // scaleType are reset explicitly because the other branches set
            // them and this view is reused across states.
            applyLauncherMark(iv)
            return
        }
        SoulIcon.decode(icon)?.let { bmp ->
            // A user image is inset like a glyph rather than filling the circle:
            // these are square photos/PNGs, and CENTER_CROP to the full circle
            // would shave their edges. Sitting them on a plate keeps a
            // transparent PNG from floating on the dark fill.
            val inset = (dpToPx(LOGO_SIZE_DP) * GLYPH_INSET_FRACTION * 0.5f).toInt()
            iv.setPadding(inset, inset, inset, inset)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            // Drop any tint left over from a tool glyph — this is the user's
            // own artwork and must not be recoloured.
            iv.clearColorFilter()
            iv.setImageBitmap(bmp)
            applyPlate(dominantPlateColor(bmp))
            return
        }
        // Not a data URI: an emoji literal. An ImageView cannot show text, so
        // rasterize the glyph into a bitmap sized to the view's own box.
        val px = dpToPx(LOGO_SIZE_DP)
        emojiBitmap(icon, px)?.let {
            iv.setPadding(0, 0, 0, 0)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iv.clearColorFilter()
            iv.setImageBitmap(it)
            // Emoji carry many colours at once, so there is no single hue to
            // pull out. A neutral translucent white reads as a surface rather
            // than competing with the glyph.
            applyPlate(Color.argb(28, 255, 255, 255))
            return
        }
        applyLauncherMark(iv)
    }

    /** [T-android-overlay-icon-plate] Full-bleed launcher mark, no plate. */
    private fun applyLauncherMark(iv: ImageView) {
        iv.setPadding(0, 0, 0, 0)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.clearColorFilter()
        iv.setImageResource(R.mipmap.ic_launcher)
        applyPlate(null)
    }

    /**
     * [T-android-overlay-icon-plate] Average colour of a user image, muted down
     * for use as its backing disc.
     *
     * Deliberately a plain average over a coarse grid rather than a real
     * dominant-colour extraction (k-means / palette quantisation): the result
     * is only ever shown at PLATE_ALPHA behind the image itself, where the
     * difference between "average hue" and "modal hue" is not visible. The
     * cheap version costs a few hundred pixel reads on a bitmap we already
     * decoded; the thorough one would pull in a palette dependency to produce
     * the same faint wash.
     *
     * Fully transparent pixels are skipped so a transparent-background PNG is
     * tinted by its art, not dragged toward black by its empty corners.
     */
    private fun dominantPlateColor(bmp: Bitmap): Int {
        val steps = 8
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return Color.argb(28, 255, 255, 255)
        for (i in 0 until steps) {
            for (j in 0 until steps) {
                val px = bmp.getPixel(i * (w - 1) / (steps - 1), j * (h - 1) / (steps - 1))
                if (Color.alpha(px) < 128) continue
                r += Color.red(px)
                g += Color.green(px)
                b += Color.blue(px)
                n++
            }
        }
        if (n == 0) return Color.argb(28, 255, 255, 255)
        return Color.argb(PLATE_ALPHA, (r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    /**
     * [T-android-overlay-soul-identity] Draw an emoji into a square bitmap.
     *
     * Cached by (glyph, size) because this runs on every capsule refresh — a
     * per-second Chronometer tick included — and allocating plus rasterizing a
     * bitmap that often would be pure waste for a value that changes only when
     * the user edits their Soul.
     *
     * The glyph is centred on the box's vertical CENTRE rather than its
     * baseline: emoji have large and inconsistent descents, so baseline
     * placement leaves them visibly high inside a circle.
     */
    private fun emojiBitmap(glyph: String, sizePx: Int): Bitmap? {
        if (sizePx <= 0) return null
        cachedEmojiBitmap?.let { (key, bmp) ->
            if (key == glyph to sizePx) return bmp
        }
        return runCatching {
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
                // 0.72 keeps a tall glyph clear of the circular clip.
                textSize = sizePx * 0.72f
            }
            val fm = paint.fontMetrics
            val baseline = sizePx / 2f - (fm.ascent + fm.descent) / 2f
            canvas.drawText(glyph, sizePx / 2f, baseline, paint)
            cachedEmojiBitmap = (glyph to sizePx) to bmp
            bmp
        }.getOrNull()
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
