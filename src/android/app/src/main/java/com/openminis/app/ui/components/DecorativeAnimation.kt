package com.openminis.app.ui.components

import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.AndroidUiDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.floor
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * [T-android-decorative-anim-perf] Shared machinery for decorative, always-on
 * animations: spinners, shimmers, breathing dots, pulsing glows.
 *
 * Why this file exists. Profiled on a Pixel 6 (90 Hz) with one tool running:
 * the 15 dp indeterminate spinner in the floating tool bar cost 193 `drawArc`
 * samples — and dragged 893 samples of text / image / rect re-rasterisation
 * with it, because nothing put a layer boundary between the spinner and its
 * siblings. Compose walks a draw invalidation UP to the nearest layer; with
 * no layer in between, that is the whole bar, so every frame re-recorded the
 * tile's twelve lines of monospace text to move a ring a few degrees.
 *
 * Four rules, each with a helper here so call sites cannot drift:
 *
 *  1. ISOLATE — give the animated node its own layer ([animationLayer], or a
 *     `graphicsLayer { }` lambda, which is one already) so its invalidation
 *     stops there and siblings are composited from cache.
 *  2. DEFER — read the animated value inside the `graphicsLayer { }` / draw
 *     lambda, never in composition. A composition-phase read recomposes the
 *     node every frame; a layer-phase read touches nothing but the layer.
 *  3. NO PER-FRAME ALLOCATION — brushes, colours and strokes are hoisted or
 *     cached; the frame path only writes a float.
 *  4. ONE THROTTLED CLOCK — every decorative animation derives its phase from
 *     [DecorativeClock], a single ~30 fps tick shared by all readers. A 2.8 s
 *     shimmer or a 1 s spin does not earn the panel's full refresh rate, and
 *     N independent `rememberInfiniteTransition`s meant N invalidation
 *     sources per frame. The clock runs only while something is reading it.
 *
 * Content the user tracks (scroll, drag, transitions) is NOT decorative and
 * must not be routed through this clock.
 */

/**
 * A layer boundary for an animated node.
 *
 * `graphicsLayer()` with no lambda: purely a RenderNode boundary so that draw
 * invalidations inside do not re-record anything outside. Use it around
 * Material's `CircularProgressIndicator` and any other component whose
 * animation you do not control; use the `graphicsLayer { }` lambda form
 * directly when you are also animating a layer property.
 */
fun Modifier.animationLayer(): Modifier = graphicsLayer()

/**
 * The single shared tick for decorative animations.
 *
 * Reference-counted: the frame loop starts when the first reader appears and
 * is cancelled when the last one leaves, so an idle screen costs nothing.
 * [nanos] updates at most every [INTERVAL_NS]; readers derive phase from it
 * with [decorativePhase] inside a layer / draw lambda.
 */
object DecorativeClock {
    /** ~30 fps. A third of a 90 Hz panel's frames, visually indistinguishable for decoration. */
    const val INTERVAL_NS = 33_333_333L

    private val _nanos = mutableLongStateOf(0L)
    val nanos: State<Long> get() = _nanos

    // AndroidUiDispatcher.Main carries the Choreographer-backed frame clock
    // that withFrameNanos needs; a plain Dispatchers.Main scope would not.
    private val scope = CoroutineScope(AndroidUiDispatcher.Main + SupervisorJob())
    private var job: Job? = null
    private var readers = 0

    /** True while at least one reader holds the clock. Exposed for tests. */
    val isRunning: Boolean get() = job != null

    @MainThread
    fun acquire() {
        if (readers++ == 0) start()
    }

    @MainThread
    fun release() {
        readers = (readers - 1).coerceAtLeast(0)
        if (readers == 0) {
            job?.cancel()
            job = null
        }
    }

    private fun start() {
        job = scope.launch {
            var last = 0L
            while (true) {
                withFrameNanos { now ->
                    if (now - last >= INTERVAL_NS) {
                        last = now
                        _nanos.longValue = now
                    }
                }
            }
        }
    }
}

/**
 * Subscribe this composable to [DecorativeClock] for as long as it is composed.
 * Read the returned state's `.value` INSIDE a `graphicsLayer { }` or draw
 * lambda (rule 2), not in composition.
 */
@Composable
fun rememberDecorativeTick(): State<Long> {
    DisposableEffect(Unit) {
        DecorativeClock.acquire()
        onDispose { DecorativeClock.release() }
    }
    return DecorativeClock.nanos
}

/**
 * Position within a repeating cycle, 0f (inclusive) to 1f (exclusive).
 * [offsetMs] staggers siblings (the classic three-dot delay).
 *
 * The last few nanoseconds of a period round to exactly 1f in single
 * precision; because phase is cyclic (1f and 0f are the same instant to every
 * consumer — 360° is 0°, ping-pong floors it, the shimmer wraps anyway) that
 * hit is folded onto 0f so the exclusive bound holds.
 */
fun decorativePhase(nanos: Long, periodMs: Int, offsetMs: Int = 0): Float {
    if (periodMs <= 0) return 0f
    val periodNs = periodMs * 1_000_000L
    val shifted = nanos - offsetMs * 1_000_000L
    val m = shifted % periodNs
    val pos = if (m < 0) m + periodNs else m
    val r = pos.toFloat() / periodNs
    return if (r >= 1f) 0f else r
}

/** 0 → 1 → 0 over one phase cycle: the `RepeatMode.Reverse` shape. */
fun decorativePingPong(phase: Float): Float {
    val p = phase - floor(phase)
    return if (p < 0.5f) p * 2f else (1f - p) * 2f
}

/**
 * [T-android-decorative-anim-perf] A drop-in replacement for Material's
 * indeterminate [androidx.compose.material3.CircularProgressIndicator] that
 * costs a layer property per tick instead of a frame-rate animation.
 *
 * Material's spinner owns a private `InfiniteTransition` running at the
 * panel's refresh rate, and nothing outside it can throttle that — measured
 * on a 90 Hz Pixel 6, wrapping it in a layer cut the *collateral* re-recording
 * of sibling text but the screen still produced 87 fps because the spinner
 * kept asking for every frame. It also drags a Material semantics node
 * (ProgressBarRangeInfo) along, and the semantics tree walk was itself
 * hundreds of samples per capture.
 *
 * This draws the same visual — a rotating arc — from the shared ~30 fps
 * [DecorativeClock], records the arc exactly once, and animates only
 * `rotationZ` on its own layer. No semantics, no per-frame recomposition, no
 * allocation on the frame path.
 *
 * Use it for decorative "работа идёт" spinners. Anything conveying real
 * determinate progress should stay a Material indicator so it keeps its
 * accessibility semantics.
 */
@Composable
fun DecorativeSpinner(
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 2.dp,
    /** Fraction of the circle the sweep covers. Material's is ~0.75 at its widest. */
    sweepFraction: Float = 0.75f,
    periodMs: Int = 900,
) {
    val tick = rememberDecorativeTick()
    Canvas(
        modifier = modifier.graphicsLayer {
            rotationZ = decorativePhase(tick.value, periodMs) * 360f
        },
    ) {
        val px = strokeWidth.toPx()
        val inset = px / 2f
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 360f * sweepFraction,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(size.width - px, size.height - px),
            style = Stroke(width = px, cap = StrokeCap.Round),
        )
    }
}
