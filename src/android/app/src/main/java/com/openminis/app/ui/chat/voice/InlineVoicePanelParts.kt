package com.openminis.app.ui.chat.voice

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.speech.RecognitionState
import com.openminis.app.speech.SpeechRecognitionManager
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring


/*
 * Leaf building blocks of InlineVoiceInputPanel: the mic disc, transcribing
 * ring, mini waveform, status label and engine-unavailable notice. Extracted
 * so the panel file stays under the architecture-policy ceiling; the parts
 * are stateless and parameter-driven — no panel state crosses this seam.
 */

internal val CompactHeight = 92.dp
internal val ExpandedBase = 168.dp
internal val CompactMic = 60.dp
internal val ExpandedMic = 72.dp

/**
 * [T-android-voice-mic-shadow] The original `shadow(6.dp)` look, drawn
 * manually so it has no downward cast.
 *
 * Compose's `shadow()` exposes no offset. Elevation feeds a renderer that
 * lights the shape from a point above the screen, and the resulting spot
 * shadow is displaced down the y-axis in proportion to the elevation — so the
 * only way to shrink the displacement through that API is to shrink the
 * elevation, which shrinks the shadow itself and changes the button's look.
 * (The light position IS adjustable, but it is a window-level attribute and
 * would move every shadow in the app.)
 *
 * Drawing it here sidesteps both problems: a radial gradient centred on the
 * circle, painted behind the content, gives the same soft halo with zero
 * offset. The radius extends [spread] beyond the circle's edge and the alpha
 * ramp is weighted so most of the darkness sits close in, matching the falloff
 * of the elevation shadow it replaces.
 */
internal fun Modifier.micShadowNoOffset(
    spread: androidx.compose.ui.unit.Dp,
    color: Color = Color.Black,
    maxAlpha: Float = 0.10f,
): Modifier = this.drawBehind {
    val r = size.minDimension / 2f
    val spreadPx = spread.toPx()
    val outer = r + spreadPx
    // Stops chosen so the gradient is near-opaque at the circle's edge and
    // fades out over the spread, rather than easing linearly across the whole
    // radius (which reads as a wide, flat smudge).
    val edge = r / outer
    drawCircle(
        brush = androidx.compose.ui.graphics.Brush.radialGradient(
            colorStops = arrayOf(
                0f to color.copy(alpha = maxAlpha),
                edge to color.copy(alpha = maxAlpha),
                (edge + (1f - edge) * 0.45f) to color.copy(alpha = maxAlpha * 0.35f),
                1f to Color.Transparent,
            ),
            center = center,
            radius = outer,
        ),
        radius = outer,
        center = center,
    )
}

@Composable
internal fun MicCircleButton(
    diameter: androidx.compose.ui.unit.Dp,
    isRecording: Boolean,
    isTranscribing: Boolean,
    levels: List<Float>,
    enabled: Boolean,
    onTap: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(diameter)
            // [T-android-voice-mic-shadow] Same white circle and same soft
            // halo as before; only the downward cast is gone. See
            // micShadowNoOffset for why this is drawn rather than expressed as
            // an elevation.
            .micShadowNoOffset(10.dp)
            .background(Color.White, CircleShape)
            .clip(CircleShape)
            .clickable(enabled = enabled) { onTap() },
        contentAlignment = Alignment.Center,
    ) {
        if (isTranscribing) {
            TranscribingRing(
                diameter = diameter,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        when {
            isRecording -> InlineMiniWaveform(levels = levels)
            isTranscribing -> Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.voice_panel_cancel_transcription),
                tint = Color.Black,
                modifier = Modifier.size(22.dp),
            )
            else -> Icon(
                Icons.Default.Mic,
                contentDescription = stringResource(R.string.voice_panel_toggle_recording),
                tint = Color.Black,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

/**
 * [T-android-transcribing-ring] The spinning arc shown while transcription is
 * in flight, drawn ON the white mic button's rim.
 *
 * Port of iOS InlineVoiceInputView's ring: a Circle trimmed to 75/360 with a
 * 2.5pt round-cap stroke, at the SAME diameter as the white disc, rotating once
 * every 0.9s.
 *
 * Drawn with Canvas rather than CircularProgressIndicator because that
 * composable insets its arc — it reserves its own internal padding and centres
 * the stroke inside the layout bounds — so even at fillMaxSize the arc floated
 * well inside the disc instead of tracing its edge (the reported symptom, made
 * worse here by an extra 3.dp padding). Here the arc's radius is set explicitly
 * to (diameter - stroke) / 2, which puts the stroke's CENTRELINE exactly on the
 * circle's edge, so it reads as riding the rim like iOS.
 */
@Composable
internal fun TranscribingRing(
    diameter: androidx.compose.ui.unit.Dp,
    color: Color,
) {
    val stroke = 2.5.dp
    val transition = rememberInfiniteTransition(label = "transcribing")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep",
    )
    Canvas(modifier = Modifier.size(diameter)) {
        val strokePx = stroke.toPx()
        // Inset by half the stroke so the stroke's centreline sits on the rim.
        val inset = strokePx / 2f
        drawArc(
            color = color,
            startAngle = angle,
            sweepAngle = 75f,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
            size = androidx.compose.ui.geometry.Size(
                size.width - strokePx,
                size.height - strokePx,
            ),
            style = Stroke(width = strokePx, cap = StrokeCap.Round),
        )
    }
}

/** 7 dark bars inside the white mic button (iOS InlineMiniWaveform). */
@Composable
internal fun InlineMiniWaveform(levels: List<Float>) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        // [T-android-voice-parity] Indexed, and each bar animates.
        //
        // Two fixes over the previous `forEach { level -> Box(...) }`:
        //  1. The bars were redrawn with raw values on every StateFlow
        //     emission — no smoothing at all, where iOS runs a per-bar spring
        //     (response 0.18, dampingFraction 0.6 —
        //     InlineVoiceInputView.swift:1302). Android's level source is also
        //     coarser (onRmsChanged at ~10-20 Hz vs iOS's ~94 Hz PCM tap), so
        //     unsmoothed bars step visibly. `spring` with dampingRatio 0.6 and
        //     stiffness 900 is the closest Compose analogue to a 0.18 s spring.
        //  2. `forEach` gave Compose no per-slot identity, so a shifted list
        //     re-associated animation state to the wrong bar. The index is a
        //     stable slot key: bar N keeps its own animation across frames
        //     while the VALUE flowing through it shifts left.
        val tail = levels.takeLast(7)
        tail.forEachIndexed { index, level ->
            val animated by animateFloatAsState(
                targetValue = level,
                animationSpec = spring(dampingRatio = 0.6f, stiffness = 900f),
                label = "voiceBar$index",
            )
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((5 + animated * 21).dp.coerceAtMost(26.dp))
                    .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(50)),
            )
        }
    }
}

/** Delete key: tap = one char, hold = repeat every 120ms (iOS VoiceDeleteButton). */
@Composable
internal fun VoiceDeleteButton(onDeleteOne: () -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var repeating by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(47.dp)
            .background(ChatColors.inputIconBg, CircleShape)
            .border(0.5.dp, ChatColors.inputIconBorder, CircleShape)
            .clip(CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onDeleteOne() },
                    onPress = {
                        val job = scope.launch {
                            delay(450)
                            repeating = true
                            while (repeating) {
                                onDeleteOne()
                                delay(120)
                            }
                        }
                        tryAwaitRelease()
                        repeating = false
                        job.cancel()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.Backspace,
            contentDescription = stringResource(R.string.voice_panel_delete),
            tint = ChatColors.secondaryText,
            modifier = Modifier.size(21.dp),
        )
    }
}

@Composable
internal fun CircleIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(30.dp)
            .background(ChatColors.inputIconBg, CircleShape)
            .border(0.5.dp, ChatColors.inputIconBorder, CircleShape)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = ChatColors.secondaryText,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
internal fun statusLabel(
    permissionDenied: Boolean,
    isEditing: Boolean,
    isRecording: Boolean,
    isTranscribing: Boolean,
    transcript: String,
    recordingTipIndex: Int,
    resultTipIndex: Int,
): String {
    if (permissionDenied) return stringResource(R.string.voice_panel_permission_denied)
    if (isEditing) return stringResource(R.string.voice_panel_editing)
    if (isRecording) {
        // [T-android-voice-parity] iOS cycles FOUR entries here, with
        // "Listening…" deliberately repeated at index 1 and 3 so the steady
        // label occupies half the cycle and the actionable hint doesn't nag
        // (InlineVoiceInputView.swift:1123-1130). We mirror that rhythm.
        //
        // iOS's other actionable slot is "Long press to paste"; Android does
        // NOT have that gesture — long-pressing the transcript CLEARS it here
        // (see onLongPress -> onClearAll below). Advertising a paste gesture
        // that instead wipes the user's text would be worse than a shorter
        // cycle, so that slot carries the delete hint the panel actually
        // implements.
        val tips = listOf(
            stringResource(R.string.voice_panel_tip_tap_to_transcribe),
            stringResource(R.string.voice_panel_tip_listening),
            stringResource(R.string.voice_panel_tip_hold_delete),
            stringResource(R.string.voice_panel_tip_listening),
        )
        return tips[recordingTipIndex % tips.size]
    }
    if (isTranscribing) return stringResource(R.string.voice_panel_recognizing)
    if (transcript.isNotEmpty()) {
        val tips = listOf(
            stringResource(R.string.voice_panel_tip_double_tap_edit),
            stringResource(R.string.voice_panel_tip_tap_to_continue),
            stringResource(R.string.voice_panel_tip_hold_delete),
        )
        return tips[resultTipIndex % tips.size]
    }
    return stringResource(R.string.voice_panel_tap_to_speak)
}

/**
 * [T-android-voice-entry-always-available] Shown in place of the recording UI
 * when no speech engine can transcribe right now.
 *
 * This exists because the composer's mic/keyboard toggle is no longer hidden
 * when speech is unavailable — hiding it was what stranded users inside voice
 * mode. The control stays, so this panel has to answer "why is nothing
 * happening?" and offer the two real fixes:
 *
 *  - the device's speech service (Google app / OEM equivalent) is missing or
 *    disabled → open system voice-input settings;
 *  - no ASR provider is configured in Minis → open Provider settings.
 *
 * Leaving is always available: the toggle in the composer is untouched.
 */
@Composable
internal fun VoiceEngineUnavailableNotice(
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (expanded) ExpandedBase else CompactHeight)
            .animateContentSize(animationSpec = tween(280))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            CircleIconButton(
                icon = if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                contentDescription = stringResource(
                    if (expanded) R.string.voice_panel_collapse else R.string.voice_panel_expand,
                ),
                modifier = Modifier.align(Alignment.TopStart),
            ) { onToggleExpanded() }

            Text(
                text = stringResource(R.string.voice_panel_no_engine_title),
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 44.dp),
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = stringResource(R.string.voice_panel_no_engine_body),
            style = TextStyle(fontSize = 12.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            NoticeActionButton(stringResource(R.string.voice_panel_no_engine_open_system)) {
                // Best-effort: the exact voice-input screen varies by OEM, so
                // fall back to the app's own settings page rather than crashing
                // on a device that doesn't expose the specific action.
                val opened = runCatching {
                    ctx.startActivity(
                        android.content.Intent("android.settings.VOICE_INPUT_SETTINGS")
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    true
                }.getOrDefault(false)
                if (!opened) {
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            NoticeActionButton(stringResource(R.string.voice_panel_no_engine_open_providers)) {
                runCatching {
                    ctx.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("minis://settings/providers"),
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
    }
}

/** Small pill button used by [VoiceEngineUnavailableNotice]. */
@Composable
internal fun NoticeActionButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(ChatColors.inputIconBg, RoundedCornerShape(14.dp))
            .border(0.5.dp, ChatColors.inputIconBorder, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = TextStyle(fontSize = 12.sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
