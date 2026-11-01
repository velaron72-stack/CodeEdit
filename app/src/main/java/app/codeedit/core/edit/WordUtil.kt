package app.codeedit.core.edit

/** Unicode-aware word boundary helpers on a char array (a single line). */
object WordUtil {
    const val WORD = 0
    const val SPACE = 1
    const val PUNCT = 2

    @JvmStatic fun cls(c: Char): Int = when {
        c == ' ' || c == '\t' || c == ' ' -> SPACE
        c == '_' || Character.isLetterOrDigit(c) || Character.isSurrogate(c) || Character.getType(c).toByte() == Character.NON_SPACING_MARK -> WORD
        else -> PUNCT
    }

    @JvmStatic fun isWordChar(c: Char): Boolean = cls(c) == WORD

    /** Start of the word run containing/left of col (selection by double tap). Returns [start, end] packed as start shl 32 | end. */
    @JvmStatic fun wordRangeAt(chars: CharArray, len: Int, col: Int): Long {
        if (len == 0) return 0L
        var c = col.coerceIn(0, len)
        // prefer the char at col; if col is at end or on a space after a word, use previous char
        val atCls = if (c < len) cls(chars[c]) else -1
        val prevCls = if (c > 0) cls(chars[c - 1]) else -1
        val k = when {
            atCls == WORD -> atCls
            prevCls == WORD -> { c -= 1; prevCls }
            atCls >= 0 -> atCls
            else -> { c -= 1; prevCls }
        }
        var s = c
        var e = c + 1
        while (s > 0 && cls(chars[s - 1]) == k) s--
        while (e < len && cls(chars[e]) == k) e++
        return (s.toLong() shl 32) or e.toLong()
    }

    /** Ctrl+Left: skip spaces to the left, then a run of one class. */
    @JvmStatic fun prevWordBoundary(chars: CharArray, len: Int, col: Int): Int {
        var i = col.coerceIn(0, len)
        while (i > 0 && cls(chars[i - 1]) == SPACE) i--
        if (i == 0) return 0
        val k = cls(chars[i - 1])
        while (i > 0 && cls(chars[i - 1]) == k) i--
        return i
    }

    /** Ctrl+Right: skip a run of one class, then spaces. */
    @JvmStatic fun nextWordBoundary(chars: CharArray, len: Int, col: Int): Int {
        var i = col.coerceIn(0, len)
        if (i >= len) return len
        val k = cls(chars[i])
        if (k != SPACE) while (i < len && cls(chars[i]) == k) i++
        while (i < len && cls(chars[i]) == SPACE) i++
        return i
    }
}
