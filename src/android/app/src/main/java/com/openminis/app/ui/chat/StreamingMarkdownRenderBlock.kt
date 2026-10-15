package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.openminis.app.ui.markdown.LocalCodeBlockRunState
import com.openminis.app.ui.markdown.LocalMarkdownCodeRunner
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import com.openminis.app.ui.DisplayBitmapLimits.limitDisplaySize
import com.openminis.app.ui.theme.ChatColors

@Composable
internal fun RenderBlock(block: MdBlock) {
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
            // [▶ 运行] 按钮仅在「块已完结 + 语言可跑 + 有回调」时出现：
            //   - LocalLiveIncremental / LocalAppendOnlyFade 只在流式尾部块上
            //     为 true（见 StreamingMarkdownBlockBody / StreamingMarkdownTextBody
            //     的 live 分支）——尾块正是还在增长的未闭合 fence（parse 层
            //     对未闭合 fence 也产出 CodeBlock），跑半截代码等于执行前缀，
            //     所以跟复制按钮不同，这里必须等块冻结。
            //   - 回调经 LocalMarkdownCodeRunner 注入（ChatScreen 全局
            //     provide viewModel::runCodeBlockInline）；null 时按钮不渲染，
            //     本渲染器的其它使用方零改动。
            val codeRunner = LocalMarkdownCodeRunner.current
            val showRun = codeRunner != null &&
                CodeBlockRunRouter.isSupported(block.language) &&
                !LocalLiveIncremental.current &&
                !LocalAppendOnlyFade.current
            val running = showRun && LocalCodeBlockRunState.current.contains(block.code)
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
                    if (showRun) {
                        if (running) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF34C759),
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "运行 ${block.language.ifEmpty { "code" }} 代码",
                                tint = Color(0xFF34C759),
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { codeRunner?.invoke(block.language, block.code) },
                            )
                        }
                    }
                    Icon(
                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = if (copied) "已复制" else "复制代码",
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
            ReportTranslateInkBlocked()
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
                        MdText(
                            text = MarkdownParseCaches.inline(item.text, colors),
                            fontSize = BaseFontSize,
                            lineHeight = BaseLineHeight,
                            color = colors.text,
                            modifier = Modifier.weight(1f),
                            inlineContent = rememberKatexInlineContent(BaseFontSize, MarkdownParseCaches.mathLatex(item.text)),
                        )
                    }
                    // [T-list-item-block-children] Nested blocks (table /
                    // code fence collected by appendContinuation) render
                    // under the item instead of being printed as prose.
                    if (item.children.isNotEmpty()) {
                        Column(
                            modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            item.children.forEach { child -> RenderBlock(child) }
                        }
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
                        MdText(
                            text = MarkdownParseCaches.inline(item.text, colors),
                            fontSize = BaseFontSize,
                            lineHeight = BaseLineHeight,
                            color = colors.text,
                            modifier = Modifier.weight(1f),
                            inlineContent = rememberKatexInlineContent(BaseFontSize, MarkdownParseCaches.mathLatex(item.text)),
                        )
                    }
                    // [T-list-item-block-children] see UnorderedList above.
                    if (item.children.isNotEmpty()) {
                        Column(
                            modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            item.children.forEach { child -> RenderBlock(child) }
                        }
                    }
                }
            }
        }

        is MdBlock.TaskList -> {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                block.items.forEach { item ->
                    Row(
                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
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
                        MdText(
                            text = MarkdownParseCaches.inline(item.text, colors),
                            fontSize = BaseFontSize,
                            lineHeight = BaseLineHeight,
                            color = if (item.checked) colors.text.copy(alpha = 0.5f) else colors.text,
                            modifier = Modifier.weight(1f),
                            inlineContent = rememberKatexInlineContent(BaseFontSize, MarkdownParseCaches.mathLatex(item.text)),
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
            ReportTranslateInkBlocked()
        }

        is MdBlock.Image -> {
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
            val imageRequest = remember(file, block.url) {
                ImageRequest.Builder(context)
                    .data(file ?: block.url)
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
            ReportTranslateInkBlocked()
        }

        is MdBlock.Video -> {
            RenderMdVideo(block)
            ReportTranslateInkBlocked()
        }

        is MdBlock.Audio -> {
            RenderMdAudio(block)
            ReportTranslateInkBlocked()
        }

        is MdBlock.Table -> {
            RenderTable(block)
            ReportTranslateInkBlocked()
        }

        is MdBlock.MathDisplay -> {
            RenderMathDisplay(block.latex)
            ReportTranslateInkBlocked()
        }
    }
}

