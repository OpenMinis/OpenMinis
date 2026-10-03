package com.openminis.app.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.ui.components.rememberDecorativeTick
import com.openminis.app.ui.components.decorativePhase
import com.openminis.app.ui.components.decorativePingPong
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember

/**
 * [T-android-context-usage-hint] Rendering for the context-usage line and the
 * composer's inner glow.
 *
 * Split from [ContextUsageHint]'s pure model so the decision logic stays
 * JVM-testable; everything here needs a Composable scope or a theme colour and
 * is therefore verified on device rather than in unit tests.
 */

/**
 * Tier colours.
 *
 * Deliberately restrained, per the spec: this line appears while the user is
 * mid-task, and a saturated warning colour on the composer reads as an error
 * they must act on. Amber/red at reduced alpha says "worth knowing" without
 * hijacking attention. The two tiers are also distinguishable by the FIGURE
 * itself, so colour is reinforcement rather than the only signal — which is
 * what keeps this usable for a red/green colour-blind user.
 */
internal object ContextUsageColors {

    /** Number-segment colour inside the placeholder line. */
    @Composable
    fun textHighlight(tier: ContextUsage.Tier): Color = when (tier) {
        ContextUsage.Tier.NORMAL -> Color.Unspecified
        // Muted to sit in the PLACEHOLDER's register rather than shouting over
        // it. The surrounding label renders at onSurface alpha 0.25, so a
        // near-opaque saturated figure next to it read as an error badge
        // pasted onto a hint. These are desaturated toward the body text and
        // dropped to 0.55 alpha: still clearly amber/red, and still the
        // brightest thing on the line, but now the same VISUAL WEIGHT as the
        // text it is part of. Colour stays the reinforcement, never the only
        // signal — the figure itself carries the meaning.
        ContextUsage.Tier.WARNING -> Color(0xFF8A6A3A).copy(alpha = 0.55f)
        ContextUsage.Tier.CRITICAL -> Color(0xFF8E4B44).copy(alpha = 0.55f)
    }

    /** Inner-glow colour drawn just inside the composer's border. */
    fun glow(tier: ContextUsage.Tier): Color? = when (tier) {
        ContextUsage.Tier.NORMAL -> null
        ContextUsage.Tier.WARNING -> Color(0xFFFFB300)
        ContextUsage.Tier.CRITICAL -> Color(0xFFE53935)
    }

    /**
     * Base opacity of the glow. Critical sits slightly higher than warning for
     * added urgency, but both stay well under half — the glow is ambient, and
     * the composer must keep looking like an input field rather than an alert.
     */
    fun glowAlpha(tier: ContextUsage.Tier): Float = when (tier) {
        ContextUsage.Tier.NORMAL -> 0f
        // Walked down twice on user feedback: 0.28/0.24 -> 0.18/0.16 -> here.
        // The wider spread (22dp stroke + 16dp blur) means this alpha is
        // applied over a much larger area than the original tight band, so
        // the peak has to keep coming down for the glow to stay ambient. At
        // this level it reads as a tint on the composer wall rather than a
        // coloured border — which is the point: it should be noticeable in
        // peripheral vision without competing with the text for attention.
        ContextUsage.Tier.WARNING -> 0.11f
        ContextUsage.Tier.CRITICAL -> 0.10f
    }
}

/**
 * The full localized line with its number segments highlighted.
 *
 * Built by locating [ContextUsageHint.highlights] inside the RESOLVED string
 * rather than by concatenating coloured pieces: the resource carries
 * `%1$s`/`%2$s` and a translation is free to reorder or re-word around them,
 * so the only reliable way to colour the numbers is to find them after
 * formatting. A segment that cannot be found is simply left unstyled, so a
 * translation that alters the digits degrades to a plain line instead of
 * throwing.
 */
@Composable
internal fun contextUsageAnnotatedText(hint: ContextUsageHint): AnnotatedString {
    val percent = hint.highlights.getOrNull(0) ?: ""
    val size = hint.highlights.getOrNull(1) ?: ""
    val full = stringResource(R.string.chat_context_usage_hint, percent, size)
    val highlight = ContextUsageColors.textHighlight(hint.tier)
    return buildAnnotatedString {
        append(full)
        for (segment in hint.highlights) {
            if (segment.isEmpty()) continue
            val start = full.indexOf(segment)
            if (start < 0) continue
            addStyle(
                SpanStyle(color = highlight, fontWeight = FontWeight.Medium),
                start,
                start + segment.length,
            )
        }
    }
}

/**
 * Draws a soft inner glow along the composer's inside edge.
 *
 * `drawWithContent` + a radial brush clipped to a stroke keeps this purely a
 * paint pass: it adds no layout, consumes no touch input, and leaves the
 * caret, buttons and hit targets exactly where they were. Drawing AFTER the
 * content puts the glow over the field's own background without tinting the
 * typed text, and using the border inset rather than an outer shadow is what
 * makes it read as light from inside the field rather than a halo around it.
 *
 * Critical breathes (≈2.6s, ±30% of base alpha); warning is static. A moving
 * element is hard to ignore, which is right at 80% and excessive at 70%.
 */
@Composable
internal fun Modifier.contextUsageGlow(
    tier: ContextUsage.Tier,
    cornerRadius: Dp = 22.dp,
    /**
     * Stroke width before blurring.
     *
     * Widened from 13dp: the user asked for a larger, softer glow, and the
     * band's reach is roughly `thickness/2 + blurRadius` inward from the
     * edge. Growing both is what spreads the light deeper into the field
     * instead of hugging the border as a bright rim.
     */
    thickness: Dp = 22.dp,
    /**
     * Gaussian blur radius.
     *
     * Raised from 7dp to 16dp. A blur this large relative to the stroke is
     * what turns the band into a diffuse wash — the falloff gets longer and
     * its peak lower, which is the "fainter gradient" half of the request.
     */
    blurRadius: Dp = 16.dp,
    animate: Boolean = true,
): Modifier {
    val color = ContextUsageColors.glow(tier) ?: return this
    val base = ContextUsageColors.glowAlpha(tier)

    // [T-android-decorative-anim-perf] The breathing alpha used to be read
    // with `.value` right here, in composition — so while the tier was
    // CRITICAL the whole composer card recomposed on every one of the panel's
    // 90 frames per second. The value is now a State that is read inside the
    // draw lambda below (a draw-phase read: no recomposition), and it steps
    // from the shared ~30 fps decorative clock. A 2.6 s breath does not need
    // more.
    //
    // Not yet layer-isolated from the card's text: this is a draw modifier
    // wrapping the composer, so its invalidation still re-records the card.
    // Full isolation needs the glow drawn as a sibling overlay at the call
    // site, which touches the composer's hit-testing and IME layout and is a
    // separate change.
    val alphaState: State<Float>? = if (tier == ContextUsage.Tier.CRITICAL && animate) {
        val tick = rememberDecorativeTick()
        remember(tick, base) {
            derivedStateOf {
                base * (0.7f + 0.6f * decorativePingPong(decorativePhase(tick.value, 5200)))
            }
        }
    } else {
        null
    }

    return this.drawWithContent {
        drawContent()
        val alpha = alphaState?.value ?: base
        val strokePx = thickness.toPx()
        val radiusPx = cornerRadius.toPx()
        val blurPx = blurRadius.toPx()
        if (alpha <= 0.001f || strokePx <= 0f) return@drawWithContent

        // [T-android-glow-blur] Stroke -> Gaussian blur -> clip to the shape.
        //
        // The previous version faked the falloff with a vertical colour
        // gradient. That was the wrong tool twice over: `Brush.verticalGradient`
        // varies colour along the component's Y axis, not outward from the
        // border, so every pixel ACROSS the stroke's width had the same alpha
        // and it read as a hard-edged frame; and the stroke was inset by
        // `stroke/2`, which is what put a visible gap between the glow and the
        // composer's real rounded edge.
        //
        // A real BlurMaskFilter is what produces the soft decay — the blur
        // spreads the stroke's energy in both directions, and clipping to the
        // rounded rect throws away the outer half, leaving exactly the inner
        // falloff an inner glow is made of. Mirrors iOS `ComposerContextGlow`
        // (stroke 13 / blur 7 / clipShape), scaled to the same ratio in dp.
        //
        // BlurMaskFilter is plain android.graphics and works back to our
        // minSdk 26 — `Modifier.blur()` needs API 31, so it is not an option
        // here.
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val save = native.save()

            // The stroke is centred ON the real edge (no inset), so its inner
            // half covers the first `stroke/2` px inside the border and the
            // blur carries it further in. The outer half is clipped away.
            val outline = android.graphics.Path().apply {
                addRoundRect(
                    0f, 0f, size.width, size.height,
                    radiusPx, radiusPx,
                    android.graphics.Path.Direction.CW,
                )
            }
            // Clip FIRST: everything drawn after this is confined to the
            // composer's outline, which is what makes the effect an inner glow
            // rather than a halo around the card.
            native.clipPath(outline)

            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = strokePx
                this.color = android.graphics.Color.argb(
                    (alpha * 255f).toInt().coerceIn(0, 255),
                    (color.red * 255f).toInt(),
                    (color.green * 255f).toInt(),
                    (color.blue * 255f).toInt(),
                )
                // NORMAL blurs both sides of the stroke; the clip above keeps
                // only the inward half.
                maskFilter = android.graphics.BlurMaskFilter(
                    blurPx,
                    android.graphics.BlurMaskFilter.Blur.NORMAL,
                )
            }
            native.drawPath(outline, paint)
            native.restoreToCount(save)
        }
    }
}
