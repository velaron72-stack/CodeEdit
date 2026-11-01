package app.codeedit.core.doc

import app.codeedit.core.io.RandomAccessSource
import app.codeedit.core.io.TextCodec
import java.nio.charset.Charset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/**
 * Read-only streaming document for files of any size. Nothing is loaded up front: lines are decoded on demand from
 * a bounded block cache, and a background pass builds a sparse line index (one byte-offset anchor per ANCHOR_STRIDE
 * lines, so a 1 GB file costs a few MB). The first lines are readable immediately, before indexing finishes.
 *
 * Line terminators: "\n", "\r\n" and a lone "\r". Thread-safe for reads. Owns [source]: close() closes it.
 */
class ViewDocument(
    val source: RandomAccessSource,
    val info: DocInfo,
    /** Bytes to skip at the start (BOM). */
    private val startOffset: Long = 0L,
    executor: Executor? = null,
) : LineStore {
    companion object {
        /** A line longer than this many chars is shown truncated (see [isTruncated]). */
        const val MAX_LINE_CHARS = 1 shl 16
        const val ANCHOR_STRIDE = 64
        private const val FIRST_CHUNK = 64 * 1024
        private const val BLOCK = 256 * 1024
        private const val CACHE_BLOCKS = 16
        private const val NO_BREAK = -1L
    }

    private val charset: Charset = TextCodec.charset(info.encoding)
    private val utf16 = TextCodec.isUtf16(info.encoding)
    private val utf16le = info.encoding.charsetName.equals("UTF-16LE", true)
    private val unit = if (utf16) 2 else 1
    private val maxLineBytes: Long = MAX_LINE_CHARS.toLong() * (if (utf16) 2 else 4)

    // ---- index state (written by the indexer thread only) ----
    @Volatile private var anchors = LongArray(1024).also { it[0] = startOffset }
    @Volatile private var anchorCount = 1
    @Volatile private var knownLines = 1          // lines started so far (>= 1)
    @Volatile private var scannedBytes = 0L
    @Volatile private var complete = false
    @Volatile private var closed = false

    private val listeners = CopyOnWriteArrayList<DocumentListener>()

    // ---- caches ----
    private val blockCache = object : LinkedHashMap<Long, ByteArray>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > CACHE_BLOCKS
    }
    private val lineCache = object : LinkedHashMap<Int, String>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>?) = size > 512
    }
    private val truncated = HashSet<Int>()

    private val thread: Thread?

    init {
        val task = Runnable { indexLoop() }
        if (executor != null) { executor.execute(task); thread = null }
        else thread = Thread(task, "view-index").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1; start() }
    }

    // ---------------- LineStore ----------------
    override val lineCount: Int get() = knownLines
    override val isIndexComplete: Boolean get() = complete
    override val indexProgress: Float
        get() = if (complete) 1f else (scannedBytes.toDouble() / maxOf(1L, source.size)).toFloat().coerceIn(0f, 0.999f)
    override val estimatedLineCount: Long
        get() {
            if (complete) return knownLines.toLong()
            val s = scannedBytes
            if (s <= 0) return knownLines.toLong()
            return maxOf(knownLines.toLong(), (knownLines.toDouble() * source.size / s).toLong())
        }
    override val contentVersion: Long get() = 0L

    override fun lineLength(line: Int): Int = getLine(line).length

    override fun getLine(line: Int): String {
        if (line < 0 || line >= knownLines || closed) return ""
        synchronized(lineCache) { lineCache[line]?.let { return it } }
        val s = decodeLine(line)
        synchronized(lineCache) { lineCache[line] = s }
        return s
    }

    override fun copyLine(line: Int, from: Int, to: Int, dst: CharArray, dstOff: Int): Int {
        val s = getLine(line)
        val a = from.coerceIn(0, s.length)
        val b = to.coerceIn(a, s.length)
        s.toCharArray(dst, dstOff, a, b)
        return b - a
    }

    override fun getText(from: Pos, to: Pos, maxChars: Int): String {
        val sb = StringBuilder()
        val last = minOf(to.line, knownLines - 1)
        for (l in maxOf(0, from.line)..last) {
            val s = getLine(l)
            val a = if (l == from.line) from.col.coerceIn(0, s.length) else 0
            val b = if (l == to.line) to.col.coerceIn(a, s.length) else s.length
            sb.append(s, a, b)
            if (l != last) sb.append('\n')
            if (sb.length >= maxChars) { sb.setLength(maxChars); break }
        }
        return sb.toString()
    }

    override fun addListener(l: DocumentListener) { listeners.add(l) }
    override fun removeListener(l: DocumentListener) { listeners.remove(l) }

    override fun close() {
        closed = true
        thread?.interrupt()
        synchronized(blockCache) { blockCache.clear() }
        synchronized(lineCache) { lineCache.clear() }
        try { source.close() } catch (_: Throwable) {}
    }

    /** True if the line was cut at MAX_LINE_CHARS. */
    fun isTruncated(line: Int): Boolean { getLine(line); return synchronized(lineCache) { line in truncated } }

    /** Sequentially visits lines [start, endExclusive) efficiently (no per-line anchor seek). cb returns false to stop. */
    fun forEachLine(start: Int, endExclusive: Int, cb: (Int, String) -> Boolean) {
        var line = maxOf(0, start)
        if (line >= knownLines) return
        var pos = lineStart(line)
        val end = minOf(endExclusive, Int.MAX_VALUE)
        while (line < end && line < knownLines && !closed) {
            val br = findBreak(pos)
            val lineEnd: Long; val next: Long; val trunc: Boolean
            if (br == NO_BREAK) { lineEnd = minOf(source.size, pos + maxLineBytes); next = source.size; trunc = lineEnd - pos >= maxLineBytes && source.size > lineEnd }
            else { val bs = br shr 2; lineEnd = minOf(bs, pos + maxLineBytes); next = bs + brLen(br); trunc = bs - pos > maxLineBytes }
            val s = decode(pos, lineEnd, trunc)
            if (!cb(line, s)) return
            line++
            if (br == NO_BREAK) return
            pos = next
        }
    }

    // ---------------- decoding ----------------
    private fun decodeLine(line: Int): String {
        val start = lineStart(line)
        val br = findBreak(start)
        val end: Long; val trunc: Boolean
        if (br == NO_BREAK) { end = minOf(source.size, start + maxLineBytes); trunc = source.size - start > maxLineBytes }
        else { val bs = br shr 2; end = minOf(bs, start + maxLineBytes); trunc = bs - start > maxLineBytes }
        val s = decode(start, end, trunc)
        if (trunc) synchronized(lineCache) { if (truncated.size > 1000) truncated.clear(); truncated.add(line) }
        return s
    }

    private fun decode(start: Long, end: Long, trunc: Boolean): String {
        val n = (end - start).toInt()
        if (n <= 0) return ""
        val bytes = readRange(start, n)
        var s = String(bytes, 0, bytes.size, charset)
        if (trunc) {
            if (s.length > MAX_LINE_CHARS) s = s.substring(0, MAX_LINE_CHARS)
            if (s.isNotEmpty() && s[s.length - 1] == '�') s = s.substring(0, s.length - 1)
        }
        return s
    }

    private fun readRange(start: Long, n: Int): ByteArray {
        val out = ByteArray(n)
        var done = 0
        var pos = start
        while (done < n) {
            val bi = pos / BLOCK
            val blk = getBlock(bi)
            val off = (pos - bi * BLOCK).toInt()
            if (off >= blk.size) break
            val c = minOf(n - done, blk.size - off)
            System.arraycopy(blk, off, out, done, c)
            done += c; pos += c
        }
        return if (done == n) out else out.copyOf(done)
    }

    /** A block (exact length; shorter than BLOCK only at the end of a final source). */
    private fun getBlock(idx: Long): ByteArray {
        synchronized(blockCache) { blockCache[idx]?.let { return it } }
        val base = idx * BLOCK
        val buf = ByteArray(BLOCK)
        var got = 0
        while (got < BLOCK) {
            val r = source.read(base + got, buf, got, BLOCK - got)
            if (r <= 0) break
            got += r
        }
        val blk = if (got == BLOCK) buf else buf.copyOf(got)
        if (got == BLOCK || source.isFinal) synchronized(blockCache) { blockCache[idx] = blk }
        return blk
    }

    private fun byteAt(pos: Long): Int {
        val bi = pos / BLOCK
        val blk = getBlock(bi)
        val off = (pos - bi * BLOCK).toInt()
        return if (off < blk.size) blk[off].toInt() and 0xFF else -1
    }

    /** Finds the next line terminator at/after `from` within maxLineBytes (+slack). Returns (start shl 2) or len, or -1. */
    private fun findBreak(from: Long, maxScan: Long = maxLineBytes + 16): Long {
        val limit = if (maxScan >= Long.MAX_VALUE - from) Long.MAX_VALUE else from + maxScan
        var p = from
        val size = source.size
        while (p < size && p < limit) {
            val bi = p / BLOCK
            val blk = getBlock(bi)
            var off = (p - bi * BLOCK).toInt()
            val n = blk.size
            if (off >= n) return NO_BREAK
            if (!utf16) {
                while (off < n) {
                    val b = blk[off].toInt()
                    if (b == 10) return ((bi * BLOCK + off) shl 2) or 1L
                    if (b == 13) {
                        val q = bi * BLOCK + off
                        val nb = if (off + 1 < n) blk[off + 1].toInt() and 0xFF else byteAt(q + 1)
                        return (q shl 2) or (if (nb == 10) 2L else 1L)
                    }
                    off++
                }
            } else {
                if ((off and 1) != 0) off++ // BOM offsets are even, blocks are even: stay aligned
                while (off + 1 < n) {
                    val lo = blk[if (utf16le) off else off + 1].toInt()
                    val hi = blk[if (utf16le) off + 1 else off].toInt()
                    if (hi == 0 && (lo == 10 || lo == 13)) {
                        val q = bi * BLOCK + off
                        if (lo == 10) return (q shl 2) or 1L
                        // CR: is it followed by LF? (code 2 = CRLF, counted in units)
                        val b0 = byteAt(q + 2); val b1 = byteAt(q + 3)
                        val nlo = if (utf16le) b0 else b1; val nhi = if (utf16le) b1 else b0
                        return (q shl 2) or (if (nlo == 10 && nhi == 0) 2L else 1L)
                    }
                    off += 2
                }
            }
            p = (bi + 1) * BLOCK
        }
        return NO_BREAK
    }

    /** Terminator length in bytes: low 2 bits hold the length in code units (1 = LF/CR, 2 = CRLF). */
    private fun brLen(br: Long): Int = (br and 3).toInt() * unit

    /** Byte offset where `line` starts; requires line < knownLines. */
    private fun lineStart(line: Int): Long {
        val a = line / ANCHOR_STRIDE
        val arr = anchors
        var cur = arr[minOf(a, anchorCount - 1)]
        var rem = line - minOf(a, anchorCount - 1) * ANCHOR_STRIDE
        while (rem > 0) {
            val br = findBreak(cur, Long.MAX_VALUE) // uncapped: skipping whole lines, however long
            if (br == NO_BREAK) break
            cur = (br shr 2) + brLen(br)
            rem--
        }
        return cur
    }

    // ---------------- indexing ----------------
    private fun addAnchor(offset: Long) {
        var arr = anchors
        val n = anchorCount
        if (n == arr.size) { arr = arr.copyOf(n * 2); anchors = arr }
        arr[n] = offset
        anchorCount = n + 1
    }

    private fun fire(complete: Boolean) {
        val lc = knownLines
        for (l in listeners) try { l.onIndexProgress(lc, complete) } catch (_: Throwable) {}
    }

    private fun indexLoop() {
        val buf = ByteArray(1 shl 20)
        var pos = startOffset
        var lines = 0L          // line terminators seen
        var lineStartOff = startOffset
        var pendingCr = false   // CR at the very end of the previous buffer, LF decision deferred
        var crOffset = 0L
        var lastFire = 0L
        try {
            while (!closed) {
                // first chunk is small so the first screenful of lines is published within a millisecond or two
                var n = source.read(pos, buf, 0, if (pos == startOffset) FIRST_CHUNK else buf.size)
                if (n < 0) break
                if (n == 0) { source.awaitData(pos, 100); continue }
                if (utf16 && (n and 1) != 0) { n-- ; if (n == 0) { source.awaitData(pos + 1, 50); continue } }
                var i = 0
                if (pendingCr) {
                    // decide CR vs CRLF using first unit
                    val isLf = if (!utf16) buf[0].toInt() == 10 else (buf[if (utf16le) 0 else 1].toInt() == 10 && buf[if (utf16le) 1 else 0].toInt() == 0)
                    lines++
                    if (isLf) { i = unit; lineStartOff = crOffset + 2L * unit } else lineStartOff = crOffset + unit
                    if (lines % ANCHOR_STRIDE == 0L) addAnchor(lineStartOff)
                    pendingCr = false
                }
                if (!utf16) {
                    while (i < n) {
                        val b = buf[i].toInt()
                        if (b == 10) {
                            lines++; lineStartOff = pos + i + 1
                            if (lines % ANCHOR_STRIDE == 0L) addAnchor(lineStartOff)
                        } else if (b == 13) {
                            if (i + 1 < n) {
                                if (buf[i + 1].toInt() == 10) { i++; lines++; lineStartOff = pos + i + 1 }
                                else { lines++; lineStartOff = pos + i + 1 }
                                if (lines % ANCHOR_STRIDE == 0L) addAnchor(lineStartOff)
                            } else { pendingCr = true; crOffset = pos + i }
                        }
                        i++
                    }
                } else {
                    while (i + 1 < n) {
                        val lo = buf[if (utf16le) i else i + 1].toInt()
                        val hi = buf[if (utf16le) i + 1 else i].toInt()
                        if (hi == 0) {
                            if (lo == 10) {
                                lines++; lineStartOff = pos + i + 2
                                if (lines % ANCHOR_STRIDE == 0L) addAnchor(lineStartOff)
                            } else if (lo == 13) {
                                if (i + 3 < n) {
                                    val nlo = buf[if (utf16le) i + 2 else i + 3].toInt()
                                    val nhi = buf[if (utf16le) i + 3 else i + 2].toInt()
                                    if (nlo == 10 && nhi == 0) i += 2
                                    lines++; lineStartOff = pos + i + 2
                                    if (lines % ANCHOR_STRIDE == 0L) addAnchor(lineStartOff)
                                } else { pendingCr = true; crOffset = pos + i }
                            }
                        }
                        i += 2
                    }
                }
                pos += n
                scannedBytes = pos
                knownLines = (lines + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val now = System.nanoTime()
                if (now - lastFire > 80_000_000L) { lastFire = now; fire(false) }
            }
            if (pendingCr) {
                lines++; lineStartOff = crOffset + unit
                if (lines % ANCHOR_STRIDE == 0L) addAnchor(lineStartOff)
            }
            scannedBytes = pos
            knownLines = (lines + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } catch (_: Throwable) {
            // read error or interrupt: stop indexing with what we have
        } finally {
            complete = true
            fire(true)
        }
    }
}
