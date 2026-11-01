package app.codeedit.core.search

import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

data class SearchQuery(val text: String, val matchCase: Boolean = false, val wholeWord: Boolean = false, val regex: Boolean = false)

class QueryException(message: String) : Exception(message)

/** Thrown by [DeadlineSeq] when a regex runs past its time budget. */
class SearchTimeout : RuntimeException(null, null, false, false)

/** CharSequence wrapper that aborts runaway regex evaluation (checked every 4096 accesses). */
class DeadlineSeq(private val inner: CharSequence, private val deadlineNanos: Long) : CharSequence {
    private var counter = 0
    override val length: Int get() = inner.length
    override fun get(index: Int): Char {
        if ((++counter and 4095) == 0 && System.nanoTime() > deadlineNanos) throw SearchTimeout()
        return inner[index]
    }
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = inner.subSequence(startIndex, endIndex)
    override fun toString(): String = inner.toString()
}

/**
 * Compiled form of a [SearchQuery]. Plain queries use String.indexOf / region matching (fast path); everything else
 * goes through java.util.regex. Empty matches are never reported.
 */
class CompiledQuery(val query: SearchQuery) {
    val isEmpty: Boolean = query.text.isEmpty()
    private val pattern: Pattern?
    private val literal: String
    private val literalLower: String

    init {
        literal = query.text
        literalLower = query.text.lowercase()
        pattern = if (isEmpty) null else if (!query.regex && query.matchCase && !query.wholeWord) null
        else if (!query.regex && !query.matchCase && !query.wholeWord) null
        else try {
            var src = if (query.regex) query.text else Pattern.quote(query.text)
            if (query.wholeWord) src = "(?<![\\p{L}\\p{N}_])(?:$src)(?![\\p{L}\\p{N}_])"
            var flags = Pattern.MULTILINE
            if (!query.matchCase) flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
            Pattern.compile(src, flags)
        } catch (e: PatternSyntaxException) {
            throw QueryException(e.description ?: "invalid pattern")
        } catch (e: StackOverflowError) {
            throw QueryException("pattern too complex")
        }
    }

    /** True when matching may span line breaks (regex on whole text). */
    val isRegex: Boolean get() = pattern != null

    /**
     * Finds the first non-empty match at or after `from`. Returns (start shl 32) | end, or -1.
     * `deadlineNanos` bounds regex work (SearchTimeout is thrown past it).
     */
    fun find(text: CharSequence, from: Int, deadlineNanos: Long = Long.MAX_VALUE): Long {
        if (isEmpty || from > text.length) return -1
        if (pattern == null) {
            val i = indexOfLiteral(text, from)
            return if (i < 0) -1 else (i.toLong() shl 32) or (i + literal.length).toLong()
        }
        val seq: CharSequence = if (deadlineNanos == Long.MAX_VALUE) text else DeadlineSeq(text, deadlineNanos)
        val m: Matcher = pattern.matcher(seq)
        var pos = from
        while (pos <= text.length && m.find(pos)) {
            if (m.end() > m.start()) return (m.start().toLong() shl 32) or m.end().toLong()
            pos = m.end() + 1
        }
        return -1
    }

    private fun indexOfLiteral(text: CharSequence, from: Int): Int {
        val n = literal.length
        val last = text.length - n
        if (query.matchCase) {
            if (text is String) return text.indexOf(literal, from)
            var i = from
            val c0 = literal[0]
            while (i <= last) { if (text[i] == c0 && regionEq(text, i)) return i; i++ }
            return -1
        }
        var i = from
        val c0 = literal[0]
        val c0l = Character.toLowerCase(c0); val c0u = Character.toUpperCase(c0)
        while (i <= last) {
            val c = text[i]
            if ((c == c0 || c == c0l || c == c0u || Character.toLowerCase(c) == c0l) && regionEqIgnoreCase(text, i)) return i
            i++
        }
        return -1
    }

    private fun regionEq(t: CharSequence, at: Int): Boolean { for (k in literal.indices) if (t[at + k] != literal[k]) return false; return true }
    private fun regionEqIgnoreCase(t: CharSequence, at: Int): Boolean {
        for (k in literal.indices) {
            val a = t[at + k]; val b = literal[k]
            if (a != b && Character.toLowerCase(a) != Character.toLowerCase(b) && Character.toUpperCase(a) != Character.toUpperCase(b)) return false
        }
        return true
    }

    /** Pattern for replacement expansion (null for plain-text queries). */
    internal fun regexPattern(): Pattern? = pattern

    /** Collects non-overlapping matches of one line as [col,len,...] (at most maxMatches). */
    fun matchesInLine(line: CharSequence, maxMatches: Int = 2000, deadlineNanos: Long = Long.MAX_VALUE): IntArray {
        if (isEmpty || line.length < 1) return EMPTY
        var out = IntArray(8); var n = 0
        var pos = 0
        while (pos <= line.length && n / 2 < maxMatches) {
            val r = find(line, pos, deadlineNanos)
            if (r < 0) break
            val s = (r ushr 32).toInt(); val e = (r and 0xFFFFFFFFL).toInt()
            if (n + 2 > out.size) out = out.copyOf(out.size * 2)
            out[n++] = s; out[n++] = e - s
            pos = e
        }
        return if (n == 0) EMPTY else out.copyOf(n)
    }

    companion object { val EMPTY = IntArray(0) }
}
