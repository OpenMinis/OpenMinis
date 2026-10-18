package com.openminis.app.ui.chat

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import kotlinx.coroutines.launch

/**
 * [T-android-table-width-by-content] Widest single word of [text], in px.
 *
 * A column cannot usefully narrow below its longest unbreakable run, so
 * [TableColumnPlanner] uses this as the hard minimum. Whitespace-separated
 * tokens are the right unit here: CJK text has no spaces and is measured as one
 * token, which is exactly why the planner then keeps such a column at its
 * natural width instead of wrapping it mid-word.
 */
private fun longestWordWidthPx(
    text: String,
    measurer: androidx.compose.ui.text.TextMeasurer,
    style: androidx.compose.ui.text.TextStyle,
): Float {
    var widest = 0f
    for (word in text.split(' ', '\t', '\n')) {
        if (word.isBlank()) continue
        val w = measurer.measure(word, style, maxLines = 1).size.width.toFloat()
        if (w > widest) widest = w
    }
    return widest
}

@Composable
internal fun RenderTable(block: MdBlock.Table) {    val colors = currentMdColors()
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
        // Cell horizontal padding (10.dp each side) is part of every column width
        // the planner sees, so the numbers below and the drawn cells agree.
        val cellHPaddingPx = with(androidx.compose.ui.platform.LocalDensity.current) { 20.dp.toPx() }
        // [T-android-table-width-by-content] Longest unbreakable word per column —
        // the width a column can never usefully drop below. Measured once per
        // table content: a layout-phase measurement would redo it every frame.
        val cellMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
        val cellTextStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = colors.text)
        val unbreakablePx = androidx.compose.runtime.remember(block.raw, cellTextStyle) {
            val per = FloatArray(colCount)
            for (row in allRows) {
                for (c in 0 until colCount) {
                    per[c] = maxOf(
                        per[c],
                        longestWordWidthPx(row.getOrElse(c) { "" }, cellMeasurer, cellTextStyle),
                    )
                }
            }
            per
        }
        androidx.compose.ui.layout.Layout(
            content = {
                for ((rowIndex, cells) in allRows.withIndex()) {
                    val isHeader = rowIndex == 0 && block.headers.isNotEmpty()
                    val isLastRow = rowIndex == totalRowCount - 1
                    for (colIndex in 0 until colCount) {
                        val isLastCol = colIndex == colCount - 1
                        // [T-android-table-align] Honour the separator row: `:---:`
                        // centres, `---:` right-aligns, everything else starts.
                        val cellAlign = block.alignments.getOrNull(colIndex) ?: MdTableAlign.START
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
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            contentAlignment = when (cellAlign) {
                                MdTableAlign.CENTER -> Alignment.Center
                                MdTableAlign.END -> Alignment.CenterEnd
                                MdTableAlign.START -> Alignment.CenterStart
                            },
                        ) {
                            val cellText = cells.getOrElse(colIndex) { "" }
                            MdText(
                                text = MarkdownParseCaches.inline(cellText, colors),
                                fontSize = 14.sp,
                                fontWeight = if (isHeader) FontWeight.SemiBold else null,
                                color = colors.text,
                                inlineContent = rememberKatexInlineContent(14.sp, MarkdownParseCaches.mathLatex(cellText)),
                                // Long-press selects the whole cell rather than a
                                // sentence-fragment of it: a cell holding "1,200"
                                // or "v1.2, beta" would otherwise stop at the
                                // comma, which is never what someone pressing a
                                // table cell is after.
                                isAtomicSelectionUnit = true,
                            )
                        }
                    }
                }
            },
        ) { measurables, _ ->
            val rowCount = allRows.size

            // Safe upper bound for intrinsic queries and constraint widths. Compose's
            // Constraints packs width into 18 bits, so values above ~262k will throw.
            // [T-android-table-col-cap-ios-parity] Cap each column at 5× the
            // viewport width, matching iOS [TableColumnCap] (SelectableMarkdownView
            // computeLayout: min(width * 5, requested)). The previous 1× cap forced
            // any long-text column to wrap at exactly one screen width, producing
            // tall many-line cells; iOS keeps such rows on one line and lets
            // horizontal scroll handle the overflow (tighter caps were reverted
            // there after user feedback 2026-05-13). 5× viewport (~5-7k px) stays
            // far below the 262k Constraints limit while still bounding
            // pathological intrinsics from unbroken strings.
            val maxCellWidth = (viewportWidthPx * 5).coerceAtLeast(1)

            // Pass 1: intrinsic widths (no measure() call) give each column its
            // natural width — the widest cell on one line.
            val natural = FloatArray(colCount)
            for (rowIdx in 0 until rowCount) {
                for (colIdx in 0 until colCount) {
                    val idx = rowIdx * colCount + colIdx
                    if (idx < measurables.size) {
                        val intrinsic = measurables[idx].maxIntrinsicWidth(0)
                            .coerceIn(0, maxCellWidth)
                        natural[colIdx] = maxOf(natural[colIdx], intrinsic.toFloat())
                    }
                }
            }

            // [T-android-table-width-by-content] Widths follow the CONTENT ratio
            // (port of upstream TableColumnPlanner): a column holding three times
            // the text is no longer squeezed to its neighbour's width, and the
            // table fills the viewport only when every column fits at a
            // comfortable width. All widths include the cell's horizontal padding.
            val plan = TableColumnPlanner.plan(
                columns = (0 until colCount).map { c ->
                    TableColumnPlanner.Column(
                        natural = natural[c] + cellHPaddingPx,
                        minUnbreakable = unbreakablePx.getOrElse(c) { 0f } + cellHPaddingPx,
                    )
                },
                availableWidth = viewportWidthPx.toFloat(),
            )
            val colWidths = IntArray(colCount) { c ->
                plan.widths.getOrElse(c) { 0f }.toInt().coerceIn(1, maxCellWidth)
            }

            // Pass 2: measure each cell exactly once, with its column's fixed width.
            val rowHeights = IntArray(rowCount)
            val placeables = Array(rowCount) { rowIdx ->
                Array(colCount) { colIdx ->
                    val idx = rowIdx * colCount + colIdx
                    val m = measurables[idx]
                    val colW = colWidths[colIdx].coerceIn(0, maxCellWidth)
                    val p = m.measure(
                        androidx.compose.ui.unit.Constraints.fixedWidth(colW)
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
                        p.place(x, y + (rowHeights[rowIdx] - p.height) / 2)
                        x += colWidths[colIdx]
                    }
                    y += rowHeights[rowIdx]
                }
            }
        }
        }
    }
}

