/*
 * T194 part-2 — native Android terminal View with long-press selection +
 * floating ActionMode, ClipboardManager copy. Architecture inspired by
 * Termux terminal-view (Apache-2.0) — long-press → setInitialTextSelection
 * → startActionMode(TYPE_FLOATING) → onActionItemClicked.copy →
 * ClipboardManager.setPrimaryClip — but reimplemented from architecture
 * description against our existing TerminalEmulator/TerminalBuffer; no
 * source copied.
 *
 * Renderer is a port of TerminalCanvasView.kt (Compose Canvas → onDraw)
 * preserving cell paint, ANSI styling, cursor blink, and the new T194
 * selection-rect overlay. Vertical scroll → emulator.scrollOffset matches
 * the existing fling-aware drag logic minus the Compose decay animator
 * (Android's OverScroller would re-introduce that — out of scope for
 * part-2; users still get manual drag scrollback).
 */
package com.openminis.app.ui.terminal.canvas

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.view.ActionMode
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.openminis.app.ui.terminal.emulator.CursorShape
import com.openminis.app.ui.terminal.emulator.TerminalCell
import com.openminis.app.ui.terminal.emulator.TerminalEmulator
import com.openminis.app.ui.terminal.emulator.TerminalPalette
import com.openminis.app.ui.terminal.emulator.TextAttributes
import com.openminis.app.ui.terminal.rememberJetBrainsMonoTypeface

/**
 * Compose wrapper around [TerminalNativeView]. Drop-in replacement for
 * [TerminalCanvasView] — same parameter shape so [TerminalScreen] can
 * swap the call site by one identifier. Uses our bundled JetBrains Mono
 * typeface so glyph metrics match the old canvas-based renderer.
 */
@Composable
fun TerminalNativeViewCompose(
    emulator: TerminalEmulator,
    modifier: Modifier = Modifier,
    fontSizeSp: Float = 13f,
    onResize: (cols: Int, rows: Int) -> Unit,
    onTap: () -> Unit = {},
) {
    val typeface = rememberJetBrainsMonoTypeface()
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TerminalNativeView(ctx, typeface).apply {
                setFontSizeSp(fontSizeSp)
                attachEmulator(emulator, onResize, onTap)
            }
        },
        update = { view ->
            view.setFontSizeSp(fontSizeSp)
            view.attachEmulator(emulator, onResize, onTap)
        },
    )
}

class TerminalNativeView @JvmOverloads constructor(
    context: Context,
    private val typeface: Typeface = Typeface.MONOSPACE,
) : View(context) {

    private var emulator: TerminalEmulator? = null
    private var onResize: (cols: Int, rows: Int) -> Unit = { _, _ -> }
    private var onTap: () -> Unit = {}

    private val basePaint = Paint().apply {
        this.typeface = this@TerminalNativeView.typeface
        textSize = 13f * resources.displayMetrics.scaledDensity
        isAntiAlias = true
        isSubpixelText = true
    }
    private var cellWidth: Float = 0f
    private var cellHeight: Float = 0f
    private var baselineOffset: Float = 0f
    private var cols: Int = 80
    private var rows: Int = 24

    // Cursor blink — flips every 500 ms.
    private var cursorVisible: Boolean = true
    private val blinkRunnable = object : Runnable {
        override fun run() {
            cursorVisible = !cursorVisible
            invalidate()
            postDelayed(this, 500)
        }
    }

    // ── Selection state ────────────────────────────────────────────────────
    private var actionMode: ActionMode? = null
    private var lastSelEndX: Int = 0   // pixels — for ActionMode anchor rect
    private var lastSelEndY: Int = 0

    init {
        isFocusable = true
        isClickable = true
        recomputeMetrics()
    }

    fun attachEmulator(
        emulator: TerminalEmulator,
        onResize: (Int, Int) -> Unit,
        onTap: () -> Unit,
    ) {
        this.emulator = emulator
        this.onResize = onResize
        this.onTap = onTap
        invalidate()
    }

    fun setFontSizeSp(sp: Float) {
        basePaint.textSize = sp * resources.displayMetrics.scaledDensity
        recomputeMetrics()
        requestLayout()
        invalidate()
    }

    private fun recomputeMetrics() {
        cellWidth = basePaint.measureText("M")
        val fm = basePaint.fontMetrics
        cellHeight = fm.bottom - fm.top
        baselineOffset = -fm.top
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(blinkRunnable)
        // Subscribe to emulator version state — invalidate() each tick.
        // Compose-version callback is awkward here; cheap polling on the
        // animator pulse covers it. Render diffs are cheap (whole-view
        // invalidate, GPU just re-rasters cells).
        post(emulatorTick)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(blinkRunnable)
        removeCallbacks(emulatorTick)
        actionMode?.finish()
    }

    private var lastEmulatorVersion: Long = -1L
    private val emulatorTick = object : Runnable {
        override fun run() {
            val v = emulator?.version?.value ?: -1L
            if (v != lastEmulatorVersion) {
                lastEmulatorVersion = v
                invalidate()
            }
            postDelayed(this, 16)  // ~60 fps redraw poll
        }
    }

    // ── Sizing ─────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val newCols = maxOf(1, (w / cellWidth).toInt())
        val newRows = maxOf(1, (h / cellHeight).toInt())
        if (newCols != cols || newRows != rows) {
            cols = newCols
            rows = newRows
            onResize(newCols, newRows)
        }
    }

    // ── Touch handling ─────────────────────────────────────────────────────

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // Tapping outside an active selection cancels it; otherwise pass focus.
            if (emulator?.selectionRect?.value != null) {
                emulator?.clearSelectionRect()
                actionMode?.finish()
                return true
            }
            onTap()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            startSelectionAt(e.x, e.y)
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float,
        ): Boolean {
            // distanceY is e1.y - e2.y in Android's GestureDetector — i.e.
            // positive when the finger moves up. Scroll back through history
            // means moving content down on screen, which from the user's
            // POV is dragging the finger down, distanceY < 0.
            val em = emulator ?: return false
            // Reverse sign: dragging down (distanceY < 0) increases scrollOffset.
            val rowDelta = (-distanceY / cellHeight).toInt()
            if (rowDelta != 0) {
                em.scrollOffset = (em.scrollOffset + rowDelta).coerceAtLeast(0)
            }
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    // ── Selection lifecycle ───────────────────────────────────────────────

    private fun startSelectionAt(px: Float, py: Float) {
        val em = emulator ?: return
        if (cellWidth <= 0f || cellHeight <= 0f) return
        val col = (px / cellWidth).toInt().coerceIn(0, cols - 1)
        val row = (py / cellHeight).toInt().coerceIn(0, rows - 1)

        // Word-expand on whitespace boundaries.
        val (sx, ex) = wordExpand(col, row)
        em.setSelectionRect(sx, row, ex, row)
        lastSelEndX = ((ex + 1) * cellWidth).toInt()
        lastSelEndY = ((row + 1) * cellHeight).toInt()

        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        startActionModeFloating()
    }

    private fun wordExpand(col: Int, row: Int): Pair<Int, Int> {
        val em = emulator ?: return col to col
        val lines = em.visibleLines()
        if (row !in lines.indices) return col to col
        val line = lines[row]
        if (col !in line.indices) return col to col

        fun isWordChar(c: Int): Boolean {
            // Letters, digits, and common URL punctuation.
            val ch = c.toChar()
            return ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch == '.' ||
                ch == '/' || ch == ':' || ch == '?' || ch == '&' || ch == '=' ||
                ch == '+' || ch == '%' || ch == '#' || ch == '~' || ch == '@'
        }

        if (!isWordChar(line[col].char)) return col to col
        var sx = col
        var ex = col
        while (sx > 0 && isWordChar(line[sx - 1].char)) sx--
        while (ex < line.size - 1 && isWordChar(line[ex + 1].char)) ex++
        return sx to ex
    }

    private fun startActionModeFloating() {
        if (actionMode != null) return
        val callback = object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(Menu.NONE, MENU_COPY, 0, android.R.string.copy)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                menu.add(Menu.NONE, MENU_SELECT_ALL, 1, android.R.string.selectAll)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                return true
            }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                val em = emulator ?: return false
                return when (item.itemId) {
                    MENU_COPY -> {
                        val sel = em.selectionRect.value
                        if (sel != null) {
                            val text = em.getSelectedText(sel[0], sel[1], sel[2], sel[3])
                            if (text.isNotEmpty()) {
                                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cb.setPrimaryClip(ClipData.newPlainText("Minis Shell", text))
                            }
                        }
                        mode.finish()
                        true
                    }
                    MENU_SELECT_ALL -> {
                        em.setSelectionRect(0, 0, cols - 1, rows - 1)
                        lastSelEndX = (cols * cellWidth).toInt()
                        lastSelEndY = (rows * cellHeight).toInt()
                        true
                    }
                    else -> false
                }
            }
            override fun onDestroyActionMode(mode: ActionMode) {
                actionMode = null
                emulator?.clearSelectionRect()
            }
            override fun onGetContentRect(mode: ActionMode, view: View?, outRect: Rect) {
                val em = emulator
                val sel = em?.selectionRect?.value
                if (sel == null) {
                    super.onGetContentRect(mode, view, outRect)
                    return
                }
                // Anchor toolbar above the bottom-right corner of the selection.
                val (sx, sy, ex, ey) = if (sel[1] < sel[3] || (sel[1] == sel[3] && sel[0] <= sel[2])) {
                    intArrayOf(sel[0], sel[1], sel[2], sel[3])
                } else {
                    intArrayOf(sel[2], sel[3], sel[0], sel[1])
                }.let { listOf(it[0], it[1], it[2], it[3]) }
                outRect.set(
                    (sx * cellWidth).toInt(),
                    (sy * cellHeight).toInt(),
                    ((ex + 1) * cellWidth).toInt(),
                    ((ey + 1) * cellHeight).toInt(),
                )
            }
        }
        actionMode = startActionMode(callback, ActionMode.TYPE_FLOATING)
    }

    // ── Render ────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val em = emulator ?: return
        val lines = em.visibleLines()

        // Clear background — matches default palette.
        canvas.drawColor(TerminalPalette.defaultBackground.toArgbInt())

        for (r in lines.indices) {
            val row = lines[r]
            val y = r * cellHeight
            for (c in row.indices) {
                val cell = row[c]
                if (cell.isWideTrailer) continue
                drawCellNative(canvas, basePaint, cell, c * cellWidth, y, cellWidth, cellHeight, baselineOffset)
            }
        }

        // Selection highlight.
        val sel = em.selectionRect.value
        if (sel != null) {
            val (sx, sy, ex, ey) = if (sel[1] < sel[3] || (sel[1] == sel[3] && sel[0] <= sel[2])) {
                intArrayOf(sel[0], sel[1], sel[2], sel[3])
            } else {
                intArrayOf(sel[2], sel[3], sel[0], sel[1])
            }.let { listOf(it[0], it[1], it[2], it[3]) }
            val selPaint = Paint().apply {
                color = android.graphics.Color.argb(0x66, 0x33, 0x99, 0xFF)
                style = Paint.Style.FILL
            }
            for (r in sy..ey) {
                if (r !in 0 until rows) continue
                val cStart = if (r == sy) sx else 0
                val cEnd = if (r == ey) ex + 1 else cols
                if (cEnd <= cStart) continue
                canvas.drawRect(
                    cStart * cellWidth, r * cellHeight,
                    cEnd * cellWidth, (r + 1) * cellHeight,
                    selPaint,
                )
            }
        }

        // Cursor (only when viewing live tail).
        if (em.cursorVisible && cursorVisible && em.scrollOffset == 0) {
            val (cc, cr) = em.cursorPos()
            if (cr in 0 until rows && cc in 0 until cols) {
                val cx = cc * cellWidth
                val cy = cr * cellHeight
                val cursorPaint = Paint().apply {
                    color = TerminalPalette.defaultForeground.toArgbInt()
                    style = Paint.Style.FILL
                }
                when (em.cursorShape) {
                    CursorShape.BLOCK -> {
                        canvas.drawRect(cx, cy, cx + cellWidth, cy + cellHeight, cursorPaint)
                        val cell = lines.getOrNull(cr)?.getOrNull(cc)
                        if (cell != null && cell.char != ' '.code) {
                            val textPaint = Paint(basePaint).apply {
                                color = TerminalPalette.defaultBackground.toArgbInt()
                            }
                            canvas.drawText(
                                String(intArrayOf(cell.char), 0, 1),
                                cx, cy + baselineOffset, textPaint,
                            )
                        }
                    }
                    CursorShape.UNDERLINE -> canvas.drawRect(
                        cx, cy + cellHeight - 2f,
                        cx + cellWidth, cy + cellHeight, cursorPaint,
                    )
                    CursorShape.BAR -> canvas.drawRect(
                        cx, cy, cx + 2f, cy + cellHeight, cursorPaint,
                    )
                }
            }
        }
    }

    companion object {
        private const val MENU_COPY = 1
        private const val MENU_SELECT_ALL = 2
    }
}

// `androidx.compose.ui.graphics.Color → toArgb()` would pull a Compose
// dependency we don't need here; do the conversion inline.
private fun androidx.compose.ui.graphics.Color.toArgbInt(): Int =
    android.graphics.Color.argb(
        (alpha * 255f).toInt(),
        (red * 255f).toInt(),
        (green * 255f).toInt(),
        (blue * 255f).toInt(),
    )

private fun drawCellNative(
    canvas: Canvas,
    basePaint: Paint,
    cell: TerminalCell,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    baselineOffset: Float,
) {
    val attrs = cell.attributes
    val inverse = attrs.has(TextAttributes.INVERSE)
    val bold = attrs.has(TextAttributes.BOLD)
    val fgColor = TerminalPalette.resolve(
        if (inverse) cell.background else cell.foreground,
        isForeground = true,
        bold = bold,
    )
    val bgColor = TerminalPalette.resolve(
        if (inverse) cell.foreground else cell.background,
        isForeground = false,
    )
    val cellW = if (cell.width == 2) w * 2 else w

    if (bgColor != TerminalPalette.defaultBackground) {
        val bgPaint = Paint().apply {
            color = bgColor.toArgbInt()
            style = Paint.Style.FILL
        }
        canvas.drawRect(x, y, x + cellW, y + h, bgPaint)
    }

    val ch = cell.char
    if (ch == ' '.code &&
        !attrs.has(TextAttributes.UNDERLINE) &&
        !attrs.has(TextAttributes.STRIKETHROUGH)
    ) return

    val glyphPaint = Paint(basePaint).apply {
        color = fgColor.toArgbInt()
        isFakeBoldText = bold
        val italic = attrs.has(TextAttributes.ITALIC)
        val base = basePaint.typeface ?: Typeface.MONOSPACE
        typeface = when {
            bold && italic -> Typeface.create(base, Typeface.BOLD_ITALIC)
            bold -> Typeface.create(base, Typeface.BOLD)
            italic -> Typeface.create(base, Typeface.ITALIC)
            else -> base
        }
        if (attrs.has(TextAttributes.UNDERLINE)) isUnderlineText = true
        if (attrs.has(TextAttributes.STRIKETHROUGH)) isStrikeThruText = true
        if (attrs.has(TextAttributes.DIM)) alpha = 128
        if (attrs.has(TextAttributes.HIDDEN)) alpha = 0
    }
    canvas.drawText(String(intArrayOf(ch), 0, 1), x, y + baselineOffset, glyphPaint)
}
