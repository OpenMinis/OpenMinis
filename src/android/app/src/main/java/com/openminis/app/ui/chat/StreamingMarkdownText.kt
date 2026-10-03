package com.openminis.app.ui.chat

import android.content.Context
import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import android.widget.Toast
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import com.openminis.app.ui.DisplayBitmapLimits.limitDisplaySize
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.io.File
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle

// ─── MinisTextKit hook ────────────────────────────────────────────────────────
// Each markdown fragment renders inside a [MarkdownBlock] / [RenderBlock]
// scope that provides a [TextShardId] via [LocalShardId]. [MdText] reads it,
// registers a [TextShard] with the ambient [SelectionController] (if any),
// and draws the selection highlight inside its existing drawBehind. The
// indirection lets MdText stay markdown-agnostic — paragraph, heading,
// blockquote, table cell, etc. all participate uniformly without each
// caller having to plumb a per-text-node identifier.
val LocalShardId = compositionLocalOf<TextShardId?> { null }

/**
 * [T-android-markdown-longtext-selection-broken] Per-MdText sub-id allocator.
 *
 * A single markdown fragment ([MarkdownBlock] / [StreamingMarkdownText]) is
 * parsed into many [MdBlock]s and each is rendered by a [RenderBlock] that may
 * itself emit several [MdText]s (every list item, table cell, blockquote line,
 * heading, paragraph…). ALL of them read the same ambient [LocalShardId], so
 * before this fix they registered [TextShard]s under one identical
 * [TextShardId] key — and `SelectionController.shards` is a map keyed by id, so
 * each registration overwrote the previous one. Only the LAST MdText in a
 * fragment survived in the registry, so a long (multi-paragraph) reply was
 * un-selectable except for its final text node; short single-paragraph replies
 * happened to have exactly one MdText and worked, which is why the regression
 * looked length-dependent.
 *
 * This allocator hands each MdText a stable, unique index within its fragment.
 * `remember { allocator.next() }` runs once per MdText composition slot, so the
 * index is assigned in first-composition order and survives recomposition
 * (Compose re-runs `remember`s in the same slot order). The index is appended
 * to the base shardId so every text node gets a distinct [TextShardId] and all
 * register independently.
 */
internal class ShardSubIndexAllocator {
    private var counter = 0
    fun next(): Int = counter++
}

internal val LocalShardSubIndexAllocator = compositionLocalOf<ShardSubIndexAllocator?> { null }

/**
 * [T-android-markdown-longtext-selection-broken] Provide a per-fragment
 * [ShardSubIndexAllocator] so every [MdText] composed under [content] gets a
 * distinct shard sub-index. The allocator is `remember`ed once per fragment
 * composable instance (NOT keyed on the block list): its counter is monotonic,
 * so when blocks stream in / change, newly-added MdText slots draw fresh
 * indices while existing slots keep theirs — indices never collide. Each
 * MdText reads it via [LocalShardSubIndexAllocator].
 */
@Composable
private fun ShardSubIndexScope(content: @Composable () -> Unit) {
    val allocator = remember { ShardSubIndexAllocator() }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalShardSubIndexAllocator provides allocator,
        content = content,
    )
}

/** Selection-highlight fill color. Resolved per-composition for theme support. */
@Composable
@androidx.compose.runtime.ReadOnlyComposable
private fun currentSelectionHighlightColor(): Color {
    // Match Android's default textSelectHandle tint at ~30% alpha so it
    // visually overlays without obscuring the glyphs underneath.
    val accent = androidx.compose.material3.MaterialTheme.colorScheme.primary
    return accent.copy(alpha = 0.28f)
}

// ─── Markdown color palette — resolved per-composition via currentMdColors() ──
private data class MdColors(
    val text: Color,
    val codeText: Color,
    val codeBg: Color,
    val inlineCodeText: Color,
    val inlineCodeBg: Color,
    val link: Color,
    val blockquote: Color,
    val divider: Color,
    val tableBorder: Color,
    val tableHeaderBg: Color,
)

@Composable
@androidx.compose.runtime.ReadOnlyComposable
private fun currentMdColors(): MdColors {
    val c = com.openminis.app.ui.theme.LocalChatPalette.current
    return MdColors(
        text = c.primaryText,
        codeText = c.codeBlockText,
        codeBg = c.codeBlockBg,
        inlineCodeText = c.inlineCodeText,
        inlineCodeBg = c.inlineCodeBg,
        link = c.link,
        blockquote = c.secondaryText,
        divider = c.separator,
        tableBorder = c.tableBorder,
        tableHeaderBg = c.secondaryBg,
    )
}

private val MdCodeLangColor = Color.White.copy(alpha = 0.5f)

val LocalMarkdownFontScale = compositionLocalOf { 1f }

/** Handler invoked when a markdown URL span is tapped. Provided by ChatScreen. */
val LocalMarkdownUrlClickHandler = compositionLocalOf<((String) -> Unit)?> { null }

/**
 * [T-android-usage-capsule-blank-tap] Handler invoked when the user taps a
 * BLANK part of an assistant text block — past the end of a line, in the
 * ragged right margin, or below the last line. Receives the message id.
 *
 * This is how the usage capsule is revealed, mirroring iOS `onTapBlank`
 * (SelectableMarkdownView.swift:5636): iOS hit-tests the tap against the
 * glyph rects and treats a miss as "blank". Gating on a separate tap strip
 * instead — as this did first — makes the capsule's own 32dp row the ONLY
 * target, which is not discoverable: the user is told to tap the reply and
 * nothing happens anywhere they naturally tap.
 *
 * Deliberately fires only on a genuine miss, so it can never swallow a URL
 * tap, an inline-code copy, or the start of a text selection.
 */
val LocalMarkdownBlankTapHandler = compositionLocalOf<((String) -> Unit)?> { null }

/**
 * [T-android-markdown-image-gallery-cross-message] Handler invoked when a
 * markdown image (`![alt](src)`) inside an assistant message is tapped, with
 * the parent message id so the host can collect every sibling image across
 * the conversation and open a paged gallery (mirrors iOS
 * `handleMarkdownImageTap` in AIChatView.swift:2082).
 *
 * Distinct from [LocalMarkdownUrlClickHandler] so the existing url-only
 * routing keeps working unchanged. When null, the image renderer falls back
 * to [LocalMarkdownUrlClickHandler] (which routes a single-item open).
 *
 * The id corresponds to [TextShardId.messageId] supplied via [LocalShardId]
 * — every assistant text block already provides one, so the renderer reads
 * the id from the ambient shard rather than threading a separate prop.
 */
val LocalMarkdownImageTapHandler =
    compositionLocalOf<((messageId: String, url: String) -> Unit)?> { null }

/**
 * Session id that owns the currently-rendering markdown. Used by
 * `resolveMdMediaFile` to prefer `PRootKernel.resolveSessionHostPath` — the
 * session-scoped resolver — over the global `bindMounts` map, which is
 * last-writer-wins across sessions. Null in contexts that don't know the
 * owning session (e.g. standalone previews).
 */
val LocalMarkdownSessionId = compositionLocalOf<String?> { null }

private val BaseFontSizeDefault = 16.sp
private val BaseLineHeightDefault = 24.sp

private val BaseFontSize: TextUnit
    @Composable get() = BaseFontSizeDefault * LocalMarkdownFontScale.current

private val BaseLineHeight: TextUnit
    @Composable get() = BaseLineHeightDefault * LocalMarkdownFontScale.current

private val InlineCodeCornerRadius = 6.dp

/** Distances from a line's baseline to the top and bottom of an inline-code rect. */
private class InlineCodeBand(val aboveBaseline: Float, val belowBaseline: Float)

/**
 * [T-android-inline-code-band] Inline-code rects came out at different heights
 * in the same message: some wrapped their text, others sat low with the tops
 * of the letters sticking out.
 *
 * The rect used to be the LINE BOX inset by 4.5dp / 1.5dp. Those insets were
 * tuned on a middle line, which is a full [lineHeight] tall. But MdText trims
 * the outer half-leading ([LineHeightStyle.Trim.Both]), so a paragraph's first
 * line starts right at its font ascent and its last line ends at its descent;
 * and how tall that ascent/descent is depends on what the line contains — with
 * fallback line spacing, a CJK glyph raises it, a Latin/monospace-only line
 * does not. Code on the first line of a list item with no CJK on that line got
 * a box ~2.5dp shorter at the top than the same code one line lower.
 *
 * Anchoring on the baseline removes both variables. The band reproduces the
 * geometry of an untrimmed middle line of the BODY font — ascent + descent,
 * plus the leading [lineHeight] adds, split evenly as
 * [LineHeightStyle.Alignment.Center] does — and applies the original insets,
 * so a middle line looks exactly as before and every other line now matches it.
 */
private fun inlineCodeBand(
    fontSize: TextUnit,
    lineHeight: TextUnit,
    density: androidx.compose.ui.unit.Density,
    topInsetPx: Float,
    bottomInsetPx: Float,
): InlineCodeBand {
    val fontSizePx = with(density) { (if (fontSize.isSp) fontSize else BaseFontSizeDefault).toPx() }
    val metrics = android.graphics.Paint().apply {
        typeface = android.graphics.Typeface.DEFAULT
        textSize = fontSizePx
    }.fontMetrics
    val ascent = -metrics.ascent
    val descent = metrics.descent
    val lineHeightPx = when {
        lineHeight.isSp -> with(density) { lineHeight.toPx() }
        lineHeight.isEm -> lineHeight.value * fontSizePx
        else -> ascent + descent
    }
    val halfLeading = ((lineHeightPx - ascent - descent) / 2f).coerceAtLeast(0f)
    return InlineCodeBand(
        aboveBaseline = ascent + halfLeading - topInsetPx,
        belowBaseline = descent + halfLeading - bottomInsetPx,
    )
}

/** Text composable that draws rounded-rect backgrounds for inline code spans. */
@Composable
private fun MdText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = BaseFontSize,
    lineHeight: TextUnit = BaseLineHeight,
    fontWeight: FontWeight? = null,
    color: Color = currentMdColors().text,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    inlineContent: Map<String, androidx.compose.foundation.text.InlineTextContent> = emptyMap(),
    /**
     * Marks this text as a self-contained selection unit — set by table cells
     * so a long-press grabs exactly the cell. See [TextShard.isAtomicUnit].
     */
    isAtomicSelectionUnit: Boolean = false,
    textAlign: TextAlign? = null,
) {
    // [T-android-stream-live-redraw-loop] The latest TextLayoutResult lives in
    // a plain holder, NOT snapshot state. It used to be a MutableState that
    // onTextLayout overwrote with a fresh object on every layout pass, and
    // three things subscribed to it: the shard `remember` below (composition
    // scope), and both drawBehind modifiers (draw scope). Every layout
    // therefore invalidated draw AND recomposed this composable, and on a
    // live streaming block that kept the frame pipeline busy between the
    // 500 ms publishes — measured on a Pixel 4a as record(draw) 18 ms p50 on
    // a 2–8k-char block and ~93 % of frames over budget.
    //
    // Reading the holder from draw is safe: layout runs before draw within a
    // frame, and a text/size change already invalidates this node's draw, so
    // the drawBehind lambdas always see the current result when they run.
    val layoutResultHolder = remember { arrayOfNulls<androidx.compose.ui.text.TextLayoutResult>(1) }
    // Composition only needs to know when the layout changed in a way the
    // selection shard cares about: different text, size, or line count.
    // Identical re-layouts (same text, same geometry) do not bump it.
    var layoutGen by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // MinisTextKit registration: when this MdText is inside a markdown
    // fragment that supplied a shard id (LocalShardId) AND a controller
    // (LocalMinisSelectionController), publish a TextShard so the controller
    // can hit-test, highlight, and copy through this text node.
    // Use a single-cell array (no snapshot state) — coordinatesProvider's
    // closure reads the current value lazily, so this doesn't need to
    // invalidate any composable when the layout coords change. The
    // previous mutableStateOf wrapper turned every onGloballyPositioned
    // pass into a state write, forcing this MdText to recompose on every
    // scroll frame even when no selection was active — measurable
    // contributor to scroll jank.
    val layoutCoordinatesHolder = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    val baseShardId = LocalShardId.current
    // [T-android-markdown-longtext-selection-broken] Disambiguate this MdText
    // from its siblings within the same fragment so each registers a distinct
    // TextShard (the registry is keyed by id; same-id registrations overwrite
    // each other, leaving only the last text node selectable). The allocator
    // assigns a stable per-slot index on first composition; we suffix it onto
    // the fragment's base shardId. Falls back to the bare base id when no
    // allocator is in scope (non-chat callers that render a single MdText).
    val allocator = LocalShardSubIndexAllocator.current
    val subIndex = remember(baseShardId) { allocator?.next() ?: 0 }
    val shardId = remember(baseShardId, subIndex) {
        baseShardId?.let {
            if (allocator == null) it
            else it.copy(shardId = "${it.shardId}#$subIndex")
        }
    }
    val selectionController = LocalMinisSelectionController.current
    val currentShard = remember(shardId, layoutGen, text, isAtomicSelectionUnit) {
        val sid = shardId
        val result = layoutResultHolder[0]
        if (sid == null || result == null) null else buildTextShard(
            id = sid,
            plainText = text.text,
            layoutResult = result,
            coordinatesProvider = { layoutCoordinatesHolder[0] },
            isAtomicUnit = isAtomicSelectionUnit,
        )
    }
    RegisterSelectionShard(currentShard)
    val selectionHighlightColor = currentSelectionHighlightColor()
    val selectionState = selectionController?.selection
    val cornerPx = with(androidx.compose.ui.platform.LocalDensity.current) { InlineCodeCornerRadius.toPx() }
    // Inset each inline-code rect: more on the top because the line box has extra leading
    // above the glyphs (font metrics ascent > visual cap-height), so an even inset would
    // visually look top-heavy. Larger top inset also keeps a clear gap when an inline-code
    // span wraps across two adjacent lines.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val inlineCodeTopInsetPx = with(density) { 4.5.dp.toPx() }
    val inlineCodeBottomInsetPx = with(density) { 1.5.dp.toPx() }
    // [T-android-inline-code-band] Vertical extent of the inline-code rect,
    // measured from each line's BASELINE, not from its line box.
    val inlineCodeBand = remember(fontSize, lineHeight, density) {
        inlineCodeBand(fontSize, lineHeight, density, inlineCodeTopInsetPx, inlineCodeBottomInsetPx)
    }
    val inlineCodeBg = currentMdColors().inlineCodeBg
    val urlClickHandler = LocalMarkdownUrlClickHandler.current
    val clipboardManager = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val hasUrlAnnotation = remember(text) { text.getStringAnnotations("url", 0, text.length).isNotEmpty() }
    val hasInlineCodeAnnotation = remember(text) { text.getStringAnnotations("inline_code", 0, text.length).isNotEmpty() }

    // [T-android-stream-fade] When this MdText is the streaming last block,
    // overlay a fade-in alpha on each freshly-appended word range. Off by
    // default (LocalAppendOnlyFade=false) so cold-loaded history and
    // completed messages render fully opaque without per-frame work.
    val fadeEnabled = LocalAppendOnlyFade.current
    // [T-android-stream-fade-seed] Was fade on from this MdText's FIRST
    // composition? Only then is the text it first receives genuinely new
    // (a paragraph that appeared as the live tail). A block rendered opaque
    // that only LATER became the live tail — the reported case: a bullet
    // list that was not the last block until its next item's text landed —
    // already showed its text; a controller created at that flip must adopt
    // it, not re-fade it from alpha 0.
    //
    // Keyless remember on purpose: it must capture the value at birth and
    // keep it, so a later flip cannot rewrite history.
    val fadeFromBirth = remember { fadeEnabled }
    val fadeController = if (fadeEnabled) rememberFadeController() else null
    if (fadeController != null) {
        // Ingest synchronously during composition (not in a LaunchedEffect):
        // the moment a recomposition delivers grown text, slice the new
        // suffix into fade ranges so the SAME frame renders them at α=0.
        // Deferring to a LaunchedEffect committed the opaque text first,
        // making the fade invisible. ingest() is a cheap prefix-diff and
        // no-ops when text is unchanged, so calling it every compose is safe.
        //
        // [T-android-stream-fade-reentry] A controller's first text is only
        // "new" if this node has never shown it. Re-entering a chat mid-stream,
        // or scrolling the streaming message out and back, recreates the node
        // from scratch; FadeShownText remembers what it showed, so only the
        // text beyond that fades.
        val fadeKey = baseShardId?.let { "${it.messageId}/${it.shardId}" }
        if (!fadeController.hasSeenText) {
            val plan = fadeBirthPlan(fadeFromBirth, fadeKey?.let { FadeShownText.get(it) } ?: emptyList(), text.text)
            when (plan) {
                FadeBirth.SeedAll -> fadeController.seed(text.text)
                is FadeBirth.SeedPrefix -> {
                    fadeController.seed(plan.shown)
                    fadeController.ingest(text.text)
                }
                FadeBirth.FadeAll -> fadeController.ingest(text.text)
            }
            // Diagnostic: a node born into a fade of a long text is the replay
            // symptom; trace which fragment and how much, so a leftover path
            // shows up in a Verbose log.
            if (plan == FadeBirth.FadeAll && text.text.length > 80) {
                com.openminis.app.logging.AppLogger.trace("StreamFade") {
                    "[T-android-stream-fade-reentry] fade-from-zero key=$fadeKey len=${text.text.length}"
                }
            }
        } else {
            fadeController.ingest(text.text)
        }
        if (fadeKey != null) FadeShownText.put(fadeKey, text.text)
        FadeFrameDriver(fadeController)
    }
    // [T-android-stream-fade-relayout] The fade is a MASK drawn over the
    // fading words, not a per-frame rewrite of the AnnotatedString. The old
    // overlay() rebuilt the whole string with alpha spans every frame, which
    // made Text re-lay-out and re-record the ENTIRE live paragraph per vsync
    // (measured: record(draw) 8→18 ms as the block grew 2k→8k chars, ~93 % of
    // frames over budget for the whole stream). Layer order matters: the
    // OUTER graphicsLayer owns the per-frame mask re-record; the INNER one
    // holds the text and is never re-recorded while the fade runs. Reading
    // the controller's snapshot state inside drawWithContent invalidates only
    // the outer layer — no recomposition, no re-layout.
    val fadeMaskColor = ChatColors.background
    val fadeMaskModifier = if (fadeController != null) {
        Modifier
            .graphicsLayer()
            .drawWithContent {
                drawContent()
                val result = layoutResultHolder[0] ?: return@drawWithContent
                val maxOffset = result.layoutInput.text.length
                fadeController.forEachActive { start, end, alpha ->
                    if (alpha >= 1f) return@forEachActive
                    val e = end.coerceAtMost(maxOffset)
                    if (e <= start) return@forEachActive
                    drawPath(result.getPathForRange(start, e), color = fadeMaskColor.copy(alpha = 1f - alpha))
                }
            }
            .graphicsLayer()
    } else Modifier
    // [T-android-usage-capsule-blank-tap] The blank-tap handler makes the
    // gesture worth installing even on a block with no links or code spans,
    // which is the common case — so the condition includes it.
    val blankTapHandler = LocalMarkdownBlankTapHandler.current
    val blankTapMessageId = LocalShardId.current?.messageId
    val wantsBlankTap = blankTapHandler != null && blankTapMessageId != null
    val tapModifier = if (hasUrlAnnotation || hasInlineCodeAnnotation || wantsBlankTap) {
        Modifier.pointerInput(text, wantsBlankTap) {
            detectTapGestures { pos ->
                val result = layoutResultHolder[0] ?: return@detectTapGestures
                val offset = result.getOffsetForPosition(pos)
                // Blank-area test, mirroring iOS: getOffsetForPosition CLAMPS
                // to the nearest character, so a tap past the end of a line
                // still returns a valid offset. Comparing the point against
                // that character's own bounding box is what distinguishes
                // "on a glyph" from "past it", and it is checked FIRST so a
                // blank tap never falls through into the url / inline-code
                // branches below (their annotations are still reported for a
                // clamped offset, which would fire a link the user missed).
                if (wantsBlankTap) {
                    val onGlyph = runCatching {
                        val box = result.getBoundingBox(offset.coerceIn(0, (text.length - 1).coerceAtLeast(0)))
                        box.contains(pos)
                    }.getOrDefault(false)
                    if (!onGlyph) {
                        blankTapHandler?.invoke(blankTapMessageId!!)
                        return@detectTapGestures
                    }
                }
                // URL wins over inline code if both annotations cover this offset.
                val urlAnn = if (urlClickHandler != null) {
                    text.getStringAnnotations("url", offset, offset).firstOrNull()
                } else null
                if (urlAnn != null) {
                    urlClickHandler?.invoke(urlAnn.item)
                    return@detectTapGestures
                }
                val codeAnn = text.getStringAnnotations("inline_code", offset, offset).firstOrNull()
                if (codeAnn != null) {
                    val snippet = text.text.substring(codeAnn.start, codeAnn.end)
                    if (snippet.isNotEmpty()) {
                        clipboardManager.setText(AnnotatedString(snippet))
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        val preview = if (snippet.length > 40) snippet.take(37) + "…" else snippet
                        Toast.makeText(context, "Copied: $preview", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    } else Modifier
    Text(
        text = text,
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontWeight = fontWeight,
        color = color,
        maxLines = maxLines,
        overflow = overflow,
        inlineContent = inlineContent,
        textAlign = textAlign,
        // [T-android-md-lineheight-trim] Spend `lineHeight` BETWEEN lines only,
        // not above the first or below the last.
        //
        // Compose's default splits the leading (lineHeight - fontSize = 8sp at
        // our 24/16) evenly around EVERY line, including the outer edges — so a
        // paragraph carries ~4sp of invisible space below its last baseline and
        // the same above its first. That slack is inside the Text node, so no
        // caller can see or subtract it, and it silently inflated every gap
        // measured between a text block and its neighbour.
        //
        // Concretely it broke the spacing contract documented on
        // ChatUserMessageUI (`user.bottom(4) + spacedBy(2) + header.top(10) = 16`)
        // in ONE direction: the Assistant→User boundary measured ~34dp on a
        // Pixel 6 against ~21dp for User→Assistant, because only the former has
        // a markdown paragraph on the near side. The fixed-value arithmetic was
        // right; it just never accounted for leading it could not see.
        //
        // Trim(FirstLineTop + LastLineBottom) removes only the OUTER half-leadings.
        // Inter-line spacing inside a paragraph is untouched, so body text reads
        // exactly as before — this is not a density change.
        style = LocalTextStyle.current.merge(
            TextStyle(
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both,
                ),
            ),
        ),
        onTextLayout = { result ->
            val prev = layoutResultHolder[0]
            layoutResultHolder[0] = result
            if (prev == null ||
                prev.layoutInput.text !== result.layoutInput.text ||
                prev.size != result.size ||
                prev.lineCount != result.lineCount
            ) {
                layoutGen++
            }
        },
        modifier = modifier
            .then(fadeMaskModifier)
            .then(tapModifier)
            .onGloballyPositioned { layoutCoordinatesHolder[0] = it }
            .drawBehind {
                // MinisTextKit selection highlight (drawn UNDER the glyphs).
                val result0 = layoutResultHolder[0]
                val shardId0 = shardId
                val sel = selectionState?.value
                if (result0 != null && shardId0 != null && sel != null) {
                    drawSelectionForShard(
                        shardId = shardId0,
                        result = result0,
                        selection = sel,
                        controller = selectionController,
                        color = selectionHighlightColor,
                    )
                }
            }
            .drawBehind {
            val result = layoutResultHolder[0] ?: return@drawBehind
            // Guard against stale layout during streaming: the AnnotatedString `text`
            // in the closure can be one recomposition ahead of the laid-out text in
            // `result`, and maxLines can clip the tail. Clamp all offsets/line indices
            // to the layout's actual extents before calling get*Line* APIs — otherwise
            // getLineStart(lineCount) throws IllegalArgumentException and crashes the
            // draw phase (see #StreamingMd crash on Pixel).
            val laidOutText = result.layoutInput.text.text
            val maxOffset = laidOutText.length
            val lineCount = result.lineCount
            if (maxOffset == 0 || lineCount == 0) return@drawBehind
            val annotations = text.getStringAnnotations("inline_code", 0, text.length)
            for (ann in annotations) {
                val startOffset = ann.start.coerceIn(0, maxOffset)
                val endOffset = ann.end.coerceIn(0, maxOffset)
                if (endOffset <= startOffset) continue
                val startLine = result.getLineForOffset(startOffset).coerceIn(0, lineCount - 1)
                val endLine = result.getLineForOffset(endOffset - 1).coerceIn(0, lineCount - 1)
                if (endLine < startLine) {
                    android.util.Log.d(
                        "StreamingMd",
                        "skip inline_code: endLine<startLine annStart=${ann.start} annEnd=${ann.end} " +
                            "clamped=[$startOffset,$endOffset) lineCount=$lineCount maxOffset=$maxOffset"
                    )
                    continue
                }
                for (line in startLine..endLine) {
                    val lineStart = if (line == startLine) startOffset else result.getLineStart(line)
                    val lineEnd = if (line == endLine) endOffset else result.getLineEnd(line)
                    if (lineEnd <= lineStart) continue
                    // T299: walk per-character via getBoundingBox and take
                    // min(left)/max(right) directly. T265 used
                    // getPathForRange(lineStart, lineEnd).getBounds() which
                    // was correct for bidi-pure runs but regressed when an
                    // inline code span itself contains an internal space and
                    // sits inside CJK prose (e.g. prose like "put it next to
                    // `Hermes Agent notes.md` and `Minis tutorial.md`"). The
                    // path returned for that range can include zero-width
                    // sub-paths at run boundaries; getBounds()'s union then
                    // expands left to a coordinate from a sibling run, which
                    // ends up painted behind the surrounding prose instead
                    // of the code. Per-character boxes are immune to that
                    // because each box is a single character's tight
                    // rectangle. iOS uses fillBackgroundRectArray which has
                    // the same per-glyph guarantee.
                    var left = Float.POSITIVE_INFINITY
                    var right = Float.NEGATIVE_INFINITY
                    for (offset in lineStart until lineEnd) {
                        val box = result.getBoundingBox(offset)
                        // getBoundingBox returns a 0-width rect for offsets
                        // that fall on a soft line break; skip those so the
                        // accumulator doesn't pick up a stray edge.
                        if (box.width <= 0f) continue
                        if (box.left < left) left = box.left
                        if (box.right > right) right = box.right
                    }
                    if (!left.isFinite() || right <= left) continue
                    val baseline = result.getLineBaseline(line)
                    val top = baseline - inlineCodeBand.aboveBaseline
                    val bottom = baseline + inlineCodeBand.belowBaseline
                    drawRoundRect(
                        color = inlineCodeBg,
                        topLeft = androidx.compose.ui.geometry.Offset(left, top),
                        size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerPx, cornerPx),
                    )
                }
            }
        },
    )
}

/**
 * Streaming-friendly markdown renderer.
 *
 * Strategy:
 * - Parse markdown into a list of Block objects
 * - Each block is an independent composable — Compose's structural diff only
 *   recomposes blocks that actually changed
 * - The LAST block is the only one that changes during streaming (text appends to it)
 * - Completed blocks above are structurally stable → Compose skips them
 *
 * Update cadence: while [isStreaming] is true, content updates are coalesced
 * to at most one re-parse every [STREAMING_THROTTLE_MS] (~120 ms). Pixel 4a
 * traces showed every TextDelta (~5 ms cadence) was triggering a full
 * `parseMarkdownBlocks` over the entire accumulating string + a recompose of
 * every RenderBlock — a 5-row markdown table plus a few tool calls was enough
 * to ANR the main thread with 22 MB GC every 2 s. Throttling the *display*
 * content (not the underlying StateFlow) keeps the conversation visually
 * live (3-4 fps of growth is plenty for reading) while leaving 90 % of the
 * frame budget free.
 *
 * When the stream finishes ([isStreaming] flips to false), the final value
 * is published immediately so the user never sees a truncated last frame.
 */
// Adaptive streaming throttle, mirrors iOS CollectionViewMessageListV3
// `flushStreamingLayout` (100 ms when auto-scrolling, 3 s when away). On
// Android we don't have direct access to the chat-level scroll state from
// here, so substitute "doc length" as a proxy: long documents already cost
// more per parse pass, so amortize them by sampling less often. Crashes
// observed on Pixel 6 traced to ICU `RegexPattern::matcher` allocations
// piling up under Scudo (OOM at ~140s of streaming) — slowing parses on
// large bodies cuts native allocation pressure dramatically.
//
// [T-android-stream-flush-dualpath] Time-throttle tiers ported verbatim from
// iOS AIChatViewModel+SSEStream (the `throttle` ladder): the time path is one
// half of the dual-path flush — the other half is the newline fast-path below.
// Tiers scale with total length to hold the Pixel 4a ANR / Pixel 6 Scudo-OOM
// line on dense streams while keeping short replies responsive.
//   < 500  : 200ms   < 2000 : 300ms   < 32K : 500ms
//   < 64K  : 1000ms  < 128K : 1500ms  else  : 2000ms
private fun streamingThrottleFor(content: String): Long = when {
    content.length < 500 -> 200L
    content.length < 2_000 -> 300L
    content.length < 32_000 -> 500L
    content.length < 64_000 -> 1_000L
    content.length < 128_000 -> 1_500L
    else -> 2_000L
}


@Composable
fun StreamingMarkdownText(
    content: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
    /** MinisTextKit shard id (see [MarkdownBlock]). */
    shardId: TextShardId? = null,
) {
    if (shardId != null) {
        androidx.compose.runtime.CompositionLocalProvider(LocalShardId provides shardId) {
            StreamingMarkdownTextBody(content, isStreaming, modifier)
        }
        return
    }
    StreamingMarkdownTextBody(content, isStreaming, modifier)
}

@Composable
private fun StreamingMarkdownTextBody(
    content: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
) {
    // While streaming, sample `content` at the adaptive throttle interval.
    // produceState + snapshotFlow.conflate() makes the upstream value collection
    // suspend-safe and frees the runtime to drop intermediate values when the
    // collector falls behind. When streaming ends, emit the final value
    // unconditionally so we don't render a stale half-block.
    val displayContent by produceState(initialValue = content, content, isStreaming) {
        if (!isStreaming) {
            value = content
            return@produceState
        }
        snapshotFlow { content }
            .conflate()
            .collect { latest ->
                value = latest
                delay(streamingThrottleFor(latest))
            }
    }
    // [T-android-inline-parse-offmain] Theme snapshot for off-main prewarm.
    val mdColors = currentMdColors()
    var blocks by remember { mutableStateOf<List<MdBlock>>(emptyList()) }
    // [T-android-md-parse-incremental] Per-composable parse state, so an
    // append-only stream re-parses only its newly-arrived tail.
    val parseState = remember { IncrementalParseState() }
    LaunchedEffect(displayContent) {
        val computed = withContext(Dispatchers.Default) {
            parseMarkdownIncremental(displayContent, parseState).also {
                MarkdownParseCaches.prewarm(it, mdColors)
            }
        }
        // If the LE was cancelled while parseMarkdownBlocks was still running
        // (a newer chunk arrived), don't publish stale blocks.
        coroutineContext.ensureActive()
        blocks = computed
    }

    ShardSubIndexScope {
        Column(modifier = modifier) {
            // [T-android-stream-fade] Last block during a live stream gets
            // LocalAppendOnlyFade=true so MdText fades in newly-appended
            // word ranges (mirrors iOS TextFadeAnimator). Every other block
            // — completed prefix, non-streaming sessions — renders opaque.
            // [T-android-stream-fade-seed] Every block goes through the SAME
            // composable shape while streaming — a provider whose VALUE says
            // whether it is the live tail — rather than an if/else that wraps
            // only the last block. The two branches were different groups, so
            // whenever `lastIdx` shifted (a trailing block re-parsed away, the
            // list before it became last) Compose disposed and rebuilt the
            // whole subtree: every MdText lost its fade controller and its
            // already-visible text re-faded from alpha 0. Gated on isStreaming
            // so frozen history composes exactly as before.
            val lastIdx = blocks.size - 1
            if (isStreaming) {
                blocks.forEachIndexed { idx, block ->
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalAppendOnlyFade provides (idx == lastIdx),
                    ) { RenderBlock(block) }
                }
            } else {
                blocks.forEach { RenderBlock(it) }
            }
        }
    }
}

/**
 * T285-md: full-document markdown viewer for FilePreviewScreen and any
 * other "open a `.md` file end-to-end" surface. Differs from
 * [StreamingMarkdownText] in two important ways:
 *
 *  1. Renders blocks via [LazyColumn] instead of [Column]. A 200-block
 *     document only composes the on-screen blocks on first frame, so
 *     `parseInline`/`collectInlineMathLatex` (still main-thread per
 *     RenderBlock) costs scale with viewport height, not document
 *     length. Critical for the chat-tap → preview transition: pre-T285-md
 *     a multi-KB markdown ran ~150-300ms of inline scanning across all
 *     blocks during the same frame the navigation animation started,
 *     stuttering the slide-in. (StreamingMarkdownText still uses Column
 *     because chat-side messages live inside ChatScreen's outer
 *     LazyColumn — putting a LazyColumn-in-LazyColumn there would hit
 *     the "infinite vertical constraint" runtime error.)
 *
 *  2. No streaming throttle / snapshotFlow plumbing — the file is
 *     loaded once and the content never mutates after publication, so
 *     the live-tail logic in [StreamingMarkdownText] would just be
 *     overhead.
 *
 * Pass the outer scroll [Modifier] (height/padding) to this composable;
 * do NOT wrap the call site in a `verticalScroll` — the LazyColumn
 * provides the scroll itself.
 */
@Composable
fun MarkdownDocument(
    content: String,
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(0.dp),
) {
    // [T-android-inline-parse-offmain] Theme snapshot for off-main prewarm —
    // the doc viewer benefits the same way: per-block inline scans become
    // cache hits as blocks scroll into view.
    val mdColors = currentMdColors()
    var blocks by remember(content) { mutableStateOf<List<MdBlock>>(emptyList()) }
    LaunchedEffect(content) {
        val computed = withContext(Dispatchers.Default) {
            // [T-android-md-parse-gate] Static document: no incremental reuse
            // to be had, but it must still queue behind the same gate so
            // opening a large .md while sub-agents stream cannot stack another
            // full-document parse on top of them.
            markdownParseGate.withPermit { parseMarkdownBlocks(content) }.also {
                MarkdownParseCaches.prewarm(it, mdColors)
            }
        }
        coroutineContext.ensureActive()
        blocks = computed
    }
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
    ) {
        // Composite key: index disambiguates blocks with identical raw
        // bodies (multiple `---` HR lines, repeated empty paragraphs, etc.
        // would otherwise crash LazyColumn with "Key was already used"),
        // while raw still helps item reuse when the list is rebuilt with
        // the same content at the same position.
        itemsIndexed(blocks, key = { idx, b -> "$idx:${b.raw}" }) { _, block ->
            RenderBlock(block)
        }
    }
}

// ─── Block-level splitting (Pattern A: stable scroll while streaming) ─────────
//
// Earlier the entire streaming markdown was rendered inside a single
// LazyColumn item. When that item's height grew mid-stream, LazyList's
// per-item anchor couldn't help — the user's scroll position drifted as the
// internal Column reflowed. Splitting the message into one LazyColumn item
// per markdown block shifts the anchor granularity down: completed blocks
// (anything before the trailing fence/blank-line boundary) become frozen
// items whose visual position is preserved by LazyList; only the trailing
// "live" block can change height.
//
// `splitMarkdownIntoBlockTexts` returns ordered raw text fragments. The
// boundary rule is:
//   - blank line OUTSIDE a fenced code block → split (paragraph end)
//   - fenced code block start/end → its own fragment
// Fence-internal blank lines never split. Tables and HR-only lines stay
// attached to their preceding/following fragment because the parser
// detects them at parse time anyway.

/**
 * Split a streaming markdown buffer into ordered raw-text fragments at
 * stable boundaries. Each fragment is suitable as the input to a
 * standalone [MarkdownBlock] composable. Concatenating the returned list
 * with "\n" reconstructs the input exactly.
 */
fun splitMarkdownIntoBlockTexts(content: String): List<String> {
    if (content.isEmpty()) return emptyList()
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var inFence = false
    val lines = content.lines()
    fun flush() {
        if (cur.isNotEmpty()) {
            // Trim trailing empty line we used as boundary, but keep
            // intentional internal newlines.
            out.add(cur.toString().trimEnd('\n'))
            cur.clear()
        }
    }
    for (line in lines) {
        val trimmed = line.trimStart()
        val isFence = trimmed.startsWith("```")
        if (isFence) {
            // A fence line both closes the previous fragment (when we're
            // not inside a fence) and opens/closes the fence fragment.
            if (!inFence) {
                flush()
                cur.append(line).append('\n')
                inFence = true
            } else {
                cur.append(line).append('\n')
                inFence = false
                flush()
            }
            continue
        }
        if (inFence) {
            cur.append(line).append('\n')
            continue
        }
        if (line.isBlank()) {
            // Boundary: paragraph end. Drop the blank line itself; it
            // signals the split.
            flush()
            continue
        }
        cur.append(line).append('\n')
    }
    flush()
    return out
}

/**
 * [T-android-defensive-fragment-merge] A fenced code block fragment is one
 * whose first non-blank line opens a ``` fence. Such fragments must stay
 * standalone (own LazyColumn row) for correct code rendering + horizontal
 * scroll, so coalescing never merges across them.
 */
private fun isFenceFragment(fragment: String): Boolean {
    val firstLine = fragment.lineSequence().firstOrNull { it.isNotBlank() } ?: return false
    return firstLine.trimStart().startsWith("```")
}

/**
 * [T-android-defensive-fragment-merge] Coalesce the per-paragraph fragments
 * produced by [splitMarkdownIntoBlockTexts] into fewer, larger fragments so
 * a long frozen assistant message becomes a handful of LazyColumn rows
 * instead of dozens.
 *
 * Why: each fragment is its own LazyColumn item carrying its own
 * BoundsTrackedBlock + MarkdownBlock + per-item Compose state. A dense
 * assistant reply (e.g. a 50-item list with blank lines) fans out into ~50
 * rows; a long session reaches several thousand rows, which on low-memory
 * devices contributes to a GC storm on cold-open full-build. Re-joining
 * adjacent plain-text fragments with their original blank-line separator
 * (`\n\n`) keeps the rendered markdown identical — MarkdownBlock re-parses
 * the joined text the same way it would parse them separately — while
 * cutting the row count ~8x.
 *
 * Rules:
 *   - Code-fence fragments are NEVER merged (kept standalone for syntax
 *     highlight + horizontal scroll). They flush the current accumulator
 *     and emit on their own.
 *   - Plain fragments accumulate until adding the next would exceed
 *     [maxChars]; then the accumulator flushes and a new one starts. This
 *     caps any single merged row's height so the streaming/scroll anchor
 *     granularity stays reasonable.
 *   - Joining uses `\n\n` so paragraph boundaries survive the round-trip.
 *
 * Callers should only apply this to FROZEN (non-streaming) messages — the
 * live streaming tail keeps fine-grained fragments so only the trailing
 * paragraph re-parses per token (Pattern A jank optimization).
 */
fun coalesceMarkdownFragments(fragments: List<String>, maxChars: Int = 2000): List<String> {
    if (fragments.size <= 1) return fragments
    val out = ArrayList<String>(fragments.size)
    val acc = StringBuilder()
    fun flush() {
        if (acc.isNotEmpty()) {
            out.add(acc.toString())
            acc.setLength(0)
        }
    }
    for (frag in fragments) {
        if (isFenceFragment(frag)) {
            flush()
            out.add(frag)
            continue
        }
        // Would appending this fragment overflow the budget? Flush first,
        // unless the accumulator is empty (a single oversized paragraph
        // still gets its own row rather than being dropped).
        if (acc.isNotEmpty() && acc.length + 2 + frag.length > maxChars) {
            flush()
        }
        if (acc.isNotEmpty()) acc.append("\n\n")
        acc.append(frag)
    }
    flush()
    return out
}

/**
 * Render a single markdown fragment (one or a few related blocks) inside
 * its own composable. Designed to be used as the body of an independent
 * LazyColumn item — each fragment is one item, so its height changes
 * cannot disturb the scroll position of any other fragment.
 *
 * `isStreaming` controls async re-parse: when false (frozen completed
 * fragment), the parse runs once on first composition and is never
 * recomputed. When true (the trailing live fragment), the parse is
 * re-run on every content tick, mirroring the original
 * StreamingMarkdownText behavior.
 */
@Composable
fun MarkdownBlock(
    rawText: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
    /**
     * MinisTextKit shard id — when supplied, every MdText composed beneath
     * this fragment will register with the ambient [SelectionController].
     * The id should be stable across recompositions so the controller's
     * registry doesn't churn (e.g. "msg:abc:block:7"). Null = participate
     * in no selection (legacy behavior).
     */
    shardId: TextShardId? = null,
) {
    if (shardId != null) {
        androidx.compose.runtime.CompositionLocalProvider(LocalShardId provides shardId) {
            // [T-android-markdown-longtext-selection-broken] Disambiguate the
            // several MdTexts a multi-block fragment renders under this one
            // shardId so each registers its own shard.
            ShardSubIndexScope {
                MarkdownBlockBody(rawText, isStreaming, modifier)
            }
        }
        return
    }
    MarkdownBlockBody(rawText, isStreaming, modifier)
}

@Composable
private fun MarkdownBlockBody(
    rawText: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
) {
    // [T-android-freeze-edge-carryover] The live branch's most recent parse
    // and the text it was for, held ABOVE the live/frozen switch. The list key
    // (mdblock:<msg>:<block>:<index>) does not include isStreaming, so this
    // composable — and this remember — survive the flip.
    //
    // Why the cache deposit alone was not enough: the VM flushes on newline,
    // so a real model routinely lands a table's last row and the blank line
    // after it in ONE publish. The fragment then goes from "4 rows, live"
    // straight to "5 rows, frozen" — its final text is never composed live,
    // the deposit is keyed to the 4-row text, and the frozen lookup misses.
    // The miss then paints the plain-text preview for as long as the
    // off-main parse takes: tens of ms on a small table, visibly longer on a
    // wide one with Default contended by a running agent. That is the flash.
    //
    // A plain holder, not snapshot state: written from the parse coroutine,
    // read in composition, must not invalidate anything by itself.
    val liveCarry = remember { arrayOf<Pair<String, List<MdBlock>>?>(null) }
    // For frozen blocks, parse once per distinct fragment text PROCESS-WIDE
    // ([MarkdownParseCaches.blocks]) — scroll-away/return and session re-entry
    // are cache hits instead of fresh main-thread parses. remember() keeps the
    // per-composition lookup free.
    //
    // [T-android-coldload-offmain-parse] Cold-load split: a cache HIT (or a
    // small fragment) still renders synchronously — no flicker on
    // scroll-back / re-entry, and small parses are sub-ms. A cache MISS on a
    // BIG fragment must NOT parse in composition: on session open the
    // viewport's fragments all miss at once and the synchronous
    // parseMarkdownBlocksBlocking + inline scans froze the main thread for
    // seconds (tester log: 3.5–8.5s on a 33K-char message; the streaming
    // breaker/degrade only cover isStreaming=true). Those parse off-main
    // with a bounded plain-text preview in the meantime — same structure as
    // the live branch below.
    if (!isStreaming) {
        val cached = remember(rawText) {
            MarkdownParseCaches.cachedBlocks(rawText)
                // [T-android-freeze-edge-carryover] The deposit may still be
                // in flight when the flip lands; the carry is the same parse.
                ?: liveCarry[0]?.takeIf { it.first == rawText }?.second
        }
        // [T-android-longtext-anr] Synchronous render ONLY on a real cache HIT.
        // The old `|| rawText.length <= COLD_PARSE_OFFMAIN_THRESHOLD_CHARS` clause
        // let a small fragment parse (block-split + per-block inline regex) on the
        // main thread during composition. Individually sub-ms, but on session open
        // the first screen holds ~20 messages split into dozens of small fragments;
        // when the parallel viewport prewarm (ChatScreen) loses the race, every one
        // of those misses parsed synchronously in the same frame and the aggregate
        // froze the main thread for 30s+ → ANR (minis-2026-07-09-anr.log: all hang
        // stacks in Matcher/Pattern via the inline parser, right after first compose).
        // A cold MISS now always goes off-main with a plain-text preview, bounding
        // the first-frame main-thread cost to cheap Text layouts regardless of how
        // many fragments miss at once. Cache HITs (scroll-back, re-entry, prewarmed
        // rows) stay synchronous and flicker-free.
        if (cached != null) {
            Column(modifier = modifier) {
                cached.forEach { RenderBlock(it) }
            }
            return
        }
        val mdColors = currentMdColors()
        var parsed by remember(rawText) { mutableStateOf<List<MdBlock>?>(null) }
        LaunchedEffect(rawText) {
            val tStartNs = System.nanoTime()
            val computed = withContext(Dispatchers.Default) {
                MarkdownParseCaches.blocks(rawText).also {
                    MarkdownParseCaches.prewarm(it, mdColors)
                }
            }
            coroutineContext.ensureActive()
            parsed = computed
            com.openminis.app.logging.AppLogger.info(
                "Perf",
                "[Perf][ColdParse] step=coldParse.offmain chars=${rawText.length} " +
                    "blocks=${computed.size} parseMs=${(System.nanoTime() - tStartNs) / 1_000_000}",
            )
        }
        // [T-android-freeze-edge-carryover] While the parse of the FINAL text
        // runs, keep painting the last parse this fragment showed while live,
        // provided that text is a prefix of what we now hold (the same-flush
        // case: 4 rows -> 5 rows). It is a rendered table missing its last
        // row for one parse, instead of raw pipes. Only a fragment that was
        // never live (cold session open) or one whose text diverged still
        // sees the plain-text preview. `blocks ?: carry` keeps both in ONE
        // branch so the swap reuses the RenderBlock slots rather than
        // rebuilding them.
        val blocks = parsed ?: liveCarry[0]?.takeIf { rawText.startsWith(it.first) }?.second
        // [T-android-freeze-edge-carryover] One line per composition of a
        // frozen fragment that has no parse of its own yet, naming what is
        // painted meanwhile. "carry" at a freeze edge and never "preview" is
        // the whole claim of this fix, and it is how the fix was verified on
        // a device whose video encoder was wedged. Fires only on the miss
        // path, for the few ms until the off-main parse lands.
        if (parsed == null) {
            android.util.Log.i(
                "FreezeEdge",
                "chars=${rawText.length} paint=" +
                    (if (blocks != null) "carry(prefix=${liveCarry[0]?.first?.length})" else "preview"),
            )
        }
        Column(modifier = modifier) {
            if (blocks == null) {
                // Bounded plain-text preview while the off-main parse runs —
                // one cheap Text layout, no markdown/regex/AnnotatedString.
                Text(
                    text = rawText.take(COLD_PARSE_PREVIEW_CHARS),
                    fontSize = BaseFontSize,
                    lineHeight = BaseLineHeight,
                    color = currentMdColors().text,
                )
            } else {
                blocks.forEach { RenderBlock(it) }
            }
        }
        return
    }
    // [T-android-live-block-degrade] B-lite: a LIVE fragment that has grown
    // huge is almost always an unsplittable single block (the splitter keeps
    // tables/fences whole — exactly the MiniMax giant-table ANR load). Parsing
    // it in full on every publish is O(fragment) with no upper bound, so over
    // the threshold render a bounded plain-text tail instead and do the full
    // parse ONCE when the fragment freezes (isStreaming flips false above).
    if (rawText.length > LIVE_FRAGMENT_DEGRADE_CHARS) {
        Column(modifier = modifier) {
            Text(
                text = stringResource(R.string.chat_stream_degraded_notice),
                style = MaterialTheme.typography.labelSmall,
                color = currentMdColors().blockquote,
            )
            Text(
                text = "…" + rawText.takeLast(LIVE_FRAGMENT_TAIL_CHARS),
                fontSize = BaseFontSize,
                lineHeight = BaseLineHeight,
                color = currentMdColors().text,
            )
        }
        return
    }
    // Live (streaming tail) block.
    //
    // [T-android-stream-flush-dualpath] Throttling moved UP to the message
    // accumulation layer (ChatViewModel.updateAssistantMessage) where the
    // dual-path (time OR newline+chars) flush actually accumulates across the
    // high-frequency token calls. The earlier per-fragment throttle here was
    // structurally broken: streaming text is split into many short-lived
    // fragment items, so this produceState (and its lastFlushMs accumulator)
    // reset on every fragment rebuild and never throttled at all — diagnostics
    // showed every tick flushing. `rawText` arriving here is already paced by
    // the VM, so the fragment just renders it directly; parse stays off-main
    // below.
    val displayContent by produceState(initialValue = rawText, rawText) {
        snapshotFlow { rawText }.conflate().collect { value = it }
    }
    // [T-android-inline-parse-offmain] Snapshot the theme colors in
    // composition so the Default-thread parse below can PREWARM the inline
    // caches with the exact keys RenderBlock will look up — main-thread
    // composition of the live block becomes a pure cache hit.
    val mdColors = currentMdColors()
    var blocks by remember { mutableStateOf<List<MdBlock>>(emptyList()) }
    // [T-android-md-parse-incremental] See StreamingMarkdownTextBody.
    val parseState = remember { IncrementalParseState() }
    // [T-android-freeze-edge-flash] The key of this fragment's most recent
    // live deposit, so each tick can REPLACE it rather than add beside it.
    // A plain holder, not snapshot state: it is written from the parse
    // coroutine and must not invalidate anything.
    val liveDepositKey = remember { arrayOf<String?>(null) }
    LaunchedEffect(displayContent) {
        // [T-android-stream-render-profile] Time the whole off-main tick
        // (block split + prewarm/incremental inline+math) — this is what the
        // incremental optimization shrinks.
        val parseStartNs = System.nanoTime()
        val computed = withContext(Dispatchers.Default) {
            parseMarkdownIncremental(displayContent, parseState).also {
                // [T-android-streaming-incremental-inline] Prewarm the frozen
                // blocks (all but the last) normally. The last block is the
                // growing live tail: when it's a Paragraph, warm it
                // incrementally (closed prefix reused + tiny fresh suffix) so
                // the main-thread RenderBlock resolves to an exact HIT without
                // re-scanning the whole accumulated paragraph; when it's a
                // table/list/etc. (which RenderBlock parses non-incrementally)
                // fall back to the normal per-block prewarm for it.
                if (it.size > 1) MarkdownParseCaches.prewarm(it.dropLast(1), mdColors)
                if (it.lastOrNull() is MdBlock.Paragraph) {
                    MarkdownParseCaches.prewarmLiveTail(it, mdColors)
                } else {
                    it.lastOrNull()?.let { last -> MarkdownParseCaches.prewarm(listOf(last), mdColors) }
                }
                // [T-android-review-p1-fixes] F2(a): deposit the live parse
                // into the blocks cache so the freeze edge (isStreaming →
                // false recomposes into the frozen branch with this exact
                // text) HITs synchronously — no plain-text preview flash, no
                // off-main re-parse.
                //
                // [T-android-freeze-edge-flash] This used to be gated on
                // `length > COLD_PARSE_OFFMAIN_THRESHOLD_CHARS`, on the
                // reasoning that a small fragment would parse sub-ms
                // synchronously at freeze anyway. That was true once. The ANR
                // fix above (T-android-longtext-anr) then made EVERY cold
                // miss go off-main behind a plain-text preview, and nobody
                // revisited this gate — so a fragment under 2000 chars was
                // guaranteed to miss at freeze and flash its raw markdown.
                // A 462-char weather table did exactly that: rendered, went
                // to `| 城市 | ... |---|` pipes for one parse, came back.
                //
                // Every tick deposits now, but each tick REMOVES the previous
                // tick's entry first: one live entry per fragment, so the
                // stream leaves no trail of intermediate texts in the LRU.
                // Both ops are O(1) and the sizer is key length, so a
                // replacement is budget-neutral to within one tick's growth.
                liveDepositKey[0]?.let { prev ->
                    if (prev != displayContent) MarkdownParseCaches.removeBlocks(prev)
                }
                MarkdownParseCaches.putBlocks(displayContent, it)
                liveDepositKey[0] = displayContent
            }
        }
        coroutineContext.ensureActive()
        StreamRenderProfiler.recordParse(displayContent.length, (System.nanoTime() - parseStartNs) / 1_000_000.0)
        blocks = computed
        // [T-android-freeze-edge-carryover] What the frozen branch falls back
        // to if this fragment freezes before its final text was ever live.
        liveCarry[0] = displayContent to computed
    }
    // [T-android-stream-grow-anim] No height/scroll animation here. We tried
    // animateContentSize to ease the bottom-pinned item's exposed height into a
    // smooth viewport follow, but diagnostics showed the live fragment's
    // composable identity is NOT stable across parse ticks (markdown re-blocks
    // every tick — blocks.size flips 1↔2, last-block position churns), so the
    // animation reset to initialH=0 almost every tick and "popped from zero"
    // instead of gliding, AND dragged single-frame cost to ~750–950ms (Davey).
    // Net regression. The smooth feel comes from reverseLayout's native bottom
    // pin (no jump, no extra layout cost) plus the per-word fade. A genuine
    // iOS-style continuous flow would require token-incremental rendering of the
    // streaming tail, not a height animation on an unstable item.
    Column(modifier = modifier) {
        // Wrap the last block in LocalAppendOnlyFade=true so MdText fades in
        // newly-appended words. [T-android-streaming-incremental-inline] Also
        // flag it as the LIVE tail so its Paragraph inline/math parse goes
        // through the incremental (frozen-prefix + fresh-suffix) path — this is
        // the only block whose raw grows every tick.
        // [T-android-stream-fade-seed] Same shape for every block — see the
        // matching note in StreamingMarkdownTextBody. This is the live
        // fragment, so there is no frozen path to protect; the provider is
        // always present and only its VALUE tracks the tail. Flipping a
        // value recomposes consumers; changing the group shape disposed them.
        val lastIdx = blocks.size - 1
        blocks.forEachIndexed { idx, block ->
            val isLast = idx == lastIdx
            androidx.compose.runtime.CompositionLocalProvider(
                LocalAppendOnlyFade provides isLast,
                LocalLiveIncremental provides isLast,
            ) { RenderBlock(block) }
        }
    }
}

/**
 * [T-android-live-block-degrade] A LIVE fragment larger than this renders as a
 * bounded plain-text tail until it freezes. 8KB of markdown in one unsplit
 * block is far beyond normal prose paragraphs — only giant tables/fences get
 * here, and those were the per-tick full-re-parse ANR load.
 */
private const val LIVE_FRAGMENT_DEGRADE_CHARS = 8_000
private const val LIVE_FRAGMENT_TAIL_CHARS = 3_000

/**
 * [T-android-coldload-offmain-parse] A FROZEN fragment above this size whose
 * block parse would be a cache MISS parses off-main (with a plain-text
 * preview in the meantime) instead of synchronously in composition. Below
 * it the parse is sub-ms and the placeholder swap would flicker for nothing.
 */
private const val COLD_PARSE_OFFMAIN_THRESHOLD_CHARS = 2_000
private const val COLD_PARSE_PREVIEW_CHARS = 4_000

/**
 * [T-android-coldload-offmain-parse] Composition-snapshot prewarmer for the
 * chat flatten pipeline: returns a thread-safe lambda that block-parses each
 * raw fragment AND prewarms the inline/math caches with the palette captured
 * here. Lets ChatScreen (which cannot see the file-private MdBlock/MdColors
 * types) warm the exact keys RenderBlock will look up, off-main, before the
 * viewport rows first compose.
 */
/**
 * [T-android-md-parse-gate] Cold-scroll prewarmer.
 *
 * This is the path the Pixel 6 tombstone died on: ChatScreen launches it on
 * `Dispatchers.Default` once per flatten tick per ChatViewModel, and each call
 * runs `MarkdownParseCaches.blocks()` → `parseMarkdownBlocksBlocking` →
 * `runBlocking { parseMarkdownBlocks(...) }` over up to 16 rows × 96 KB. With
 * several sub-agent sessions flattening at once these stack up unbounded, one
 * full ICU-matcher-allocating parse per core, until `operator new` throws
 * `std::bad_alloc`.
 *
 * Made `suspend` so it can take the shared [markdownParseGate]: prewarming is
 * pure look-ahead for scrolling, so it is exactly the work that should yield
 * to a visible stream's parse rather than race it.
 */
@Composable
internal fun rememberMarkdownPrewarmer(): suspend (List<String>) -> Unit {
    val mdColors = currentMdColors()
    return remember(mdColors) {
        val fn: suspend (List<String>) -> Unit = { raws ->
            for (raw in raws) {
                markdownParseGate.withPermit {
                    MarkdownParseCaches.prewarm(MarkdownParseCaches.blocks(raw), mdColors)
                }
                // Cooperative: a cancelled scroll must not keep prewarming.
                coroutineContext.ensureActive()
            }
        }
        fn
    }
}

/**
 * Synchronous variant of [parseMarkdownBlocks] used for frozen blocks
 * where we don't need cooperative cancellation. Implemented by reusing
 * the suspend version under a runBlocking on the calling thread — frozen
 * blocks parse once and the input is small, so this is fine.
 */
/**
 * [T-android-codeblock-fence-indent] A fenced code block's content, with the
 * fence's own indentation removed from every line (CommonMark: a fence indented
 * N spaces strips up to N spaces from each content line).
 *
 * The parser recognises an indented fence (it tests the trimmed line) but used
 * to keep the content lines verbatim. Models put code blocks inside list items
 * all the time —
 *
 *     - Install the dependencies:
 *       ```bash
 *       npm install
 *       ```
 *
 * — so the copy button handed out "  npm install": two leading spaces under a
 * bullet, three under "1.". iOS parses with cmark and strips them.
 *
 * Spaces only, and never more than the fence's indent: a line indented
 * further keeps its extra indentation (it is part of the code), and a line
 * indented less loses only what it has.
 */
internal fun fencedCodeContent(fenceLine: String, contentLines: List<String>): String {
    val indent = fenceLine.length - fenceLine.trimStart(' ').length
    if (indent == 0) return contentLines.joinToString("\n")
    return contentLines.joinToString("\n") { line ->
        var n = 0
        while (n < indent && n < line.length && line[n] == ' ') n++
        line.substring(n)
    }
}

private fun parseMarkdownBlocksBlocking(content: String): List<MdBlock> =
    kotlinx.coroutines.runBlocking { parseMarkdownBlocks(content) }

// ─── Block model ────────────────────────────────────────────────────────────

private sealed class MdBlock(val raw: String) {
    class Paragraph(raw: String) : MdBlock(raw)
    class Heading(raw: String, val level: Int, val text: String) : MdBlock(raw)
    class CodeBlock(raw: String, val language: String, val code: String) : MdBlock(raw)
    class BlockQuote(raw: String, val innerBlocks: List<MdBlock>) : MdBlock(raw)
    class UnorderedList(raw: String, val items: List<ListItem>) : MdBlock(raw)
    class OrderedList(raw: String, val items: List<ListItem>, val startNum: Int = 1) : MdBlock(raw)
    class TaskList(raw: String, val items: List<TaskItem>) : MdBlock(raw)
    class HorizontalRule(raw: String) : MdBlock(raw)
    class Table(
        raw: String,
        val headers: List<String>,
        val rows: List<List<String>>,
        /** Per-column alignment from the separator row; missing columns are START. */
        val alignments: List<MdTableAlign> = emptyList(),
    ) : MdBlock(raw)
    class Image(raw: String, val alt: String, val url: String) : MdBlock(raw)
    class Video(raw: String, val alt: String, val url: String) : MdBlock(raw)
    class Audio(raw: String, val alt: String, val url: String) : MdBlock(raw)
    /** T155: display-mode LaTeX rendered via KaTeX (`$$…$$` or `\[…\]`). */
    class MathDisplay(raw: String, val latex: String) : MdBlock(raw)
}

private val nativeVideoExts = setOf("mp4", "mov", "m4v", "avi", "mkv", "webm")
private val nativeAudioExts = setOf("mp3", "m4a", "wav", "aac", "ogg", "flac")

private fun mediaBlockFrom(raw: String, alt: String, url: String): MdBlock {
    // Classify by the last path segment's extension. Decoding first means a
    // filename like `foo%23China.mp4` or `foo#China.mp4` still resolves to
    // `.mp4` instead of being swallowed by `substringBefore('#')`.
    val lastSeg = url.substringAfterLast('/')
    val decoded = runCatching { java.net.URLDecoder.decode(lastSeg, "UTF-8") }.getOrDefault(lastSeg)
    val ext = decoded.substringAfterLast('.', "").lowercase()
    return when (ext) {
        in nativeVideoExts -> MdBlock.Video(raw, alt, url)
        in nativeAudioExts -> MdBlock.Audio(raw, alt, url)
        else -> MdBlock.Image(raw, alt, url)
    }
}

/** Matches any `![alt](url)` anywhere in a line. Non-greedy to handle multiple per line. */
private val inlineMediaRegex = Regex("""!\[([^\]\n]*)]\(([^)\s]+)\)""")

// ─── Hoisted block-parser regexes ─────────────────────────────────────────────
//
// parseMarkdownBlocks runs on every recompose during streaming — once per
// chunk, often dozens of times a second. Constructing each Regex inline
// triggered Pattern.compile (an ICU JNI call) on the main thread for every
// pattern, every chunk, on every block; long markdown documents pinned the
// main thread inside Pattern.compile long enough that the OS posted ANRs
// (>5 s waited for input). Hoisting to file-level vals compiles each pattern
// exactly once, at class init, so the streaming hot path is allocation-free
// for these matches.
private val thematicBreakRegex = Regex("^[-*_]{3,}\\s*$")
private val standaloneImageLineRegex = Regex("^!\\[.*]\\(.*\\)\\s*$")
private val imageMatchRegex = Regex("^!\\[(.*)\\]\\((.*)\\)")
private val tableSeparatorRegex = Regex("^\\|?[\\s\\-:|]+\\|?$")
private val taskListItemRegex = Regex("^[-*+]\\s+\\[[ xX]\\]\\s+.*")
private val taskListPrefixRegex = Regex("^[-*+]\\s+\\[[ xX]\\]\\s+")
private val bulletListItemRegex = Regex("^[-*+]\\s+.*")
private val bulletListPrefixRegex = Regex("^[-*+]\\s+")
private val numberedListItemRegex = Regex("^\\d+[.)\\s]+.*")
private val numberedListStartRegex = Regex("^(\\d+)")
private val numberedListPrefixRegex = Regex("^\\d+[.)\\s]+")

/**
 * A blockquote line must be `>` followed by a space, a tab, or end of line.
 * Anything else (e.g. `>foo`, `>5`, `>=`) is regular prose — likely shell
 * output or a comparison emitted by the LLM, not an intentional quote.
 */
private fun isBlockquoteLine(trimmed: String): Boolean {
    if (!trimmed.startsWith(">")) return false
    if (trimmed.length == 1) return true
    val next = trimmed[1]
    return next == ' ' || next == '\t'
}

/**
 * Split a paragraph's raw text at inline `![alt](url)` occurrences, extracting
 * video/audio references into standalone MdBlock.Video/Audio blocks. Image
 * references stay inline (Compose doesn't render inline bitmap attachments in
 * text here, but the `[alt]` link fallback is acceptable for images).
 *
 * Why: LLMs very commonly emit `"Here's the video: ![robot](minis://attachments/x.mp4)"`
 * on a single line alongside explanatory text. Without this split, the line
 * becomes one Paragraph and the video markdown is rendered as just a blue
 * `[alt]` link — no preview card, no tap-to-play.
 */
private fun splitParagraphOnInlineMedia(text: String): List<MdBlock> {
    val matches = inlineMediaRegex.findAll(text).toList()
    if (matches.isEmpty()) return listOf(MdBlock.Paragraph(text))

    // Pre-check: only split when at least one match is a media (video/audio)
    // that we can render as a card. Plain image-extension matches stay inline.
    val hasMediaExt = matches.any { m ->
        val url = m.groupValues[2]
        val lastSeg = url.substringAfterLast('/')
        val decoded = runCatching { java.net.URLDecoder.decode(lastSeg, "UTF-8") }.getOrDefault(lastSeg)
        val ext = decoded.substringAfterLast('.', "").lowercase()
        ext in nativeVideoExts || ext in nativeAudioExts
    }
    if (!hasMediaExt) return listOf(MdBlock.Paragraph(text))

    val result = mutableListOf<MdBlock>()
    var cursor = 0
    for (m in matches) {
        val url = m.groupValues[2]
        val lastSeg = url.substringAfterLast('/')
        val decoded = runCatching { java.net.URLDecoder.decode(lastSeg, "UTF-8") }.getOrDefault(lastSeg)
        val ext = decoded.substringAfterLast('.', "").lowercase()
        val isMedia = ext in nativeVideoExts || ext in nativeAudioExts
        if (!isMedia) continue

        val preceding = text.substring(cursor, m.range.first).trim('\n', ' ', '\t')
        if (preceding.isNotBlank()) result.add(MdBlock.Paragraph(preceding))
        val alt = m.groupValues[1]
        result.add(mediaBlockFrom(m.value, alt, url))
        cursor = m.range.last + 1
    }
    val tail = text.substring(cursor).trim('\n', ' ', '\t')
    if (tail.isNotBlank()) result.add(MdBlock.Paragraph(tail))
    return result
}

/**
 * T208-4 part 3: heuristic for "wide" inline math that should be promoted
 * to a display-mode block instead of stuffed into Compose's fixed-size
 * `InlineTextContent` placeholder.
 *
 * Wide constructs (matrices, aligned, multi-row \\, large \frac, long
 * formulas) overflow the inline slot — Compose's Placeholder API can't
 * resize per-formula, so the only options inside an inline span are
 * "clip" or "scale-down to unreadable". Promoting to a display block
 * lets it render at its natural size on its own line (same shape that
 * Markwon and MathJax adopt for `\displaystyle` / `\begin{...}`).
 *
 * Short inline math (`$x$`, `$x_i$`, `$f(x)=5$`) stays inline so prose
 * still flows naturally.
 */
private fun looksLikeWideMath(latex: String): Boolean {
    if (latex.length > 30) return true
    if (latex.contains("\\begin{")) return true        // bmatrix, pmatrix, aligned, cases…
    if (latex.contains("\\\\")) return true            // explicit LaTeX line break / matrix row sep
    if (latex.contains("\\frac")) return true          // fractions render two-line
    if (latex.contains("\\sum") || latex.contains("\\int") || latex.contains("\\prod")) return true
    if (latex.contains("\\sqrt")) return true
    if (latex.contains("\\mathbf{") || latex.contains("\\mathbb{") || latex.contains("\\mathcal{")) return true
    if (latex.contains("\\overline") || latex.contains("\\underline")) return true
    if (latex.contains("\\binom")) return true
    return false
}

/**
 * T208-4 part 3: split a paragraph at *wide* inline math spans, promoting
 * each one to a `MathDisplay` block. Mirrors the inline-media split: the
 * text before the math becomes a Paragraph, the math becomes its own
 * block, the trailing text becomes a Paragraph. Short math stays inline.
 *
 * Recognises the same delimiters as `parseInline`: `\(...\)` and
 * single-`$...$` (skipping `$$` which is already a block-level form).
 *
 * Walking the string by hand (rather than regex) so escape rules and
 * the "stop at newline" behavior of `findInlineMathClose` stay in sync
 * with the inline parser.
 */
private fun splitParagraphOnWideMath(text: String): List<MdBlock> {
    if (!text.contains('\\') && !text.contains('$')) return listOf(MdBlock.Paragraph(text))

    data class Span(val start: Int, val end: Int, val latex: String)
    val spans = mutableListOf<Span>()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        // Skip escaped chars inside prose so `\$5` doesn't open a math span.
        if (c == '\\' && i + 1 < text.length && text[i + 1] != '(' && text[i + 1] != '[') {
            i += 2; continue
        }
        if (c == '\\' && i + 1 < text.length && text[i + 1] == '(') {
            val end = text.indexOf("\\)", i + 2)
            if (end != -1) {
                val latex = text.substring(i + 2, end)
                if (looksLikeMath(latex) && looksLikeWideMath(latex)) {
                    spans.add(Span(i, end + 2, latex))
                }
                i = end + 2; continue
            }
        }
        if (c == '$' && i + 1 < text.length && text[i + 1] != '$' && text[i + 1] != ' ') {
            val end = findInlineMathClose(text, i + 1)
            if (end != -1) {
                val latex = text.substring(i + 1, end)
                if (looksLikeMath(latex) && looksLikeWideMath(latex)) {
                    spans.add(Span(i, end + 1, latex))
                }
                i = end + 1; continue
            }
        }
        i++
    }
    if (spans.isEmpty()) return listOf(MdBlock.Paragraph(text))

    val result = mutableListOf<MdBlock>()
    var cursor = 0
    for (s in spans) {
        val before = text.substring(cursor, s.start).trim('\n', ' ', '\t')
        if (before.isNotBlank()) result.add(MdBlock.Paragraph(before))
        result.add(MdBlock.MathDisplay(text.substring(s.start, s.end), s.latex))
        cursor = s.end
    }
    val tail = text.substring(cursor).trim('\n', ' ', '\t')
    if (tail.isNotBlank()) result.add(MdBlock.Paragraph(tail))
    return result
}

private data class ListItem(val text: String, val children: List<MdBlock> = emptyList())

/**
 * [T-android-math-overflow-wrap] A list item whose text holds wide inline
 * math renders as [ListItem.children]: its prose as paragraphs and each wide
 * formula as a display block that fits (and wraps to) the line. Top-level
 * paragraphs already get this split ([splitParagraphOnWideMath]); list items
 * kept the formula inline, in a fixed-size slot up to 22em wide, so on a
 * phone a long formula in a list ran past the right edge and was clipped —
 * the iOS report behind 7238a39d4. Items without wide math are unchanged.
 */
private fun ListItem.withWideMathSplit(): ListItem {
    val split = splitParagraphOnWideMath(text)
    return if (split.any { it is MdBlock.MathDisplay }) copy(children = split) else this
}
private data class TaskItem(val checked: Boolean, val text: String, val children: List<MdBlock> = emptyList())

/** [T-android-math-overflow-wrap] Same wide-math split as [ListItem.withWideMathSplit]. */
private fun TaskItem.withWideMathSplit(): TaskItem {
    val split = splitParagraphOnWideMath(text)
    return if (split.any { it is MdBlock.MathDisplay }) copy(children = split) else this
}

// ─── Block parser ───────────────────────────────────────────────────────────

/**
 * [T-android-latex-code-mask] Find the line index that closes a multi-line
 * `$$` display-math block opened just before [from], or null when no
 * *plausible* closer exists.
 *
 * Mirrors the rules ported into MarkdownParser (521b2dc7 / iOS bce7e2ed):
 *  - stop at a blank line — that is a paragraph break, so the `$$` was never
 *    a formula opener;
 *  - stop at a fence marker (``` / ~~~) and never look past it, so a `$$`
 *    living inside a code block can never be mistaken for the closer;
 *  - require at least one LaTeX-ish glyph in the body, so runs of plain prose
 *    are not silently rendered as math.
 *
 * Returning null makes the caller emit the `$$` as literal text, which is what
 * the user typed and what every other markdown renderer does.
 */
private fun findDisplayMathClose(lines: List<String>, from: Int): Int? {
    var j = from
    val body = StringBuilder()
    while (j < lines.size) {
        val l = lines[j]
        val t = l.trimStart()
        // A fence starts/ends a code region — a `$$` beyond it is not our closer.
        if (t.startsWith("```") || t.startsWith("~~~")) return null
        // Blank line = paragraph break; real display math has no interior blank.
        if (t.isBlank()) return null
        val close = l.indexOf("$$")
        if (close >= 0) {
            body.append(l.substring(0, close))
            val text = body.toString()
            // A closer sitting alone on its own line is the conventional
            // `$$ … $$` block shape and is accepted unconditionally — that
            // covers glyph-free but perfectly valid math like "1 + 2 = 3",
            // which an "always require a LaTeX glyph" rule would wrongly
            // demote to plain text.
            if (t == "$$") return j
            // Degenerate empty body is harmless.
            if (text.isBlank()) return j
            // Otherwise the closer is mid-line (e.g. "… foo $$ bar"), which is
            // the shape a stray delimiter in prose produces. Only accept it
            // when the body actually looks like a formula.
            return if (text.any { it == '\\' || it == '^' || it == '_' || it == '{' || it == '}' }) j else null
        }
        body.append(l).append('\n')
        j++
    }
    return null
}

/**
 * [T-android-md-parse-gate] Global cap on how many markdown parses may run at
 * once, across every ChatViewModel in the process.
 *
 * Background: a multi-subtask agent run streams N child sessions at the same
 * time. Each visible transcript owns its own throttled parse loop, and every
 * pass re-parses that session's WHOLE accumulated reply. `Regex.matches` is
 * used ~8 times per line, and each call allocates a fresh native ICU
 * `MatcherState` (libicu_jni `MatcherState::updateInput`). With N streams the
 * native allocation rate multiplies by N while `Dispatchers.Default` happily
 * runs one parse per core — on a Pixel 6 that reached 2.09 GB native heap, a
 * 512 MB Dalvik heap pinned at its largeHeap ceiling, `ReferenceQueueDaemon`
 * spinning a full core on GC, and finally `std::bad_alloc` inside
 * `operator new` → SIGABRT.
 *
 * Serialising parses does NOT slow the visible stream down: a parse is at most
 * a few tens of ms and the display content is already throttled to
 * 200-2000 ms per session ([streamingThrottleFor]). What it does is bound
 * peak concurrent ICU matcher + block-list allocation to one pass instead of
 * `min(N, coreCount)`, which is what turns the OOM into ordinary back-pressure.
 *
 * Permit 1 by design: with 2 the peak halves but does not bound, and the
 * throttle already leaves each stream far more wall-clock budget than it
 * needs. Callers that are cancelled while queued never start a parse at all,
 * which is exactly the behaviour we want for superseded chunks.
 */
private val markdownParseGate = Semaphore(1)

/**
 * [T-android-md-parse-incremental] Longest already-parsed prefix of a growing
 * stream, so an append-only update re-parses only the tail.
 *
 * Streaming content is append-only, and markdown block structure is
 * prefix-stable up to the last blank-line boundary: everything before the
 * final `

` can no longer change no matter what arrives next (the parser
 * never looks backwards past a blank line — each `when` branch consumes
 * forward from its opening line). So we cache the blocks for that stable
 * prefix and, on the next pass, parse only what follows it.
 *
 * The saving is what makes long streams survivable: without it, a 60 KB reply
 * is re-parsed from byte 0 on every throttle tick — O(n²) native matcher
 * allocations over the life of the stream. With it, each tick parses only the
 * newly-arrived tail.
 */
private class IncrementalParseState {
    var prefixLength: Int = 0
    var prefixBlocks: List<MdBlock> = emptyList()
    /**
     * Cheap fingerprint of the frozen prefix, to detect that growth really was
     * append-only (a retry / edit / shorter re-render must invalidate).
     *
     * Deliberately NOT the prefix text itself: holding a copy would retain a
     * second full-size String per live composable and make the check O(n) on
     * every tick — reintroducing exactly the memory and CPU pressure this
     * change exists to remove. A length + hash of the boundary region is
     * O(1)-ish and false-positives only if an edit preserved both.
     */
    var prefixHash: Int = 0
}

/** Fingerprint over the tail of [content] up to [end] (bounded work). */
private fun prefixFingerprint(content: String, end: Int): Int {
    if (end <= 0) return 0
    val from = (end - 512).coerceAtLeast(0)
    var h = end
    for (i in from until end) h = h * 31 + content[i].code
    return h
}

/**
 * Offset of the end of the last "sealed" block boundary — the final blank line
 * that is followed by more content. Returns 0 when nothing is sealed yet.
 *
 * Deliberately conservative: a fenced code block can contain blank lines, so
 * an odd number of ``` fences before the candidate boundary means the boundary
 * is inside a fence and is not safe to freeze. Same for `$$` display math.
 */
/**
 * [T-android-md-parse-incremental] Test hook: the `raw` text of each block a
 * parse produces. Lets a unit test assert that feeding a stream in chunks
 * through [parseMarkdownIncremental] yields exactly the same block split as
 * one full parse of the final text — the invariant the whole optimization
 * rests on.
 */
internal fun debugParseBlockRaws(content: String): List<String> =
    kotlinx.coroutines.runBlocking { parseMarkdownBlocks(content).map { it.raw } }

/** Test hook: same, but replayed incrementally as the text grows. */
internal fun debugParseIncrementalRaws(chunks: List<String>): List<String> =
    kotlinx.coroutines.runBlocking {
        val state = IncrementalParseState()
        var last: List<MdBlock> = emptyList()
        for (c in chunks) last = parseMarkdownIncremental(c, state)
        last.map { it.raw }
    }

internal fun stableParsePrefixEnd(content: String, searchFrom: Int): Int {
    var idx = content.lastIndexOf("\n\n")
    while (idx >= searchFrom) {
        val candidate = idx + 2
        if (isSealedBoundary(content, candidate)) return candidate
        if (idx == 0) break
        idx = content.lastIndexOf("\n\n", idx - 1)
    }
    return searchFrom
}

/**
 * True when [end] is not inside an open ``` fence, `$$` / `\[` display-math
 * span, or a list that can still continue past the blank line.
 * Counts delimiters that START a line, matching how [parseMarkdownBlocks]
 * recognises them (it tests `trimmed.startsWith`).
 *
 * [T-android-md-sealed-boundary-lists] A blank line is only a safe freeze
 * point if no block construct in [parseMarkdownBlocks] crosses it. Fences and
 * `$$` were covered; two more constructs DO cross a blank line and were not:
 *  - `\[ … \]` display math: its loop scans forward over blank lines to the
 *    first line containing `\]`, so a blank line inside it is not a boundary.
 *  - bullet / ordered lists: both list loops `continue` over blank lines and
 *    keep consuming the next list item or indented line. A loose list or an
 *    item with an indented continuation paragraph is ONE block in a full
 *    parse; freezing at its blank line split it into several while streaming,
 *    and the block re-laid out at the streaming -> frozen edge.
 * Refusing costs only re-parse work; accepting wrongly changes the render.
 */
internal fun isSealedBoundary(content: String, end: Int): Boolean {
    var fences = 0
    var maths = 0
    var bracketMathOpen = false
    // Last non-blank line before [end] — a list only ever ends on a list item
    // or an indented line, so this is what decides whether one is still open.
    var lastNonBlankStart = -1
    var lastNonBlankEnd = -1
    var lineStart = 0
    while (lineStart < end) {
        var lineEnd = content.indexOf('\n', lineStart)
        if (lineEnd < 0 || lineEnd > end) lineEnd = end
        var p = lineStart
        while (p < lineEnd && (content[p] == ' ' || content[p] == '\t')) p++
        if (bracketMathOpen) {
            // Mirrors the parser's `\[` loop: the first line containing `\]`
            // anywhere closes the span.
            if (content.indexOf("\\]", lineStart).let { it in lineStart until lineEnd }) {
                bracketMathOpen = false
            }
        } else if (content.startsWith("\\[", p)) {
            bracketMathOpen = content.indexOf("\\]", p + 2).let { it !in 0 until lineEnd }
        }
        if (content.startsWith("```", p)) fences++
        else if (content.startsWith("$$", p)) {
            // A single-line `$$ … $$` opens and closes on the same line.
            maths += if (content.indexOf("$$", p + 2).let { it in 0 until lineEnd }) 0 else 1
        }
        if (p < lineEnd && content.substring(p, lineEnd).isNotBlank()) {
            lastNonBlankStart = lineStart
            lastNonBlankEnd = lineEnd
        }
        lineStart = lineEnd + 1
    }
    if (fences % 2 != 0 || maths % 2 != 0 || bracketMathOpen) return false
    if (lastNonBlankStart < 0) return true
    val prev = content.substring(lastNonBlankStart, lastNonBlankEnd)
    if (!isListContinuationLine(prev)) return true
    // A list is (possibly) open. The blank line is a boundary only if the
    // next non-blank line is COMPLETE and would break the list — neither a
    // list item nor indented. An unterminated line may still grow into one
    // ("-" -> "- beta", "" -> "   more"), and no next line at all means the
    // stream has not decided yet.
    var q = end
    while (q < content.length) {
        val nl = content.indexOf('\n', q)
        if (nl < 0) return false
        val line = content.substring(q, nl)
        if (line.isNotBlank()) return !isListContinuationLine(line)
        q = nl + 1
    }
    return false
}

/**
 * [T-android-md-sealed-boundary-lists] Whether [line] is something the list
 * loops in [parseMarkdownBlocks] would keep consuming after a blank line: a
 * list item, or any indented line (continuation / nested content).
 */
private fun isListContinuationLine(line: String): Boolean {
    if (line.isEmpty()) return false
    if (line[0] == ' ' || line[0] == '\t') return true
    return line.matches(bulletListItemRegex) || line.matches(numberedListItemRegex) ||
        line.matches(taskListItemRegex)
}

/**
 * [T-android-md-parse-gate] The entry point every streaming parse must use.
 *
 * Combines the two protections: it takes the global [markdownParseGate]
 * permit (so N concurrent sub-agent streams cannot multiply peak native ICU
 * allocation) and reuses the already-parsed stable prefix carried in [state]
 * (so an append-only stream is not re-parsed from byte 0 on every tick).
 *
 * The permit is taken around the parse only. Suspending here is the desired
 * back-pressure: a caller whose LaunchedEffect is cancelled while queued
 * never runs its parse, which is correct — a newer chunk has superseded it.
 */
private suspend fun parseMarkdownIncremental(
    content: String,
    state: IncrementalParseState,
): List<MdBlock> = markdownParseGate.withPermit {
    // Growth must be append-only for the cached prefix to remain valid. Any
    // other edit (retry, edit-message, a shorter re-render) resets the state.
    val reusable = state.prefixLength in 1..content.length &&
        prefixFingerprint(content, state.prefixLength) == state.prefixHash
    if (!reusable) {
        state.prefixLength = 0
        state.prefixBlocks = emptyList()
        state.prefixHash = 0
    }

    val head = state.prefixLength
    // Split the unparsed remainder at its newest sealed boundary, so the part
    // that can never change again is parsed exactly once, here, and then
    // frozen into the prefix for every later tick.
    val sealedEnd = stableParsePrefixEnd(content, head)
    val sealedTail = if (sealedEnd > head) {
        parseMarkdownBlocks(content.substring(head, sealedEnd))
    } else {
        emptyList()
    }
    val liveTail = if (sealedEnd < content.length) {
        parseMarkdownBlocks(content.substring(sealedEnd))
    } else {
        emptyList()
    }

    if (sealedEnd > head) {
        state.prefixBlocks = state.prefixBlocks + sealedTail
        state.prefixLength = sealedEnd
        state.prefixHash = prefixFingerprint(content, sealedEnd)
    }
    state.prefixBlocks + liveTail
}

private suspend fun parseMarkdownBlocks(content: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = content.lines()
    var i = 0
    // Counter so we don't query coroutineContext on EVERY line (small but
    // measurable allocation overhead at ~thousands of lines per pass).
    var sinceLastCheck = 0

    while (i < lines.size) {
        if (sinceLastCheck >= 64) {
            coroutineContext.ensureActive()
            sinceLastCheck = 0
        }
        sinceLastCheck++
        val line = lines[i]
        val trimmed = line.trimStart()

        when {
            // T155: display math `$$...$$` (single-line or multi-line) and `\[...\]`
            trimmed.startsWith("$$") -> {
                val rest = trimmed.removePrefix("$$")
                val inlineEnd = rest.indexOf("$$")
                if (inlineEnd >= 0) {
                    // Same-line `$$ … $$`
                    val latex = rest.substring(0, inlineEnd).trim()
                    blocks.add(MdBlock.MathDisplay(line, latex))
                    i++
                } else {
                    // [T-android-latex-code-mask] Multi-line: LOOK AHEAD for the
                    // closing `$$` and validate it before committing. The old
                    // loop scanned forward unconditionally, so an unclosed `$$`
                    // (a model forgetting to close it, or prose explaining
                    // LaTeX) paired with a `$$` inside a LATER ``` fence and
                    // swallowed every paragraph in between plus the fence's own
                    // opening line — leaving an orphaned closing fence. This is
                    // issue #117 defect 3 on the streaming path; the same defect
                    // was fixed in MarkdownParser (521b2dc7), but THIS is the
                    // renderer the chat transcript actually uses.
                    val closeIdx = findDisplayMathClose(lines, i + 1)
                    if (closeIdx == null) {
                        // No plausible closer — emit the `$$` as ordinary text
                        // and let the following lines parse normally.
                        blocks.add(MdBlock.Paragraph(line))
                        i++
                    } else {
                        val rawLines = mutableListOf(line)
                        val mathLines = mutableListOf<String>()
                        if (rest.isNotEmpty()) mathLines.add(rest)
                        i++
                        while (i <= closeIdx) {
                            rawLines.add(lines[i])
                            if (i == closeIdx) {
                                val close = lines[i].indexOf("$$")
                                val pre = lines[i].substring(0, close)
                                if (pre.isNotEmpty()) mathLines.add(pre)
                                i++
                                break
                            }
                            mathLines.add(lines[i])
                            i++
                        }
                        blocks.add(
                            MdBlock.MathDisplay(
                                rawLines.joinToString("\n"),
                                mathLines.joinToString("\n").trim(),
                            ),
                        )
                    }
                }
            }
            trimmed.startsWith("\\[") -> {
                val rest = trimmed.removePrefix("\\[")
                val inlineEnd = rest.indexOf("\\]")
                if (inlineEnd >= 0) {
                    val latex = rest.substring(0, inlineEnd).trim()
                    blocks.add(MdBlock.MathDisplay(line, latex))
                    i++
                } else {
                    val rawLines = mutableListOf(line)
                    val mathLines = mutableListOf<String>()
                    if (rest.isNotEmpty()) mathLines.add(rest)
                    i++
                    while (i < lines.size) {
                        rawLines.add(lines[i])
                        val close = lines[i].indexOf("\\]")
                        if (close >= 0) {
                            val pre = lines[i].substring(0, close)
                            if (pre.isNotEmpty()) mathLines.add(pre)
                            i++
                            break
                        }
                        mathLines.add(lines[i])
                        i++
                    }
                    blocks.add(MdBlock.MathDisplay(rawLines.joinToString("\n"), mathLines.joinToString("\n").trim()))
                }
            }
            // Fenced code block
            trimmed.startsWith("```") -> {
                val lang = trimmed.removePrefix("```").trim()
                val codeLines = mutableListOf<String>()
                val rawLines = mutableListOf(line)
                i++
                while (i < lines.size) {
                    rawLines.add(lines[i])
                    if (lines[i].trimStart().startsWith("```")) { i++; break }
                    codeLines.add(lines[i])
                    i++
                }
                blocks.add(MdBlock.CodeBlock(rawLines.joinToString("\n"), lang, fencedCodeContent(line, codeLines)))
            }

            // Heading
            trimmed.startsWith("#") && (trimmed.length == 1 || trimmed[trimmed.indexOfFirst { it != '#' }.coerceAtLeast(0)] == ' ') -> {
                val level = trimmed.takeWhile { it == '#' }.length.coerceAtMost(6)
                val text = trimmed.drop(level).trimStart()
                blocks.add(MdBlock.Heading(line, level, text))
                i++
            }

            // Horizontal rule
            trimmed.matches(thematicBreakRegex) -> {
                blocks.add(MdBlock.HorizontalRule(line))
                i++
            }

            // Image / Video / Audio: ![alt](url) on its own line — routed by file extension.
            trimmed.matches(standaloneImageLineRegex) -> {
                val match = imageMatchRegex.find(trimmed)
                if (match != null) {
                    val alt = match.groupValues[1]
                    val url = match.groupValues[2]
                    val blk = mediaBlockFrom(line, alt, url)
                    android.util.Log.d("MdStream", "media match: alt=\"$alt\" url=$url -> ${blk::class.simpleName}")
                    blocks.add(blk)
                }
                i++
            }

            // Table (line contains | and next line is separator)
            trimmed.contains('|') && i + 1 < lines.size &&
                lines[i + 1].trim().matches(tableSeparatorRegex) -> {
                val tableLines = mutableListOf<String>()
                while (i < lines.size && (lines[i].contains('|') ||
                        lines[i].trim().matches(tableSeparatorRegex))) {
                    tableLines.add(lines[i])
                    i++
                }
                val parsed = parseTable(tableLines)
                blocks.add(MdBlock.Table(tableLines.joinToString("\n"), parsed.headers, parsed.rows, parsed.alignments))
            }

            // Blockquote — strict CommonMark match: `>` followed by space or end
            // of line. The looser `startsWith(">")` accidentally swallowed
            // shell-output prompts like `>foo`, comparisons (`>5`, `>=`), and
            // generally any inline `>`-led token an LLM happens to emit, which
            // wrapped innocent prose in an orange leading rule (T117).
            isBlockquoteLine(trimmed) -> {
                val rawLines = mutableListOf<String>()
                val innerLines = mutableListOf<String>()
                while (i < lines.size && isBlockquoteLine(lines[i].trimStart())) {
                    rawLines.add(lines[i])
                    innerLines.add(lines[i].trimStart().removePrefix(">").removePrefix(" "))
                    i++
                }
                val innerBlocks = parseMarkdownBlocks(innerLines.joinToString("\n"))
                blocks.add(MdBlock.BlockQuote(rawLines.joinToString("\n"), innerBlocks))
            }

            // Task list: - [x] or - [ ]
            trimmed.matches(taskListItemRegex) -> {
                val items = mutableListOf<TaskItem>()
                val rawLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trimStart().matches(taskListItemRegex)) {
                    rawLines.add(lines[i])
                    val t = lines[i].trimStart()
                    val checked = t.contains("[x]", ignoreCase = true)
                    val text = t.replaceFirst(taskListPrefixRegex, "")
                    items.add(TaskItem(checked, text))
                    i++
                }
                blocks.add(MdBlock.TaskList(rawLines.joinToString("\n"), items.map { it.withWideMathSplit() }))
            }

            // Unordered list
            trimmed.matches(bulletListItemRegex) -> {
                val items = mutableListOf<ListItem>()
                val rawLines = mutableListOf<String>()
                val baseIndent = line.length - trimmed.length
                while (i < lines.size) {
                    val l = lines[i]
                    val t = l.trimStart()
                    val indent = l.length - t.length
                    if (t.isEmpty()) { i++; continue }
                    if (!t.matches(bulletListItemRegex) && indent <= baseIndent) break
                    if (indent > baseIndent) {
                        // Continuation or nested — append to last item
                        if (items.isNotEmpty()) {
                            val last = items.last()
                            items[items.lastIndex] = last.copy(text = last.text + "\n" + t)
                        }
                    } else {
                        rawLines.add(l)
                        items.add(ListItem(t.replaceFirst(bulletListPrefixRegex, "")))
                    }
                    i++
                }
                blocks.add(MdBlock.UnorderedList(rawLines.joinToString("\n"), items.map { it.withWideMathSplit() }))
            }

            // Ordered list
            trimmed.matches(numberedListItemRegex) -> {
                val items = mutableListOf<ListItem>()
                val rawLines = mutableListOf<String>()
                val startMatch = numberedListStartRegex.find(trimmed)
                val startNum = startMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val baseIndent = line.length - trimmed.length
                while (i < lines.size) {
                    val l = lines[i]
                    val t = l.trimStart()
                    val indent = l.length - t.length
                    if (t.isEmpty()) { i++; continue }
                    if (!t.matches(numberedListItemRegex) && indent <= baseIndent) break
                    if (indent > baseIndent) {
                        if (items.isNotEmpty()) {
                            val last = items.last()
                            items[items.lastIndex] = last.copy(text = last.text + "\n" + t)
                        }
                    } else {
                        rawLines.add(l)
                        items.add(ListItem(t.replaceFirst(numberedListPrefixRegex, "")))
                    }
                    i++
                }
                blocks.add(MdBlock.OrderedList(rawLines.joinToString("\n"), items.map { it.withWideMathSplit() }, startNum))
            }

            // Empty line
            trimmed.isEmpty() -> { i++ }

            // Paragraph
            else -> {
                val paraLines = mutableListOf<String>()
                while (i < lines.size) {
                    val l = lines[i]
                    val t = l.trimStart()
                    if (t.isEmpty() || t.startsWith("#") || t.startsWith("```") ||
                        isBlockquoteLine(t) || t.matches(thematicBreakRegex) ||
                        t.matches(bulletListItemRegex) || t.matches(numberedListItemRegex) ||
                        t.matches(standaloneImageLineRegex) ||
                        (t.contains('|') && i + 1 < lines.size &&
                            lines[i + 1].trim().matches(tableSeparatorRegex))
                    ) break
                    paraLines.add(l)
                    i++
                }
                val text = paraLines.joinToString("\n")
                if (text.isNotBlank()) {
                    // Split out inline media (`![alt](url)` that's a .mp4/.mp3/etc)
                    // so videos/audio that the LLM emits adjacent to text still
                    // get their dedicated preview card. Image extensions stay
                    // inline (rendered as `[alt]` link) since Compose's inline
                    // text-image attachment path isn't implemented here.
                    //
                    // T208-4 part 3: after the media split, run a second pass
                    // that promotes "wide" inline math (matrices, multi-row
                    // \\, large \frac, long formulas) to standalone display
                    // blocks. Compose's `InlineTextContent` placeholder is
                    // fixed-size — wide formulas inside it either clip or
                    // scale to unreadable. Splitting at parse time lets each
                    // wide span render at its natural display-mode size on
                    // its own line; short inline math (`$x_i$`) stays inline.
                    val mediaBlocks = splitParagraphOnInlineMedia(text)
                    for (b in mediaBlocks) {
                        if (b is MdBlock.Paragraph) {
                            blocks.addAll(splitParagraphOnWideMath(b.raw))
                        } else {
                            blocks.add(b)
                        }
                    }
                }
            }
        }
    }
    return blocks
}

/**
 * [T-android-table-column-align] GFM column alignment, from the separator row:
 * `:---:` centre, `---:` end, `---` / `:---` start (the default, as on iOS).
 */
enum class MdTableAlign { START, CENTER, END }

internal class ParsedTable(
    val headers: List<String>,
    val rows: List<List<String>>,
    val alignments: List<MdTableAlign>,
)

internal fun parseTableAlignments(separator: String): List<MdTableAlign> =
    separator.trim().removePrefix("|").removeSuffix("|").split("|").map { raw ->
        val cell = raw.trim()
        val left = cell.startsWith(':')
        val right = cell.endsWith(':') && cell.length > 1
        when {
            left && right -> MdTableAlign.CENTER
            right -> MdTableAlign.END
            else -> MdTableAlign.START
        }
    }

internal fun parseTable(lines: List<String>): ParsedTable {
    val headers = mutableListOf<String>()
    val rows = mutableListOf<List<String>>()
    var alignments: List<MdTableAlign> = emptyList()
    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.matches(tableSeparatorRegex)) {
            // Only the separator right under the header defines alignment; a stray
            // separator-looking row further down is skipped as before.
            if (alignments.isEmpty() && headers.isNotEmpty() && rows.isEmpty()) {
                alignments = parseTableAlignments(trimmed)
            }
            continue
        }
        // T308: Strip the leading/trailing pipe (if present) before splitting.
        // The previous `.filter { isNotEmpty() }` swallowed legitimate empty
        // cells like the first column of `| | Manus | TikTok |`, leaving the
        // header with fewer columns than body rows and breaking alignment.
        val core = trimmed.removePrefix("|").removeSuffix("|")
        val cells = core.split("|").map { it.trim() }
        if (headers.isEmpty()) headers.addAll(cells) else rows.add(cells)
    }
    return ParsedTable(headers, rows, alignments)
}

// ─── Block renderers ────────────────────────────────────────────────────────

@Composable
private fun RenderBlock(block: MdBlock) {
    val colors = currentMdColors()
    // [T-android-streaming-incremental-inline] The live streaming tail block
    // re-parses its growing paragraph every throttle tick; route it through the
    // incremental cache (frozen closed prefix + fresh suffix). Frozen/history
    // blocks (false) keep the plain per-block cache — no behavior change there.
    val liveIncremental = LocalLiveIncremental.current
    when (block) {
        is MdBlock.Paragraph -> {
            MdText(
                text = if (liveIncremental) MarkdownParseCaches.inlineIncremental(block.raw, colors)
                       else MarkdownParseCaches.inline(block.raw, colors),
                fontSize = BaseFontSize,
                lineHeight = BaseLineHeight,
                color = colors.text,
                modifier = Modifier.padding(bottom = 4.dp),
                inlineContent = rememberKatexInlineContent(
                    BaseFontSize,
                    if (liveIncremental) MarkdownParseCaches.mathLatexIncremental(block.raw)
                    else MarkdownParseCaches.mathLatex(block.raw),
                ),
            )
        }

        is MdBlock.Heading -> {
            val (size, weight) = when (block.level) {
                1 -> (BaseFontSize * 1.5f) to FontWeight.Bold
                2 -> (BaseFontSize * 1.3f) to FontWeight.Bold
                3 -> (BaseFontSize * 1.15f) to FontWeight.SemiBold
                4 -> BaseFontSize to FontWeight.SemiBold
                5 -> (BaseFontSize * 0.875f) to FontWeight.SemiBold
                else -> (BaseFontSize * 0.85f) to FontWeight.SemiBold
            }
            MdText(
                text = MarkdownParseCaches.inline(block.text, colors),
                fontSize = size,
                fontWeight = weight,
                lineHeight = size * 1.3f,
                color = colors.text,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                inlineContent = rememberKatexInlineContent(size, MarkdownParseCaches.mathLatex(block.text)),
            )
        }

        is MdBlock.CodeBlock -> {
            val clipboardManager = LocalClipboardManager.current
            var copied by remember { mutableStateOf(false) }
            if (copied) {
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(1500)
                    copied = false
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.codeBg),
            ) {
                // Header row: language label + copy button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 8.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = block.language.ifEmpty { "code" },
                        fontSize = 11.sp,
                        color = MdCodeLangColor,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = if (copied) "Copied" else "Copy code",
                        tint = if (copied) Color(0xFF34C759) else Color.White.copy(alpha = 0.4f),
                        modifier = Modifier
                            .size(16.dp)
                            .clickable {
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(block.code))
                                copied = true
                            },
                    )
                }
                // iOS parity (SelectableMarkdownView.swift L971): cap visual
                // code-block height at ~400 pt and let an internal scroll
                // view handle overflow vertically, so a 200-line dump
                // doesn't push the rest of the message off the bottom of
                // the chat. Nest scrolls: inner Row owns horizontal scroll
                // (long lines), outer Box owns vertical scroll + height
                // cap (long blocks). Compose disallows two scroll modifiers
                // on the same node, hence the nesting.
                val vScroll = rememberScrollState()
                val hScroll = rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(vScroll)
                        .padding(bottom = 8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .horizontalScroll(hScroll)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = block.code,
                            fontSize = BaseFontSize * 0.85f,
                            fontFamily = FontFamily.Monospace,
                            color = colors.codeText,
                            lineHeight = BaseLineHeight * 0.9f,
                        )
                    }
                }
            }
        }

        is MdBlock.BlockQuote -> {
            // T307: previous IntrinsicSize.Min approach crashes when inner
            // blocks contain SubcomposeLayout (tables, images, etc.) — Compose
            // refuses intrinsic measurement on those. Draw the orange rule
            // directly behind a single Column so layout never queries
            // intrinsics.
            val barColor = Color(0xFFFF9500)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .drawBehind {
                        drawRect(
                            color = barColor,
                            topLeft = Offset.Zero,
                            size = Size(3.dp.toPx(), size.height),
                        )
                    }
                    .padding(start = 15.dp),
            ) {
                block.innerBlocks.forEach { inner -> RenderBlock(inner) }
            }
        }

        is MdBlock.UnorderedList -> {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                block.items.forEach { item ->
                    Row(modifier = Modifier.padding(start = 8.dp, bottom = 2.dp)) {
                        Text("•  ", fontSize = BaseFontSize * 1.3f, color = colors.text)
                        RenderListItemBody(item, colors, Modifier.weight(1f))
                    }
                }
            }
        }

        is MdBlock.OrderedList -> {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                block.items.forEachIndexed { index, item ->
                    Row(modifier = Modifier.padding(start = 8.dp, bottom = 2.dp)) {
                        Text(
                            "${block.startNum + index}.  ",
                            fontSize = BaseFontSize,
                            color = colors.text,
                        )
                        RenderListItemBody(item, colors, Modifier.weight(1f))
                    }
                }
            }
        }

        is MdBlock.TaskList -> {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                block.items.forEach { item ->
                    Row(
                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                        // An item split around a display formula is several
                        // lines tall: keep the box on its first line, not
                        // floating beside the formula in the middle.
                        verticalAlignment = if (item.children.isEmpty()) Alignment.CenterVertically else Alignment.Top,
                    ) {
                        Checkbox(
                            checked = item.checked,
                            onCheckedChange = null,
                            modifier = Modifier.size(20.dp),
                            colors = CheckboxDefaults.colors(
                                checkedColor = colors.link,
                            ),
                        )
                        Spacer(Modifier.width(6.dp))
                        RenderListItemBody(
                            text = item.text,
                            children = item.children,
                            colors = colors,
                            modifier = Modifier.weight(1f),
                            textColor = if (item.checked) colors.text.copy(alpha = 0.5f) else colors.text,
                        )
                    }
                }
            }
        }

        is MdBlock.HorizontalRule -> {
            HorizontalDivider(
                color = colors.divider,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        is MdBlock.Image -> {
            android.util.Log.d("MdStream", "render Image url=${block.url}")
            // [T-android-markdown-image-gallery-cross-message] Prefer the
            // image-specific handler when provided so the host can collect
            // every sibling image across the conversation and open a paged
            // gallery (mirrors iOS AIChatView.handleMarkdownImageTap). Fall
            // back to the generic URL handler — which routes a single-item
            // open via ChatLinkResolver — when the host hasn't supplied an
            // image handler (keeps the previous behaviour intact).
            val imageTapHandler = LocalMarkdownImageTapHandler.current
            val urlTapHandler = LocalMarkdownUrlClickHandler.current
            val ambientMessageId = LocalShardId.current?.messageId
            val onTap: (() -> Unit)? = when {
                imageTapHandler != null && ambientMessageId != null ->
                    { -> imageTapHandler(ambientMessageId, block.url) }
                urlTapHandler != null -> { -> urlTapHandler(block.url) }
                else -> null
            }
            val context = LocalContext.current
            val sessionId = LocalMarkdownSessionId.current
            // Resolve to a host File via the session-scoped resolver before
            // handing off to Coil. AsyncImage(model = "minis://...") routes
            // through MinisImageFetcher → PRootKernel.resolveHostPath, which
            // reads the *global* bindMounts map — last-writer-wins across
            // sessions. When another session booted its shell more recently,
            // that global lookup answers with the wrong session's path (or
            // null) and the image quietly renders as a 0-height placeholder.
            // The video/audio renderers already follow this pattern.
            val file = remember(block.url, sessionId) { resolveMdMediaFile(context, block.url, sessionId) }
            // T146: 1dp hairline + 2dp soft shadow so a white-bg PNG (matplotlib
            // chart, screenshot…) reads as a discrete card against the chat
            // surface. Same ChatColors.thumbnailBorder / inputShadow recipe as
            // the attachment chip in T179 — keeps the visual rhythm consistent.
            // shadow → clip → border so the elevation paints behind the rounded
            // edge and the border stays crisp on top.
            val imageShape = RoundedCornerShape(8.dp)
            val imageBaseModifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .shadow(
                    elevation = 2.dp,
                    shape = imageShape,
                    clip = false,
                    ambientColor = ChatColors.inputShadow,
                    spotColor = ChatColors.inputShadow,
                )
                .clip(imageShape)
                .border(1.dp, ChatColors.thumbnailBorder, imageShape)
                .let { m -> if (onTap != null) m.clickable { onTap() } else m }
            // T148: SubcomposeAsyncImage so we can render a broken-image
            // placeholder when the underlying file is gone (deleted workspace
            // PNG, broken URL). Without this slot, Coil paints nothing and
            // the user sees a blank gap where a chart should be — easy to
            // mistake for a render bug.
            // [T-android-canvas-large-bitmap-crash] Cap the DECODE size.
            // Without an explicit request size, Coil sizes from the layout
            // constraints — but this column scrolls vertically, so the height
            // constraint is unbounded and Coil falls back to the image's
            // intrinsic size, decoding a very tall chart PNG at full
            // resolution. The resulting bitmap (215MB in the vivo/Android 16
            // report) exceeds RecordingCanvas's draw ceiling and crashes the
            // process from ThreadedRenderer.draw. Capping here means the
            // oversized bitmap is never allocated at all. FillWidth still
            // scales the (now bounded) bitmap to the column width, so normal
            // images render byte-identically to before.
            // Remembered per (file, url): this renderer recomposes on every
            // streaming token, and rebuilding the request each time would churn
            // allocations in a hot path.
            // [T-android-image-session-direct] When the session-scoped lookup
            // above missed (file not there yet, or a link copied from another
            // chat), the raw minis:// URL goes to MinisImageFetcher — tell it
            // which chat this row belongs to, so it reads that session's dir
            // directly instead of guessing from global state.
            val imageRequest = remember(file, block.url, sessionId) {
                ImageRequest.Builder(context)
                    .data(file ?: block.url)
                    .apply { if (file == null && sessionId != null) setParameter(com.openminis.app.ui.MinisImageFetcher.SESSION_PARAM, sessionId) }
                    .limitDisplaySize()
                    .build()
            }
            SubcomposeAsyncImage(
                model = imageRequest,
                contentDescription = block.alt,
                modifier = imageBaseModifier,
                contentScale = ContentScale.FillWidth,
            ) {
                when (painter.state) {
                    is AsyncImagePainter.State.Error -> BrokenImagePlaceholder(alt = block.alt)
                    else -> SubcomposeAsyncImageContent()
                }
            }
        }

        is MdBlock.Video -> {
            android.util.Log.d("MdStream", "render Video url=${block.url}")
            RenderMdVideo(block)
        }

        is MdBlock.Audio -> {
            android.util.Log.d("MdStream", "render Audio url=${block.url}")
            RenderMdAudio(block)
        }

        is MdBlock.Table -> {
            RenderTable(block)
        }

        is MdBlock.MathDisplay -> {
            RenderMathDisplay(block.latex)
        }
    }
}

// ─── Math (KaTeX) ───────────────────────────────────────────────────────────

/**
 * T208-4 part 4: Per-latex InlineTextContent registry.
 *
 * Compose's Placeholder API requires a fixed size at construction — there
 * is no way to resize a placeholder after the inline text has been laid
 * out. The previous design used a single shared placeholder sized to
 * `fontSize * 6 × fontSize * 1.4` (≈ 96 × 22.5 dp at 16 sp); KaTeX
 * routinely produces ~26 dp tall bitmaps (subscript descenders), and any
 * formula wider than 96 dp simply did not fit. ContentScale.Fit then
 * shrank every formula to ~85 % to make it fit the slot, producing the
 * "everything looks shrunken" output the user reported in T208-4.
 *
 * The fix: each unique latex string registers its OWN InlineTextContent
 * with its own placeholder, sized via `estimateInlineMathSize` based on
 * the latex's character count and structural triggers. Compose draws the
 * KaTeX bitmap at its natural dp size centered inside that slot — no
 * shrink, no clip. Extra padding inside an over-estimated slot is
 * harmless; under-estimating would re-introduce the shrink, so the
 * estimator is intentionally generous.
 *
 * The list of latex strings comes from `collectInlineMathLatex`, which
 * runs the same delimiter scanner as `parseInline` over the raw text.
 */
@Composable
private fun rememberKatexInlineContent(
    fontSize: TextUnit,
    latexList: List<String>,
): Map<String, androidx.compose.foundation.text.InlineTextContent> {
    if (latexList.isEmpty()) return emptyMap()
    return remember(fontSize, latexList) {
        val map = HashMap<String, androidx.compose.foundation.text.InlineTextContent>(latexList.size)
        for (latex in latexList.toSet()) {
            val (w, h) = estimateInlineMathSize(latex, fontSize)
            map[katexInlineTagFor(latex)] = androidx.compose.foundation.text.InlineTextContent(
                placeholder = androidx.compose.ui.text.Placeholder(
                    width = w,
                    height = h,
                    // [T-android-math-baseline] TextCenter, was AboveBaseline.
                    // The slot is over-estimated AND the KaTeX bitmap carries
                    // its own top/bottom whitespace, so an above-baseline slot
                    // put the formula's optical center well ABOVE the line's
                    // ("N(100) 渲染偏上"). Centering the slot on the line and
                    // the bitmap in the slot (CenterStart below) aligns the
                    // two optical centers instead — robust against both the
                    // generous estimate and the bitmap padding.
                    placeholderVerticalAlign = androidx.compose.ui.text.PlaceholderVerticalAlign.TextCenter,
                ),
            ) { _ ->
                // Compose passes the alternative-text to the children lambda;
                // we already keyed the slot per-latex so we use the closure's
                // `latex` directly to avoid any tag/text mismatch.
                RenderInlineMath(latex = latex, fontSize = fontSize)
            }
        }
        map
    }
}

@Composable
private fun RenderInlineMath(latex: String, fontSize: TextUnit) {
    val context = LocalContext.current
    val isDark = ChatColors.isDark
    // T208-5: pass the sp value as CSS px so the rendered glyph height
    // matches the surrounding body text. KaTeX's HTML sets
    // `el.style.fontSize = fontSize + 'px'` and the WebView viewport runs
    // at initial-scale=1.0, so 1 CSS px = 1 dp. Passing 16 here makes the
    // formula glyphs 16 dp tall — same as the 16-sp Compose body text.
    // Earlier code passed sp.toPx() (= sp × density = 42 on a density-2.625
    // device), which produced a bitmap ~2.6× too large; combined with the
    // CSS-vs-physical-px snapshot bug it accidentally landed near correct
    // size, but with the snapshot bug fixed the inflation showed through.
    //
    // [T-android-math-fontscale] ×fontScale: 16 sp of TEXT draws at
    // 16 × fontScale dp when the SYSTEM font size setting isn't 100%, and
    // the inline Placeholder (TextUnit sp) scales with it — but the CSS-px
    // bitmap did NOT. On a small-font device (fontScale < 1) the bitmap
    // came out LARGER than the shrunken slot, and Compose clips inline
    // content to the placeholder bounds — the field report's "N(10(" (a
    // clipped N(100)) and formulas visibly oversized next to their own
    // paragraph text. fontScale > 1 gave the inverse: formulas too small.
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val fontSizeCssPx = (fontSize.value * fontScale).toInt().coerceAtLeast(12)
    val palette = currentMdColors()
    val result by androidx.compose.runtime.produceState<KatexRenderResult?>(
        initialValue = null,
        key1 = latex,
        key2 = isDark,
        key3 = fontSizeCssPx,
    ) {
        value = KatexWebViewPool.render(
            context = context,
            latex = latex,
            displayMode = false,
            isDark = isDark,
            fontSizePx = fontSizeCssPx,
        )
    }
    val rendered = result
    if (rendered != null) {
        // T208-4 part 4: the slot was sized by `estimateInlineMathSize`
        // generously enough for this latex, so draw the bitmap at its
        // natural dp size (no scaling, no shrinking). ContentScale.Fit
        // is the defensive fallback if the estimator ever under-shoots.
        val density = androidx.compose.ui.platform.LocalDensity.current.density
        val naturalWidthDp = (rendered.bitmap.width / density).dp
        val naturalHeightDp = (rendered.bitmap.height / density).dp
        // [T-android-math-fontscale] Defensive de-clip: the slot was sized by
        // estimateInlineMathSize, but any residual estimator drift (or a
        // future slot/bitmap unit mismatch) used to CLIP the formula — inline
        // content never exceeds its placeholder bounds. Measure the slot and
        // scale the bitmap DOWN to fit when needed: a slightly shrunken
        // formula is readable, a clipped one ("N(10(") is not.
        //
        // [T-android-math-baseline] BOTTOM-align the bitmap. The placeholder
        // uses AboveBaseline (slot bottom sits ON the text baseline), but its
        // height is deliberately over-estimated — with the default TopStart
        // alignment the formula rode at the TOP of the too-tall slot,
        // floating visibly above its own line ("N(100) 渲染偏上"). Anchoring
        // to the slot's bottom puts the formula on the baseline regardless of
        // how generous the height estimate is.
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.CenterStart,
        ) {
            val fit = minOf(
                1f,
                if (naturalWidthDp > maxWidth) maxWidth / naturalWidthDp else 1f,
                if (naturalHeightDp > maxHeight) maxHeight / naturalHeightDp else 1f,
            )
            androidx.compose.foundation.Image(
                bitmap = rendered.bitmap.asImageBitmap(),
                contentDescription = "math: $latex",
                modifier = Modifier.size(naturalWidthDp * fit, naturalHeightDp * fit),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )
        }
    } else {
        // Fallback while loading or on error: show the raw latex so the
        // user is never staring at an empty rectangle.
        Text(
            text = latex,
            fontSize = fontSize * 0.9f,
            fontFamily = FontFamily.Monospace,
            color = palette.text,
        )
    }
}

/**
 * One list item's content: its inline text, or — when it holds wide math
 * ([withWideMathSplit]) — its prose paragraphs with each wide formula as a
 * display block in between.
 */
@Composable
private fun RenderListItemBody(item: ListItem, colors: MdColors, modifier: Modifier) =
    RenderListItemBody(item.text, item.children, colors, modifier, colors.text)

@Composable
private fun RenderListItemBody(
    text: String,
    children: List<MdBlock>,
    colors: MdColors,
    modifier: Modifier,
    textColor: androidx.compose.ui.graphics.Color,
) {
    if (children.isEmpty()) {
        MdText(
            text = MarkdownParseCaches.inline(text, colors),
            fontSize = BaseFontSize,
            lineHeight = BaseLineHeight,
            color = textColor,
            modifier = modifier,
            inlineContent = rememberKatexInlineContent(BaseFontSize, MarkdownParseCaches.mathLatex(text)),
        )
        return
    }
    Column(modifier = modifier) {
        for (child in children) {
            when (child) {
                is MdBlock.MathDisplay -> RenderMathDisplay(child.latex)
                else -> MdText(
                    text = MarkdownParseCaches.inline(child.raw, colors),
                    fontSize = BaseFontSize,
                    lineHeight = BaseLineHeight,
                    color = textColor,
                    inlineContent = rememberKatexInlineContent(BaseFontSize, MarkdownParseCaches.mathLatex(child.raw)),
                )
            }
        }
    }
}

/**
 * T155: Display-mode math rendered via the shared KaTeX WebView pool.
 * Shows the bitmap snapshot once KaTeX returns; falls back to monospace
 * raw LaTeX while loading or on render error so the user always sees
 * *something* meaningful even before / instead of the rendered formula.
 *
 * iOS parity: KaTeXRenderer.swift (single offscreen WKWebView, snapshot,
 * cached). The render call is suspending — Compose drives it via
 * `produceState` keyed by (latex, isDark, fontSize) so flipping themes
 * or scrolling back-and-forth never re-renders the same formula twice.
 */
@Composable
private fun RenderMathDisplay(latex: String) {
    val context = LocalContext.current
    val isDark = ChatColors.isDark
    // T208-5: render at sp.value (CSS px = dp) so glyph height matches the
    // surrounding 16-sp body text. See RenderInlineMath comment for the
    // full reasoning. [T-android-math-fontscale] ×fontScale so display math
    // tracks the SYSTEM font size setting the way the surrounding sp text
    // does (the inline path had the same gap — see RenderInlineMath).
    val displayFontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val fontSizeCssPx = (BaseFontSize.value * displayFontScale).toInt().coerceAtLeast(12)
    val palette = currentMdColors()

    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        val density = androidx.compose.ui.platform.LocalDensity.current.density
        val lineMaxWidth = maxWidth
        val maxWidthPx = (lineMaxWidth.value * density).toInt()
        // [T-android-math-overflow-wrap] Rows that fit the line (iOS
        // SwiftMathRenderer.renderFitting, 7238a39d4). A formula wider than
        // the line used to be scaled down to fit as one row, which for a long
        // chain of equalities meant unreadably small glyphs. Now it breaks
        // before top-level relations (=, \approx, <, \le, ...), packing as
        // many pieces per row as fit; a row that still does not fit (one huge
        // term) is scaled down below. A formula that fits is one row, the
        // same single render as before.
        val rows by androidx.compose.runtime.produceState<List<KatexRenderResult>?>(
            initialValue = null,
            latex, isDark, fontSizeCssPx, maxWidthPx,
        ) {
            suspend fun render(tex: String) = KatexWebViewPool.render(
                context = context,
                latex = tex,
                displayMode = true,
                isDark = isDark,
                fontSizePx = fontSizeCssPx,
            )
            val full = render(latex)
            if (full == null || full.bitmap.width <= maxWidthPx || maxWidthPx <= 0) {
                value = full?.let { listOf(it) } ?: emptyList()
                return@produceState
            }
            val pieces = MathLineBreaker.splitAtTopLevelRelations(latex)
            if (pieces.size <= 1) {
                value = listOf(full)
                return@produceState
            }
            val rowTex = MathLineBreaker.packRows(pieces) { candidate ->
                (render(candidate)?.bitmap?.width ?: Int.MAX_VALUE) <= maxWidthPx
            }
            val rendered = rowTex.map { render(it) }
            // Any row failing to render: show the whole formula (scaled) rather
            // than a formula with a hole in it.
            value = if (rendered.all { it != null }) rendered.filterNotNull() else listOf(full)
        }

        val rendered = rows
        if (!rendered.isNullOrEmpty()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                    (fontSizeCssPx * 0.25f).dp,
                ),
            ) {
                for ((rowIdx, row) in rendered.withIndex()) {
                    val naturalWidthDp = (row.bitmap.width / density).dp
                    val naturalHeightDp = (row.bitmap.height / density).dp
                    val scale = if (naturalWidthDp > lineMaxWidth) lineMaxWidth / naturalWidthDp else 1f
                    androidx.compose.foundation.Image(
                        bitmap = row.bitmap.asImageBitmap(),
                        contentDescription = if (rowIdx == 0) "math: $latex" else null,
                        modifier = Modifier.size(naturalWidthDp * scale, naturalHeightDp * scale),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    )
                }
            }
        } else {
            // Fallback: raw latex in monospace inside a faint surface.
            Text(
                text = latex,
                fontSize = BaseFontSize * 0.95f,
                fontFamily = FontFamily.Monospace,
                color = palette.text,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

// ─── Broken image placeholder ───────────────────────────────────────────────

/**
 * T148: Visible fallback for `SubcomposeAsyncImage` error state inside
 * markdown — rendered when Coil can't load the source (file deleted,
 * 404, IO error). Without this the slot paints nothing and the user
 * can't tell whether the image is missing or whether the renderer is
 * broken. The outer `imageBaseModifier` already supplies the T146
 * border/shadow/rounded-corner frame; here we just fill the inside
 * with a subtle tool-bg, a broken-image glyph, and the alt text.
 */
@Composable
private fun BrokenImagePlaceholder(alt: String?) {
    val palette = com.openminis.app.ui.theme.LocalChatPalette.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .background(palette.toolBg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.BrokenImage,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = palette.secondaryText,
            )
            Text(
                text = alt?.takeIf { it.isNotBlank() } ?: "Image not available",
                fontSize = 12.sp,
                color = palette.secondaryText,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ─── Media helpers ──────────────────────────────────────────────────────────

/**
 * Resolve a markdown media URL (`minis://attachments/foo.mp4`, file://, or
 * plain absolute path) to a host File.
 *
 * First tries `PRootKernel.resolveHostPath` (same as MinisImageFetcher). If
 * that fails — e.g. bind mounts are pointing at a different session, or the
 * file was written under a `__new__...` draft id that predates
 * `ensureSession()` rename — we fall back to scanning all per-session
 * attachment directories for a file of the same basename. Mirrors the iOS
 * attach-path resolution which walks the session cache when the primary
 * lookup misses.
 */
internal fun resolveMdMediaFile(context: Context, url: String, sessionId: String? = null): File? {
    if (url.isBlank()) return null
    // Strip a real query (`?`), but NOT `#` — attachment filenames legitimately
    // contain '#' (hashtags). `minis://` URLs don't carry fragments anyway,
    // and truncating here would hide the '.mp4' extension and the file's real
    // name from the resolver.
    val stripped = url.substringBefore('?')
    val primary: File? = when {
        stripped.startsWith("minis://") -> {
            val decoded = java.net.URLDecoder.decode(stripped.removePrefix("minis://"), "UTF-8")
            val linuxPath = "/var/minis/$decoded"
            // Prefer the session-scoped resolver when the caller supplied a
            // sessionId: the global `bindMounts` map is overwritten every time
            // another session boots its shell, so without sessionId we'd route
            // this chat's attachment lookup to whichever session happened to
            // boot last.
            if (sessionId != null) PRootKernel.resolveSessionHostPath(sessionId, linuxPath, context)
            else PRootKernel.resolveHostPath(linuxPath)
        }
        stripped.startsWith("file://") -> File(Uri.parse(stripped).path ?: return null)
        stripped.startsWith("/") -> File(stripped)
        else -> null
    }
    if (primary?.let { it.exists() && it.isFile } == true) {
        android.util.Log.d("MdStream", "resolveMdMediaFile url=$url sid=$sessionId -> primary=${primary.absolutePath}")
        return primary
    }

    // Fallback: search every minis-sessions/<id>/{attachments,workspace,offloads,browser}
    // subtree for a file whose basename matches. Handles leftover files from a
    // draft session whose bind mount has already switched over, and the case
    // where `resolveHostPath`'s global bindMounts map points at a different
    // session than the one owning this message.
    if (!stripped.startsWith("minis://")) {
        android.util.Log.d("MdStream", "resolveMdMediaFile primary miss url=$url (non-minis scheme, no fallback)")
        return null
    }
    val decoded = java.net.URLDecoder.decode(stripped.removePrefix("minis://"), "UTF-8")
    val basename = decoded.substringAfterLast('/')
    val subdir = decoded.substringBefore('/', missingDelimiterValue = "").takeIf { it.isNotEmpty() } ?: "attachments"
    val root = File(context.filesDir, "minis-sessions")
    if (root.isDirectory) {
        root.listFiles()?.forEach { sessionDir ->
            val candidate = File(sessionDir, "$subdir/$basename")
            if (candidate.exists() && candidate.isFile) {
                android.util.Log.d("MdStream", "resolveMdMediaFile url=$url -> fallback=${candidate.absolutePath}")
                return candidate
            }
        }
    }
    // Also probe `minis-global/<subdir>` for shared/memory/skills buckets.
    val globalCandidate = File(context.filesDir, "minis-global/$subdir/$basename")
    if (globalCandidate.exists() && globalCandidate.isFile) {
        android.util.Log.d("MdStream", "resolveMdMediaFile url=$url -> global=${globalCandidate.absolutePath}")
        return globalCandidate
    }
    android.util.Log.w("MdStream", "resolveMdMediaFile url=$url -> NOT FOUND (primary=${primary?.absolutePath})")
    return null
}

private fun filenameFromMdUrl(url: String): String {
    // Keep '#' — it's a legitimate character in attachment filenames.
    val stripped = url.substringBefore('?')
    val last = stripped.substringAfterLast('/')
    return try { java.net.URLDecoder.decode(last, "UTF-8") } catch (_: Throwable) { last }
}

private fun openMdMediaExternally(context: Context, file: File, mime: String) {
    android.util.Log.d("MdStream", "openMdMediaExternally file=${file.absolutePath} mime=$mime")
    val authority = context.packageName + ".fileprovider"
    val uri = try {
        androidx.core.content.FileProvider.getUriForFile(context, authority, file)
    } catch (t: Throwable) {
        android.util.Log.w("MdStream", "FileProvider failed: ${t.message}")
        Uri.fromFile(file)
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val chooser = Intent.createChooser(intent, file.name).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try { context.startActivity(chooser) } catch (t: Throwable) {
        android.util.Log.w("MdStream", "startActivity failed: ${t.message}")
    }
}

private fun formatMdMediaMs(ms: Int): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

@Composable
private fun RenderMdVideo(block: MdBlock.Video) {
    val context = LocalContext.current
    val colors = currentMdColors()
    val sessionId = LocalMarkdownSessionId.current
    val file = remember(block.url, sessionId) { resolveMdMediaFile(context, block.url, sessionId) }
    val filename = remember(block.url) { filenameFromMdUrl(block.url) }
    var showPlayer by remember { mutableStateOf(false) }

    val thumbnail by produceState<Bitmap?>(initialValue = null, key1 = file?.absolutePath) {
        val f = file ?: run { value = null; return@produceState }
        value = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(f.absolutePath)
                val bmp = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                android.util.Log.d("MdStream", "video thumbnail ${f.name} -> ${bmp?.width}x${bmp?.height}")
                bmp
            } catch (t: Throwable) {
                android.util.Log.w("MdStream", "video thumbnail failed: ${t.message}")
                null
            } finally {
                try { retriever.release() } catch (_: Throwable) {}
            }
        }
    }

    if (showPlayer && file != null) {
        com.openminis.app.ui.media.MinisFullscreenVideoPlayer(
            file = file,
            onDismiss = { showPlayer = false },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.inlineCodeBg)
            .border(0.5.dp, colors.tableBorder, RoundedCornerShape(8.dp))
            .clickable(enabled = file != null) {
                android.util.Log.d("MdStream", "open fullscreen video for ${file?.absolutePath}")
                showPlayer = true
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 180.dp, max = 280.dp),
            contentAlignment = Alignment.Center,
        ) {
            val thumb = thumbnail
            if (thumb != null) {
                Image(
                    bitmap = thumb.asImageBitmap(),
                    contentDescription = block.alt.ifEmpty { filename },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Icon(
                imageVector = Icons.Filled.PlayCircleFilled,
                contentDescription = "Play video",
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(56.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Videocam,
                contentDescription = null,
                tint = colors.blockquote,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            MdText(
                text = AnnotatedString(filename),
                fontSize = 12.sp,
                color = colors.blockquote,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun RenderMdAudio(block: MdBlock.Audio) {
    val context = LocalContext.current
    val colors = currentMdColors()
    val sessionId = LocalMarkdownSessionId.current
    val file = remember(block.url, sessionId) { resolveMdMediaFile(context, block.url, sessionId) }
    val filename = remember(block.url) { filenameFromMdUrl(block.url) }

    val player = remember(file?.absolutePath) {
        if (file == null) null else try {
            MediaPlayer().apply { setDataSource(file.absolutePath); prepare() }
        } catch (t: Throwable) {
            android.util.Log.w("MdStream", "audio prepare failed: ${t.message}")
            null
        }
    }
    DisposableEffect(player) {
        onDispose { try { player?.release() } catch (_: Throwable) {} }
    }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0) }
    val durationMs = player?.duration ?: 0

    LaunchedEffect(isPlaying) {
        while (isPlaying && player != null) {
            positionMs = try { player.currentPosition } catch (_: Throwable) { 0 }
            if (!player.isPlaying) { isPlaying = false; break }
            delay(200)
        }
    }
    DisposableEffect(player) {
        player?.setOnCompletionListener {
            isPlaying = false
            positionMs = 0
            try { player.seekTo(0) } catch (_: Throwable) {}
        }
        onDispose { try { player?.setOnCompletionListener(null) } catch (_: Throwable) {} }
    }

    val tint = colors.link
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.inlineCodeBg)
            .border(0.5.dp, colors.tableBorder, RoundedCornerShape(10.dp))
            .clickable(enabled = file != null) {
                if (player == null) {
                    file?.let { openMdMediaExternally(context, it, "audio/*") }
                } else {
                    if (isPlaying) { try { player.pause() } catch (_: Throwable) {} ; isPlaying = false }
                    else { try { player.start(); isPlaying = true } catch (_: Throwable) {} }
                }
            }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Audiotrack,
            contentDescription = null,
            tint = colors.blockquote,
            modifier = Modifier.size(18.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
        ) {
            MdText(
                text = AnnotatedString(block.alt.ifEmpty { filename }),
                fontSize = 13.sp,
                color = colors.text,
                maxLines = 1,
            )
            val progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .height(3.dp),
                color = tint,
                trackColor = tint.copy(alpha = 0.2f),
            )
            if (durationMs > 0) {
                MdText(
                    text = AnnotatedString("${formatMdMediaMs(positionMs)} / ${formatMdMediaMs(durationMs)}"),
                    fontSize = 11.sp,
                    color = colors.blockquote,
                )
            }
        }
        Icon(
            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Play",
            tint = tint,
            modifier = Modifier.size(28.dp),
        )
    }
}

/**
 * [T-android-table-hscroll-preserve] Process-level cache of table horizontal
 * ScrollStates, keyed by a STABLE table identity (message/shard/headers).
 *
 * Why not a plain `rememberScrollState()`: remember is positional. A streaming
 * publish re-parses the fragment, and when the fragment freezes the render
 * moves from the live branch to the frozen-cache branch — a different position
 * in the composition tree — so the anonymous ScrollState was recreated and the
 * user's horizontal offset snapped back to 0 on wide tables (the Android
 * sibling of iOS T-ios-table-hscroll-offset-lost, fixed by a persistent
 * per-attachment offset there). Handing out the SAME ScrollState instance for
 * the same table identity keeps the offset across recomposition, branch moves,
 * and re-parses.
 *
 * Key uses the header row (stable from the moment a table starts streaming —
 * rows append below it) rather than full content, which would change on every
 * appended row. Two tables with an identical header row in the SAME shard
 * would share an offset — acceptable: they'd have identical column layouts.
 * LRU-bounded so long sessions can't accumulate states without limit.
 */
internal object TableHScrollStates {
    private const val MAX_ENTRIES = 64

    // Access-ordered LinkedHashMap LRU (pure Kotlin — android.util.LruCache is
    // a throwing stub in JVM unit tests, and this object is unit-tested).
    private val states = object : LinkedHashMap<String, ScrollState>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ScrollState>): Boolean =
            size > MAX_ENTRIES
    }

    fun stateFor(key: String): ScrollState =
        synchronized(states) {
            states.getOrPut(key) { ScrollState(0) }
        }
}

@Composable
private fun RenderTable(block: MdBlock.Table) {
    val colors = currentMdColors()
    val colCount = maxOf(block.headers.size, block.rows.maxOfOrNull { it.size } ?: 0)
    if (colCount == 0) return

    // Build the list of rows — header first if present
    val allRows: List<List<String>> = buildList {
        if (block.headers.isNotEmpty()) add(block.headers)
        addAll(block.rows)
    }

    // [T-android-markdown-table-copy-actions] Copy Table (markdown text) +
    // Copy Table Image (rendered bitmap), aligning with iOS
    // SelectableMarkdownView.copyTable / copyTableImage. These are NOT a
    // separate popup: the table publishes them to the SelectionController so
    // the ONE selection toolbar appends them after Copy / Copy Markdown / etc.
    // A long-press on a table cell already starts a text selection (cells are
    // MdText shards), which is what surfaces that toolbar.
    val context = LocalContext.current
    val tableScope = rememberCoroutineScope()
    val tableGraphicsLayer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    val tableCopiedToast = stringResource(R.string.markdown_table_copied_toast)
    val tableImageCopiedToast = stringResource(R.string.markdown_table_image_copied_toast)
    val tableImageCopyFailedToast = stringResource(R.string.markdown_table_image_copy_failed_toast)

    val selectionController = LocalMinisSelectionController.current
    val shardIdForTable = LocalShardId.current
    val messageId = shardIdForTable?.messageId

    // [T-android-table-hscroll-preserve] Stable identity-keyed ScrollState so
    // streaming re-parses / the live→frozen branch move don't reset the user's
    // horizontal offset (see TableHScrollStates). Falls back to an anonymous
    // state when no shard id is available (non-chat contexts).
    val tableHScroll = if (shardIdForTable != null) {
        val hScrollKey = "${shardIdForTable.messageId}/${shardIdForTable.shardId}" +
            "/tbl:${block.headers.joinToString("|")}"
        remember(hScrollKey) { TableHScrollStates.stateFor(hScrollKey) }
    } else {
        rememberScrollState()
    }
    if (selectionController != null && messageId != null) {
        // Re-register whenever the inputs that the actions close over change.
        androidx.compose.runtime.DisposableEffect(selectionController, messageId, block.raw) {
            val actions = SelectionController.TableActions(
                copyTableMarkdown = {
                    val md = block.raw
                    if (md.isNotEmpty()) {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("table", md))
                        Toast.makeText(context, tableCopiedToast, Toast.LENGTH_SHORT).show()
                    }
                },
                copyTableImage = {
                    tableScope.launch {
                        try {
                            val imageBitmap = tableGraphicsLayer.toImageBitmap()
                            val androidBitmap = imageBitmap.asAndroidBitmap()
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                val shareDir = java.io.File(context.cacheDir, "share").apply { mkdirs() }
                                val outFile = java.io.File(shareDir, "table_${System.currentTimeMillis()}.png")
                                outFile.outputStream().use {
                                    androidBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                                }
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    outFile,
                                )
                                context.grantUriPermission(
                                    "*", uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                )
                                val clip = android.content.ClipData.newUri(
                                    context.contentResolver, "table-image", uri,
                                )
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                        as android.content.ClipboardManager
                                    cm.setPrimaryClip(clip)
                                    Toast.makeText(context, tableImageCopiedToast, Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, tableImageCopyFailedToast, Toast.LENGTH_SHORT).show()
                        }
                    }
                    Unit
                },
            )
            selectionController.rememberTableActions(messageId, actions)
            onDispose { selectionController.forgetTableActions(messageId) }
        }
    }

    // BoxWithConstraints (OUTSIDE horizontalScroll) reads the real viewport width —
    // inside a horizontalScroll container, maxWidth would be Constraints.Infinity.
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        val viewportWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) { maxWidth.toPx() }.toInt()
        // Inner box owns the rounded border/clip and horizontal scroll. Border + clip
        // are applied before horizontalScroll so the frame stays anchored to the visible
        // viewport when the table is wider than the screen.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, colors.tableBorder, RoundedCornerShape(6.dp))
                // Record this draw pass into the GraphicsLayer so Copy Table
                // Image can materialise the styled table (cells, borders, header
                // shading, inline code) via toImageBitmap() — drawLayer also
                // renders the recorded content as the visible output. Wraps the
                // bordered frame so the captured bitmap includes the border. For
                // a table wider than the viewport this captures the visible
                // (scrolled) frame, matching what the user sees on screen.
                .drawWithContent {
                    tableGraphicsLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(tableGraphicsLayer)
                }
                .horizontalScroll(tableHScroll),
        ) {
        val lineColor = colors.tableBorder
        val lineStrokePx = with(androidx.compose.ui.platform.LocalDensity.current) { 1.dp.toPx() }
        val totalRowCount = allRows.size
        // [T-android-table-cell-font-body-size] Cells use the body size, scaled
        // by the in-app font setting like every other block. A fixed 14.sp made
        // table text 87.5% of the reply around it and left it behind whenever
        // the user enlarged chat text; iOS already sets cells in
        // theme.baseFontSize (SelectableMarkdownView computeLayout).
        val cellFontSize = BaseFontSize
        // [T-android-table-column-width-plan] Widest word each column should
        // not split (iOS TableAttachment: longestUnbreakableRun measured with
        // the cell font, + padding + 4). Measured here because the Layout's
        // measure block only has intrinsics, which give the whole line.
        val tableDensity = androidx.compose.ui.platform.LocalDensity.current
        val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
        val unbreakableDp = remember(allRows, colCount, cellFontSize, tableDensity) {
            FloatArray(colCount) { colIdx ->
                var widest = 0f
                for ((rowIndex, cells) in allRows.withIndex()) {
                    val plain = MarkdownParseCaches.inline(cells.getOrElse(colIdx) { "" }, colors).text
                    val word = TableColumnPlanner.longestUnbreakableRun(plain) ?: continue
                    val bold = rowIndex == 0 && block.headers.isNotEmpty()
                    val px = textMeasurer.measure(
                        word,
                        TextStyle(fontSize = cellFontSize, fontWeight = if (bold) FontWeight.SemiBold else null),
                        softWrap = false,
                        maxLines = 1,
                    ).size.width
                    widest = maxOf(widest, with(tableDensity) { px.toDp().value } + TABLE_CELL_PAD_H_DP * 2 + 4f)
                }
                widest
            }
        }
        // Widest inline formula per column, as the slot rememberKatexInlineContent
        // reserves for it (estimateInlineMathSize), + padding. A formula cannot
        // wrap, so a column capped narrower than its slot drew the formula over
        // the next column; the planner keeps the column at least this wide.
        val mathMinDp = remember(allRows, colCount, cellFontSize, tableDensity) {
            FloatArray(colCount) { colIdx ->
                var widest = 0f
                for (cells in allRows) {
                    for (latex in MarkdownParseCaches.mathLatex(cells.getOrElse(colIdx) { "" })) {
                        val slot = with(tableDensity) { estimateInlineMathSize(latex, cellFontSize).first.toDp().value }
                        widest = maxOf(widest, slot + TABLE_CELL_PAD_H_DP * 2)
                    }
                }
                widest
            }
        }
        // [T-android-table-column-width-plan] Streaming stability: at a given
        // viewport width, column widths only grow while rows stream in (iOS
        // applyingStreamingFloor), so a new row never makes the columns shrink
        // and the rows above re-wrap. Keyed to the table's shape; a finished
        // table never changes content, so the floor is a no-op there.
        val widthFloor = remember(block.headers, colCount) { TableWidthFloor() }
        androidx.compose.ui.layout.Layout(
            content = {
                for ((rowIndex, cells) in allRows.withIndex()) {
                    val isHeader = rowIndex == 0 && block.headers.isNotEmpty()
                    val isLastRow = rowIndex == totalRowCount - 1
                    for (colIndex in 0 until colCount) {
                        val isLastCol = colIndex == colCount - 1
                        val columnAlign = block.alignments.getOrElse(colIndex) { MdTableAlign.START }
                        Box(
                            modifier = Modifier
                                .then(if (isHeader) Modifier.background(colors.tableHeaderBg) else Modifier)
                                .drawBehind {
                                    // Right divider between columns
                                    if (!isLastCol) {
                                        drawLine(
                                            color = lineColor,
                                            start = androidx.compose.ui.geometry.Offset(size.width - lineStrokePx / 2, 0f),
                                            end = androidx.compose.ui.geometry.Offset(size.width - lineStrokePx / 2, size.height),
                                            strokeWidth = lineStrokePx,
                                        )
                                    }
                                    // Bottom divider between rows
                                    if (!isLastRow) {
                                        drawLine(
                                            color = lineColor,
                                            start = androidx.compose.ui.geometry.Offset(0f, size.height - lineStrokePx / 2),
                                            end = androidx.compose.ui.geometry.Offset(size.width, size.height - lineStrokePx / 2),
                                            strokeWidth = lineStrokePx,
                                        )
                                    }
                                }
                                .padding(horizontal = TABLE_CELL_PAD_H_DP.dp, vertical = 8.dp),
                            // [T-android-table-column-width-plan] Top-aligned,
                            // like iOS: with wrapping columns rows get tall, and
                            // a centred one-liner next to a five-line cell reads
                            // as belonging to neither line.
                            // [T-android-table-column-align] Horizontally, the
                            // column's GFM alignment (default start), like iOS.
                            contentAlignment = when (columnAlign) {
                                MdTableAlign.CENTER -> Alignment.TopCenter
                                MdTableAlign.END -> Alignment.TopEnd
                                MdTableAlign.START -> Alignment.TopStart
                            },
                        ) {
                            val cellText = cells.getOrElse(colIndex) { "" }
                            MdText(
                                text = MarkdownParseCaches.inline(cellText, colors),
                                fontSize = cellFontSize,
                                fontWeight = if (isHeader) FontWeight.SemiBold else null,
                                color = colors.text,
                                inlineContent = rememberKatexInlineContent(cellFontSize, MarkdownParseCaches.mathLatex(cellText)),
                                // Long-press selects the whole cell rather than a
                                // sentence-fragment of it: a cell holding "1,200"
                                // or "v1.2, beta" would otherwise stop at the
                                // comma, which is never what someone pressing a
                                // table cell is after.
                                isAtomicSelectionUnit = true,
                                // A wrapped cell fills the column, so the Box
                                // alignment alone would leave its lines at start.
                                textAlign = when (columnAlign) {
                                    MdTableAlign.CENTER -> TextAlign.Center
                                    MdTableAlign.END -> TextAlign.End
                                    MdTableAlign.START -> TextAlign.Start
                                },
                            )
                        }
                    }
                }
            },
        ) { measurables, _ ->
            val rowCount = allRows.size

            // Safe upper bound for intrinsic queries and constraint widths. Compose's
            // Constraints packs width into 18 bits, so values above ~262k will throw.
            // Bound for intrinsic queries only: 5× viewport (~5-7k px) stays far
            // below the 262k Constraints limit while still bounding pathological
            // intrinsics from unbroken strings. Column widths themselves come
            // from TableColumnPlanner below (at most 0.7× viewport per column
            // when the table scrolls), no longer from this cap.
            val maxCellWidth = (viewportWidthPx * 5).coerceAtLeast(1)

            // Pass 1: use intrinsic widths (no measure() call) to compute per-column max width.
            // Compose forbids calling measure() twice on the same Measurable in one layout pass.
            // Pass height=0 (standard "no height constraint" sentinel for intrinsic queries).
            val naturalPx = IntArray(colCount)
            for (rowIdx in 0 until rowCount) {
                for (colIdx in 0 until colCount) {
                    val idx = rowIdx * colCount + colIdx
                    if (idx < measurables.size) {
                        val intrinsic = measurables[idx].maxIntrinsicWidth(0)
                            .coerceIn(0, maxCellWidth)
                        naturalPx[colIdx] = maxOf(naturalPx[colIdx], intrinsic)
                    }
                }
            }

            // [T-android-table-width-by-content] Let TableColumnPlanner decide
            // (iOS b3ec29cee): widths follow each column's text, a wordier
            // column is never narrower, a table that fits fills the width in
            // proportion to content, and a table that cannot fit scrolls with a
            // peek of the next column. Planned in dp so the iOS point constants
            // apply as-is.
            val dpPerPx = 1f / density
            val viewportDp = viewportWidthPx * dpPerPx
            var plan = TableColumnPlanner.plan(
                // +12: the same single-line margin iOS adds to its measured
                // natural width, so the mode thresholds (fits / wraps /
                // scrolls) fall at the same content widths on both platforms.
                List(colCount) {
                    TableColumnPlanner.Column(naturalPx[it] * dpPerPx + 12f, unbreakableDp[it], mathMinDp[it])
                },
                viewportDp,
            )
            plan = TableColumnPlanner.applyingStreamingFloor(
                plan,
                widthFloor.widths.takeIf { widthFloor.viewportPx == viewportWidthPx },
                viewportDp,
            )
            widthFloor.viewportPx = viewportWidthPx
            widthFloor.widths = plan.widths
            val colWidths = IntArray(colCount) { kotlin.math.round(plan.widths[it] * density).toInt() }
            // Rounding each column can leave the sum a pixel short of the
            // viewport; the last column absorbs it, as the stretch always did.
            val roundedWidth = colWidths.sum()
            if (roundedWidth < viewportWidthPx && colCount > 0) {
                colWidths[colCount - 1] += viewportWidthPx - roundedWidth
            }

            // Pass 2: each row's height, from intrinsics (no measure() yet).
            //
            // [T-android-table-row-uniform-height] Every cell paints its own
            // right and bottom divider from its OWN size, so all cells of a row
            // must share the row's height or the grid breaks: a shorter cell
            // was centred in the row and its lines floated inside it — row
            // borders stepped between columns and each vertical divider broke
            // at every row. Cells used to come out equal because every single-
            // line cell was exactly lineHeight tall; since MdText trims the
            // outer half-leading (T-android-md-lineheight-trim) a single line
            // is as tall as its font, and a cell with CJK text (fallback font)
            // is taller than a Latin-only one ("23–32°C") in the same row.
            val colW = IntArray(colCount) { colWidths[it].coerceIn(0, maxCellWidth) }
            val rowHeights = IntArray(rowCount) { rowIdx ->
                var h = 0
                for (colIdx in 0 until colCount) {
                    h = maxOf(h, measurables[rowIdx * colCount + colIdx].maxIntrinsicHeight(colW[colIdx]))
                }
                h
            }

            // Pass 3: measure each cell exactly once — its column's width, and at
            // least its row's height (contentAlignment keeps the text at the top).
            // The max() keeps the row honest if a measured height ever exceeds
            // the intrinsic estimate.
            val placeables = Array(rowCount) { rowIdx ->
                Array(colCount) { colIdx ->
                    val p = measurables[rowIdx * colCount + colIdx].measure(
                        androidx.compose.ui.unit.Constraints(
                            minWidth = colW[colIdx],
                            maxWidth = colW[colIdx],
                            minHeight = rowHeights[rowIdx],
                        )
                    )
                    rowHeights[rowIdx] = maxOf(rowHeights[rowIdx], p.height)
                    p
                }
            }

            // Cells are laid out edge-to-edge; dividers are painted inside each cell
            // via drawBehind, so no extra spacing between cells is needed here.
            val totalWidth = colWidths.sum()
            val totalHeight = rowHeights.sum()

            layout(totalWidth, totalHeight) {
                var y = 0
                for (rowIdx in 0 until rowCount) {
                    var x = 0
                    for (colIdx in 0 until colCount) {
                        val p = placeables[rowIdx][colIdx]
                        p.place(x, y)
                        x += colWidths[colIdx]
                    }
                    y += rowHeights[rowIdx]
                }
            }
        }
        }
    }
}

/**
 * Horizontal padding of a table cell, per side. [T-android-table-cell-padding]
 * 8, matching iOS 26711373d (cellPaddingH 16 -> 8 pt); was 10.
 */
private const val TABLE_CELL_PAD_H_DP = 8f

/**
 * [T-android-table-column-width-plan] Last planned column widths (dp) of one
 * rendered table and the viewport they were planned for. Plain fields, not
 * snapshot state: written from the measure pass, read by the next one, and
 * never meant to trigger a recomposition.
 */
private class TableWidthFloor {
    var viewportPx: Int = -1
    var widths: FloatArray? = null
}

// ─── [T-android-inline-parse-offmain] Parse caches ──────────────────────────
//
// RenderBlock used to call parseInline / collectInlineMathLatex DIRECTLY in
// composition — on the main thread, un-remembered, so every recomposition of a
// visible block re-ran the inline scan, and the LIVE block re-ran it on every
// streaming publish. That is the main-thread regex/ICU load in the
// minis-2026-06-10 ANR stack. All call sites now go through these process-wide
// LRUs; the streaming parse paths PREWARM them on Dispatchers.Default before
// publishing blocks, so the subsequent main-thread composition is a pure cache
// hit. Inputs are pure functions of (text [, colors]) and the outputs
// (AnnotatedString / List<String> / List<MdBlock>) are immutable, so
// cross-thread sharing is safe. MdColors is a data class → structural key;
// theme switches simply mint new entries and old ones age out.
/**
 * [T-android-stream-render-profile] Always-on, low-overhead aggregate profiler
 * for the live streaming markdown render. Accumulates the per-tick off-main
 * parse time (block split + prewarm/incremental inline+math) of the live tail
 * and emits ONE summary line every [FLUSH_TICKS] ticks (not per tick — keeps
 * log volume + the logging cost itself negligible). Gives a directly-comparable
 * "how heavy is streaming render per tick" number for benchmarking (e.g.
 * incremental on vs off) and a standing signal in real-device use. Verified on
 * a Pixel 4a with DeepSeek V4 Flash at a 30432-char single reply: parse avg
 * 3-8ms / max ~15ms even as the live fragment grew, native heap flat 55-88MB
 * (vs the pre-fix 42↔207MB GC storm), 0 hangs.
 */
internal object StreamRenderProfiler {
    private const val FLUSH_TICKS = 20
    private var ticks = 0
    private var parseMsSum = 0.0
    private var parseMsMax = 0.0
    private var lastFragLen = 0
    private var maxFragLen = 0

    /** One off-main live-tick: block split + prewarm/incremental inline+math. */
    @Synchronized
    fun recordParse(fragLen: Int, ms: Double) {
        parseMsSum += ms
        if (ms > parseMsMax) parseMsMax = ms
        lastFragLen = fragLen
        if (fragLen > maxFragLen) maxFragLen = fragLen
        ticks++
        if (ticks < FLUSH_TICKS) return
        val n = ticks
        com.openminis.app.logging.AppLogger.info(
            "StreamRender",
            "[StreamRender] ticks=$n fragLen=$lastFragLen(max=$maxFragLen) " +
                "parseMs avg=${"%.1f".format(parseMsSum / n)} max=${"%.1f".format(parseMsMax)}",
        )
        ticks = 0; parseMsSum = 0.0; parseMsMax = 0.0; maxFragLen = 0
    }
}

private object MarkdownParseCaches {
    // [T-android-parse-lru-char-budget] (#759) Per-cache character budget,
    // replacing the previous fixed entry count of 768.
    //
    // The old cap-by-count rule blew up on Larky-class sessions: a 33KB
    // assistant message produces hundreds of cached entries (one per
    // paragraph / table / list item / math snippet), and 768 entries × an
    // average per-entry text length of 100KB+ of inline `AnnotatedString`
    // backing arrays cleared tens of megabytes of resident heap before
    // eviction kicked in. Cap on source characters instead so the cache
    // bytes scale with the source bytes, not with the entry count.
    //
    // Why 2 MB (= 2,000,000 chars) per cache:
    //   - For Larky's worst case (1.9 MB session, single message up to
    //     ~33 KB): the budget holds ~60 distinct 33 KB messages or
    //     ~1500 distinct 1 KB chat blocks — plenty for the tail-window
    //     scroll range (200 messages × ~50 paragraphs avg).
    //   - For typical sessions (1–2 KB messages): ~1000+ entries, ≥ the
    //     previous count-based cap; hit-rate effectively identical to
    //     the 768-entry cap.
    //   - Across the three caches that's 6 MB worst-case (chars only;
    //     AnnotatedString backing arrays are larger but scale with the
    //     same source). On a Pixel-class device with ~96 MB heap budget,
    //     6 MB is acceptable; was previously unbounded by chars on the
    //     33 KB tail of the distribution.
    private const val CHAR_BUDGET_PER_CACHE = 2_000_000

    /**
     * Access-order LinkedHashMap with eviction driven by a running
     * character total rather than entry count. [sizer] returns the number
     * of source characters each (key, value) pair contributes (we count
     * source-side bytes — the canonical input — rather than
     * AnnotatedString output, because output sizes are not easily
     * computable and source length is the dominant correlate anyway).
     *
     * `get` continues to refresh recency via LinkedHashMap's accessOrder;
     * `put` is overridden to maintain [totalChars] and trim trailing
     * eldest entries until the running total fits in [budget], while
     * always keeping at least one entry — see the put loop guard. This
     * preserves the "single oversized message lands in the cache without
     * an infinite eviction loop" invariant required by the spec.
     */
    private class Lru<K, V>(
        private val budget: Int,
        private val sizer: (K, V) -> Int,
    ) : LinkedHashMap<K, V>(64, 0.75f, true) {
        var totalChars: Int = 0
            private set

        override fun put(key: K, value: V): V? {
            val prior = super.put(key, value)
            // The map call above may have replaced an existing entry —
            // adjust the running total by the difference, not the new
            // value alone. Also defends against accidental double-count
            // when the same key is re-put with a different value.
            if (prior != null) totalChars -= sizer(key, prior).coerceAtLeast(0)
            totalChars += sizer(key, value).coerceAtLeast(0)
            // Trim eldest entries until under budget. Always keep at
            // least one entry alive so a single message larger than
            // budget still gets cached (its first parse is the
            // expensive one; we eat the over-budget transient until
            // any other put displaces it).
            while (totalChars > budget && size > 1) {
                val eldest = entries.iterator().next()
                totalChars -= sizer(eldest.key, eldest.value).coerceAtLeast(0)
                entries.remove(eldest)
            }
            return prior
        }

        override fun remove(key: K): V? {
            val v = super.remove(key) ?: return null
            totalChars -= sizer(key, v).coerceAtLeast(0)
            return v
        }

        override fun clear() {
            super.clear()
            totalChars = 0
        }

        // Suppress the count-based eviction path inherited from
        // LinkedHashMap; our own put() does the work and removeEldestEntry
        // would race with our totalChars bookkeeping if both fired.
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = false
    }

    private val inlineLru = Lru<Pair<String, MdColors>, AnnotatedString>(
        budget = CHAR_BUDGET_PER_CACHE,
        sizer = { k, _ -> k.first.length },
    )
    private val mathLru = Lru<String, List<String>>(
        budget = CHAR_BUDGET_PER_CACHE,
        sizer = { k, _ -> k.length },
    )
    private val blocksLru = Lru<String, List<MdBlock>>(
        budget = CHAR_BUDGET_PER_CACHE,
        sizer = { k, _ -> k.length },
    )

    // Double-checked get: the lock is held only for map access, never during a
    // parse — a slow parse on Default must not block a main-thread hit on a
    // DIFFERENT key. A concurrent miss on the same key computes twice and the
    // results are equal immutable values; harmless.
    fun inline(text: String, colors: MdColors): AnnotatedString {
        val key = text to colors
        synchronized(inlineLru) { inlineLru[key] }?.let { return it }
        val t0 = System.nanoTime()
        val computed = parseInline(text, colors)
        maybeLogSlowParse("inline", text.length, (System.nanoTime() - t0) / 1_000_000)
        synchronized(inlineLru) { inlineLru[key] = computed }
        return computed
    }

    fun mathLatex(text: String): List<String> {
        synchronized(mathLru) { mathLru[text] }?.let { return it }
        val t0 = System.nanoTime()
        val computed = collectInlineMathLatex(text)
        maybeLogSlowParse("math", text.length, (System.nanoTime() - t0) / 1_000_000)
        synchronized(mathLru) { mathLru[text] = computed }
        return computed
    }

    // ─── [T-android-streaming-incremental-inline] Live-tail incremental parse ──
    //
    // The streaming live fragment is (usually) a single growing paragraph. Its
    // `raw` changes every throttle tick, so `inline()` / `mathLatex()` MISS the
    // cache every tick and re-scan the WHOLE accumulated paragraph char-by-char
    // — the confirmed 81%-of-hang-stacks inline/math regex hotspot + Matcher
    // allocation GC storm (see minis-2026-07-06.log analysis).
    //
    // Incremental fix: find a SAFE closed boundary (a newline where every inline
    // construct — bold/italic/strike/code/math/link — is balanced), parse the
    // frozen prefix ONCE (it only advances in discrete jumps, so it's a cache
    // HIT across ticks), and re-parse only the short unclosed suffix each tick,
    // then splice. Correctness rests on `safeInlineSplitOffset` never splitting
    // inside an open construct, so parse(prefix) ++ parse(suffix) == parse(full).

    /** Below this length the whole-fragment parse is sub-ms; incremental
     *  splitting/splicing overhead isn't worth it. */
    private const val INCREMENTAL_MIN_CHARS = 1_500

    /** Incremental inline parse for the live streaming tail. Returns the exact
     *  same AnnotatedString as `inline(text, colors)` would, but reuses a cached
     *  parse of the closed prefix and only scans the unclosed suffix. Falls back
     *  to the plain cached path for short text or when no safe boundary exists. */
    fun inlineIncremental(text: String, colors: MdColors): AnnotatedString {
        // Exact-match cache hit (e.g. a re-publish of the same content) — free.
        synchronized(inlineLru) { inlineLru[text to colors] }?.let { return it }
        if (text.length < INCREMENTAL_MIN_CHARS) return inline(text, colors)
        val split = safeInlineSplitOffset(text)
        if (split <= 0) return inline(text, colors)
        // Prefix goes through the normal cache: identical across ticks until the
        // boundary advances, so this is a HIT on all but the (rare) advance tick.
        val prefixAnn = inline(text.substring(0, split), colors)
        val t0 = System.nanoTime()
        val suffixAnn = parseInline(text.substring(split), colors)
        maybeLogSlowParse("inline-incr", text.length - split, (System.nanoTime() - t0) / 1_000_000)
        return androidx.compose.ui.text.buildAnnotatedString {
            append(prefixAnn)
            append(suffixAnn)
        }
    }

    /** Incremental math-latex collection for the live streaming tail. Same
     *  contract as `mathLatex(text)`; reuses the closed prefix's list. */
    fun mathLatexIncremental(text: String): List<String> {
        synchronized(mathLru) { mathLru[text] }?.let { return it }
        if (text.length < INCREMENTAL_MIN_CHARS) return mathLatex(text)
        val split = safeInlineSplitOffset(text)
        if (split <= 0) return mathLatex(text)
        val prefix = mathLatex(text.substring(0, split))
        val suffix = collectInlineMathLatex(text.substring(split))
        return if (suffix.isEmpty()) prefix else prefix + suffix
    }

    /**
     * [T-android-jank-diag-logging] Threshold-gated slow-parse visibility:
     * silent in normal operation, one INFO line when a SINGLE parse exceeds
     * [SLOW_PARSE_LOG_MS]. thread=main is exactly the signal a future jank
     * report needs — it means a parse escaped every off-main path.
     */
    private const val SLOW_PARSE_LOG_MS = 80L
    private fun maybeLogSlowParse(layer: String, chars: Int, ms: Long, extra: String = "") {
        if (ms < SLOW_PARSE_LOG_MS) return
        val thread = if (android.os.Looper.getMainLooper().isCurrentThread) {
            "main"
        } else {
            Thread.currentThread().name
        }
        com.openminis.app.logging.AppLogger.info(
            "JankDiag",
            "[JankDiag] slow markdown parse layer=$layer chars=$chars ms=$ms thread=$thread$extra",
        )
    }

    /** [T-android-coldload-offmain-parse] Lock-only cache peek — never
     *  computes. Lets the frozen render path keep cache HITs synchronous
     *  while routing big MISSes off-main. */
    fun cachedBlocks(raw: String): List<MdBlock>? =
        synchronized(blocksLru) { blocksLru[raw] }

    /** [T-android-review-p1-fixes] F2(a): freeze-edge handoff — the live
     *  branch deposits its parse result here so the frozen branch HITs
     *  synchronously when the segment freezes (stream end / live→frozen
     *  migration) instead of flashing the plain-text preview while an
     *  off-main re-parse runs. */
    fun putBlocks(raw: String, blocks: List<MdBlock>) {
        synchronized(blocksLru) { blocksLru[raw] = blocks }
    }

    /** [T-android-freeze-edge-flash] Drop one entry — the live branch uses
     *  it to retire the previous tick's deposit before storing the next, so
     *  a streaming fragment occupies one slot rather than one per tick.
     *  Lru.remove keeps the character budget in step. */
    fun removeBlocks(raw: String) {
        synchronized(blocksLru) { blocksLru.remove(raw) }
    }

    /** Block-level parse for a FROZEN fragment. First parse may run on the
     *  caller's thread (once per distinct fragment text process-wide); scroll
     *  away/return and session re-entry are hits. */
    fun blocks(raw: String): List<MdBlock> {
        synchronized(blocksLru) { blocksLru[raw] }?.let { return it }
        val t0 = System.nanoTime()
        val computed = parseMarkdownBlocksBlocking(raw)
        maybeLogSlowParse(
            "blocks", raw.length, (System.nanoTime() - t0) / 1_000_000,
            extra = " blocks=${computed.size}",
        )
        synchronized(blocksLru) { blocksLru[raw] = computed }
        return computed
    }

    /** Pre-compute everything RenderBlock will ask for, off-main. Walks the
     *  same texts the RenderBlock branches feed to inline()/mathLatex();
     *  anything missed simply computes lazily on first composition (once). */
    fun prewarm(blocks: List<MdBlock>, colors: MdColors) {
        for (b in blocks) {
            when (b) {
                is MdBlock.Paragraph -> { inline(b.raw, colors); mathLatex(b.raw) }
                is MdBlock.Heading -> { inline(b.text, colors); mathLatex(b.text) }
                is MdBlock.UnorderedList -> b.items.forEach {
                    inline(it.text, colors); mathLatex(it.text); prewarm(it.children, colors)
                }
                is MdBlock.OrderedList -> b.items.forEach {
                    inline(it.text, colors); mathLatex(it.text); prewarm(it.children, colors)
                }
                is MdBlock.TaskList -> b.items.forEach {
                    inline(it.text, colors); mathLatex(it.text); prewarm(it.children, colors)
                }
                is MdBlock.Table -> {
                    b.headers.forEach { inline(it, colors); mathLatex(it) }
                    b.rows.forEach { row -> row.forEach { inline(it, colors); mathLatex(it) } }
                }
                is MdBlock.BlockQuote -> prewarm(b.innerBlocks, colors)
                else -> Unit // code blocks / media / HR / math-display don't inline-parse
            }
        }
    }

    /**
     * [T-android-streaming-incremental-inline] Off-main prewarm for the LIVE
     * tail block. Warms the closed-prefix inline/math cache so the main-thread
     * `inlineIncremental` / `mathLatexIncremental` (which RenderBlock calls for
     * the live block) resolve to a prefix HIT + a tiny suffix scan. Only the
     * LAST block is the live one; earlier blocks are frozen and handled by the
     * normal [prewarm] above. No-op for non-Paragraph tails (they don't take
     * the incremental path in RenderBlock).
     */
    fun prewarmLiveTail(blocks: List<MdBlock>, colors: MdColors) {
        val last = blocks.lastOrNull() as? MdBlock.Paragraph ?: return
        // inlineIncremental/mathLatexIncremental internally split at the safe
        // boundary and reuse inline(prefix)/mathLatex(prefix). Computing them
        // here (off-main) both warms the prefix AND memoizes the exact full-text
        // result under the plain key, so the main-thread call is a clean HIT.
        val ann = inlineIncremental(last.raw, colors)
        synchronized(inlineLru) { inlineLru[last.raw to colors] = ann }
        val math = mathLatexIncremental(last.raw)
        synchronized(mathLru) { mathLru[last.raw] = math }
    }
}

// ─── Inline markdown parser → AnnotatedString ───────────────────────────────

/**
 * [T-android-streaming-incremental-inline] Largest safe offset to split [text]
 * for incremental inline re-parse of a streaming tail. The result `p` satisfies:
 *   - `p` sits immediately AFTER a `\n` (so it lands on an inline-parse "reset"
 *     line boundary — inline code / `$…$` / `\(…\)` all stop at `\n`), and
 *   - `text[0, p)` has every multi-line-capable inline construct CLOSED, i.e.
 *     an even number of `**`, `__`, `~~`, `` ` `` runs and no dangling
 *     `[…](…` link, and no trailing `\` escape.
 *
 * This guarantees `parseInline(prefix) ++ parseInline(suffix) == parseInline(text)`
 * because no inline span crosses the split point. It's a single forward linear
 * scan (cheaper than the parse it saves). Returns 0 when no safe split exists
 * (caller then parses the whole thing) — conservative by construction: any
 * doubt about closure keeps the boundary earlier, never inside an open marker.
 *
 * We keep a [TAIL_MARGIN] of trailing chars unsplit so the still-growing tail
 * (where the model may still be mid-token, mid-`**`, mid-`$`) is always fully
 * re-scanned; only well-settled earlier content is frozen.
 */
private const val INCR_TAIL_MARGIN = 256

@androidx.annotation.VisibleForTesting
internal fun safeInlineSplitOffset(text: String): Int {
    // Track parity of the multi-line-capable delimiters. Single-line
    // constructs (inline code `…`, `$…$`, `\(…\)`, links) reset at every '\n'
    // (their close-scanners stop at newline), so at a line boundary they are
    // never "open" — we only need to prove the multi-line ones are balanced
    // AND that we're not sitting on a trailing escape.
    var boldStar = false   // ** run open  (also covers *** via two toggles)
    var boldUnder = false  // __ run open
    var strike = false     // ~~ run open
    // Link/image `[label](url` state: parseInline's [text](url) / ![alt](url)
    // use plain indexOf for `]`/`)` and thus CAN span newlines — a newline
    // inside an open link/image is NOT a safe split point.
    var inLabel = false    // seen unmatched `[` (or `![`)
    var inUrl = false      // seen `](`, awaiting `)`
    var lastSafeNewlineEnd = 0 // offset AFTER the last balanced '\n'
    val limit = text.length - INCR_TAIL_MARGIN
    if (limit <= 0) return 0

    var i = 0
    while (i < limit) {
        val c = text[i]
        when {
            // Escape — skip the escaped char so `\*`, `\[` etc. don't toggle.
            c == '\\' && i + 1 < text.length -> { i += 2; continue }
            // [T-android-inline-code-poisons-split] Skip the CONTENTS of a
            // closed inline-code span. parseInline gives `…` priority over every
            // emphasis marker, so `**` inside code is a literal, not a toggle.
            // This scanner did not know that, so one stray backtick-wrapped
            // `**` (`` `a**b` ``, an `ls **` example, a glob) flipped boldStar
            // and never flipped it back — from that point on NO newline could be
            // marked safe, `lastSafeNewlineEnd` stayed 0, and every throttle tick
            // re-parsed the ENTIRE message instead of just the tail. On a long
            // reply that full parse is slow enough to be visible: already-styled
            // text dropped back to raw `**…**` for a frame and re-styled on the
            // next tick, over and over (user report, 2026-09-02).
            //
            // An UNCLOSED backtick deliberately falls through to `i++`: both
            // findInlineCodeClose and parseInline treat it as a literal
            // character, so treating it as anything else here would break the
            // prefix ++ suffix == whole invariant this function must uphold.
            c == '`' -> {
                val close = findInlineCodeClose(text, i + 1)
                i = if (close != -1) close + 1 else i + 1
                continue
            }
            text.startsWith("~~", i) -> { strike = !strike; i += 2; continue }
            text.startsWith("**", i) -> { boldStar = !boldStar; i += 2; continue }
            text.startsWith("__", i) -> { boldUnder = !boldUnder; i += 2; continue }
            // `](` transitions label -> url (only when a label is open).
            inLabel && text.startsWith("](", i) -> { inLabel = false; inUrl = true; i += 2; continue }
            c == '[' -> { inLabel = true; i++ }             // `![` also lands here on the `[`
            c == ']' && inLabel -> { inLabel = false; i++ } // `]` not followed by `(`
            c == ')' && inUrl -> { inUrl = false; i++ }
            c == '\n' -> {
                // Safe only when every newline-spanning construct is closed.
                // `$…$` / `\(…\)` don't need tracking: their close-scanners stop
                // at '\n', so an unclosed one renders literally on both sides of
                // the split — identical either way. Inline code is different and
                // IS tracked above: it does not span newlines either, but its
                // CONTENTS must not feed the emphasis counters, because
                // parseInline resolves a code span before any `**` inside it.
                if (!boldStar && !boldUnder && !strike && !inLabel && !inUrl) {
                    lastSafeNewlineEnd = i + 1
                }
                i++
            }
            else -> i++
        }
    }
    return lastSafeNewlineEnd
}

private fun parseInline(text: String, colors: MdColors): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                // [T-latex-inline] `\( … \)` inline math must be matched BEFORE the
                // generic `\`-escape branch below — otherwise `\(` is consumed as
                // an escaped `(` and the math delimiter never fires (latent dead
                // code before this reorder). `\[ … \]` display math stays block-
                // level; here we only handle the inline `\( … \)` form.
                text.startsWith("\\(", i) -> {
                    val end = text.indexOf("\\)", i + 2)
                    if (end != -1) {
                        appendInlineContent(katexInlineTagFor(text.substring(i + 2, end)), text.substring(i + 2, end))
                        i = end + 2
                    } else { append(text[i]); i++ }
                }
                // Escape: \* \_ \` etc.
                text[i] == '\\' && i + 1 < text.length -> {
                    append(text[i + 1]); i += 2
                }
                // Image: ![alt](url) — render as [alt] link
                text.startsWith("![", i) -> {
                    val cb = text.indexOf(']', i + 2)
                    if (cb != -1 && cb + 1 < text.length && text[cb + 1] == '(') {
                        val cp = text.indexOf(')', cb + 2)
                        if (cp != -1) {
                            val alt = text.substring(i + 2, cb).ifEmpty { "image" }
                            withStyle(SpanStyle(color = colors.link)) { append("[$alt]") }
                            i = cp + 1
                        } else { append(text[i]); i++ }
                    } else { append(text[i]); i++ }
                }
                // Bold + italic: ***text***
                text.startsWith("***", i) -> {
                    val end = text.indexOf("***", i + 3)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                            appendRecursive(text.substring(i + 3, end), colors)
                        }
                        i = end + 3
                    } else { append(text[i]); i++ }
                }
                // Bold: **text** or __text__
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            appendRecursive(text.substring(i + 2, end), colors)
                        }
                        i = end + 2
                    } else { append(text[i]); i++ }
                }
                text.startsWith("__", i) -> {
                    val end = text.indexOf("__", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            appendRecursive(text.substring(i + 2, end), colors)
                        }
                        i = end + 2
                    } else { append(text[i]); i++ }
                }
                // Strikethrough: ~~text~~
                text.startsWith("~~", i) -> {
                    val end = text.indexOf("~~", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                            appendRecursive(text.substring(i + 2, end), colors)
                        }
                        i = end + 2
                    } else { append(text[i]); i++ }
                }
                // Triple backtick (fenced code fence leaked into inline) — skip as literal
                text.startsWith("```", i) -> {
                    append("```"); i += 3
                }
                // T155: inline math $ ... $ (skip $$ which is display-math, handled at block level)
                text[i] == '$' && i + 1 < text.length && text[i + 1] != '$' && text[i + 1] != ' ' -> {
                    val end = findInlineMathClose(text, i + 1)
                    if (end != -1) {
                        val latex = text.substring(i + 1, end)
                        if (looksLikeMath(latex) && !isTablePipeArtifact(latex)) {
                            appendInlineContent(katexInlineTagFor(latex), latex)
                            i = end + 1
                        } else { append(text[i]); i++ }
                    } else { append(text[i]); i++ }
                }
                // Inline code: `text`
                text[i] == '`' -> {
                    val end = findInlineCodeClose(text, i + 1)
                    if (end != -1) {
                        val codeStyle = SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            color = colors.inlineCodeText,
                        )
                        withStyle(codeStyle) { append("\u2006") }
                        // Annotation excludes the U+2006 pads on either side.
                        // Compose treats U+2006 as a break opportunity, so on
                        // wrap the leading pad sits at the prior line's tail \u2014
                        // including it in the annotation made drawBehind paint
                        // background back onto that prior line (T223).
                        val annStart = length
                        withStyle(codeStyle) { append(text.substring(i + 1, end)) }
                        val annEnd = length
                        withStyle(codeStyle) { append("\u2006") }
                        addStringAnnotation("inline_code", "", annStart, annEnd)
                        i = end + 1
                    } else { append(text[i]); i++ }
                }
                // Link: [text](url)
                text[i] == '[' && !text.startsWith("![", i - 1.coerceAtLeast(0)) -> {
                    val cb = text.indexOf(']', i + 1)
                    if (cb != -1 && cb + 1 < text.length && text[cb + 1] == '(') {
                        val cp = text.indexOf(')', cb + 2)
                        if (cp != -1) {
                            val url = text.substring(cb + 2, cp).trim()
                            val linkStart = length
                            withStyle(SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline)) {
                                append(text.substring(i + 1, cb))
                            }
                            addStringAnnotation("url", url, linkStart, length)
                            i = cp + 1
                        } else { append(text[i]); i++ }
                    } else { append(text[i]); i++ }
                }
                // Italic: *text* or _text_ (single delimiter, not followed by same)
                (text[i] == '*' || text[i] == '_') && i + 1 < text.length && text[i + 1] != text[i] && text[i + 1] != ' ' -> {
                    val delim = text[i]
                    val end = text.indexOf(delim, i + 1)
                    if (end != -1 && end > i + 1) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            appendRecursive(text.substring(i + 1, end), colors)
                        }
                        i = end + 1
                    } else { append(text[i]); i++ }
                }
                // Line break: two trailing spaces or \n
                text[i] == '\n' -> {
                    append('\n'); i++
                }
                else -> { append(text[i]); i++ }
            }
        }
    }
}

/** Recursively parse inline markdown within a styled span. */
private fun AnnotatedString.Builder.appendRecursive(text: String, colors: MdColors) {
    var i = 0
    while (i < text.length) {
        when {
            // [T-latex-inline] Inline math must be handled INSIDE emphasis too —
            // `**$\approx$**`, `*$x$*`, `~~$a$~~` all appear in AI replies. Without
            // these branches emphasis content fell through to the literal `else`
            // and the `$…$` / `\(…\)` rendered as raw dollar text. The KaTeX
            // InlineTextContent slots are pre-registered by collectInlineMathLatex
            // (which scans the whole raw line, emphasis markers included), so
            // emitting the tag here resolves to the same rendered math. Placed
            // before the `\` escape branch so `\(` is treated as math, not an
            // escaped `(`.
            text.startsWith("\\(", i) -> {
                val end = text.indexOf("\\)", i + 2)
                if (end != -1) {
                    appendInlineContent(katexInlineTagFor(text.substring(i + 2, end)), text.substring(i + 2, end))
                    i = end + 2
                } else { append(text[i]); i++ }
            }
            text[i] == '$' && i + 1 < text.length && text[i + 1] != '$' && text[i + 1] != ' ' -> {
                val end = findInlineMathClose(text, i + 1)
                if (end != -1) {
                    val latex = text.substring(i + 1, end)
                    if (looksLikeMath(latex) && !isTablePipeArtifact(latex)) {
                        appendInlineContent(katexInlineTagFor(latex), latex)
                        i = end + 1
                    } else { append(text[i]); i++ }
                } else { append(text[i]); i++ }
            }
            text[i] == '\\' && i + 1 < text.length -> { append(text[i + 1]); i += 2 }
            text.startsWith("```", i) -> { append("```"); i += 3 }
            text[i] == '`' -> {
                val end = findInlineCodeClose(text, i + 1)
                if (end != -1) {
                    val codeStyle = SpanStyle(fontFamily = FontFamily.Monospace, color = colors.inlineCodeText)
                    withStyle(codeStyle) { append("\u2006") }
                    // See T223 in the top-level inline-code branch \u2014 annotation
                    // excludes the U+2006 pads to keep wrap-line background
                    // from overshooting onto the prior line.
                    val annStart = length
                    withStyle(codeStyle) { append(text.substring(i + 1, end)) }
                    val annEnd = length
                    withStyle(codeStyle) { append("\u2006") }
                    addStringAnnotation("inline_code", "", annStart, annEnd)
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            text.startsWith("~~", i) -> {
                val end = text.indexOf("~~", i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else { append(text[i]); i++ }
            }
            text[i] == '[' -> {
                val cb = text.indexOf(']', i + 1)
                if (cb != -1 && cb + 1 < text.length && text[cb + 1] == '(') {
                    val cp = text.indexOf(')', cb + 2)
                    if (cp != -1) {
                        val url = text.substring(cb + 2, cp).trim()
                        val linkStart = length
                        withStyle(SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline)) { append(text.substring(i + 1, cb)) }
                        addStringAnnotation("url", url, linkStart, length)
                        i = cp + 1
                    } else { append(text[i]); i++ }
                } else { append(text[i]); i++ }
            }
            else -> { append(text[i]); i++ }
        }
    }
}

/**
 * T156: find the closing backtick for an inline-code span starting at
 * [from]. Streaming chunks can deliver an odd backtick ahead of its
 * real partner; if a naive `indexOf` walks past a hard line break to
 * pair it with a backtick on a later line, the intervening prose gets
 * highlighted as code (the user-reported "first half of the sentence turns into code" bug). The
 * CommonMark spec already disallows newlines inside inline code, so
 * stopping at `\n` matches the canonical parser AND defends against
 * mid-stream pairings — the orphan backtick falls back to a literal
 * character until the real closer streams in.
 *
 * Returns -1 when no close is available before the next newline,
 * mirroring `indexOf` so the call site falls into the existing
 * "literal backtick" branch.
 */
private fun findInlineCodeClose(text: String, from: Int): Int {
    var k = from
    while (k < text.length) {
        val c = text[k]
        if (c == '`') return k
        if (c == '\n') return -1
        k++
    }
    return -1
}

// ─── T155: inline math helpers ──────────────────────────────────────────────

/** Compose annotation tag for inline KaTeX placeholders. */
/**
 * T208-4 part 4: per-latex inline-content tag. Each unique latex span gets
 * its own InlineTextContent slot so the placeholder can be sized to the
 * formula's predicted dimensions instead of one fixed-size slot for all
 * formulas (which forced ContentScale.Fit to shrink every taller-than-slot
 * formula to ~85% scale and clipped wider-than-slot ones).
 */
internal const val KATEX_INLINE_TAG_PREFIX = "katex_inline:"

internal fun katexInlineTagFor(latex: String): String = KATEX_INLINE_TAG_PREFIX + latex

/**
 * T208-4 part 4: estimate the on-screen dp size of an inline KaTeX render
 * BEFORE it has actually rendered, so we can size the InlineTextContent
 * placeholder appropriately. Compose's Placeholder API requires a size at
 * construction time and the inline Text layout reserves exactly that
 * amount of space — so we have to predict.
 *
 * Heuristics calibrated against the T208-DBG logs collected from a real
 * device: KaTeX produces ~`fontSize * 1.6` dp wide per visible character
 * for ordinary glyphs at 16 sp / density 2.625, and ~`fontSize * 1.65` dp
 * tall for one-line formulas (descenders + sub/superscript whitespace).
 * Stacked constructs (\frac, \begin, \sqrt with fraction inside) need
 * 2-3× the height. These numbers are intentionally generous — Compose
 * will draw the bitmap at its natural dp size centered inside the slot,
 * so an over-sized slot just produces extra whitespace, but an
 * under-sized slot triggers shrink/clip.
 */
internal fun estimateInlineMathSize(latex: String, fontSize: TextUnit): Pair<TextUnit, TextUnit> {
    val visibleCharCount = run {
        var c = 0
        var i = 0
        while (i < latex.length) {
            val ch = latex[i]
            if (ch == '\\' && i + 1 < latex.length) {
                // Skip a TeX command name; count the command as ~1.5 visible chars.
                i++
                while (i < latex.length && latex[i].isLetter()) i++
                c += 1
                continue
            }
            if (ch == '{' || ch == '}' || ch == ' ') { i++; continue }
            c++
            i++
        }
        c.coerceAtLeast(1)
    }

    // Width: ~0.95 em per visible char for typical math glyphs. KaTeX's
    // measured widths run ~0.95 em/char for ordinary symbols and >1 em/char
    // when `\text{...}` switches to a proportional sans/serif body face;
    // tuning down to 0.65 underestimated formulas like `W_c^{\text{non-private}}`
    // (244 dp natural wide vs 166 dp slot → Image got clipped/shrunk).
    // Cap at a generous upper bound — wide-math splitter has already
    // promoted truly wide formulas (length>30 OR `\begin{...}` etc.) to
    // display blocks, so anything reaching this estimator is short-ish
    // inline math; the cap is just defensive against pathological input.
    val charWidthEm = 0.95f
    val widthEm = (visibleCharCount * charWidthEm).coerceIn(1.5f, 22f)

    // Height: ~1.7 em base (matches measured ~26 dp for 16 sp).
    // Stacked constructs need vertical room for numerator+bar+denominator.
    var heightEm = 1.7f
    if (latex.contains("\\frac") || latex.contains("\\binom") ||
        latex.contains("\\sum") || latex.contains("\\int") ||
        latex.contains("\\prod") || latex.contains("\\sqrt[")
    ) heightEm = 3.2f
    if (latex.contains("\\begin{") || latex.contains("\\\\")) heightEm = 4.5f

    return (fontSize * widthEm) to (fontSize * heightEm)
}

/**
 * T208-4 part 4: scan a markdown line for inline-math spans (same delimiter
 * logic as parseInline) so we can pre-register a sized placeholder for
 * each unique latex BEFORE the AnnotatedString is laid out.
 */
internal fun collectInlineMathLatex(text: String): List<String> {
    val out = mutableListOf<String>()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '\\' && i + 1 < text.length && text[i + 1] != '(' && text[i + 1] != '[') {
            i += 2; continue
        }
        if (c == '\\' && i + 1 < text.length && text[i + 1] == '(') {
            val end = text.indexOf("\\)", i + 2)
            if (end != -1) {
                out.add(text.substring(i + 2, end))
                i = end + 2; continue
            }
        }
        if (c == '$' && i + 1 < text.length && text[i + 1] != '$' && text[i + 1] != ' ') {
            val end = findInlineMathClose(text, i + 1)
            if (end != -1) {
                val latex = text.substring(i + 1, end)
                if (looksLikeMath(latex) && !isTablePipeArtifact(latex)) {
                    out.add(latex)
                    // Consumed a real span — jump past its closing `$`.
                    i = end + 1; continue
                }
                // [T-latex-inline] Rejected (currency / artifact): DON'T skip past
                // the closing `$`. Advancing to end+1 here would swallow the `$`
                // that legitimately OPENS the next span (`cost $5, and $x+y$` lost
                // `x+y` because the `$` before `x` got consumed as the first span's
                // close). Fall through to i++ so that `$` stays available. Matches
                // parseInline, which appends the char and advances by 1 on reject.
            }
        }
        i++
    }
    return out
}

/**
 * Find the closing `$` for an inline-math span starting at [from].
 * Mirrors [findInlineCodeClose]: stop at a newline so streaming chunks
 * never pair a stray `$` with the next dollar that arrives later, and
 * skip `\$` (escaped) and `$$` (which would be display math).
 */
private fun findInlineMathClose(text: String, from: Int): Int {
    var k = from
    while (k < text.length) {
        val c = text[k]
        if (c == '\n') return -1
        if (c == '\\' && k + 1 < text.length) { k += 2; continue }
        if (c == '$') {
            // `$$` here is the start of display math, not a single-dollar close.
            if (k + 1 < text.length && text[k + 1] == '$') return -1
            return k
        }
        k++
    }
    return -1
}

/**
 * Crude heuristic to skip plain currency like `$5`, `$1,000` and avoid
 * turning prose dollar signs into KaTeX renders. Real LaTeX math nearly
 * always carries a backslash command, a brace, a math operator, or a
 * superscript/subscript marker. iOS uses the same idea
 * (MinisMarkdownParser.looksLikeMath).
 */
private fun looksLikeMath(latex: String): Boolean {
    if (latex.isBlank()) return false
    if (latex.contains('\\')) return true
    if (latex.contains('{') || latex.contains('}')) return true
    if (latex.contains('^') || latex.contains('_')) return true
    val mathChars = "=+-*/<>≤≥≠∑∫∏√∞αβγθπφλμωΔΩ"
    if (latex.any { it in mathChars }) return true
    // [T-latex-inline] Bare short spans like `$x$`, `$pi$`, `$abc$` carry no
    // LaTeX glyph but ARE math. Mirror iOS MinisMarkdownParser.looksLikeMath,
    // which accepts `count > 2`, and additionally accept a single alphanumeric
    // token (`$x$`, `$n$`) — the strict "needs a math char" rule was the drift
    // that made single-variable inline math leak as literal `$…$`. Currency
    // (`$5`, `$1,000`) is filtered by the leading-digit guard, and `$$`/space
    // openers never reach here (gated by the caller).
    if (latex[0].isDigit()) return false            // currency: `$5`, `$10.99`
    if (latex.first().isWhitespace() || latex.last().isWhitespace()) return false
    if (latex.length <= 30 && latex.all { it.isLetterOrDigit() }) return true
    return latex.length > 2
}

/**
 * [T-latex-inline] True when a candidate inline-math span is really a markdown
 * table-cell artifact (a `$` that paired across `|` column separators) rather
 * than a formula. Mirrors iOS MinisMarkdownParser.isTablePipeArtifact so a row
 * like `| 月付 | $20|$ **3** |` doesn't capture `20|` as fake math (which would
 * eat the bold `**3**`). Two signals a real formula avoids: an unescaped pipe
 * with whitespace on a side (the ` | ` column separator), or an ODD number of
 * unescaped pipes (abs-value / norm bars always come in balanced pairs).
 * Escaped `\|` (LaTeX norm) is never counted.
 */
private fun isTablePipeArtifact(content: String): Boolean {
    var bareCount = 0
    for (idx in content.indices) {
        if (content[idx] != '|') continue
        if (idx > 0 && content[idx - 1] == '\\') continue      // escaped norm bar
        bareCount++
        val prevIsSpace = idx > 0 && content[idx - 1].isWhitespace()
        val nextIsSpace = idx + 1 < content.length && content[idx + 1].isWhitespace()
        if (prevIsSpace || nextIsSpace) return true
    }
    return bareCount % 2 == 1
}

