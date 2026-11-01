package app.codeedit.editor

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import app.codeedit.core.doc.Pos
import app.codeedit.core.edit.FoldFinder
import app.codeedit.core.edit.TextMetrics
import app.codeedit.highlight.LineTokens
import app.codeedit.highlight.SyntaxHighlighter
import app.codeedit.highlight.TokenMeta

/** All drawing of [EditorView]. No allocation per frame (paints, rects and buffers are reused). */
internal class EditorPainter(private val v: EditorView) {
    private val fill = Paint()
    private val line = Paint().apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val path = Path()
    private val buf = LineBuf()

    private var visLine = IntArray(128)
    private var visTop = FloatArray(128)
    private var visCount = 0

    // run state of drawText
    private var rs = -1; private var rc = 0; private var rcol = 0; private var rsty = 0; private var rInk = false
    private var cellMin = 0; private var cellMax = 0; private var x0 = 0f; private var base = 0f
    private val one = CharArray(2)

    fun draw(c: Canvas) {
        val s = v.session ?: return
        val col = v.colors
        c.drawColor(col.background)
        v.checkGutter()
        ViewportMath.normalize(s, v.layout, v.lineH)
        v.clampScrollX()

        val store = s.store
        val lineH = v.lineH
        val h = v.height.toFloat()
        val w = v.width.toFloat()
        val hl = s.highlighter
        val caret = s.selection.head
        val sel = s.selection

        // ---- collect visible blocks
        visCount = 0
        var ln = s.topLine
        var y = -s.topOffsetPx
        val last = store.lineCount - 1
        while (y < h && ln <= last) {
            if (visCount == visLine.size) { visLine = visLine.copyOf(visCount * 2); visTop = visTop.copyOf(visCount * 2) }
            visLine[visCount] = ln; visTop[visCount] = y; visCount++
            y += v.layout.rowCount(ln) * lineH
            ln = s.folds.nextVisible(ln)
        }

        // ---- highlight request + missing tokens check
        var missing = false
        if (hl != null && visCount > 0) {
            for (i in 0 until visCount) if (hl.tokensFor(visLine[i]) == null) { missing = true; break }
            v.requestHighlight(visLine[0], visLine[visCount - 1], missing)
        }

        // ---- text area
        c.save()
        c.clipRect(v.gutterW, 0f, w, h)
        var maxCells = 0
        for (i in 0 until visCount) {
            val l = visLine[i]
            val top = visTop[i]
            val len = buf.fill(store, l)
            val rows = v.layout.rowStarts(l)
            val toks = hl?.tokensFor(l)
            val matches = v.matchHighlighter?.matchesInLine(l)
            val isCaretLine = l == caret.line
            for (r in rows.indices) {
                val rowTop = top + r * lineH
                if (rowTop + lineH < 0f) continue
                if (rowTop > h) break
                val from = rows[r]
                val to = if (r + 1 < rows.size) rows[r + 1] else len
                drawRowBackgrounds(c, l, r, rows.size, from, to, len, rowTop, isCaretLine, sel.isEmpty, matches)
                val cells = drawRowText(c, hl, toks, from, to, rowTop)
                if (cells > maxCells) maxCells = cells
                if (r == rows.size - 1 && s.folds.isFoldedStart(l)) drawFoldChip(c, cells, rowTop)
            }
        }
        if (!v.layout.wrapping && visCount > 0) v.maxCellsSeen = maxCells
        drawCaret(c, s)
        c.restore()

        // ---- gutter
        drawGutter(c, s)

        // ---- overlays
        v.touch.drawOverlays(c, col)
        if (v.touch.needsAnimation()) v.postInvalidateOnAnimation()
    }

    // ------------------------------------------------------------------ backgrounds
    private fun cellsTo(from: Int, idx: Int): Int = TextMetrics.cells(buf.chars, from, idx, v.config.tabSize)

    private fun drawRowBackgrounds(c: Canvas, l: Int, r: Int, rowCount: Int, from: Int, to: Int, len: Int, rowTop: Float,
                                   isCaretLine: Boolean, selEmpty: Boolean, matches: IntArray?) {
        val s = v.session!!
        val col = v.colors
        val lineH = v.lineH
        val x0 = v.cellX(0)
        val w = v.width.toFloat()
        if (isCaretLine && selEmpty && v.config.highlightCurrentLine) {
            fill.color = col.lineHighlight
            c.drawRect(v.gutterW, rowTop, w, rowTop + lineH, fill)
        }
        // selection
        val sel = s.selection
        if (!sel.isEmpty) {
            val st = sel.start; val en = sel.end
            if (l in st.line..en.line) {
                val a = if (l == st.line) st.col else 0
                val b = if (l == en.line) en.col else len
                val ra = maxOf(a, from); val rb = minOf(b, to)
                val extendEol = l < en.line && r == rowCount - 1
                if (rb > ra || extendEol) {
                    val xa = x0 + cellsTo(from, ra.coerceIn(from, to)) * v.charW
                    var xb = x0 + cellsTo(from, rb.coerceIn(from, to)) * v.charW
                    if (extendEol) xb += v.charW * 0.6f
                    fill.color = col.selection
                    c.drawRect(xa, rowTop, maxOf(xb, xa + 2f), rowTop + lineH, fill)
                }
            }
        }
        // find matches
        if (matches != null && matches.isNotEmpty()) {
            val cur = v.matchHighlighter?.current
            var i = 0
            while (i + 1 < matches.size) {
                val mc = matches[i]; val ml = matches[i + 1]; i += 2
                val ra = maxOf(mc, from); val rb = minOf(mc + ml, to)
                if (rb <= ra) continue
                val xa = x0 + cellsTo(from, ra.coerceIn(from, to)) * v.charW
                val xb = x0 + cellsTo(from, rb.coerceIn(from, to)) * v.charW
                val isCur = cur != null && ((l == cur.start.line && mc == cur.start.col) ||
                    (l > cur.start.line && l <= cur.end.line && mc == 0))
                fill.color = if (isCur) col.findMatchCurrent else col.findMatch
                c.drawRect(xa, rowTop, maxOf(xb, xa + 2f), rowTop + lineH, fill)
            }
        }
        // bracket match
        val bp = v.bracketPair
        if (bp != null) {
            for (p in arrayOf(bp.first, bp.second)) {
                if (p.line == l && p.col in from until maxOf(to, from + 1) && (p.col < to || r == rowCount - 1)) {
                    val xa = x0 + cellsTo(from, p.col) * v.charW
                    fill.color = col.bracketMatch
                    c.drawRect(xa, rowTop, xa + v.charW, rowTop + lineH, fill)
                }
            }
        }
        // indent guides
        if (v.config.indentGuides && r == 0) drawGuides(c, from, to, rowTop)
    }

    private var guideCarry = 0

    private fun drawGuides(c: Canvas, from: Int, to: Int, rowTop: Float) {
        val tab = v.config.tabSize
        var cells = 0
        var i = from
        var blank = true
        while (i < to) {
            val ch = buf.chars[i]
            if (ch == ' ') cells++ else if (ch == '\t') cells = (cells / tab + 1) * tab else { blank = false; break }
            i++
        }
        if (blank) cells = guideCarry else guideCarry = cells
        if (cells <= 0) return
        val col = v.colors
        line.strokeWidth = maxOf(1f, v.density * 0.8f)
        line.color = col.indentGuide
        var k = 0
        val x0 = v.cellX(0)
        val left = v.gutterW
        while (k * tab < cells) {
            val x = x0 + k * tab * v.charW + 0.5f
            if (x > left) c.drawLine(x, rowTop, x, rowTop + v.lineH, line)
            k++
        }
    }

    // ------------------------------------------------------------------ text
    private fun metaColor(hl: SyntaxHighlighter?, meta: Int): Int {
        val map = hl?.colorMap ?: return v.colors.foreground
        val id = TokenMeta.foreground(meta)
        return if (id in map.indices && map[id] != 0) map[id] else v.colors.foreground
    }

    /** Draws chars [from,to) of the current buffer line; returns the number of cells the row occupies. */
    private fun drawRowText(c: Canvas, hl: SyntaxHighlighter?, toks: LineTokens?, from: Int, to: Int, rowTop: Float): Int {
        val chars = buf.chars
        val charW = v.charW
        val tab = v.config.tabSize
        val showWs = v.config.showWhitespace
        x0 = v.cellX(0)
        base = rowTop + v.textBaseline
        cellMin = Math.floor(((v.gutterW - x0) / charW).toDouble()).toInt() - 2
        cellMax = Math.ceil(((v.width - x0) / charW).toDouble()).toInt() + 2
        val fg = v.colors.foreground
        var color = fg; var style = 0
        var ti = 0
        var tokEnd = Int.MAX_VALUE
        if (toks != null && toks.count > 0) {
            while (ti + 1 < toks.count && toks.starts[ti + 1] <= from) ti++
            color = metaColor(hl, toks.meta[ti]); style = TokenMeta.fontStyle(toks.meta[ti])
            tokEnd = if (ti + 1 < toks.count) toks.starts[ti + 1] else Int.MAX_VALUE
        }
        rs = -1
        var cells = 0
        var i = from
        var lastCellX = 0
        while (i < to) {
            if (i >= tokEnd && toks != null) {
                flushRun(c, chars, i)
                while (ti + 1 < toks.count && toks.starts[ti + 1] <= i) ti++
                color = metaColor(hl, toks.meta[ti]); style = TokenMeta.fontStyle(toks.meta[ti])
                tokEnd = if (ti + 1 < toks.count) toks.starts[ti + 1] else Int.MAX_VALUE
            }
            if (cells > cellMax && !v.layout.wrapping) {
                // past the right edge: only the width of the row is still needed for horizontal scrolling
                cells += estimateRest(chars, i, to, tab)
                i = to
                break
            }
            val ch = chars[i]
            val code = ch.code
            if (code in 0x20..0x7E) {
                if (ch == ' ' && showWs) {
                    flushRun(c, chars, i)
                    drawGlyph(c, '·', cells, v.colors.whitespace, 0)
                    lastCellX = cells; cells++; i++
                    continue
                }
                if (rs < 0) { rs = i; rc = cells; rcol = color; rsty = style; rInk = false }
                if (ch != ' ') rInk = true
                lastCellX = cells
                cells++; i++
            } else {
                flushRun(c, chars, i)
                if (ch == '\t') {
                    val next = (cells / tab + 1) * tab
                    if (showWs) drawGlyph(c, '→', cells, v.colors.whitespace, 0)
                    lastCellX = cells; cells = next; i++
                } else if (code < 0x20 || code == 0x7F || (code in 0x80..0x9F)) {
                    drawGlyph(c, '·', cells, 0xFFE06C75.toInt(), 0)
                    lastCellX = cells; cells++; i++
                } else {
                    val n = TextMetrics.cpLen(chars, i, to)
                    val cp = if (n == 2) Character.toCodePoint(ch, chars[i + 1]) else code
                    val wcells = TextMetrics.cellWidth(cp)
                    if (wcells == 0) {
                        drawChars(c, chars, i, n, lastCellX, color, style)     // combining mark: over the previous cell
                    } else {
                        drawChars(c, chars, i, n, cells, color, style)
                        lastCellX = cells; cells += wcells
                    }
                    i += n
                }
            }
        }
        flushRun(c, chars, i)
        return cells
    }

    private fun estimateRest(chars: CharArray, from: Int, to: Int, tab: Int): Int {
        // cheap upper-ish estimate for the width of the unseen tail (exact for ASCII without tabs)
        var n = 0
        var i = from
        while (i < to && n < 2_000_000) { n += if (chars[i] == '\t') tab else 1; i++ }
        return n
    }

    private fun flushRun(c: Canvas, chars: CharArray, end: Int) {
        val start = rs
        if (start < 0) return
        rs = -1
        var a = start; var cellA = rc; var cnt = end - start
        if (cnt <= 0 || !rInk) return
        if (cellA < cellMin) { val skip = cellMin - cellA; a += skip; cellA += skip; cnt -= skip }
        if (cellA + cnt > cellMax) cnt = cellMax - cellA + 1
        if (cnt <= 0) return
        val p = if (rsty and TokenMeta.BOLD != 0) v.paintBold else v.paint
        p.color = rcol
        if (rsty and TokenMeta.ITALIC != 0) p.textSkewX = -0.2f
        if (rsty and TokenMeta.UNDERLINE != 0) p.isUnderlineText = true
        if (rsty and TokenMeta.STRIKE != 0) p.isStrikeThruText = true
        c.drawText(chars, a, cnt, x0 + cellA * v.charW, base, p)
        if (rsty and (TokenMeta.ITALIC or TokenMeta.UNDERLINE or TokenMeta.STRIKE) != 0) {
            p.textSkewX = 0f; p.isUnderlineText = false; p.isStrikeThruText = false
        }
    }

    private fun drawChars(c: Canvas, chars: CharArray, i: Int, n: Int, cell: Int, color: Int, style: Int) {
        if (cell < cellMin || cell > cellMax) return
        val p = if (style and TokenMeta.BOLD != 0) v.paintBold else v.paint
        p.color = color
        c.drawText(chars, i, n, x0 + cell * v.charW, base, p)
    }

    private fun drawGlyph(c: Canvas, ch: Char, cell: Int, color: Int, style: Int) {
        if (cell < cellMin || cell > cellMax) return
        one[0] = ch
        val p = v.paint
        p.color = color
        c.drawText(one, 0, 1, x0 + cell * v.charW, base, p)
    }

    private fun drawFoldChip(c: Canvas, cells: Int, rowTop: Float) {
        val x = v.cellX(cells) + v.charW * 0.8f
        val h = v.lineH * 0.62f
        val top = rowTop + (v.lineH - h) / 2f
        val wch = v.charW * 3.2f
        rect.set(x, top, x + wch, top + h)
        fill.color = v.colors.indentGuideActive
        c.drawRoundRect(rect, h / 3f, h / 3f, fill)
        fill.color = v.colors.lineNumberActive
        val cy = top + h / 2f
        val r = maxOf(1f, v.density * 1.1f)
        for (k in 0..2) c.drawCircle(x + wch * (0.25f + 0.25f * k), cy, r, fill)
    }

    // ------------------------------------------------------------------ caret
    private val rowCell = IntArray(1)

    private fun drawCaret(c: Canvas, s: app.codeedit.core.edit.EditorSession) {
        val sel = s.selection
        if (!sel.isEmpty || !v.caretVisible || !v.hasFocus()) return
        val head = s.clampPos(sel.head)
        val y0 = ViewportMath.yOfLineTop(s, v.layout, v.lineH, head.line, 400) ?: return
        if (s.folds.isHidden(head.line)) return
        val row = v.rowAndCells(head, rowCell)
        val top = y0 + row * v.lineH
        if (top + v.lineH < 0f || top > v.height) return
        val x = v.cellX(rowCell[0])
        val col = v.colors
        fill.color = col.cursor
        when (v.config.cursorStyle) {
            CursorStyle.BAR -> c.drawRect(x, top + 1f, x + maxOf(2f, v.density * 1.6f), top + v.lineH - 1f, fill)
            CursorStyle.UNDERLINE -> c.drawRect(x, top + v.lineH - maxOf(2f, v.density * 1.8f) - 1f, x + v.charW, top + v.lineH - 1f, fill)
            CursorStyle.BLOCK -> {
                fill.color = (col.cursor and 0x00FFFFFF) or 0x88000000.toInt()
                c.drawRect(x, top, x + v.charW, top + v.lineH, fill)
            }
        }
    }

    // ------------------------------------------------------------------ gutter
    private fun drawGutter(c: Canvas, s: app.codeedit.core.edit.EditorSession) {
        val col = v.colors
        val h = v.height.toFloat()
        fill.color = col.gutterBackground
        if (v.gutterW > 0f) c.drawRect(0f, 0f, v.gutterW, h, fill)
        val showNums = v.config.showLineNumbers
        val folding = v.config.folding
        if (!showNums && !folding) return
        val caretLine = s.selection.head.line
        val base = v.config.lineNumberBase
        val numRight = v.dp(6f) + v.lnDigits * v.lnCharW
        v.lnPaint.textAlign = Paint.Align.LEFT
        val store = s.store
        val tab = v.config.tabSize
        for (i in 0 until visCount) {
            val l = visLine[i]
            val top = visTop[i]
            if (top + v.lineH < 0f) continue
            if (showNums) {
                val str = (l + base).toString()
                val w = str.length * v.lnCharW
                v.lnPaint.color = if (l == caretLine) col.lineNumberActive else col.lineNumber
                val baseY = top + (v.lineH - (v.lnPaint.descent() - v.lnPaint.ascent())) / 2f - v.lnPaint.ascent()
                c.drawText(str, numRight - w, baseY, v.lnPaint)
            }
            if (folding) {
                val folded = s.folds.isFoldedStart(l)
                if (folded || FoldFinder.canFold(store, l, tab)) {
                    val cx = v.gutterW - v.foldColW / 2f - v.dp(1f)
                    val cy = top + v.lineH / 2f
                    val a = v.dp(3.2f)
                    line.color = if (l == caretLine) col.lineNumberActive else col.lineNumber
                    line.strokeWidth = maxOf(1.2f, v.density * 1.3f)
                    line.strokeCap = Paint.Cap.ROUND
                    path.reset()
                    if (folded) { path.moveTo(cx - a * 0.6f, cy - a); path.lineTo(cx + a * 0.6f, cy); path.lineTo(cx - a * 0.6f, cy + a) }
                    else { path.moveTo(cx - a, cy - a * 0.6f); path.lineTo(cx, cy + a * 0.6f); path.lineTo(cx + a, cy - a * 0.6f) }
                    c.drawPath(path, line)
                    line.strokeCap = Paint.Cap.BUTT
                }
            }
        }
    }
}
