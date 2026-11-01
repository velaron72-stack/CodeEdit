package app.codeedit.core.edit

import app.codeedit.core.doc.LineStore
import app.codeedit.core.doc.Pos

/** Caret movement on logical lines (the view handles visual rows for word wrap). Works on any [LineStore]. */
object CursorMotion {
    @JvmStatic
    fun left(store: LineStore, p: Pos): Pos {
        if (p.col > 0) {
            val len = store.lineLength(p.line)
            val col = p.col.coerceAtMost(len)
            if (col >= 2) {
                val two = CharArray(2); store.copyLine(p.line, col - 2, col, two, 0)
                if (Character.isHighSurrogate(two[0]) && Character.isLowSurrogate(two[1])) return Pos(p.line, col - 2)
            }
            return Pos(p.line, (col - 1).coerceAtLeast(0))
        }
        return if (p.line > 0) Pos(p.line - 1, store.lineLength(p.line - 1)) else p
    }

    @JvmStatic
    fun right(store: LineStore, p: Pos): Pos {
        val len = store.lineLength(p.line)
        if (p.col < len) {
            if (p.col + 2 <= len) {
                val two = CharArray(2); store.copyLine(p.line, p.col, p.col + 2, two, 0)
                if (Character.isHighSurrogate(two[0]) && Character.isLowSurrogate(two[1])) return Pos(p.line, p.col + 2)
            }
            return Pos(p.line, p.col + 1)
        }
        return if (p.line + 1 < store.lineCount) Pos(p.line + 1, 0) else p
    }

    /** Visual column in cells of [p]. */
    @JvmStatic
    fun cellColumn(store: LineStore, p: Pos, tabSize: Int): Int {
        val len = store.lineLength(p.line).coerceAtMost(MAX_MEASURE)
        val chars = CharArray(len); store.copyLine(p.line, 0, len, chars, 0)
        return TextMetrics.visualColumn(chars, len, p.col.coerceAtMost(len), tabSize)
    }

    /** Position on [line] closest to [goalCells]. */
    @JvmStatic
    fun atCells(store: LineStore, line: Int, goalCells: Int, tabSize: Int): Pos {
        val len = store.lineLength(line)
        val n = len.coerceAtMost(MAX_MEASURE)
        val chars = CharArray(n); store.copyLine(line, 0, n, chars, 0)
        val col = TextMetrics.colAtVisual(chars, 0, n, goalCells, tabSize)
        return Pos(line, if (col >= n) n else col)   // lines longer than the measured prefix: clamp to its end
    }

    @JvmStatic
    fun vertical(store: LineStore, p: Pos, delta: Int, goalCells: Int, tabSize: Int): Pos {
        val target = p.line + delta
        if (target < 0) return Pos(0, 0)
        if (target >= store.lineCount) return Pos(store.lineCount - 1, store.lineLength(store.lineCount - 1))
        return atCells(store, target, goalCells, tabSize)
    }

    /** Smart home: first non-blank column, or column 0 when already there. */
    @JvmStatic
    fun home(store: LineStore, p: Pos, smart: Boolean): Pos {
        if (!smart) return Pos(p.line, 0)
        val len = store.lineLength(p.line).coerceAtMost(1024)
        val chars = CharArray(len); store.copyLine(p.line, 0, len, chars, 0)
        var i = 0
        while (i < len && (chars[i] == ' ' || chars[i] == '\t')) i++
        return if (p.col == i) Pos(p.line, 0) else Pos(p.line, i)
    }

    @JvmStatic fun end(store: LineStore, p: Pos): Pos = Pos(p.line, store.lineLength(p.line))

    @JvmStatic
    fun wordLeft(store: LineStore, p: Pos): Pos {
        if (p.col == 0) return if (p.line > 0) Pos(p.line - 1, store.lineLength(p.line - 1)) else p
        val len = store.lineLength(p.line)
        val chars = CharArray(len); store.copyLine(p.line, 0, len, chars, 0)
        return Pos(p.line, WordUtil.prevWordBoundary(chars, len, p.col))
    }

    @JvmStatic
    fun wordRight(store: LineStore, p: Pos): Pos {
        val len = store.lineLength(p.line)
        if (p.col >= len) return if (p.line + 1 < store.lineCount) Pos(p.line + 1, 0) else p
        val chars = CharArray(len); store.copyLine(p.line, 0, len, chars, 0)
        return Pos(p.line, WordUtil.nextWordBoundary(chars, len, p.col))
    }

    @JvmStatic fun docStart(): Pos = Pos(0, 0)
    @JvmStatic fun docEnd(store: LineStore): Pos = Pos(store.lineCount - 1, store.lineLength(store.lineCount - 1))

    private const val MAX_MEASURE = 20_000
}

/** Bracket matching with a bounded scan. [skip] may tell that a position is inside a string/comment (unknown => false). */
object BracketMatcher {
    private const val OPEN = "([{"
    private const val CLOSE = ")]}"
    private const val MAX_CHARS = 60_000
    private const val MAX_LINES = 3_000

    /** Returns the pair (bracket, partner) for the bracket touching the caret, preferring the one before it. */
    @JvmStatic
    fun find(store: LineStore, caret: Pos, skip: ((Int, Int) -> Boolean)? = null): Pair<Pos, Pos>? {
        val len = store.lineLength(caret.line)
        if (len > 10_000) return null
        val line = store.getLine(caret.line)
        for (col in intArrayOf(caret.col - 1, caret.col)) {
            if (col < 0 || col >= line.length) continue
            val c = line[col]
            val o = OPEN.indexOf(c); val cl = CLOSE.indexOf(c)
            if (skip != null && skip(caret.line, col)) continue
            if (o >= 0) { scan(store, Pos(caret.line, col), c, CLOSE[o], +1, skip)?.let { return Pos(caret.line, col) to it } }
            else if (cl >= 0) { scan(store, Pos(caret.line, col), c, OPEN[cl], -1, skip)?.let { return Pos(caret.line, col) to it } }
        }
        return null
    }

    private fun scan(store: LineStore, from: Pos, self: Char, target: Char, dir: Int, skip: ((Int, Int) -> Boolean)?): Pos? {
        var depth = 0
        var budget = MAX_CHARS
        var line = from.line
        var lines = 0
        while (line in 0 until store.lineCount && lines <= MAX_LINES) {
            val len = store.lineLength(line)
            if (len <= 10_000) {
                val text = store.getLine(line)
                var i = if (line == from.line) from.col else if (dir > 0) 0 else text.length - 1
                while (i in text.indices) {
                    val c = text[i]
                    if ((c == self || c == target) && !(skip != null && skip(line, i))) {
                        if (c == self) depth++ else { depth--; if (depth == 0) return Pos(line, i) }
                    }
                    i += dir
                    if (--budget <= 0) return null
                }
            } else budget -= 1000
            line += dir
            lines++
        }
        return null
    }
}
