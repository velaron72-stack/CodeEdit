package app.codeedit.core.search

import app.codeedit.core.doc.EditableDocument
import app.codeedit.core.doc.LineStore
import app.codeedit.core.doc.Match
import app.codeedit.core.doc.Pos
import app.codeedit.core.doc.ViewDocument
import java.io.Closeable
import java.util.concurrent.Executor

/**
 * Search over a LineStore.
 *  - VIEW documents: streaming per-line scan in the background (bounded memory, results as they arrive).
 *  - EDIT documents: scan over a text snapshot (multi-line regex allowed). After edits call [onDocumentChanged] and then
 *    start() again (debounced by the caller, UI thread) — the snapshot is taken on the calling thread.
 * findNext/findPrevious work immediately, without waiting for the background scan, and may be slow on huge files:
 * call them off the UI thread for documents larger than a few MB.
 */
class SearchSession(
    private val store: LineStore,
    val query: SearchQuery,
    private val executor: Executor,
    private val listener: Listener? = null,
) : Closeable {

    fun interface Listener { fun onProgress(matchCount: Long, complete: Boolean) }

    companion object {
        const val MAX_STORED = 5_000_000
        private const val LEN_BITS = 13
        private const val COL_BITS = 20
        private fun pack(line: Int, col: Int, len: Int): Long =
            (line.toLong() shl (LEN_BITS + COL_BITS)) or (col.coerceIn(0, (1 shl COL_BITS) - 1).toLong() shl LEN_BITS) or len.coerceIn(0, (1 shl LEN_BITS) - 1).toLong()
    }

    private val cq = CompiledQuery(query)          // throws QueryException
    private val isEdit = store is EditableDocument
    private val lock = Any()
    private var matches = LongArray(1024)
    private var count = 0

    @Volatile var matchCount: Long = 0L; private set
    @Volatile var isComplete: Boolean = false; private set
    @Volatile var timedOut: Boolean = false; private set
    /** More than MAX_STORED matches exist: the list (and matchCount) stops at the cap. */
    @Volatile var capped: Boolean = false; private set
    /** True after onDocumentChanged until start() runs again. */
    @Volatile var needsRestart: Boolean = false; private set
    /** The last findNext/findPrevious stopped at an unindexed region or ran out of time. */
    @Volatile var lastFindPartial: Boolean = false; private set
    @Volatile private var gen = 0

    // EDIT snapshot
    private var snapText: String? = null
    private var snapStarts: IntArray? = null
    private var snapVersion = -1L

    private val lineCache = HashMap<Int, IntArray>()
    private var lineCacheVersion = -1L

    // ---------------- background scan ----------------
    fun start() {
        val g = ++gen
        synchronized(lock) { count = 0; matchCount = 0 }
        isComplete = false; timedOut = false; capped = false; needsRestart = false
        if (cq.isEmpty) { isComplete = true; notifyProgress(); return }
        if (isEdit) {
            refreshSnapshot()
            val text = snapText!!
            executor.execute { scanSnapshot(g, text) }
        } else executor.execute { scanLines(g) }
    }

    fun cancel() { gen++ }
    override fun close() { gen++; synchronized(lock) { matches = LongArray(0); count = 0 }; snapText = null; snapStarts = null }

    fun onDocumentChanged(startLine: Int, oldCount: Int, newCount: Int) {
        if (!isEdit) return
        gen++
        needsRestart = true
        isComplete = false
        lineCache.clear()
    }

    private fun notifyProgress() { try { listener?.onProgress(matchCount, isComplete) } catch (_: Throwable) {} }

    private fun add(key: Long): Boolean {
        synchronized(lock) {
            if (count >= MAX_STORED) { capped = true; return false }
            if (count == matches.size) matches = matches.copyOf(count * 2)
            matches[count++] = key
            matchCount = count.toLong()
        }
        return true
    }

    private fun scanSnapshot(g: Int, text: String) {
        val starts = snapshotStarts(text)
        var pos = 0
        var lastNotify = System.nanoTime()
        try {
            while (g == gen) {
                val r = try { cq.find(text, pos, System.nanoTime() + 3_000_000_000L) } catch (_: SearchTimeout) { timedOut = true; break }
                if (r < 0) break
                val s = (r ushr 32).toInt(); val e = (r and 0xFFFFFFFFL).toInt()
                val line = lineOf(starts, s)
                if (!add(pack(line, s - starts[line], e - s))) break
                pos = e
                val now = System.nanoTime()
                if (now - lastNotify > 100_000_000L) { lastNotify = now; notifyProgress() }
            }
        } finally {
            if (g == gen) { isComplete = true; notifyProgress() }
        }
    }

    private fun scanLines(g: Int) {
        var line = 0
        var lastNotify = System.nanoTime()
        var ended = false
        try {
            while (g == gen && !ended) {
                val lc = store.lineCount
                if (line >= lc) {
                    if (store.isIndexComplete && line >= store.lineCount) break
                    try { Thread.sleep(15) } catch (_: InterruptedException) { break }
                    continue
                }
                val before = line
                val visit = { l: Int, s: String ->
                    if (g != gen) false else {
                        val m = try { cq.matchesInLine(s, 5000, System.nanoTime() + 250_000_000L) } catch (_: SearchTimeout) { timedOut = true; CompiledQuery.EMPTY }
                        var i = 0
                        var ok = true
                        while (i < m.size) { if (!add(pack(l, m[i], m[i + 1]))) { ok = false; ended = true; break }; i += 2 }
                        line = l + 1
                        val now = System.nanoTime()
                        if (now - lastNotify > 100_000_000L) { lastNotify = now; notifyProgress() }
                        ok
                    }
                }
                if (store is ViewDocument) store.forEachLine(line, lc, visit)
                else { while (line < lc && g == gen && !ended) { if (!visit(line, store.getLine(line))) break } }
                if (line == before && !ended) { try { Thread.sleep(5) } catch (_: InterruptedException) { break } }
            }
        } finally {
            if (g == gen) { isComplete = true; notifyProgress() }
        }
    }

    // ---------------- snapshot helpers (EDIT) ----------------
    private fun refreshSnapshot() {
        val d = store as EditableDocument
        if (snapVersion != d.contentVersion || snapText == null) {
            snapText = d.fullText(); snapStarts = null; snapVersion = d.contentVersion
        }
    }

    @Synchronized private fun snapshotStarts(text: String): IntArray {
        snapStarts?.let { if (snapText === text) return it }
        var n = 1
        for (c in text) if (c == '\n') n++
        val a = IntArray(n); var k = 1
        for (i in text.indices) if (text[i] == '\n') a[k++] = i + 1
        if (snapText === text) snapStarts = a
        return a
    }

    private fun lineOf(starts: IntArray, off: Int): Int {
        var lo = 0; var hi = starts.size - 1
        while (lo < hi) { val mid = (lo + hi + 1) ushr 1; if (starts[mid] <= off) lo = mid else hi = mid - 1 }
        return lo
    }

    private fun findSafe(text: CharSequence, from: Int): Long =
        try { cq.find(text, from, System.nanoTime() + 1_500_000_000L) } catch (_: SearchTimeout) { timedOut = true; lastFindPartial = true; -1 }

    // ---------------- navigation ----------------
    /** First match starting at/after `from` (inclusive=true) or strictly after (inclusive=false). */
    fun findNext(from: Pos, wrap: Boolean = true, inclusive: Boolean = false, maxMillis: Long = 4000): Match? {
        if (cq.isEmpty) return null
        lastFindPartial = false
        return if (isEdit) findNextEdit(from, wrap, inclusive) else findNextView(from, wrap, inclusive, maxMillis)
    }

    fun findPrevious(from: Pos, wrap: Boolean = true, maxMillis: Long = 4000): Match? {
        if (cq.isEmpty) return null
        lastFindPartial = false
        return if (isEdit) findPrevEdit(from, wrap) else findPrevView(from, wrap, maxMillis)
    }

    private fun findNextEdit(from: Pos, wrap: Boolean, inclusive: Boolean): Match? {
        val d = store as EditableDocument
        refreshSnapshot()
        val text = snapText!!
        val off = d.offsetOf(from)
        var r = findSafe(text, if (inclusive) off else off + 1)
        if (r < 0 && wrap) r = findSafe(text, 0)
        if (r < 0) return null
        return Match(d.posOf((r ushr 32).toInt()), d.posOf((r and 0xFFFFFFFFL).toInt()))
    }

    private fun lastMatchBefore(text: String, limit: Int): Long {
        var win = 1 shl 16
        while (true) {
            var ws = maxOf(0, limit - win)
            if (ws > 0) { val nl = text.lastIndexOf('\n', ws - 1); ws = if (nl < 0) 0 else nl + 1 }
            var pos = ws; var last = -1L
            while (true) {
                val r = findSafe(text, pos)
                if (r < 0) break
                val s = (r ushr 32).toInt()
                if (s >= limit) break
                last = r; pos = (r and 0xFFFFFFFFL).toInt()
            }
            if (last >= 0 || ws == 0) return last
            win *= 4
        }
    }

    private fun findPrevEdit(from: Pos, wrap: Boolean): Match? {
        val d = store as EditableDocument
        refreshSnapshot()
        val text = snapText!!
        val off = d.offsetOf(from)
        var r = lastMatchBefore(text, off)
        if (r < 0 && wrap) r = lastMatchBefore(text, text.length + 1)
        if (r < 0) return null
        return Match(d.posOf((r ushr 32).toInt()), d.posOf((r and 0xFFFFFFFFL).toInt()))
    }

    private fun findNextView(from: Pos, wrap: Boolean, inclusive: Boolean, maxMillis: Long): Match? {
        val deadline = System.nanoTime() + maxMillis * 1_000_000
        var found: Match? = null
        fun scan(l0: Int, l1: Int, firstCol: Int, lastLineMaxStart: Int): Boolean {
            val visit = { l: Int, s: String ->
                val c0 = if (l == l0) firstCol else 0
                val r = if (c0 > s.length) -1L else try { cq.find(s, c0, System.nanoTime() + 200_000_000L) } catch (_: SearchTimeout) { timedOut = true; -1L }
                if (r >= 0 && !(l == l1 && (r ushr 32).toInt() > lastLineMaxStart)) {
                    found = Match(Pos(l, (r ushr 32).toInt()), Pos(l, (r and 0xFFFFFFFFL).toInt())); false
                } else if (System.nanoTime() > deadline) { lastFindPartial = true; false } else true
            }
            if (store is ViewDocument) store.forEachLine(l0, l1 + 1, visit) else { var l = l0; while (l <= l1) { if (!visit(l, store.getLine(l))) break; l++ } }
            return found != null || lastFindPartial
        }
        val lc = store.lineCount
        if (!store.isIndexComplete) lastFindPartial = true
        val startCol = if (inclusive) from.col else from.col + 1
        if (from.line < lc && scan(from.line, lc - 1, startCol, Int.MAX_VALUE)) return found
        if (wrap) { if (scan(0, minOf(from.line, lc - 1), 0, from.col)) return found }
        return found
    }

    private fun findPrevView(from: Pos, wrap: Boolean, maxMillis: Long): Match? {
        val deadline = System.nanoTime() + maxMillis * 1_000_000
        fun lastIn(s: String, maxStartExclusive: Int): Long {
            var pos = 0; var last = -1L
            while (pos <= s.length) {
                val r = try { cq.find(s, pos, System.nanoTime() + 200_000_000L) } catch (_: SearchTimeout) { timedOut = true; -1L }
                if (r < 0) break
                val st = (r ushr 32).toInt(); if (st >= maxStartExclusive) break
                last = r; pos = (r and 0xFFFFFFFFL).toInt()
            }
            return last
        }
        val lc = store.lineCount
        if (!store.isIndexComplete) lastFindPartial = true
        var l = minOf(from.line, lc - 1)
        while (l >= 0) {
            val r = lastIn(store.getLine(l), if (l == from.line) from.col else Int.MAX_VALUE)
            if (r >= 0) return Match(Pos(l, (r ushr 32).toInt()), Pos(l, (r and 0xFFFFFFFFL).toInt()))
            if (System.nanoTime() > deadline) { lastFindPartial = true; return null }
            l--
        }
        if (wrap) {
            l = lc - 1
            while (l >= from.line) {
                val r = lastIn(store.getLine(l), Int.MAX_VALUE)
                if (r >= 0) return Match(Pos(l, (r ushr 32).toInt()), Pos(l, (r and 0xFFFFFFFFL).toInt()))
                if (System.nanoTime() > deadline) { lastFindPartial = true; return null }
                l--
            }
        }
        return null
    }

    // ---------------- painting / counter ----------------
    /** [col,len,...] of matches on this line, computed directly (UI thread). */
    fun matchesInLine(line: Int): IntArray {
        if (cq.isEmpty || line < 0 || line >= store.lineCount) return CompiledQuery.EMPTY
        val ver = store.contentVersion
        if (ver != lineCacheVersion) { lineCache.clear(); lineCacheVersion = ver }
        lineCache[line]?.let { return it }
        val r = try { cq.matchesInLine(store.getLine(line), 2000, System.nanoTime() + 100_000_000L) } catch (_: SearchTimeout) { CompiledQuery.EMPTY }
        if (lineCache.size > 512) lineCache.clear()
        lineCache[line] = r
        return r
    }

    /** 1-based ordinal of the stored match starting at m.start, or -1 when unknown (not scanned yet / stale). */
    fun ordinalOf(m: Match): Long {
        if (isEdit && snapVersion != (store as EditableDocument).contentVersion) return -1
        val base = pack(m.start.line, m.start.col, 0)
        synchronized(lock) {
            var lo = 0; var hi = count
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (matches[mid] < base) lo = mid + 1 else hi = mid }
            if (lo < count && (matches[lo] ushr LEN_BITS) == (base ushr LEN_BITS)) return (lo + 1).toLong()
        }
        return -1
    }
}
