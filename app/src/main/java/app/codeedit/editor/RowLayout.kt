package app.codeedit.editor

import app.codeedit.core.doc.LineStore
import app.codeedit.core.edit.TextMetrics

/**
 * Visual rows of logical lines. Without wrapping every line is one row. With wrapping a line is split on a cell
 * grid (preferring to break after a space/tab/'-'); row starts are char indexes. Pure Kotlin (no android.*).
 * UI thread only.
 */
class RowLayout(private val store: LineStore) {
    var wrapCells: Int = 0; private set
    var tabSize: Int = 4; private set
    val wrapping: Boolean get() = wrapCells > 0

    private val cache = object : LinkedHashMap<Int, IntArray>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, IntArray>?) = size > MAX_CACHE
    }
    private var buf = CharArray(512)

    /** wrapCells <= 0 disables wrapping. */
    fun configure(wrapCells: Int, tabSize: Int) {
        val wc = if (wrapCells <= 0) 0 else wrapCells.coerceAtLeast(2)
        val ts = tabSize.coerceIn(1, 16)
        if (wc != this.wrapCells || ts != this.tabSize) { this.wrapCells = wc; this.tabSize = ts; cache.clear() }
    }

    fun invalidateAll() = cache.clear()

    fun onContentChanged(startLine: Int, oldCount: Int, newCount: Int) {
        if (cache.isEmpty()) return
        if (oldCount != newCount) cache.clear() else for (l in startLine until startLine + oldCount) cache.remove(l)
    }

    /** Char index where each row starts (always at least [0]). */
    fun rowStarts(line: Int): IntArray {
        if (wrapCells <= 0) return SINGLE
        cache[line]?.let { return it }
        val r = compute(line)
        cache[line] = r
        return r
    }

    fun rowCount(line: Int): Int = if (wrapCells <= 0) 1 else rowStarts(line).size

    private fun compute(line: Int): IntArray {
        val len = store.lineLength(line)
        if (len == 0) return SINGLE
        if (len > MAX_SCAN_CHARS) return fixedGrid(len)
        if (buf.size < len) buf = CharArray(maxOf(len, buf.size * 2))
        val n = store.copyLine(line, 0, len, buf, 0)
        var out = IntArray(4); var cnt = 1          // out[0] = 0
        var rowStart = 0; var cells = 0; var i = 0; var lastOpp = -1
        while (i < n) {
            val w = TextMetrics.cpLen(buf, i, n)
            val next = TextMetrics.advance(cells, buf, i, n, tabSize)
            if (next > wrapCells && i > rowStart) {
                val brk = if (lastOpp > rowStart) lastOpp else i
                if (cnt == out.size) out = out.copyOf(cnt * 2)
                out[cnt++] = brk
                rowStart = brk; cells = 0; i = brk; lastOpp = -1
                continue
            }
            cells = next
            val c = buf[i]
            if (c == ' ' || c == '\t' || c == '-') lastOpp = i + w
            i += w
        }
        return if (cnt == 1) SINGLE else out.copyOf(cnt)
    }

    /**
     * Very long lines (minified JS/JSON, one-line dumps): a plain grid of [wrapCells] chars per row, without reading the text, so that
     * wrapping (on by default) can never exhaust memory or stall the UI. Tabs and wide characters may overshoot such a row.
     * Beyond [MAX_GRID_ROWS] rows the line stays a single (horizontally clipped) row.
     */
    private fun fixedGrid(len: Int): IntArray {
        val step = wrapCells.coerceAtLeast(1)
        val rows = len / step + (if (len % step != 0) 1 else 0)
        if (rows <= 1 || rows > MAX_GRID_ROWS) return SINGLE
        return IntArray(rows) { it * step }
    }

    companion object {
        private const val MAX_CACHE = 1024
        const val MAX_SCAN_CHARS = 100_000
        const val MAX_GRID_ROWS = 2_000_000
        @JvmField val SINGLE = intArrayOf(0)

        /** Row containing char index [col]: the last row whose start <= col. */
        @JvmStatic fun rowOfCol(starts: IntArray, col: Int): Int {
            var lo = 0; var hi = starts.size - 1
            while (lo < hi) { val mid = (lo + hi + 1) ushr 1; if (starts[mid] <= col) lo = mid else hi = mid - 1 }
            return lo
        }
    }
}
