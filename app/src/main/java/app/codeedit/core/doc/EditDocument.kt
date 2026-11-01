package app.codeedit.core.doc

import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory editable document: gap buffer of chars + array of line start offsets. Lines are separated by '\n' only
 * (callers normalise; the original EOL is kept in DocInfo/EditorSession and re-applied on save). UI-thread only.
 *
 * Edits near the caret are O(1) amortised for the text; the line-start table is shifted in O(lines after the edit)
 * (a 24 MB document with ~600k lines shifts in well under a millisecond).
 */
class EditDocument private constructor(
    private var buf: CharArray,
    private var gapStart: Int,
    private var gapEnd: Int,
    private var starts: IntArray,
    private var lineCnt: Int,
) : EditableDocument {

    companion object {
        private const val MAX_HISTORY_CHARS = 4_000_000L
        private const val MAX_MERGED_INSERT = 400
        private const val COALESCE_WINDOW_MS = 1000L

        fun normalizeEol(s: String): String {
            if (s.indexOf('\r') < 0) return s
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\r') { sb.append('\n'); if (i + 1 < s.length && s[i + 1] == '\n') i++ } else sb.append(c)
                i++
            }
            return sb.toString()
        }

        /** Creates a document from text already using '\n' separators (use normalizeEol first if unsure). */
        fun fromString(text: String): EditDocument {
            val b = Builder(text.length)
            b.append(text.toCharArray(), 0, text.length)
            return b.finish()
        }
    }

    /** Streaming constructor used by the opener: feed decoded chunks, EOLs are normalised on the fly. */
    class Builder(capacityHint: Int) {
        private var buf = CharArray(maxOf(capacityHint + 4096, 4096))
        private var n = 0
        private var starts = IntArray(1024)
        private var lines = 1
        private var skipLf = false
        init { starts[0] = 0 }

        fun append(chars: CharArray, off: Int, len: Int) {
            if (n + len + 16 > buf.size) buf = buf.copyOf(maxOf(buf.size + buf.size / 2, n + len + 4096))
            var i = off
            val end = off + len
            while (i < end) {
                var c = chars[i++]
                if (skipLf) { skipLf = false; if (c == '\n') continue }
                if (c == '\r') { c = '\n'; skipLf = true }
                buf[n++] = c
                if (c == '\n') {
                    if (lines == starts.size) starts = starts.copyOf(lines + lines / 2)
                    starts[lines++] = n
                }
            }
        }

        val length: Int get() = n

        fun finish(): EditDocument {
            val gapSize = buf.size - n
            // gap at the end
            return EditDocument(buf, n, buf.size, starts, lines).also { check(gapSize >= 0) }
        }
    }

    // ---------------- state ----------------
    private var version = 0L
    private val listeners = CopyOnWriteArrayList<DocumentListener>()

    override val length: Int get() = buf.size - (gapEnd - gapStart)
    override val lineCount: Int get() = lineCnt
    override val isIndexComplete: Boolean get() = true
    override val indexProgress: Float get() = 1f
    override val estimatedLineCount: Long get() = lineCnt.toLong()
    override val contentVersion: Long get() = version

    // ---------------- buffer primitives ----------------
    private fun moveGap(pos: Int) {
        if (pos == gapStart) return
        if (pos < gapStart) {
            val n = gapStart - pos
            System.arraycopy(buf, pos, buf, gapEnd - n, n)
            gapStart = pos; gapEnd -= n
        } else {
            val n = pos - gapStart
            System.arraycopy(buf, gapEnd, buf, gapStart, n)
            gapStart += n; gapEnd += n
        }
    }

    private fun ensureGap(n: Int) {
        val gap = gapEnd - gapStart
        if (gap >= n) return
        val tail = buf.size - gapEnd
        val newSize = maxOf(buf.size + buf.size / 2, length + n + 8192)
        val nb = CharArray(newSize)
        System.arraycopy(buf, 0, nb, 0, gapStart)
        System.arraycopy(buf, gapEnd, nb, newSize - tail, tail)
        gapEnd = newSize - tail
        buf = nb
    }

    override fun charAt(offset: Int): Char = if (offset < gapStart) buf[offset] else buf[offset + (gapEnd - gapStart)]

    private fun copyChars(from: Int, to: Int, dst: CharArray, dstOff: Int) {
        if (to <= from) return
        if (to <= gapStart) System.arraycopy(buf, from, dst, dstOff, to - from)
        else if (from >= gapStart) { val g = gapEnd - gapStart; System.arraycopy(buf, from + g, dst, dstOff, to - from) }
        else {
            val a = gapStart - from
            System.arraycopy(buf, from, dst, dstOff, a)
            System.arraycopy(buf, gapEnd, dst, dstOff + a, to - gapStart)
        }
    }

    private fun substring(from: Int, to: Int): String {
        if (to <= from) return ""
        val tmp = CharArray(to - from)
        copyChars(from, to, tmp, 0)
        return String(tmp)
    }

    // ---------------- LineStore ----------------
    private fun lineEnd(line: Int): Int = if (line + 1 < lineCnt) starts[line + 1] - 1 else length

    override fun lineLength(line: Int): Int {
        if (line < 0 || line >= lineCnt) return 0
        return lineEnd(line) - starts[line]
    }

    override fun getLine(line: Int): String {
        if (line < 0 || line >= lineCnt) return ""
        return substring(starts[line], lineEnd(line))
    }

    override fun copyLine(line: Int, from: Int, to: Int, dst: CharArray, dstOff: Int): Int {
        if (line < 0 || line >= lineCnt) return 0
        val s = starts[line]; val len = lineEnd(line) - s
        val a = from.coerceIn(0, len); val b = to.coerceIn(a, len)
        copyChars(s + a, s + b, dst, dstOff)
        return b - a
    }

    override fun getText(from: Pos, to: Pos, maxChars: Int): String {
        val a = offsetOf(from); var b = offsetOf(to)
        if (b < a) return getText(to, from, maxChars)
        if (b - a > maxChars) b = a + maxChars
        return substring(a, b)
    }

    override fun fullText(): String = substring(0, length)

    override fun addListener(l: DocumentListener) { listeners.add(l) }
    override fun removeListener(l: DocumentListener) { listeners.remove(l) }
    override fun close() { listeners.clear(); undoStack.clear(); redoStack.clear() }

    // ---------------- offsets ----------------
    private fun lineOfOffset(off: Int): Int {
        var lo = 0; var hi = lineCnt - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= off) lo = mid else hi = mid - 1
        }
        return lo
    }

    override fun offsetOf(p: Pos): Int {
        val line = p.line.coerceIn(0, lineCnt - 1)
        val len = lineEnd(line) - starts[line]
        return starts[line] + p.col.coerceIn(0, len)
    }

    override fun posOf(offset: Int): Pos {
        val o = offset.coerceIn(0, length)
        val line = lineOfOffset(o)
        return Pos(line, o - starts[line])
    }

    // ---------------- raw edit ----------------
    private fun rawReplace(start: Int, removeLen: Int, insert: String) {
        val end = start + removeLen
        val sl = lineOfOffset(start)
        val el = if (removeLen == 0) sl else lineOfOffset(end)
        moveGap(start)
        gapEnd += removeLen
        val n = insert.length
        ensureGap(n)
        insert.toCharArray(buf, gapStart, 0, n)
        gapStart += n
        // line table
        var k = 0
        for (i in 0 until n) if (insert[i] == '\n') k++
        val delta = n - removeLen
        val newCnt = lineCnt - (el - sl) + k
        if (newCnt > starts.size) starts = starts.copyOf(maxOf(newCnt + 16, starts.size + starts.size / 2))
        val tailStart = el + 1
        val tailCount = lineCnt - tailStart
        if (tailCount > 0 && (sl + 1 + k) != tailStart) System.arraycopy(starts, tailStart, starts, sl + 1 + k, tailCount)
        var li = sl + 1
        for (i in 0 until n) if (insert[i] == '\n') starts[li++] = start + i + 1
        if (delta != 0) for (i in sl + 1 + k until newCnt) starts[i] += delta
        lineCnt = newCnt
        version++
        val oldLines = el - sl + 1
        for (l in listeners) l.onContentChanged(sl, oldLines, k + 1)
    }

    // ---------------- undo model ----------------
    private class Edit(var offset: Int, var removed: String, var inserted: String)
    private class Group(var kind: Coalesce, var time: Long) {
        val edits = ArrayList<Edit>(2)
        var selBefore: Selection? = null
        var selAfter: Selection? = null
        var sealed = false
        fun chars(): Long { var c = 0L; for (e in edits) c += e.removed.length + e.inserted.length; return c }
    }

    private val undoStack = ArrayList<Group>()
    private val redoStack = ArrayList<Group>()
    private var compound: Group? = null
    private var compoundDepth = 0
    private var historyChars = 0L
    private var savedGroup: Group? = null
    private var savedValid = true
    private var replaying = false

    override val canUndo: Boolean get() = undoStack.isNotEmpty()
    override val canRedo: Boolean get() = redoStack.isNotEmpty()

    override val isModified: Boolean
        get() {
            if (compoundDepth > 0 && compound?.edits?.isNotEmpty() == true) return true
            return !savedValid || undoStack.lastOrNull() !== savedGroup
        }

    override fun markSaved() {
        savedGroup = undoStack.lastOrNull()
        savedGroup?.sealed = true
        savedValid = true
    }

    override fun beginCompound() {
        if (compoundDepth++ == 0) compound = Group(Coalesce.NONE, System.currentTimeMillis())
    }

    override fun endCompound() {
        if (compoundDepth == 0) return
        if (--compoundDepth == 0) {
            val g = compound; compound = null
            if (g != null && g.edits.isNotEmpty()) pushGroup(g)
        }
    }

    private fun pushGroup(g: Group) {
        clearRedo()
        undoStack.add(g)
        historyChars += g.chars()
        trimHistory()
    }

    private fun clearRedo() {
        if (redoStack.isEmpty()) return
        if (savedGroup != null && redoStack.contains(savedGroup!!)) savedValid = false
        for (g in redoStack) historyChars -= g.chars()
        redoStack.clear()
    }

    private fun trimHistory() {
        while (historyChars > MAX_HISTORY_CHARS && undoStack.size > 1) {
            val g = undoStack.removeAt(0)
            historyChars -= g.chars()
        }
    }

    override fun replace(from: Pos, to: Pos, text: String, selBefore: Selection?, selAfter: Selection?, coalesce: Coalesce): Pos {
        var a = offsetOf(from); var b = offsetOf(to)
        if (a > b) { val t = a; a = b; b = t }
        val ins = normalizeEol(text)
        if (a == b && ins.isEmpty()) return posOf(a)
        val removed = substring(a, b)
        val edit = Edit(a, removed, ins)
        if (!replaying) {
            val now = System.currentTimeMillis()
            val cg = compound
            if (cg != null) {
                if (cg.edits.isEmpty()) cg.selBefore = selBefore
                cg.edits.add(edit)
                if (selAfter != null) cg.selAfter = selAfter
            } else {
                val top = undoStack.lastOrNull()
                if (coalesce != Coalesce.NONE && top != null && !top.sealed && top.kind == coalesce &&
                    now - top.time < COALESCE_WINDOW_MS && top.edits.size == 1 && redoStack.isEmpty() && tryMerge(top.edits[0], edit, coalesce)) {
                    top.selAfter = selAfter; top.time = now
                    historyChars += ins.length + removed.length
                } else {
                    val g = Group(coalesce, now)
                    g.selBefore = selBefore; g.selAfter = selAfter
                    g.edits.add(edit)
                    pushGroup(g)
                }
            }
        }
        rawReplace(a, b - a, ins)
        return posOf(a + ins.length)
    }

    private fun tryMerge(last: Edit, e: Edit, kind: Coalesce): Boolean {
        when (kind) {
            Coalesce.TYPING -> {
                if (e.removed.isEmpty() && last.removed.isEmpty() && e.offset == last.offset + last.inserted.length &&
                    e.inserted.indexOf('\n') < 0 && last.inserted.length < MAX_MERGED_INSERT) { last.inserted += e.inserted; return true }
            }
            Coalesce.DELETING -> {
                if (e.inserted.isEmpty() && last.inserted.isEmpty() && last.removed.length < MAX_MERGED_INSERT) {
                    if (e.offset + e.removed.length == last.offset) { last.offset = e.offset; last.removed = e.removed + last.removed; return true }
                    if (e.offset == last.offset) { last.removed += e.removed; return true }
                }
            }
            else -> {}
        }
        return false
    }

    override fun undo(): Selection? {
        if (compoundDepth > 0 || undoStack.isEmpty()) return null
        val g = undoStack.removeAt(undoStack.size - 1)
        replaying = true
        try { for (i in g.edits.indices.reversed()) { val e = g.edits[i]; rawReplace(e.offset, e.inserted.length, e.removed) } }
        finally { replaying = false }
        redoStack.add(g)
        return g.selBefore ?: Selection.caret(posOf(g.edits.firstOrNull()?.offset ?: 0))
    }

    override fun redo(): Selection? {
        if (compoundDepth > 0 || redoStack.isEmpty()) return null
        val g = redoStack.removeAt(redoStack.size - 1)
        replaying = true
        try { for (e in g.edits) rawReplace(e.offset, e.removed.length, e.inserted) }
        finally { replaying = false }
        undoStack.add(g)
        val last = g.edits.lastOrNull()
        return g.selAfter ?: Selection.caret(posOf((last?.offset ?: 0) + (last?.inserted?.length ?: 0)))
    }
}
