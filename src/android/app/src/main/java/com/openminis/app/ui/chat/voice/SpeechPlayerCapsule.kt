package com.openminis.app.ui.chat.voice

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.speech.VoiceOutputState
import kotlinx.coroutines.delay

/**
 * [T-android-tts-capsule] Floating speech-player control for "Read replies"
 * TTS. Port of iOS `SpeechPlayerControl` (SpeechPlayerControl.swift), with the
 * same two-level design:
 *
 *  • compact — a small circular speaker button. Single tap → expand;
 *    double tap → temporary mute. Speed badge when >1×; rotating arc while
 *    cloud TTS is synthesizing.
 *  • expand  — a capsule: speaker (mute toggle) · model chip (tap to switch
 *    the TTS model) · speed chip (tap to cycle 1×→1.25×→1.5×→2×) · close (×,
 *    turns read-replies fully off).
 *
 * Lifecycle mirrors iOS: enabling read-replies opens it EXPANDED; the first
 * utterance actually speaking collapses it to compact; 10 s idle while
 * expanded collapses it; tapping outside collapses it.
 *
 * [T-android-tts-capsule-drag] Drag-to-reposition is now ported: the capsule
 * can be dragged and remembers where it was left (persisted in dp, relative to
 * the resting corner, like iOS).
 *
 * [T-android-tts-capsule-drag-vertical] Movement is VERTICAL ONLY — the capsule
 * slides up and down one fixed edge line and never moves horizontally. This is
 * narrower than iOS, which allows free 2D dragging; it is a deliberate product
 * choice, not a porting gap.
 *
 * The clamp is deliberately simpler than iOS's. iOS computes bounds from window
 * geometry and safe-area insets because its control is mounted at APP ROOT,
 * where nothing else constrains it. The Android capsule is mounted inside
 * ChatScreen's `Box(Modifier.weight(1f))`, which already ends above the composer
 * and lives under `.imePadding()` — so "never cover the input bar" and "never
 * fight the keyboard" fall out of the layout, and the clamp only has to keep the
 * capsule inside that box. Same guarantees, expressed through layout instead of
 * inset arithmetic.
 *
 * Still not ported: iOS's asymmetric rise/settle ratchet over an oscillating
 * obstacle set (its `recompute`/`descentTask`). Android's one obstacle — the
 * floating tool bar — has a stable measured height within a turn, so the plain
 * animated lift below suffices.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeechPlayerCapsule(
    modifier: Modifier = Modifier,
    /**
     * [T-android-tts-capsule-avoid] Pixels of UI stacked at the host Box's
     * bottom edge that the capsule must clear — the floating tool status bar,
     * measured by ChatScreen's existing onGloballyPositioned. 0 when absent.
     * The composer itself needs no avoidance: the host Box ends above it.
     * This is the simplified stand-in for iOS's protectedRects/requiredLift
     * machinery — deterministic lift over the one full-width obstacle that
     * actually shares the capsule's corner.
     */
    bottomObstructionPx: Int = 0,
    /**
     * [T-android-tts-capsule-avoid-fabs] Additional obstruction expressed as
     * the TOP edge (dp above the host Box's bottom) of the scroll-FAB stack
     * sharing this corner — 0 when no FAB is visible. The caller derives it
     * from the FABs' own placement constants, so capsule and FABs can't
     * disagree about where the FABs are.
     */
    additionalObstructionDp: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { VoiceOutputState.init(context) }

    val enabled by VoiceOutputState.isEnabled.collectAsState()
    val muted by VoiceOutputState.isMuted.collectAsState()
    val speed by VoiceOutputState.speed.collectAsState()
    val speaking by VoiceOutputState.isSpeaking.collectAsState()
    val synthesizing by VoiceOutputState.isSynthesizing.collectAsState()
    val modelLabel by VoiceOutputState.activeModelLabel.collectAsState()
    // [T-android-system-voice-catalog] The picked system voice's label shows
    // on the chip IMMEDIATELY after selection (iOS refreshModelLabel reads the
    // configured selection), not only once the first utterance speaks.
    val systemVoiceLabel by VoiceOutputState.systemVoiceLabel.collectAsState()

    // Visible whenever read-replies is ON (so it can be turned off from
    // anywhere) or something is actively playing/synthesizing.
    val isActive = enabled || speaking || synthesizing
    if (!isActive) return

    // [T-android-tts-capsule-default-collapsed] Starts COMPACT. The first cut
    // initialised this to true, which read as "opens expanded at every mount":
    // with read-replies persisted ON, every chat entry popped the full control
    // strip for 10 s before settling. iOS's rule is narrower — the capsule
    // expands at the MOMENT read-replies is enabled (so the user sees the
    // controls they just summoned), and otherwise rests compact. The
    // enable-edge effect below reproduces exactly that: it skips the mount
    // snapshot and reacts only to false→true flips while visible.
    // [T-android-tts-capsule-ios-parity] No expand-on-enable either: iOS's
    // header comment claims "enabling opens it EXPANDED", but the
    // implementation has no isEnabled→expand hook and the iPhone 11 confirms
    // it — toggling Read replies shows the capsule COMPACT (speed badge and
    // all). Screenshots beat stale comments; the capsule only ever expands on
    // an explicit tap.
    var expanded by remember { mutableStateOf(false) }
    var idleToken by remember { mutableIntStateOf(0) }
    var showModelPicker by remember { mutableStateOf(false) }

    // First real utterance → collapse to compact (iOS onChange(isReadingAloud)).
    LaunchedEffect(speaking) { if (speaking && expanded) expanded = false }

    // 10 s idle in expand mode → collapse. Any interaction bumps the token.
    LaunchedEffect(expanded, idleToken) {
        if (!expanded) return@LaunchedEffect
        delay(10_000)
        expanded = false
    }
    fun bumpIdle() { idleToken++ }

    // [T-android-tts-capsule-drag] BoxWithConstraints so the clamp has real
    // bounds to work against. maxWidth/maxHeight are THIS box's, and the host
    // (ChatScreen's `Box(Modifier.weight(1f))`) already ends above the composer
    // and sits inside a Column with `.imePadding()`. So the two hard
    // requirements — never cover the input bar, never fight the keyboard — are
    // satisfied by construction: the box itself shrinks when the IME opens, and
    // the clamp below simply keeps the capsule inside it. That is the Android
    // equivalent of iOS computing `lowestTop = screen.maxY - bottomReserve`,
    // reached through layout rather than arithmetic over window insets.
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // Tap-outside-to-collapse scrim, only while expanded (iOS tap-catcher).
        if (expanded) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { expanded = false } },
            )
        }
        // [T-android-tts-capsule-avoid] Animated lift above the floating tool
        // bar. iOS's asymmetric ratchet (rise instantly, settle before
        // descending) exists because its obstacle set oscillates per token;
        // the tool bar's measured height is stable within a turn, so a plain
        // animated padding suffices here.
        val liftDp = with(androidx.compose.ui.platform.LocalDensity.current) {
            bottomObstructionPx.toDp()
        }
        // Lift above whichever obstacle reaches higher: the full-width tool
        // bar (measured px) or the scroll-FAB stack (placement-derived top
        // edge). The capsule's resting bottom padding is 12dp, so clearing an
        // obstacle whose top sits at `additionalObstructionDp` needs
        // `top + 8dp gap − 12dp base`.
        val toolbarLift = if (bottomObstructionPx > 0) liftDp + 8.dp else 0.dp
        val fabLift =
            if (additionalObstructionDp > 0.dp) additionalObstructionDp + 8.dp - 12.dp else 0.dp
        val animatedLift by androidx.compose.animation.core.animateDpAsState(
            targetValue = maxOf(toolbarLift, fabLift, 0.dp),
            label = "capsuleLift",
        )
        // ── Drag state ───────────────────────────────────────────────────────
        // [T-android-tts-capsule-drag-vertical] VERTICAL ONLY: the capsule
        // slides along one vertical line and never moves horizontally, so the
        // gesture's dx is discarded and only dy accumulates. There is
        // deliberately no `dragX` state — pinning x to the resting edge is
        // stronger than storing a value we always set to 0, because it makes a
        // horizontal drift impossible by construction rather than by
        // convention.
        //
        // Offset in PX from the resting bottom-end corner; y<0 moves up. Kept
        // in px (not dp) so the gesture's own px deltas add without a
        // conversion per pointer event.
        val density = androidx.compose.ui.platform.LocalDensity.current
        var dragY by remember { mutableStateOf(0f) }
        var isDragging by remember { mutableStateOf(false) }
        // The capsule's measured HEIGHT — needed by the vertical clamp, and it
        // changes between compact (40dp circle) and expanded, so a fixed
        // constant would let one of the two forms overshoot the box edge.
        // Width is no longer measured: with x pinned, nothing consumes it.
        var capsuleH by remember { mutableStateOf(0) }

        val edgeMarginPx = with(density) { 16.dp.toPx() }
        val restBottomPx = with(density) { (12.dp + animatedLift).toPx() }
        val maxHPx = with(density) { maxHeight.toPx() }

        // Clamp a proposed VERTICAL offset so the capsule stays fully inside
        // the host box, `edgeMargin` away from its top and bottom. Mirrors iOS
        // `clampedOffset`, but expressed against this box instead of the
        // window: the box's own bounds already encode the composer and IME
        // exclusions, so "can't be dragged into the input bar" is inherited.
        //
        // `coerceIn` requires min <= max; when the box is shorter than the
        // capsule plus its margins (a very short box mid-IME animation) the
        // range inverts, so it is guarded rather than allowed to throw.
        fun clampY(py: Float): Float {
            if (capsuleH <= 0 || maxHPx <= 0f) return py
            // Resting top of the capsule inside this box.
            val restTop = maxHPx - restBottomPx - capsuleH
            val minY = edgeMarginPx - restTop
            val maxY = (maxHPx - edgeMarginPx - capsuleH) - restTop
            return if (minY <= maxY) py.coerceIn(minY, maxY) else 0f
        }

        // Restore the persisted position once the capsule has been measured
        // (the clamp is a no-op before that). Re-runs when the box or capsule
        // size changes — rotation, IME, compact↔expanded — so a saved offset
        // that is now out of bounds is pulled back in rather than stranding the
        // capsule off-screen. Skipped while dragging so it can't fight the
        // finger.
        // [T-android-tts-capsule-drag-vertical] Only the Y component is read
        // back. A previously-saved X (from the two-axis build) is ignored
        // rather than migrated: the capsule simply returns to the resting edge,
        // which is where vertical-only mode always puts it. That is the
        // gentlest upgrade — the position shifts horizontally at most once, to
        // the edge the user can now never leave — and it needs no migration
        // code. The stale X stays on disk harmlessly and is overwritten as 0
        // on the next drag release.
        val savedOffset by VoiceOutputState.dragOffsetDp.collectAsState()
        LaunchedEffect(savedOffset, capsuleH, maxHPx, isDragging) {
            if (isDragging || capsuleH <= 0) return@LaunchedEffect
            val targetY = savedOffset?.let { (_, yDp) ->
                with(density) { yDp.dp.toPx() }
            } ?: 0f
            dragY = clampY(targetY)
        }

        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 12.dp + animatedLift)
                // x is always 0: the capsule stays pinned to the resting edge.
                .offset { IntOffset(0, dragY.toInt()) }
                .onGloballyPositioned { capsuleH = it.size.height }
                // [T-android-tts-capsule-drag] Drag lives on the OUTER box, not
                // on the inner AnimatedContent children: those are swapped on
                // every compact↔expanded transition, which would cancel an
                // in-flight gesture.
                //
                // [T-android-tts-capsule-drag-vertical] `detectVerticalDragGestures`
                // rather than `detectDragGestures` with the dx thrown away: it
                // discards the horizontal component at the source AND uses a
                // vertical-only touch slop, so a mostly-sideways swipe never
                // starts a drag at all instead of starting one that then
                // refuses to move. The capsule cannot drift horizontally
                // because no code path can write an x offset.
                //
                // Keyed on `expanded` so the handler is re-installed when the
                // content swaps — without the key the pointerInput would keep a
                // stale closure over the old size.
                .pointerInput(expanded) {
                    detectVerticalDragGestures(
                        onDragStart = { isDragging = true },
                        onDragEnd = {
                            isDragging = false
                            bumpIdle()
                            // Persist ONCE per gesture, in dp so the value
                            // survives a density change (iOS saves on release
                            // for the same reason). x is written as 0 — the
                            // capsule has no horizontal freedom any more, so
                            // that is its true offset, and it also clears a
                            // stale x left by the earlier two-axis build.
                            with(density) {
                                VoiceOutputState.setCapsuleDragOffsetDp(
                                    0f,
                                    dragY.toDp().value,
                                )
                            }
                        },
                        onDragCancel = { isDragging = false },
                    ) { change, dragAmountY ->
                        // Consume so the parent list can't also scroll from the
                        // same pointer stream.
                        change.consume()
                        dragY = clampY(dragY + dragAmountY)
                    }
                },
        ) {
            AnimatedContent(
                targetState = expanded,
                transitionSpec = {
                    (scaleIn(initialScale = 0.6f) + fadeIn())
                        .togetherWith(scaleOut(targetScale = 0.6f) + fadeOut())
                },
                label = "capsule",
            ) { isExpanded ->
                if (isExpanded) {
                    ExpandedCapsule(
                        muted = muted,
                        synthesizing = synthesizing,
                        speed = speed,
                        modelLabel = modelLabel
                            ?: systemVoiceLabel
                            ?: stringResource(R.string.tts_capsule_system_engine),
                        onMuteToggle = { VoiceOutputState.setMuted(!muted); bumpIdle() },
                        onModelTap = { showModelPicker = true; bumpIdle() },
                        onSpeedTap = { VoiceOutputState.nextSpeed(); bumpIdle() },
                        onClose = { VoiceOutputState.setEnabled(false) },
                    )
                } else {
                    CompactSpeaker(
                        muted = muted,
                        synthesizing = synthesizing,
                        speed = speed,
                        onTap = { expanded = true; bumpIdle() },
                        onDoubleTap = { VoiceOutputState.setMuted(!muted) },
                    )
                }
            }
        }
    }

    if (showModelPicker) {
        // [T-android-tts-capsule-unified-picker] Same unified voice picker the
        // rest of the app uses (iOS: the capsule opens UnifiedModelPicker with
        // config .voiceOutput()): bound Voice Output group + System TTS + every
        // provider section with audio-output models and Quick Test — replacing
        // the first-cut flat candidate list.
        val repo = (LocalContext.current.applicationContext as? MinisApp)?.providerRepository
        if (repo != null) {
            VoiceOutputPickerSheet(
                providerRepository = repo,
                onDismiss = { showModelPicker = false; bumpIdle() },
            )
        }
    }
}

@Composable
private fun CompactSpeaker(
    muted: Boolean,
    synthesizing: Boolean,
    speed: Float,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
) {
    Box {
        Box(
            Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), CircleShape)
                .border(0.5.dp, Color.Gray.copy(alpha = 0.25f), CircleShape)
                .pointerInput(muted) {
                    detectTapGestures(
                        onDoubleTap = { onDoubleTap() },
                        onTap = { onTap() },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            SpeakerGlyph(muted = muted, synthesizing = synthesizing, ring = 40.dp)
        }
        // Speed badge, bottom-trailing, only when >1× (iOS compactButton).
        //
        // The (6, 4) offset is load-bearing, not decoration: BottomEnd alone
        // keeps the badge INSIDE the 40dp circle, where it sits squarely on
        // top of the speaker glyph and hides it (reported at 1.25×, where the
        // badge is widest). iOS pushes it out past the corner with the same
        // .offset(x: 6, y: 4) so badge and glyph never overlap.
        if (speed > 1.0f) {
            Text(
                VoiceOutputState.speedLabel(speed),
                fontSize = 9.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 6.dp, y = 4.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun ExpandedCapsule(
    muted: Boolean,
    synthesizing: Boolean,
    speed: Float,
    modelLabel: String,
    onMuteToggle: () -> Unit,
    onModelTap: () -> Unit,
    onSpeedTap: () -> Unit,
    onClose: () -> Unit,
) {
    // [T-android-tts-capsule-height] Height is PINNED to the compact button's
    // 40dp so expand↔collapse is a pure width morph with no vertical jump.
    // Letting content drive the height made the capsule ~56dp: Compose Text
    // reserves the font's full ascent/descent leading (includeFontPadding) on
    // top of the padding — the same inflation the read-replies pill had to
    // correct. A fixed frame with centered content sidesteps per-text
    // lineHeight surgery entirely.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(40.dp)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f), RoundedCornerShape(50))
            .border(0.5.dp, Color.Gray.copy(alpha = 0.25f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp),
    ) {
        // Speaker = mute toggle (temporary silence; capsule stays visible).
        Box(
            Modifier.size(28.dp).clickable(onClick = onMuteToggle),
            contentAlignment = Alignment.Center,
        ) {
            SpeakerGlyph(muted = muted, synthesizing = synthesizing, ring = 28.dp)
        }
        Spacer(Modifier.width(10.dp))
        // Model chip — tap to switch the TTS model.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.widthIn(max = 130.dp).clickable(onClick = onModelTap),
        ) {
            Icon(
                Icons.Default.GraphicEq,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                modelLabel,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(3.dp))
            // [T-android-tts-capsule-unified-picker] iOS shows
            // chevron.up.chevron.down after the model name — the affordance
            // that the chip is a switcher, not a label. UnfoldMore is the
            // Material equivalent (stacked up/down chevrons).
            Icon(
                Icons.Default.UnfoldMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(12.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        // Speed chip — cycles the steps. Fixed size so cycling doesn't jitter.
        // [T-android-tts-capsule-height] The chip is PINNED to 22dp with the
        // label centered both ways: vertical padding + Text's default font
        // padding stacked up into a visibly over-tall capsule, and the
        // fixed-width label rendered left-aligned (start-anchored) inside its
        // 30dp box, which read as off-center text.
        Box(
            Modifier
                .size(width = 46.dp, height = 22.dp)
                .background(
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f),
                    RoundedCornerShape(50),
                )
                .clickable(onClick = onSpeedTap),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                VoiceOutputState.speedLabel(speed),
                maxLines = 1,
                color = if (speed > 1.0f) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = androidx.compose.ui.text.TextStyle(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 11.sp,
                    platformStyle = androidx.compose.ui.text.PlatformTextStyle(
                        includeFontPadding = false,
                    ),
                ),
            )
        }
        Spacer(Modifier.width(10.dp))
        // Close — read-replies fully off (capsule hides).
        Icon(
            Icons.Default.Close,
            contentDescription = stringResource(R.string.standard_sheet_close),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp).clickable(onClick = onClose),
        )
    }
}

/** Speaker icon with a rotating 75° arc while cloud TTS is synthesizing. */
@Composable
private fun SpeakerGlyph(muted: Boolean, synthesizing: Boolean, ring: androidx.compose.ui.unit.Dp) {
    Box(contentAlignment = Alignment.Center) {
        Icon(
            if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = null,
            tint = if (muted) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        if (synthesizing && !muted) {
            val transition = rememberInfiniteTransition(label = "synthArc")
            val angle by transition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
                label = "synthAngle",
            )
            val arcColor = MaterialTheme.colorScheme.primary
            Canvas(Modifier.size(ring)) {
                drawArc(
                    color = arcColor,
                    startAngle = angle,
                    sweepAngle = 75f,
                    useCenter = false,
                    style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round),
                    topLeft = Offset(0.75.dp.toPx(), 0.75.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(
                        size.width - 1.5.dp.toPx(),
                        size.height - 1.5.dp.toPx(),
                    ),
                )
            }
        }
    }
}
