package app.codeedit.highlight

import app.codeedit.core.doc.DocumentListener
import app.codeedit.core.doc.EditableDocument
import app.codeedit.core.doc.LineStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

/**
 * [SyntaxHighlighter] on top of a native TextMate session (one session per document).
 *
 * Model: the engine keeps its state every `stride` lines ("checkpoints"). A request tokenizes the visible block from the
 * nearest checkpoint (exact), or, when that is further than [COLD_DISTANCE] lines away, from a cold start a few lines
 * before the block (approximate: [isApproximate]). A low priority "frontier" job walks the document forward, recording
 * checkpoints, so that approximate regions are replaced by exact ones. Tokens live in a bounded LRU-ish cache.
 *
 * Threading: [tokensFor]/[requestRange]/[onDocumentChanged]/[invalidateAll]/[close] are UI-thread calls. Tokenization runs on
 * [worker]; the native session is only touched while holding [sessionLock]. A document that is only readable on the UI
 * thread (EditableDocument) is read through [ui] (the worker never holds [sessionLock] while waiting for the UI thread).
 */
class NativeHighlighter internal constructor(
    private val backend: TokenizerBackend,
    private val registry: Long,
    private val store: LineStore,
    override val languageId: String,
    private val scope: String,
    private val worker: Executor,
    private val ui: Executor,
    initialColorMap: IntArray,
    private val maxLineChars: Int,
    private val onClosed: (NativeHighlighter) -> Unit,
) : SyntaxHighlighter, DocumentListener {

    @Volatile override var colorMap: IntArray = initialColorMap
        internal set
    @Volatile override var listener: HighlightListener? = null

    private val sessionLock = ReentrantLock()
    private var session = 0L                      // guarded by sessionLock
    private val stride: Int = chooseStride(store.estimatedLineCount)

    private val cacheLock = Any()
    private val cache = object : LinkedHashMap<Int, LineTokens>(1024, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, LineTokens>?): Boolean = size > MAX_CACHE
    }
    private val approx = ArrayList<IntArray>()    // [firstLine, lastLine] ranges tokenized from a cold start; guarded by cacheLock

    private val generation = AtomicInteger()
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val reqLock = Any()
    private var reqFirst = -1                      // pending request (guarded by reqLock)
    private var reqLast = -1
    @Volatile private var lastFirst = 0            // last requested viewport (+margin), re-tokenized after edits
    @Volatile private var lastLast = 0
    @Volatile private var frontier = 0             // highest checkpoint line (state there is exact)
    @Volatile private var frontierDone = false
    @Volatile private var sessionFailed = false

    override val exactFrontier: Int get() = frontier

    init {
        store.addListener(this)
    }

    // ------------------------------------------------------------------ SyntaxHighlighter (UI thread)

    override fun tokensFor(line: Int): LineTokens? = synchronized(cacheLock) { cache[line] }

    override fun requestRange(firstLine: Int, lastLine: Int) {
        if (closed.get() || sessionFailed) return
        val lc = store.lineCount
        val f = (firstLine - MARGIN).coerceAtLeast(0)
        val l = (lastLine + MARGIN).coerceAtMost(lc - 1)
        if (l < f) return
        lastFirst = f; lastLast = l
        var missing = false
        synchronized(cacheLock) { for (i in f..l) if (!cache.containsKey(i)) { missing = true; break } }
        if (!missing) return
        post(f, l)
    }

    /**
     * UI thread: tokenizes the first screenful immediately (bounded: only when an exact checkpoint is within [PREFETCH_MAX_DISTANCE]
     * lines), so the first frame is already coloured instead of flashing plain text. Everything else stays asynchronous.
     */
    override fun prefetch(firstLine: Int, lastLine: Int) {
        if (closed.get() || sessionFailed) return
        val lc = store.lineCount
        if (lc <= 0) return
        val f = firstLine.coerceIn(0, lc - 1)
        val l = lastLine.coerceIn(f, lc - 1)
        lastFirst = (f - MARGIN).coerceAtLeast(0); lastLast = (l + MARGIN).coerceAtMost(lc - 1)
        if (!sessionLock.tryLock()) return
        try {
            if (!ensureSession()) return
            applyPendingInvalidation()
            if (f - backend.nearestCheckpoint(session, f) > PREFETCH_MAX_DISTANCE) return
            runBlock(f, l, ::fetchDirect, generation.get(), notify = false)
        } catch (_: Throwable) {
        } finally { sessionLock.unlock() }
    }

    override fun onContentChanged(startLine: Int, oldLineCount: Int, newLineCount: Int) = onDocumentChanged(startLine, oldLineCount, newLineCount)

    override fun onIndexProgress(lineCount: Int, complete: Boolean) {
        // more of a streaming document became available: the frontier can continue (and a pending request may now be satisfiable)
        if (frontierDone) { frontierDone = false; kick() }
    }

    override fun onDocumentChanged(startLine: Int, oldLineCount: Int, newLineCount: Int) {
        if (closed.get()) return
        generation.incrementAndGet()
        val delta = newLineCount - oldLineCount
        synchronized(cacheLock) {
            // keep the colours of the unchanged lines below the edit (shifted) so the screen does not flash;
            // lines inside the edit are dropped and re-tokenized right below
            // (lines that exist before and after the edit keep their stale tokens until the re-tokenization below)
            val moved = ArrayList<Pair<Int, LineTokens>>()
            val keepUntil = startLine + minOf(oldLineCount, newLineCount)
            val it = cache.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.key >= startLine) {
                    if (e.key >= startLine + oldLineCount) moved.add((e.key + delta) to e.value)
                    else if (e.key < keepUntil) moved.add(e.key to e.value)
                    it.remove()
                }
            }
            for ((k, v) in moved) cache[k] = v
            approx.clear()
        }
        frontierDone = false
        // edited lines synchronously (small: <= one stride of work) so typing never shows unstyled text
        if (sessionLock.tryLock()) {
            try {
                if (session != 0L) {
                    backend.invalidateFrom(session, startLine)
                    frontier = backend.maxCheckpoint(session)
                    val last = (startLine + newLineCount - 1).coerceAtLeast(startLine).coerceAtMost(store.lineCount - 1)
                    runBlock(startLine, last, ::fetchDirect, generation.get(), notify = false)
                }
            } catch (_: Throwable) {
            } finally { sessionLock.unlock() }
        } else {
            sessionInvalidateFrom = minOf(sessionInvalidateFrom, startLine)
        }
        // then the visible block below (state may have changed, e.g. an opened comment) and the frontier
        val f = lastFirst.coerceAtLeast(0); val l = lastLast.coerceAtMost(store.lineCount - 1)
        if (l >= f) post(f, l) else kick()
    }

    @Volatile private var sessionInvalidateFrom = Int.MAX_VALUE   // edits that arrived while the worker held the session

    override fun invalidateAll() {
        if (closed.get()) return
        generation.incrementAndGet()
        synchronized(cacheLock) { cache.clear(); approx.clear() }
        sessionInvalidateFrom = -1
        frontierDone = false
        val f = lastFirst.coerceAtLeast(0); val l = lastLast.coerceAtMost(store.lineCount - 1)
        if (l >= f) post(f, l) else kick()
    }

    /** New colour table after a theme change: all tokens carry colour ids of the old table. */
    internal fun onThemeChanged(newMap: IntArray) {
        colorMap = newMap
        invalidateAll()
        val l = listener
        if (l != null) ui.execute { l.onTokensChanged(0, Int.MAX_VALUE) }
    }

    override fun isApproximate(line: Int): Boolean = synchronized(cacheLock) { approx.any { line >= it[0] && line <= it[1] } }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        store.removeListener(this)
        onClosed(this)
        listener = null
        synchronized(cacheLock) { cache.clear(); approx.clear() }
        // the worker may be inside a native call: wait for it (bounded by one chunk), then free the session
        sessionLock.lock()
        try {
            if (session != 0L) { backend.destroySession(session); session = 0L }
        } finally { sessionLock.unlock() }
    }

    // ------------------------------------------------------------------ scheduling

    private fun post(first: Int, last: Int) {
        synchronized(reqLock) { reqFirst = first; reqLast = last }
        kick()
    }

    private fun kick() {
        if (closed.get() || sessionFailed) return
        if (running.compareAndSet(false, true)) {
            try { worker.execute(::pump) } catch (t: Throwable) { running.set(false) }
        }
    }

    private fun takeRequest(): IntArray? = synchronized(reqLock) {
        if (reqFirst < 0) null else intArrayOf(reqFirst, reqLast).also { reqFirst = -1; reqLast = -1 }
    }

    private fun hasRequest(): Boolean = synchronized(reqLock) { reqFirst >= 0 }

    private fun pump() {
        try {
            while (!closed.get() && !sessionFailed) {
                val req = takeRequest()
                if (req != null) {
                    val gen = generation.get()
                    val ok = processRequest(req[0], req[1], gen)
                    if (!ok && generation.get() != gen) post(req[0].coerceAtLeast(0), req[1])   // invalidated meanwhile: redo
                    continue
                }
                if (!frontierDone && frontierStep()) {
                    try { Thread.sleep(FRONTIER_PAUSE_MS) } catch (_: InterruptedException) { return }
                    continue
                }
                break
            }
        } catch (t: Throwable) {
            // never let a native/IO problem kill the worker; the view simply stays unstyled
            errorSink?.invoke(t)
        } finally {
            running.set(false)
            if (!closed.get() && !sessionFailed && (hasRequest() || !frontierDone)) kick()
        }
    }

    // ------------------------------------------------------------------ tokenization

    private class Chunk(val chars: CharArray, val offsets: IntArray, val count: Int, val docLines: Int)

    private fun fetchDirect(from: Int, toExclusive: Int): Chunk {
        val docLines = store.lineCount
        val to = minOf(toExclusive, docLines)
        if (from >= to) return Chunk(CharArray(0), intArrayOf(0), 0, docLines)
        var total = 0
        var n = 0
        val lens = IntArray(to - from)
        while (from + n < to) {
            val len = minOf(store.lineLength(from + n), maxLineChars + 1)   // over-long lines are not tokenized: a prefix is enough
            if (n > 0 && total + len > MAX_CHUNK_CHARS) break
            lens[n] = len; total += len; n++
        }
        val chars = CharArray(total)
        val offsets = IntArray(n + 1)
        var o = 0
        for (i in 0 until n) {
            offsets[i] = o
            store.copyLine(from + i, 0, lens[i], chars, o)
            o += lens[i]
        }
        offsets[n] = o
        return Chunk(chars, offsets, n, docLines)
    }

    /** Reads on the UI thread when the document is only safe to read there. Must NOT be called with [sessionLock] held. */
    private fun fetchForWorker(from: Int, to: Int): Chunk? {
        if (store !is EditableDocument) return fetchDirect(from, to)
        val latch = CountDownLatch(1)
        var result: Chunk? = null
        try {
            ui.execute { try { if (!closed.get()) result = fetchDirect(from, to) } finally { latch.countDown() } }
        } catch (t: Throwable) { return null }
        if (!latch.await(3, TimeUnit.SECONDS)) return null
        return result
    }

    private fun ensureSession(): Boolean {
        // sessionLock held
        if (session != 0L) return true
        val s = backend.createSession(registry, scope, maxLineChars, stride, MAX_CHECKPOINTS)
        if (s == 0L) { sessionFailed = true; return false }
        session = s
        return true
    }

    private fun processRequest(first: Int, last: Int, gen: Int): Boolean {
        val f = first.coerceAtLeast(0)
        if (last < f) return true
        val ok = runBlockOnWorker(f, last, gen)
        if (ok) {
            val lst = listener
            if (lst != null) ui.execute { lst.onTokensChanged(f, last) }
        }
        return ok
    }

    /** Worker-side entry: fetches without holding the session lock. */
    private fun runBlockOnWorker(first: Int, last: Int, gen: Int): Boolean {
        // 1. which checkpoint do we start from? (short critical section)
        var cp: Int
        sessionLock.lock()
        try {
            if (!ensureSession()) return false
            applyPendingInvalidation()
            cp = backend.nearestCheckpoint(session, first)
        } finally { sessionLock.unlock() }
        if (first - cp > COLD_DISTANCE) return runCold(first, last, gen)
        return runExact(first, last, gen)
    }

    private fun applyPendingInvalidation() {
        val from = sessionInvalidateFrom
        if (from != Int.MAX_VALUE) {
            sessionInvalidateFrom = Int.MAX_VALUE
            backend.invalidateFrom(session, from)
            frontier = backend.maxCheckpoint(session)
        }
    }

    private fun runCold(first: Int, last: Int, gen: Int): Boolean {
        val start = (first - COLD_CONTEXT).coerceAtLeast(1)
        val chunk = fetchForWorker(start, last + 1) ?: return false
        if (chunk.count == 0) return true
        sessionLock.lock()
        try {
            if (closed.get() || session == 0L || generation.get() != gen) return false
            val out = backend.tokenize(session, start, chunk.chars, chunk.offsets, chunk.count, first, true, false, LINE_TIME_LIMIT_MS) ?: return false
            val lastDone = start + chunk.count - 1
            store(out, first, lastDone, gen, cold = true)
        } finally { sessionLock.unlock() }
        return true
    }

    private fun runExact(first: Int, last: Int, gen: Int): Boolean {
        var resume = first            // next line whose tokens we still need
        var chunkLines = CHUNK_LINES
        var prevStart = -1
        while (resume <= last) {
            if (closed.get() || generation.get() != gen) return false
            var c: Int
            sessionLock.lock()
            try {
                if (session == 0L) return false
                applyPendingInvalidation()
                c = backend.nearestCheckpoint(session, resume)
            } finally { sessionLock.unlock() }
            if (c == prevStart) chunkLines *= 2     // the engine kept no checkpoint inside the last chunk (stride > chunk)
            prevStart = c
            val end = minOf(last + 1, c + chunkLines)
            val chunk = fetchForWorker(c, end) ?: return false
            if (chunk.count == 0) return true
            sessionLock.lock()
            try {
                if (closed.get() || session == 0L || generation.get() != gen) return false
                val out = backend.tokenize(session, c, chunk.chars, chunk.offsets, chunk.count, maxOf(first, resume), false, true, 0) ?: return false
                frontier = backend.maxCheckpoint(session)
                store(out, maxOf(first, resume), c + chunk.count - 1, gen, cold = false)
            } finally { sessionLock.unlock() }
            resume = c + chunk.count
        }
        return true
    }

    /** Synchronous variant used right after an edit; sessionLock is held by the caller (UI thread). */
    private fun runBlock(first: Int, last: Int, fetch: (Int, Int) -> Chunk, gen: Int, notify: Boolean): Boolean {
        var resume = first
        var chunkLines = CHUNK_LINES
        var prevStart = -1
        while (resume <= last) {
            if (generation.get() != gen) return false
            val c = backend.nearestCheckpoint(session, resume)
            if (c == prevStart) chunkLines *= 2
            prevStart = c
            val chunk = fetch(c, minOf(last + 1, c + chunkLines))
            if (chunk.count == 0) return true
            val out = backend.tokenize(session, c, chunk.chars, chunk.offsets, chunk.count, maxOf(first, resume), false, true, 0) ?: return false
            frontier = backend.maxCheckpoint(session)
            store(out, maxOf(first, resume), c + chunk.count - 1, gen, cold = false)
            resume = c + chunk.count
        }
        if (notify) { val l = listener; if (l != null) ui.execute { l.onTokensChanged(first, last) } }
        return true
    }

    /** Parses [n, start0, meta0, ...] per emitted line (lines emitFrom..lastLine) into the cache. */
    private fun store(out: IntArray, emitFrom: Int, lastLine: Int, gen: Int, cold: Boolean) {
        val parsed = ArrayList<LineTokens>(maxOf(0, lastLine - emitFrom + 1))
        var p = 0
        while (p < out.size) {
            val n = out[p++]
            val starts = IntArray(n); val meta = IntArray(n)
            for (i in 0 until n) { starts[i] = out[p++]; meta[i] = out[p++] }
            parsed.add(LineTokens(n, starts, meta))
        }
        synchronized(cacheLock) {
            if (generation.get() != gen) return
            for ((i, t) in parsed.withIndex()) cache[emitFrom + i] = t
            if (cold && parsed.isNotEmpty()) approx.add(intArrayOf(emitFrom, emitFrom + parsed.size - 1))
            else if (parsed.isNotEmpty()) removeApprox(emitFrom, emitFrom + parsed.size - 1)
        }
    }

    private fun removeApprox(a: Int, b: Int) {
        // cacheLock held
        val it = approx.iterator()
        while (it.hasNext()) { val r = it.next(); if (r[0] >= a && r[1] <= b) it.remove() }
    }

    // ------------------------------------------------------------------ frontier

    @Volatile private var frontierChunk = FRONTIER_CHUNK

    /** Walks one chunk beyond the highest checkpoint. Returns true when it did work (and more may remain). */
    private fun frontierStep(): Boolean {
        val gen = generation.get()
        val from: Int
        sessionLock.lock()
        try {
            if (!ensureSession()) { frontierDone = true; return false }
            applyPendingInvalidation()
            from = backend.maxCheckpoint(session)
            frontier = from
        } finally { sessionLock.unlock() }
        if (from >= FRONTIER_CAP_LINES) { frontierDone = true; return false }
        val chunk = fetchForWorker(from, from + frontierChunk)
        if (chunk == null) { frontierDone = true; return false }
        if (chunk.count == 0) { frontierDone = true; return false }   // nothing (more) to read; re-armed by index progress / edits
        sessionLock.lock()
        try {
            if (closed.get() || session == 0L || generation.get() != gen) return true
            if (backend.tokenize(session, from, chunk.chars, chunk.offsets, chunk.count, Int.MAX_VALUE, false, true, 0) == null) { frontierDone = true; return false }
            frontier = backend.maxCheckpoint(session)
        } finally { sessionLock.unlock() }
        // no checkpoint inside this chunk (stride larger than the chunk): read more next time
        frontierChunk = if (frontier <= from) minOf(frontierChunk * 2, 1 shl 16) else FRONTIER_CHUNK
        refreshApprox()
        if (from + chunk.count >= chunk.docLines) { frontierDone = true; return false }
        return true
    }

    /** Approximate blocks that are now within reach of an exact checkpoint get re-tokenized. */
    private fun refreshApprox() {
        var target: IntArray? = null
        synchronized(cacheLock) {
            for (r in approx) if (r[0] - frontier <= COLD_DISTANCE) { target = r; break }
            target?.let { approx.remove(it) }
        }
        val t = target ?: return
        post(t[0], t[1])
    }

    companion object {
        /** Diagnostics hook for unexpected worker failures (the app logs them; tests print them). */
        @Volatile @JvmStatic var errorSink: ((Throwable) -> Unit)? = null

        const val MARGIN = 40
        const val PREFETCH_MAX_DISTANCE = 300
        const val MAX_CACHE = 4000
        const val COLD_DISTANCE = 20_000
        const val COLD_CONTEXT = 80
        const val CHUNK_LINES = 600
        const val MAX_CHUNK_CHARS = 4 shl 20
        const val MAX_CHECKPOINTS = 8192
        const val FRONTIER_CHUNK = 512
        const val FRONTIER_PAUSE_MS = 8L
        const val FRONTIER_CAP_LINES = 1_500_000
        const val LINE_TIME_LIMIT_MS = 200

        /** 64 for small documents up to ~2048 for tens of millions of lines. */
        @JvmStatic fun chooseStride(estimatedLines: Long): Int {
            var s = 64
            while (s < 2048 && estimatedLines / s > 4096) s *= 2
            return s
        }
    }
}
