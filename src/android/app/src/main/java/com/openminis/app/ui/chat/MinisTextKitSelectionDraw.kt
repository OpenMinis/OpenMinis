package com.openminis.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextLayoutResult

/**
 * Rendering side of MinisTextKit selection: shard registration lifetime and
 * the per-shard highlight drawing. Split out of MinisTextKitSelection.kt
 * (which keeps the controller state) so the controller file stays under its
 * architecture-policy baseline; the two files change for different reasons —
 * state/ordering logic vs. draw mechanics.
 */

/**
 * Helper for shard owners: registers the shard with the ambient controller
 * (if any) for the lifetime of the call site, then unregisters on dispose.
 *
 * Pass the *currently up-to-date* [TextShard] every time — the helper holds
 * the reference under the same id, replacing on each recomposition so
 * position-in-window callbacks and TextLayoutResult always reflect the
 * latest measurement.
 */
@Composable
fun RegisterSelectionShard(shard: TextShard?) {
    val controller = LocalMinisSelectionController.current ?: return
    if (shard == null) return
    DisposableEffect(controller, shard.id, shard) {
        controller.register(shard)
        onDispose { controller.unregister(shard.id) }
    }
}

/**
 * Draw the active selection's highlight rectangles inside a single shard's
 * DrawScope. Call from a `Modifier.drawBehind` on the same node whose
 * [TextLayoutResult] backed the shard registration — the highlight is drawn
 * in the shard's local coordinate space and only the portion of the
 * selection that falls inside this shard.
 */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSelectionForShard(
    shardId: TextShardId,
    result: TextLayoutResult,
    selection: TextSelection,
    controller: SelectionController?,
    color: androidx.compose.ui.graphics.Color,
) {
    val laidOutText = result.layoutInput.text.text
    val maxOffset = laidOutText.length
    val lineCount = result.lineCount
    if (maxOffset == 0 || lineCount == 0) return

    // [T-android-selection-offscreen-order] (GH#296) When the order is
    // unknown, keep the user's own direction (start, end). This used to
    // return (end, start) for ANY cross-shard pair, inverting the range.
    val ordered = controller?.orderedEndpoints(selection) ?: run {
        val a = selection.start
        val b = selection.end
        if (a.shard == b.shard && a.charOffset > b.charOffset) b to a else a to b
    }
    val (first, last) = ordered

    // Decide the [from, to] character range within this shard.
    val isFirstHere = first.shard == shardId
    val isLastHere = last.shard == shardId
    if (!isFirstHere && !isLastHere) {
        // This shard sits BETWEEN the two endpoints — only include it if
        // the selection actually spans across us. Use shard order via the
        // controller's snapshot of registered shards' window-y positions.
        if (controller == null) {
            return
        }
        val between = controller.isShardBetween(first.shard, last.shard, shardId)
        if (!between) return
    }

    val from = if (isFirstHere) first.charOffset.coerceIn(0, maxOffset) else 0
    val to = if (isLastHere) last.charOffset.coerceIn(0, maxOffset) else maxOffset
    val lo = minOf(from, to)
    val hi = maxOf(from, to)
    if (hi <= lo) return

    val startLine = result.getLineForOffset(lo).coerceIn(0, lineCount - 1)
    val endLine = result.getLineForOffset((hi - 1).coerceAtLeast(0)).coerceIn(0, lineCount - 1)
    if (endLine < startLine) return

    for (line in startLine..endLine) {
        val lineStart = if (line == startLine) lo else result.getLineStart(line)
        val lineEndRaw = if (line == endLine) hi else result.getLineEnd(line)
        val lineEnd = lineEndRaw.coerceAtMost(maxOffset)
        if (lineEnd <= lineStart) continue
        var left = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        for (offset in lineStart until lineEnd) {
            val box = result.getBoundingBox(offset)
            if (box.width <= 0f) continue
            if (box.left < left) left = box.left
            if (box.right > right) right = box.right
        }
        if (!left.isFinite() || right <= left) continue
        // If this is the last visible line of the selection in this shard
        // and we DON'T contain the final endpoint, extend the highlight
        // to the line's right edge so cross-shard selections feel continuous.
        val isFinalLineHere = line == endLine && isLastHere
        val drawRight = if (!isFinalLineHere) {
            val lineRight = result.getLineRight(line)
            maxOf(right, lineRight)
        } else right
        val top = result.getLineTop(line)
        val bottom = result.getLineBottom(line)
        drawRect(
            color = color,
            topLeft = Offset(left, top),
            size = androidx.compose.ui.geometry.Size(drawRight - left, bottom - top),
        )
    }
}
