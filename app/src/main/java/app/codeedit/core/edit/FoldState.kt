package app.codeedit.core.edit

import app.codeedit.core.doc.LineStore

/**
 * Collapsed regions of a document. A region `(start, end)` hides lines `start+1..end`; the start line stays visible.
 * Regions never overlap or nest (folding an enclosing region replaces the inner ones). UI thread only.
 */
class FoldState {
    private var starts = IntArray(8)
    private var ends = IntArray(8)
    var count = 0
        private set

    val isEmpty: Boolean get() = count == 0

    fun clear() { count = 0 }

    /** Index of the region with the greatest start <= line, or -1. */
    private fun floor(line: Int): Int {
        var lo = 0; var hi = count - 1; var r = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= line) { r = mid; lo = mid + 1 } else hi = mid - 1
        }
        return r
    }

    /** End line if a region starts exactly at [line], else -1. */
    fun foldedEndAt(line: Int): Int { val i = floor(line); return if (i >= 0 && starts[i] == line) ends[i] else -1 }

    fun isFoldedStart(line: Int): Boolean = foldedEndAt(line) >= 0

    /** True when [line] is hidden inside a collapsed region. */
    fun isHidden(line: Int): Boolean { val i = floor(line); return i >= 0 && line > starts[i] && line <= ends[i] }

    /** The first line of the region hiding [line] (its visible start), or [line] if it is visible. */
    fun visibleAnchor(line: Int): Int { val i = floor(line); return if (i >= 0 && line > starts[i] && line <= ends[i]) starts[i] else line }

    /** Next visible line after [line] (may equal lineCount, i.e. past the end). */
    fun nextVisible(line: Int): Int { val e = foldedEndAt(line); return if (e >= 0) e + 1 else line + 1 }

    /** Previous visible line before [line], or -1. */
    fun prevVisible(line: Int): Int {
        val p = line - 1
        return if (p < 0) -1 else visibleAnchor(p)
    }

    /** Number of hidden lines in `[0, line)`. */
    fun hiddenBefore(line: Int): Int {
        var n = 0
        for (i in 0 until count) {
            if (starts[i] >= line) break
            n += minOf(ends[i], line - 1) - starts[i]
        }
        return n
    }

    fun fold(start: Int, end: Int) {
        if (end <= start) return
        // inside an existing region: nothing to do
        val f = floor(start)
        if (f >= 0 && start >= starts[f] && end <= ends[f]) return
        // drop regions swallowed by the new one (nested blocks are either swallowed or disjoint)
        var w = 0
        for (i in 0 until count) {
            val s = starts[i]; val e = ends[i]
            if (s >= start && e <= end) continue
            starts[w] = s; ends[w] = e; w++
        }
        count = w
        ensureCapacity(count + 1)
        var pos = 0
        while (pos < count && starts[pos] < start) pos++
        System.arraycopy(starts, pos, starts, pos + 1, count - pos)
        System.arraycopy(ends, pos, ends, pos + 1, count - pos)
        starts[pos] = start; ends[pos] = end
        count++
    }

    fun unfold(start: Int): Boolean {
        val i = floor(start)
        if (i < 0 || starts[i] != start) return false
        remove(i)
        return true
    }

    /** Unfolds whatever hides [line] (e.g. before moving the caret into it). Returns true if something changed. */
    fun reveal(line: Int): Boolean {
        val i = floor(line)
        if (i >= 0 && line > starts[i] && line <= ends[i]) { remove(i); return true }
        return false
    }

    fun unfoldAll() { count = 0 }

    private fun remove(i: Int) {
        System.arraycopy(starts, i + 1, starts, i, count - i - 1)
        System.arraycopy(ends, i + 1, ends, i, count - i - 1)
        count--
    }

    private fun ensureCapacity(n: Int) {
        if (n > starts.size) { starts = starts.copyOf(n * 2); ends = ends.copyOf(n * 2) }
    }

    /** Keeps regions valid across an edit: regions touching the edit are dropped, the ones below move. */
    fun onContentChanged(startLine: Int, oldLineCount: Int, newLineCount: Int) {
        if (count == 0) return
        val delta = newLineCount - oldLineCount
        val editEnd = startLine + oldLineCount - 1
        var w = 0
        for (i in 0 until count) {
            val s = starts[i]; val e = ends[i]
            if (e < startLine) { starts[w] = s; ends[w] = e; w++ }                       // entirely above
            else if (s > editEnd) { starts[w] = s + delta; ends[w] = e + delta; w++ }     // entirely below
            // else: overlaps the edited lines -> unfolded
        }
        count = w
    }

    /** All regions as `[start, end]` pairs (for tests / diagnostics). */
    fun regions(): List<IntArray> = (0 until count).map { intArrayOf(starts[it], ends[it]) }
}

/** Indentation based fold detection (language independent, bounded work). */
object FoldFinder {
    private const val MAX_SCAN = 20_000

    private fun indentOf(store: LineStore, line: Int, tabSize: Int): Int {
        // -1 for blank lines
        val len = store.lineLength(line)
        if (len == 0) return -1
        val n = minOf(len, 256)
        val buf = CharArray(n)
        store.copyLine(line, 0, n, buf, 0)
        var col = 0
        for (i in 0 until n) {
            when (buf[i]) {
                ' ' -> col++
                '\t' -> col = (col / tabSize + 1) * tabSize
                else -> return col
            }
        }
        return if (len > n) col else -1   // only whitespace
    }

    /** Cheap per-frame check: the next non-blank line (within 8 lines) is indented deeper than [line]. */
    @JvmStatic
    fun canFold(store: LineStore, line: Int, tabSize: Int = 4): Boolean {
        val lc = store.lineCount
        if (line < 0 || line >= lc - 1) return false
        val base = indentOf(store, line, tabSize)
        if (base < 0) return false
        var i = line + 1
        val limit = minOf(lc, line + 9)
        while (i < limit) {
            val ind = indentOf(store, i, tabSize)
            if (ind >= 0) return ind > base
            i++
        }
        return false
    }

    /** Last line of the block that starts at [line] (lines indented deeper than it), or -1 when the line cannot be folded. */
    @JvmStatic
    fun endOfBlock(store: LineStore, line: Int, tabSize: Int = 4): Int {
        val lc = store.lineCount
        if (line < 0 || line >= lc - 1) return -1
        val base = indentOf(store, line, tabSize)
        if (base < 0) return -1
        var last = -1
        var i = line + 1
        val limit = minOf(lc, line + 1 + MAX_SCAN)
        while (i < limit) {
            val ind = indentOf(store, i, tabSize)
            if (ind < 0) { i++; continue }          // blank lines belong to the block when more indented text follows
            if (ind <= base) break
            last = i
            i++
        }
        return last
    }
}
