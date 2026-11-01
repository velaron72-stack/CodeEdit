package app.codeedit.core.edit

/**
 * Cell-grid text metrics. The editor lays text out on a monospace grid: every char occupies `cellWidth` cells
 * (0 for combining/format marks, 2 for East-Asian wide + emoji, 1 otherwise); a tab advances to the next tab stop.
 * Pixel x = cells * charWidthPx.
 */
object TextMetrics {
    /** Width of a code point in cells (0, 1 or 2). Tabs are handled by callers. */
    @JvmStatic fun cellWidth(cp: Int): Int {
        if (cp < 0x300) return 1
        if (cp in 0x1100..0x115F) return 2
        when (Character.getType(cp).toByte()) {
            Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.FORMAT -> return if (cp == 0xAD) 1 else 0
        }
        if (cp in 0xFE00..0xFE0F) return 0
        if (cp in 0x2E80..0x303E || cp in 0x3041..0x33FF || cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF ||
            cp in 0xA000..0xA4CF || cp in 0xAC00..0xD7A3 || cp in 0xF900..0xFAFF || cp in 0xFE30..0xFE6F ||
            cp in 0xFF00..0xFF60 || cp in 0xFFE0..0xFFE6 || cp in 0x1F300..0x1F64F || cp in 0x1F680..0x1F6FF ||
            cp in 0x1F900..0x1F9FF || cp in 0x1FA70..0x1FAFF || cp in 0x20000..0x3FFFD) return 2
        return 1
    }

    /** True for chars that need the slow (per-char) draw path: non-ASCII, tab, control. */
    @JvmStatic fun isSimpleAscii(c: Char): Boolean = c.code in 0x20..0x7E

    /** Advances `cells` over the char at index i (handles surrogates). Tab: next stop relative to `base` cells. */
    @JvmStatic fun advance(cells: Int, chars: CharArray, i: Int, len: Int, tabSize: Int, base: Int = 0): Int {
        val c = chars[i]
        if (c == '\t') { val rel = cells - base; return base + (rel / tabSize + 1) * tabSize }
        if (c.code < 0x300) return cells + 1
        if (Character.isHighSurrogate(c) && i + 1 < len && Character.isLowSurrogate(chars[i + 1]))
            return cells + cellWidth(Character.toCodePoint(c, chars[i + 1]))
        return cells + cellWidth(c.code)
    }

    /** Length (1 or 2 chars) of the code point starting at i. */
    @JvmStatic fun cpLen(chars: CharArray, i: Int, len: Int): Int =
        if (Character.isHighSurrogate(chars[i]) && i + 1 < len && Character.isLowSurrogate(chars[i + 1])) 2 else 1

    /** Visual column (cells) of char index `col`, measuring tabs from `from` (row start) whose cell offset is 0. */
    @JvmStatic fun visualColumn(chars: CharArray, len: Int, col: Int, tabSize: Int, from: Int = 0): Int {
        var cells = 0
        var i = from
        val end = if (col > len) len else col
        while (i < end) {
            val n = cpLen(chars, i, len)
            cells = advance(cells, chars, i, len, tabSize)
            i += n
        }
        return cells
    }

    /** Total cells of chars[from, to). */
    @JvmStatic fun cells(chars: CharArray, from: Int, to: Int, tabSize: Int): Int = visualColumn(chars, to, to, tabSize, from)

    /**
     * Char index whose left edge is nearest to `target` cells (measured from `from`), clamped to [from, to].
     * A wide char spanning the target is resolved to the nearer edge.
     */
    @JvmStatic fun colAtVisual(chars: CharArray, from: Int, to: Int, target: Int, tabSize: Int): Int {
        var cells = 0
        var i = from
        while (i < to) {
            val n = cpLen(chars, i, to)
            val next = advance(cells, chars, i, to, tabSize)
            if (target < next) {
                // target lies inside char [cells, next)
                return if (target - cells >= next - target) i + n else i
            }
            cells = next
            i += n
        }
        return to
    }
}
