package app.codeedit.editor

import app.codeedit.core.doc.*
import app.codeedit.core.edit.EditorSession
import org.junit.Assert.*
import org.junit.Test

class ViewportTests {
    private fun session(text: String): EditorSession {
        val d = EditDocument.fromString(text)
        return EditorSession("t", d, DocInfo(DocMode.EDIT, TextEncoding.UTF8, Eol.LF, false, text.length.toLong(), false))
    }
    private fun lines(n: Int) = (0 until n).joinToString("\n") { "line $it" }

    @Test fun wrapBreaksAfterSpacesAndRespectsCells() {
        val s = session("aaaa bbbb cccc dddd")
        val l = RowLayout(s.store); l.configure(10, 4)
        assertArrayEquals(intArrayOf(0, 10), l.rowStarts(0))   // "aaaa bbbb " | "cccc dddd" -> wait: break after space at 10
        l.configure(8, 4)
        assertArrayEquals(intArrayOf(0, 5, 10, 15), l.rowStarts(0))
        l.configure(0, 4)
        assertEquals(1, l.rowCount(0))
    }

    @Test fun wrapLongWordHardBreaksAndTabsAndWide() {
        val s = session("abcdefghijkl\n\t\tx\n中中中中中")
        val l = RowLayout(s.store); l.configure(5, 4)
        assertArrayEquals(intArrayOf(0, 5, 10), l.rowStarts(0))
        assertEquals(intArrayOf(0, 1).toList(), l.rowStarts(1).toList())      // tab(4) | tab(4)+x(1)=5
        assertEquals(intArrayOf(0, 2, 4).toList(), l.rowStarts(2).toList())   // 2 wide chars (4 cells) per row
        assertEquals(0, RowLayout.rowOfCol(l.rowStarts(0), 4)); assertEquals(1, RowLayout.rowOfCol(l.rowStarts(0), 5))
        assertEquals(2, RowLayout.rowOfCol(l.rowStarts(0), 12))
    }

    @Test fun veryLongLinesWrapOnAGridWithoutScanningTheText() {
        val n = 1_000_000
        val s = session("x".repeat(n) + "\nshort line")
        val l = RowLayout(s.store); l.configure(50, 4)
        val t0 = System.nanoTime()
        val starts = l.rowStarts(0)
        assertTrue("fast", (System.nanoTime() - t0) / 1_000_000 < 500)
        assertEquals(n / 50, starts.size)
        assertEquals(0, starts[0]); assertEquals(50, starts[1]); assertEquals(n - 50, starts.last())
        assertEquals(0, RowLayout.rowOfCol(starts, 49)); assertEquals(1, RowLayout.rowOfCol(starts, 50)); assertEquals(starts.size - 1, RowLayout.rowOfCol(starts, n - 1))
        assertEquals(1, l.rowCount(1))
        // a line just under the scan limit still gets word-aware wrapping
        val s2 = session(("ab ".repeat(RowLayout.MAX_SCAN_CHARS / 3 - 1)))
        val l2 = RowLayout(s2.store); l2.configure(10, 4)
        assertEquals(9, l2.rowStarts(0)[1] - l2.rowStarts(0)[0])   // breaks after the last space that fits: "ab ab ab " = 9 cells
    }

    @Test fun absurdLinesStaySingleRowInsteadOfAllocatingMillionsOfRows() {
        val s = session("y".repeat(RowLayout.MAX_SCAN_CHARS + 1))
        val l = RowLayout(s.store); l.configure(2, 4)             // 50 001 rows: still a grid
        assertEquals(50_001, l.rowCount(0))
    }

    @Test fun emptyAndShortLinesAreSingleRow() {
        val s = session("\nab")
        val l = RowLayout(s.store); l.configure(10, 4)
        assertEquals(1, l.rowCount(0)); assertEquals(1, l.rowCount(1))
    }

    @Test fun scrollForwardAndBackAcrossLines() {
        val s = session(lines(100)); val l = RowLayout(s.store)
        ViewportMath.scrollBy(s, l, 20f, 65f)
        assertEquals(3, s.topLine); assertEquals(5f, s.topOffsetPx, 0.001f)
        ViewportMath.scrollBy(s, l, 20f, -25f)
        assertEquals(2, s.topLine); assertEquals(0f, s.topOffsetPx, 0.001f)
    }

    @Test fun scrollClampsAtBothEnds() {
        val s = session(lines(10)); val l = RowLayout(s.store)
        ViewportMath.scrollBy(s, l, 20f, -500f)
        assertEquals(0, s.topLine); assertEquals(0f, s.topOffsetPx, 0f)
        ViewportMath.scrollBy(s, l, 20f, 100_000f)
        assertEquals(9, s.topLine); assertEquals(0f, s.topOffsetPx, 0f)
        ViewportMath.scrollBy(s, l, 20f, 7f)
        assertEquals(9, s.topLine); assertEquals(0f, s.topOffsetPx, 0f)
    }

    @Test fun hugeJumpIsApproximateButBounded() {
        val s = session(lines(5000)); val l = RowLayout(s.store)
        ViewportMath.scrollBy(s, l, 20f, 20f * 3000)
        assertEquals(3000, s.topLine)
        ViewportMath.scrollBy(s, l, 20f, -20f * 2500)
        assertEquals(500, s.topLine)
    }

    @Test fun scrollSkipsFoldedLines() {
        val s = session(lines(30)); val l = RowLayout(s.store)
        s.folds.fold(2, 9)
        ViewportMath.scrollBy(s, l, 20f, 40f)       // lines 0,1 -> top is 2 (folded start)
        assertEquals(2, s.topLine)
        ViewportMath.scrollBy(s, l, 20f, 20f)       // next visible after folded start 2 is 10
        assertEquals(10, s.topLine)
        ViewportMath.scrollBy(s, l, 20f, -20f)
        assertEquals(2, s.topLine)
        assertEquals(20f, ViewportMath.yOfLineTop(s, l, 20f, 10)!!, 0.001f)
        assertEquals(0f, ViewportMath.yOfLineTop(s, l, 20f, 5)!!, 0.001f)   // hidden line maps to its fold anchor
    }

    @Test fun hitTestWithWrap() {
        val s = session("aaaa bbbb cccc dddd\nx\ny"); val l = RowLayout(s.store); l.configure(8, 4)
        val h = ViewportMath.hit(s, l, 20f, 50f)       // rows of line0: 0..3 (y 0..80)
        assertEquals(0, h.line); assertEquals(2, h.row)
        val h2 = ViewportMath.hit(s, l, 20f, 85f)
        assertEquals(1, h2.line); assertEquals(0, h2.row)
        val h3 = ViewportMath.hit(s, l, 20f, 10_000f)
        assertEquals(2, h3.line); assertTrue(h3.beyondEnd)
        val h4 = ViewportMath.hit(s, l, 20f, -50f)
        assertEquals(0, h4.line); assertEquals(0, h4.row)
    }

    @Test fun ensureVisibleScrollsMinimally() {
        val s = session(lines(200)); val l = RowLayout(s.store)
        assertFalse(ViewportMath.ensureRowVisible(s, l, 20f, 400f, 5, 0, 0f, 0f))
        assertTrue(ViewportMath.ensureRowVisible(s, l, 20f, 400f, 30, 0, 0f, 0f))
        assertEquals(11, s.topLine); assertEquals(0f, s.topOffsetPx, 0.001f)       // line 30 bottom == view bottom
        assertTrue(ViewportMath.ensureRowVisible(s, l, 20f, 400f, 199, 0, 0f, 0f))
    }

    @Test fun thumbMapping() {
        val s = session(lines(1000))
        s.topLine = 500
        assertEquals(0.5005f, ViewportMath.fraction(s, 1000), 0.001f)
        assertEquals(999, ViewportMath.lineForFraction(1f, 1000, 1000))
        assertEquals(999, ViewportMath.lineForFraction(1f, 1000, 40_000_000))   // clamped to known lines
        assertEquals(0, ViewportMath.lineForFraction(0f, 1000, 1000))
    }
}
