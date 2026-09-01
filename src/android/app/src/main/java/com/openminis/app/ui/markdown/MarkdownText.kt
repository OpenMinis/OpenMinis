package com.openminis.app.ui.markdown

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.openminis.app.sandbox.PRootKernel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Renders markdown text with full formatting support.
 * Supports: headings, bold, italic, strikethrough, inline code, code blocks (with copy),
 * bullet/numbered/task lists, blockquotes, tables, thematic breaks, and links.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val parsed = remember(markdown) { MarkdownParser.parseWithMath(markdown) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (block in parsed.blocks) {
            BlockContent(block, color, style, parsed.mathSpans)
        }
    }
}

@Composable
private fun BlockContent(
    block: MarkdownParser.Block,
    color: Color,
    baseStyle: TextStyle,
    mathSpans: List<MarkdownParser.MathSpan> = emptyList(),
) {
    android.util.Log.d("MdRender", "BlockContent: ${block::class.simpleName}")
    when (block) {
        is MarkdownParser.Block.Heading -> HeadingBlock(block, color)
        is MarkdownParser.Block.Paragraph -> ParagraphBlock(block.content, color, baseStyle, mathSpans)
        is MarkdownParser.Block.CodeBlock -> CodeBlockView(block)
        is MarkdownParser.Block.Blockquote -> BlockquoteView(block, color, baseStyle, mathSpans)
        is MarkdownParser.Block.BulletList -> BulletListView(block, color, baseStyle)
        is MarkdownParser.Block.NumberedList -> NumberedListView(block, color, baseStyle)
        is MarkdownParser.Block.Table -> TableView(block, color, baseStyle, mathSpans)
        is MarkdownParser.Block.MathBlock -> MathBlockView(latex = block.latex)
        is MarkdownParser.Block.ThematicBreak -> HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = color.copy(alpha = 0.3f),
        )
        is MarkdownParser.Block.Image -> MinisImageBlock(block)
        is MarkdownParser.Block.Video -> MinisVideoBlock(block)
        is MarkdownParser.Block.Audio -> MinisAudioBlock(block)
    }
}

// -- Heading --

@Composable
private fun HeadingBlock(heading: MarkdownParser.Block.Heading, color: Color) {
    val (fontSize, fontWeight) = when (heading.level) {
        1 -> 24.sp to FontWeight.Bold
        2 -> 20.sp to FontWeight.Bold
        3 -> 18.sp to FontWeight.SemiBold
        4 -> 16.sp to FontWeight.SemiBold
        5 -> 15.sp to FontWeight.Medium
        else -> 14.sp to FontWeight.Medium
    }
    InlineContent(
        text = heading.content,
        color = color,
        style = TextStyle(fontSize = fontSize, fontWeight = fontWeight),
    )
}

// -- Paragraph --

@Composable
private fun ParagraphBlock(
    content: String,
    color: Color,
    style: TextStyle,
    mathSpans: List<MarkdownParser.MathSpan> = emptyList(),
) {
    // Fast path: no math placeholders → original single-Text rendering.
    if (mathSpans.isEmpty() || !content.contains('￼')) {
        InlineContent(text = content, color = color, style = style)
        return
    }

    // T208 Layer B: split the paragraph at each placeholder and render
    // inline math as a *non-display-mode*, *non-fillMaxWidth* tile so the
    // formula visually flows after the preceding text segment instead of
    // becoming a centered full-width block. Inline math should be roughly
    // the size of surrounding text and aligned to the start of its row.
    // Display math (`$$ … $$`) goes through Block.MathBlock above and
    // still renders centered via MathBlockView — that path is correct.
    // True inline-flow (math glyph on the same baseline as text within
    // a single Text composable via InlineTextContent) is a follow-up;
    // for now we stay with the row-split shape but keep the math visually
    // small.
    val byPlaceholder = remember(mathSpans) { mathSpans.associateBy { it.placeholder } }
    val regex = remember { Regex("\\uFFFC" + "MATH(\\d+)" + "\\uFFFC") }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        var cursor = 0
        for (match in regex.findAll(content)) {
            val span = byPlaceholder[match.value] ?: continue
            if (match.range.first > cursor) {
                val segment = content.substring(cursor, match.range.first)
                if (segment.isNotBlank()) {
                    InlineContent(text = segment, color = color, style = style)
                }
            }
            // Inline math: non-display KaTeX, no fillMaxWidth, start-aligned.
            KaTeXRenderView(latex = span.latex, displayMode = false)
            cursor = match.range.last + 1
        }
        if (cursor < content.length) {
            val tail = content.substring(cursor)
            if (tail.isNotBlank()) {
                InlineContent(text = tail, color = color, style = style)
            }
        }
    }
}

// -- Code Block --

@Composable
private fun CodeBlockView(block: MarkdownParser.Block.CodeBlock) {
    val context = LocalContext.current
    val codeColor = Color(0xFF4EC9B0) // Green code text
    val bgColor = MaterialTheme.colorScheme.surfaceContainerHighest

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor),
    ) {
        // Header with language label + copy button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = block.language.ifEmpty { "code" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("code", block.code))
                },
                modifier = Modifier.height(28.dp),
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "Copy code",
                    modifier = Modifier.height(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Scrollable code content
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = block.code,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = codeColor,
                    lineHeight = 18.sp,
                ),
            )
        }
    }
}

// -- Blockquote --

@Composable
private fun BlockquoteView(
    block: MarkdownParser.Block.Blockquote,
    color: Color,
    baseStyle: TextStyle,
    mathSpans: List<MarkdownParser.MathSpan> = emptyList(),
) {
    // Orange accent rule on the leading edge — matches iOS, where the
    // blockquote uses the app systemOrange (0xFFFF9500) at full opacity.
    // The previous primary @ 0.5 alpha disappeared into theme purple on the
    // dark palette and didn't read as a quote at all in some compositions.
    val barColor = Color(0xFFFF9500)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(
                    color = barColor,
                    topLeft = Offset(0f, 0f),
                    size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height),
                )
            }
            .padding(start = 12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Inner content uses onSurfaceVariant so quoted text reads as
            // secondary, matching the iOS quote treatment.
            val quotedColor = MaterialTheme.colorScheme.onSurfaceVariant
            for (inner in block.blocks) {
                BlockContent(inner, quotedColor, baseStyle, mathSpans)
            }
        }
    }
}

// -- Bullet List --

@Composable
private fun BulletListView(
    block: MarkdownParser.Block.BulletList,
    color: Color,
    baseStyle: TextStyle,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (item in block.items) {
            Row(modifier = Modifier.fillMaxWidth()) {
                if (item.checked != null) {
                    // Task list item
                    Icon(
                        imageVector = if (item.checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                        contentDescription = null,
                        tint = color.copy(alpha = 0.6f),
                        modifier = Modifier
                            .padding(end = 4.dp, top = 2.dp)
                            .height(18.dp)
                            .width(18.dp),
                    )
                } else {
                    Text(
                        text = "•",
                        color = color,
                        style = baseStyle,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                InlineContent(
                    text = item.content,
                    color = color,
                    style = baseStyle,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// -- Numbered List --

@Composable
private fun NumberedListView(
    block: MarkdownParser.Block.NumberedList,
    color: Color,
    baseStyle: TextStyle,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((index, item) in block.items.withIndex()) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${block.startNumber + index}.",
                    color = color,
                    style = baseStyle,
                    modifier = Modifier.padding(end = 8.dp),
                )
                InlineContent(
                    text = item.content,
                    color = color,
                    style = baseStyle,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// -- Table --

@Composable
private fun TableView(
    table: MarkdownParser.Block.Table,
    color: Color,
    baseStyle: TextStyle,
    mathSpans: List<MarkdownParser.MathSpan> = emptyList(),
) {
    val borderColor = color.copy(alpha = 0.2f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .border(0.5.dp, borderColor, RoundedCornerShape(4.dp))
            .clip(RoundedCornerShape(4.dp)),
    ) {
        Column {
            // Header row
            Row(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .height(IntrinsicSize.Min),
            ) {
                for ((colIdx, header) in table.headers.withIndex()) {
                    val align = table.alignments.getOrElse(colIdx) { MarkdownParser.Alignment.LEFT }
                    Box(
                        modifier = Modifier
                            .width(120.dp)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        contentAlignment = when (align) {
                            MarkdownParser.Alignment.CENTER -> Alignment.Center
                            MarkdownParser.Alignment.RIGHT -> Alignment.CenterEnd
                            MarkdownParser.Alignment.LEFT -> Alignment.CenterStart
                        },
                    ) {
                        TableCellContent(
                            text = header,
                            color = color,
                            style = baseStyle.copy(fontWeight = FontWeight.Bold),
                            mathSpans = mathSpans,
                        )
                    }
                }
            }

            HorizontalDivider(color = borderColor)

            // Data rows
            for (row in table.rows) {
                Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                    for ((colIdx, cell) in row.withIndex()) {
                        val align = table.alignments.getOrElse(colIdx) { MarkdownParser.Alignment.LEFT }
                        Box(
                            modifier = Modifier
                                .width(120.dp)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            contentAlignment = when (align) {
                                MarkdownParser.Alignment.CENTER -> Alignment.Center
                                MarkdownParser.Alignment.RIGHT -> Alignment.CenterEnd
                                MarkdownParser.Alignment.LEFT -> Alignment.CenterStart
                            },
                        ) {
                            TableCellContent(
                                text = cell,
                                color = color,
                                style = baseStyle,
                                mathSpans = mathSpans,
                            )
                        }
                    }
                }
                HorizontalDivider(color = borderColor)
            }
        }
    }
}

/**
 * T208 Layer D: a table cell that resolves inline-math placeholders the
 * same way [ParagraphBlock] does. Without this, `$x^2$` inside a cell
 * would have its placeholder stripped by [InlineContent]'s cleanup pass
 * and the math would silently disappear.
 *
 * Cells that don't carry any math placeholder fall through to a plain
 * [InlineContent] call so existing inline markdown (bold/italic/code,
 * links) keeps working in cells without paying for an extra Column.
 */
@Composable
private fun TableCellContent(
    text: String,
    color: Color,
    style: TextStyle,
    mathSpans: List<MarkdownParser.MathSpan>,
) {
    if (mathSpans.isEmpty() || !text.contains('￼')) {
        InlineContent(text = text, color = color, style = style)
        return
    }
    val byPlaceholder = remember(mathSpans) { mathSpans.associateBy { it.placeholder } }
    val regex = remember { Regex("\\uFFFC" + "MATH(\\d+)" + "\\uFFFC") }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        var cursor = 0
        for (match in regex.findAll(text)) {
            val span = byPlaceholder[match.value] ?: continue
            if (match.range.first > cursor) {
                val segment = text.substring(cursor, match.range.first)
                if (segment.isNotBlank()) {
                    InlineContent(text = segment, color = color, style = style)
                }
            }
            KaTeXRenderView(latex = span.latex, displayMode = false)
            cursor = match.range.last + 1
        }
        if (cursor < text.length) {
            val tail = text.substring(cursor)
            if (tail.isNotBlank()) {
                InlineContent(text = tail, color = color, style = style)
            }
        }
    }
}

// -- Inline Content --

@Composable
private fun InlineContent(
    text: String,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val inlineCodeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val inlineCodeColor = MaterialTheme.colorScheme.primary
    val linkColor = MaterialTheme.colorScheme.primary

    // Strip any leftover math placeholders (U+FFFC MATH<n> U+FFFC). The
    // ParagraphBlock split path resolves them when math spans are
    // threaded through, but lists/headings/table cells don't carry the
    // span list — collapsing the placeholder here keeps the U+FFFC
    // sentinel from leaking into the rendered glyph stream.
    val cleaned = remember(text) {
        if (text.contains('￼')) {
            text.replace(Regex("\\uFFFCMATH\\d+\\uFFFC"), "")
        } else {
            text
        }
    }

    // T136: route non-http(s) link taps (intent://, mailto:, tel:, …) through
    // BrowserExternalSchemeHandler instead of Compose's default UriHandler,
    // which fires a plain ACTION_VIEW and breaks for `intent://...#Intent;…`
    // URIs. For http(s)/about/file the handler is a no-op and we fall back to
    // a plain ACTION_VIEW to the system browser.
    val context = LocalContext.current
    val linkListener = remember(context) {
        LinkInteractionListener { link ->
            val url = (link as? LinkAnnotation.Url)?.url ?: return@LinkInteractionListener
            // [T-android-user-initiated-scheme-dispatch] User tapped a link
            // in rendered markdown — dispatch any scheme.
            val handled = com.openminis.app.ui.browser.BrowserExternalSchemeHandler
                .handle(
                    context,
                    url,
                    com.openminis.app.ui.browser.BrowserExternalSchemeHandler
                        .Origin.USER_INITIATED,
                )
            if (handled) return@LinkInteractionListener
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    val annotated = remember(cleaned, color, linkListener) {
        parseInline(cleaned, color, style, inlineCodeBg, inlineCodeColor, linkColor, linkListener)
    }

    Text(
        text = annotated,
        modifier = modifier,
        style = style.copy(color = color),
    )
}

/**
 * Parse inline markdown: **bold**, *italic*, ~~strikethrough~~, `code`, [links](url)
 */
private fun parseInline(
    text: String,
    color: Color,
    baseStyle: TextStyle,
    inlineCodeBg: Color,
    inlineCodeColor: Color,
    linkColor: Color,
    linkListener: LinkInteractionListener? = null,
): AnnotatedString = buildAnnotatedString {
    var i = 0
    val len = text.length

    while (i < len) {
        // Escaped character
        if (text[i] == '\\' && i + 1 < len) {
            append(text[i + 1])
            i += 2
            continue
        }

        // Inline code
        if (text[i] == '`') {
            val end = text.indexOf('`', i + 1)
            if (end > i) {
                withStyle(SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    color = inlineCodeColor,
                    background = inlineCodeBg,
                    fontSize = (baseStyle.fontSize.value * 0.85).sp,
                )) {
                    append(text.substring(i + 1, end))
                }
                i = end + 1
                continue
            }
        }

        // Link [text](url)
        if (text[i] == '[') {
            val closeBracket = text.indexOf(']', i + 1)
            if (closeBracket > i && closeBracket + 1 < len && text[closeBracket + 1] == '(') {
                val closeParen = text.indexOf(')', closeBracket + 2)
                if (closeParen > closeBracket) {
                    val linkText = text.substring(i + 1, closeBracket)
                    val url = text.substring(closeBracket + 2, closeParen)
                    withLink(LinkAnnotation.Url(url, linkInteractionListener = linkListener)) {
                        withStyle(SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                        )) {
                            append(linkText)
                        }
                    }
                    i = closeParen + 1
                    continue
                }
            }
        }

        // Bold + Italic ***text***
        if (i + 2 < len && text.substring(i, i + 3) == "***") {
            val end = text.indexOf("***", i + 3)
            if (end > i) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                    append(text.substring(i + 3, end))
                }
                i = end + 3
                continue
            }
        }

        // Bold **text** or __text__
        if (i + 1 < len && (text.substring(i, i + 2) == "**" || text.substring(i, i + 2) == "__")) {
            val marker = text.substring(i, i + 2)
            val end = text.indexOf(marker, i + 2)
            if (end > i) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(text.substring(i + 2, end))
                }
                i = end + 2
                continue
            }
        }

        // Strikethrough ~~text~~
        if (i + 1 < len && text.substring(i, i + 2) == "~~") {
            val end = text.indexOf("~~", i + 2)
            if (end > i) {
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    append(text.substring(i + 2, end))
                }
                i = end + 2
                continue
            }
        }

        // Italic *text* or _text_ (single delimiter, not followed by another)
        if ((text[i] == '*' || text[i] == '_') &&
            (i + 1 >= len || text[i + 1] != text[i]) // not ** or __
        ) {
            val delim = text[i]
            val end = text.indexOf(delim, i + 1)
            if (end > i && end > i + 1) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(text.substring(i + 1, end))
                }
                i = end + 1
                continue
            }
        }

        // Regular character
        append(text[i])
        i++
    }
}

// ─── Media blocks (inline image / video / audio) ────────────────────────────

/**
 * Resolve a URL used in Markdown (`minis://...` or a plain path) to a host
 * File, suitable for MediaPlayer, MediaMetadataRetriever, or file share
 * intents. Returns null when the path can't be resolved or the file is
 * missing. Mirrors MinisImageFetcher's resolution logic so inline media
 * tracks the same rules as inline images.
 */
private fun resolveMediaFile(url: String): File? {
    if (url.isBlank()) return null
    // Strip a real query string, but NOT `#`: attachment filenames can
    // contain '#' (e.g. `foo #China.mp4`). `minis://` URLs don't use
    // fragments, so truncating at '#' would lose part of the filename.
    val stripped = url.substringBefore('?')
    val hostFile: File? = when {
        stripped.startsWith("minis://") -> {
            val decoded = java.net.URLDecoder.decode(stripped.removePrefix("minis://"), "UTF-8")
            PRootKernel.resolveHostPath("/var/minis/$decoded")
        }
        stripped.startsWith("file://") -> File(Uri.parse(stripped).path ?: return null)
        stripped.startsWith("/") -> File(stripped)
        else -> null
    }
    val ok = hostFile?.let { it.exists() && it.isFile } == true
    android.util.Log.d("MdMedia", "resolveMediaFile url=$url -> host=${hostFile?.absolutePath} exists=$ok")
    return hostFile?.takeIf { ok }
}

private fun filenameFromUrl(url: String): String {
    val stripped = url.substringBefore('?')
    val last = stripped.substringAfterLast('/')
    return try {
        java.net.URLDecoder.decode(last, "UTF-8")
    } catch (_: Throwable) { last }
}

/**
 * Share a resolved media file via the system chooser (system player, file
 * manager, etc.). Used when tapping an inline video card — simpler than
 * bundling ExoPlayer for a fullscreen experience.
 */
private fun openMediaExternally(context: Context, file: File, mime: String) {
    android.util.Log.d("MdMedia", "openMediaExternally file=${file.absolutePath} mime=$mime")
    val authority = context.packageName + ".fileprovider"
    val uri = try {
        androidx.core.content.FileProvider.getUriForFile(context, authority, file)
    } catch (_: Throwable) {
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
    try { context.startActivity(chooser) } catch (_: Throwable) { /* no handler */ }
}

// -- Image block --

@Composable
private fun MinisImageBlock(block: MarkdownParser.Block.Image) {
    val surfaceBg = MaterialTheme.colorScheme.surfaceVariant
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    AsyncImage(
        model = block.url,
        contentDescription = block.alt.ifEmpty { filenameFromUrl(block.url) },
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(surfaceBg)
            .border(0.5.dp, borderColor, RoundedCornerShape(8.dp))
            .padding(vertical = 4.dp),
    )
}

// -- Video block (thumbnail card + tap to open system player) --

@Composable
private fun MinisVideoBlock(block: MarkdownParser.Block.Video) {
    android.util.Log.d("MdMedia", "MinisVideoBlock url=${block.url} alt=${block.alt}")
    val context = LocalContext.current
    val file = remember(block.url) { resolveMediaFile(block.url) }
    val filename = remember(block.url) { filenameFromUrl(block.url) }
    val surfaceBg = MaterialTheme.colorScheme.surfaceVariant
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    val captionColor = MaterialTheme.colorScheme.onSurfaceVariant

    // Generate a thumbnail frame off the main thread via MediaMetadataRetriever.
    val thumbnail by produceState<Bitmap?>(initialValue = null, key1 = file?.absolutePath) {
        val f = file ?: run {
            android.util.Log.d("MdMedia", "video thumbnail skipped (no file)")
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(f.absolutePath)
                val bmp = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                android.util.Log.d("MdMedia", "video thumbnail for ${f.name} -> ${bmp?.width}x${bmp?.height}")
                bmp
            } catch (t: Throwable) {
                android.util.Log.w("MdMedia", "video thumbnail failed: ${t.message}")
                null
            } finally {
                try { retriever.release() } catch (_: Throwable) {}
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(surfaceBg)
            .border(0.5.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable {
                file?.let { openMediaExternally(context, it, "video/*") }
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
                modifier = Modifier.width(56.dp).height(56.dp),
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
                tint = captionColor,
                modifier = Modifier.width(14.dp).height(14.dp),
            )
            Text(
                text = filename,
                style = MaterialTheme.typography.bodySmall,
                color = captionColor,
                maxLines = 1,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

// -- Audio block (inline play/pause + progress) --

@Composable
private fun MinisAudioBlock(block: MarkdownParser.Block.Audio) {
    val file = remember(block.url) { resolveMediaFile(block.url) }
    val filename = remember(block.url) { filenameFromUrl(block.url) }

    // Dedicated MediaPlayer per card. Released on DisposableEffect dispose.
    val player = remember(file?.absolutePath) {
        if (file == null) null else try {
            MediaPlayer().apply { setDataSource(file.absolutePath); prepare() }
        } catch (_: Throwable) { null }
    }
    DisposableEffect(player) {
        onDispose { try { player?.release() } catch (_: Throwable) {} }
    }

    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0) }
    val durationMs = player?.duration ?: 0

    // Poll position while playing to drive the progress bar.
    LaunchedEffect(isPlaying) {
        while (isPlaying && player != null) {
            positionMs = try { player.currentPosition } catch (_: Throwable) { 0 }
            if (!player.isPlaying) { isPlaying = false; break }
            delay(200)
        }
    }
    DisposableEffect(player) {
        val listener = MediaPlayer.OnCompletionListener {
            isPlaying = false
            positionMs = 0
            try { player?.seekTo(0) } catch (_: Throwable) {}
        }
        player?.setOnCompletionListener(listener)
        onDispose { try { player?.setOnCompletionListener(null) } catch (_: Throwable) {} }
    }

    val tint = MaterialTheme.colorScheme.primary
    val subtle = MaterialTheme.colorScheme.onSurfaceVariant
    val cardBg = MaterialTheme.colorScheme.surfaceVariant
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(cardBg)
            .border(0.5.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(enabled = file != null) {
                if (player == null) {
                    file?.let { openMediaExternally(context, it, "audio/*") }
                } else {
                    if (isPlaying) { try { player.pause() } catch (_: Throwable) {} ; isPlaying = false }
                    else { try { player.start(); isPlaying = true } catch (_: Throwable) {} }
                }
            }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Leading icon
        Icon(
            imageVector = Icons.Filled.Audiotrack,
            contentDescription = null,
            tint = subtle,
            modifier = Modifier.width(18.dp).height(18.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
        ) {
            Text(
                text = block.alt.ifEmpty { filename },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
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
                Text(
                    text = "${formatMs(positionMs)} / ${formatMs(durationMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = subtle,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Icon(
            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Play",
            tint = tint,
            modifier = Modifier.width(28.dp).height(28.dp),
        )
    }
}

private fun formatMs(ms: Int): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
