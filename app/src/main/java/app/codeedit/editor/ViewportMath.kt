package app.codeedit.editor

import app.codeedit.core.edit.EditorSession

/**
 * Vertical scrolling arithmetic for the editor: scroll position is (topLine, topOffsetPx) stored in EditorSession,
 * so it works for documents with millions of lines (no pixel total is ever needed). Pure Kotlin.
 */
object ViewportMath {
    /** Moves beyond this many rows are done approximately (every line = 1 row) so a fling over 40M lines stays O(1)/O(folds). */
    const val JUMP_ROWS = 200

    class Hit(@JvmField val line: Int, @JvmField val row: Int, @JvmField val lineTopY: Float, @JvmField val beyondEnd: Boolean)

    @JvmStatic fun blockH(layout: RowLayout, line: Int, lineH: Float): Float = layout.rowCount(line) * lineH

    @JvmStatic fun lastLine(s: EditorSession): Int = (s.store.lineCount - 1).coerceAtLeast(0)

    /** Makes (topLine, offset) valid after external changes (edits, mode switch, relayout). */
    @JvmStatic fun normalize(s: EditorSession, layout: RowLayout, lineH: Float) {
        val last = lastLine(s)
        s.topLine = s.folds.visibleAnchor(s.topLine.coerceIn(0, last))
        val maxOff = (blockH(layout, s.topLine, lineH) - lineH).coerceAtLeast(0f)
        s.topOffsetPx = s.topOffsetPx.coerceIn(0f, maxOff)
    }

    @JvmStatic fun scrollBy(s: EditorSession, layout: RowLayout, lineH: Float, dy: Float) {
        if (dy == 0f || lineH <= 0f) return
        val last = lastLine(s)
        val f = s.folds
        var line = f.visibleAnchor(s.topLine.coerceIn(0, last))
        var off = s.topOffsetPx + dy
        if (off >= 0f) {
            if (off > JUMP_ROWS * lineH) {
                var n = (off / lineH).toLong()
                if (f.isEmpty) line = minOf(last.toLong(), line + n).toInt()
                else while (n > 0) { val nx = f.nextVisible(line); if (nx > last) break; line = nx; n-- }
                line = f.visibleAnchor(line)
                off = 0f
            } else {
                while (true) {
                    val h = blockH(layout, line, lineH)
                    if (off < h) break
                    val nx = f.nextVisible(line)
                    if (nx > last) { off = minOf(off, (h - lineH).coerceAtLeast(0f)); break }
                    off -= h; line = nx
                }
            }
        } else {
            if (-off > JUMP_ROWS * lineH) {
                var n = (-off / lineH).toLong()
                if (f.isEmpty) line = maxOf(0L, line - n).toInt()
                else while (n > 0) { val pv = f.prevVisible(line); if (pv < 0) break; line = pv; n-- }
                line = f.visibleAnchor(line)
                off = 0f
            } else {
                while (off < 0f) {
                    val pv = f.prevVisible(line)
                    if (pv < 0) { off = 0f; break }
                    line = pv; off += blockH(layout, line, lineH)
                }
            }
        }
        if (f.nextVisible(line) > last) off = minOf(off, (blockH(layout, line, lineH) - lineH).coerceAtLeast(0f))
        s.topLine = line; s.topOffsetPx = off
    }

    /** Top y (viewport coordinates) of the block of [line] if it is within [maxSteps] visible lines of the viewport top, else null. */
    @JvmStatic fun yOfLineTop(s: EditorSession, layout: RowLayout, lineH: Float, line: Int, maxSteps: Int = 4000): Float? {
        val f = s.folds
        val target = f.visibleAnchor(line)
        var y = -s.topOffsetPx
        var l = s.topLine
        var steps = 0
        if (target >= l) {
            while (l < target) {
                y += blockH(layout, l, lineH)
                l = f.nextVisible(l)
                if (++steps > maxSteps || l > lastLine(s)) return null
            }
            return y
        }
        while (l > target) {
            l = f.prevVisible(l)
            if (l < 0 || ++steps > maxSteps) return null
            y -= blockH(layout, l, lineH)
        }
        return y
    }

    /** The line/row under viewport y. Clamps to the first/last line. */
    @JvmStatic fun hit(s: EditorSession, layout: RowLayout, lineH: Float, y: Float): Hit {
        val f = s.folds
        val last = lastLine(s)
        var line = f.visibleAnchor(s.topLine.coerceIn(0, last))
        var top = -s.topOffsetPx
        var steps = 0
        while (y < top) {
            val pv = f.prevVisible(line)
            if (pv < 0 || ++steps > 20_000) return Hit(line, 0, top, false)
            line = pv; top -= blockH(layout, line, lineH)
        }
        while (true) {
            val rows = layout.rowCount(line)
            val h = rows * lineH
            if (y < top + h) return Hit(line, ((y - top) / lineH).toInt().coerceIn(0, rows - 1), top, false)
            val nx = f.nextVisible(line)
            if (nx > last || ++steps > 20_000) return Hit(line, rows - 1, top, true)
            top += h; line = nx
        }
    }

    /** Scrolls so that [line] is vertically centered in a viewport of [viewH] px. */
    @JvmStatic fun centerOn(s: EditorSession, layout: RowLayout, lineH: Float, viewH: Float, line: Int) {
        val last = lastLine(s)
        s.topLine = s.folds.visibleAnchor(line.coerceIn(0, last)); s.topOffsetPx = 0f
        scrollBy(s, layout, lineH, -((viewH - lineH) / 2f).coerceAtLeast(0f))
    }

    /** Adjust scroll minimally so that [line]'s row [row] lies inside [marginTop, viewH - marginBottom]. Returns true if scrolled. */
    @JvmStatic fun ensureRowVisible(s: EditorSession, layout: RowLayout, lineH: Float, viewH: Float, line: Int, row: Int,
                                    marginTop: Float, marginBottom: Float): Boolean {
        val y0 = yOfLineTop(s, layout, lineH, line)
        if (y0 == null) { centerOn(s, layout, lineH, viewH, line); return true }
        val top = y0 + row * lineH
        val bottom = top + lineH
        val limitBottom = viewH - marginBottom
        return when {
            top < marginTop -> { scrollBy(s, layout, lineH, top - marginTop); true }
            bottom > limitBottom -> { scrollBy(s, layout, lineH, bottom - limitBottom); true }
            else -> false
        }
    }

    // ---- fast-scroll thumb ----

    /** 0..1 position of the viewport inside the document, by line index. */
    @JvmStatic fun fraction(s: EditorSession, totalLines: Long): Float {
        val span = (totalLines - 1).coerceAtLeast(1L)
        return (s.topLine.toDouble() / span.toDouble()).coerceIn(0.0, 1.0).toFloat()
    }

    @JvmStatic fun lineForFraction(frac: Float, lineCount: Int, totalLines: Long): Int {
        val span = (totalLines - 1).coerceAtLeast(0L)
        val l = (frac.coerceIn(0f, 1f).toDouble() * span.toDouble()).toLong()
        return l.coerceIn(0L, (lineCount - 1).coerceAtLeast(0).toLong()).toInt()
    }
}
