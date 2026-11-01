package app.codeedit.core.search

import app.codeedit.core.doc.DocInfo
import app.codeedit.core.doc.EditableDocument
import app.codeedit.core.doc.Pos
import app.codeedit.core.doc.Selection
import app.codeedit.core.io.RandomAccessSource
import app.codeedit.core.io.SaveTarget
import app.codeedit.core.io.TextCodec
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.channels.Channels
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.MatchResult

class StreamReplaceResult(val ok: Boolean, val bytesWritten: Long, val replacements: Long, val error: Throwable?)

object Replacer {
    private const val LIMIT = 5_000_000
    private const val MAX_STREAM_LINE = 8 * 1024 * 1024

    /**
     * Replacement template for regex queries: $0-$9 / \1-\9 = groups, \n newline, \t tab, \\ backslash, \$ dollar.
     * Plain-text queries use the replacement literally.
     */
    fun expandTemplate(m: MatchResult, t: String): String {
        if (t.indexOf('$') < 0 && t.indexOf('\\') < 0) return t
        val sb = StringBuilder()
        var i = 0
        while (i < t.length) {
            val c = t[i]
            if (c == '\\' && i + 1 < t.length) {
                val d = t[i + 1]
                when {
                    d in '0'..'9' -> { appendGroup(sb, m, d - '0'); i += 2 }
                    d == 'n' -> { sb.append('\n'); i += 2 }
                    d == 't' -> { sb.append('\t'); i += 2 }
                    else -> { sb.append(d); i += 2 }
                }
            } else if (c == '$' && i + 1 < t.length && t[i + 1] in '0'..'9') {
                appendGroup(sb, m, t[i + 1] - '0'); i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    private fun appendGroup(sb: StringBuilder, m: MatchResult, g: Int) {
        if (g <= m.groupCount()) m.group(g)?.let { sb.append(it) }
    }

    /** Replacement text for the match [start,end) found in `context` (e.g. the line text). */
    fun expandAt(query: SearchQuery, context: String, start: Int, end: Int, replacement: String): String {
        if (!query.regex) return replacement
        val p = CompiledQuery(query).regexPattern() ?: return replacement
        val m = p.matcher(context)
        m.useTransparentBounds(true).useAnchoringBounds(false)
        m.region(start, minOf(end, context.length))
        return if (m.find() && m.start() == start) expandTemplate(m, replacement) else replacement
    }

    /** Same as expandAt on the matched text alone (lookarounds/anchors may not hold there; falls back to the literal replacement). */
    fun expand(query: SearchQuery, matchedText: String, replacement: String): String = expandAt(query, matchedText, 0, matchedText.length, replacement)

    private class Scan(val count: Int, val first: Int, val last: Int, val region: String)

    /** Replaces every match in `text`; the result region spans from the first match start to the last match end. */
    private fun scan(cq: CompiledQuery, text: String, replacement: String, deadline: Long): Scan? {
        val pat = cq.regexPattern()
        val sb = StringBuilder()
        var count = 0; var first = -1; var prevEnd = 0
        if (pat == null || !cq.query.regex) {
            var pos = 0
            while (count < LIMIT) {
                val r = cq.find(text, pos, deadline)
                if (r < 0) break
                val s = (r ushr 32).toInt(); val e = (r and 0xFFFFFFFFL).toInt()
                if (first < 0) first = s else sb.append(text, prevEnd, s)
                sb.append(replacement); prevEnd = e; pos = e; count++
            }
        } else {
            val m = pat.matcher(if (deadline == Long.MAX_VALUE) text else DeadlineSeq(text, deadline))
            var pos = 0
            while (count < LIMIT && pos <= text.length && m.find(pos)) {
                if (m.end() == m.start()) { pos = m.end() + 1; continue }
                val s = m.start()
                if (first < 0) first = s else sb.append(text, prevEnd, s)
                sb.append(expandTemplate(m, replacement)); prevEnd = m.end(); pos = m.end(); count++
            }
        }
        return if (count == 0) null else Scan(count, first, prevEnd, sb.toString())
    }

    /**
     * Replaces all matches in an in-memory document as ONE undo step (only the region between the first and last match
     * is rewritten). Returns the number of replacements. Throws SearchTimeout when a regex runs away.
     */
    fun replaceAll(doc: EditableDocument, query: SearchQuery, replacement: String, selBefore: Selection?, selAfter: Selection?): Int {
        val cq = CompiledQuery(query)
        if (cq.isEmpty) return 0
        val text = doc.fullText()
        val r = scan(cq, text, replacement, System.nanoTime() + 10_000_000_000L) ?: return 0
        doc.replace(doc.posOf(r.first), doc.posOf(r.last), r.region, selBefore, selAfter)
        return r.count
    }

    /** Per-line replacement for a plain string (stream mode). */
    private fun replaceLine(cq: CompiledQuery, s: String, replacement: String, counter: LongArray): String {
        val r = scan(cq, s, replacement, System.nanoTime() + 2_000_000_000L) ?: return s
        counter[0] += r.count.toLong()
        return if (r.first == 0 && r.last == s.length) r.region else s.substring(0, r.first) + r.region + s.substring(r.last)
    }

    private class SourceStream(val src: RandomAccessSource, var pos: Long, val cancel: AtomicBoolean?) : InputStream() {
        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xFF }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (cancel?.get() == true) throw CancellationException("cancelled")
            while (true) {
                val r = src.read(pos, b, off, len)
                if (r < 0) return -1
                if (r == 0) { if (len == 0) return 0; src.awaitData(pos, 100); continue }
                pos += r; return r
            }
        }
    }

    /**
     * Streaming replace for any file size: decodes strictly (invalid bytes abort instead of silently altering data),
     * replaces per line (no multi-line matches), keeps each line's original terminator, encoding and BOM, and writes to
     * `target`. The original is untouched until target.commit(); on error/cancel target.abort() is called.
     */
    fun streamReplaceAll(
        source: RandomAccessSource, info: DocInfo, query: SearchQuery, replacement: String, target: SaveTarget,
        progress: ((Float) -> Unit)?, cancel: AtomicBoolean?,
    ): StreamReplaceResult {
        val counter = LongArray(1)
        var written = 0L
        try {
            val cq = CompiledQuery(query)
            if (cq.isEmpty) return StreamReplaceResult(true, 0, 0, null)
            while (!source.isFinal) source.awaitData(source.size, 200)
            val total = maxOf(1L, source.size)
            val cs = TextCodec.charset(info.encoding)
            val dec = cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val enc = cs.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val bom = TextCodec.bomBytes(info.encoding)
            val stream = SourceStream(source, bom.size.toLong(), cancel)
            val reader = InputStreamReader(stream, dec)
            val rawOut = target.openOutput()
            val counting = object : java.io.OutputStream() {
                override fun write(b: Int) { rawOut.write(b); written++ }
                override fun write(b: ByteArray, off: Int, len: Int) { rawOut.write(b, off, len); written += len }
                override fun flush() = rawOut.flush()
                override fun close() = rawOut.close()
            }
            if (bom.isNotEmpty()) counting.write(bom)
            val writer = Channels.newWriter(Channels.newChannel(counting), enc, 64 * 1024)
            val chunk = CharArray(64 * 1024)
            val line = StringBuilder()
            var pendingCr = false
            var lastProgress = 0L
            fun emit(term: String) {
                val s = line.toString(); line.setLength(0)
                writer.write(replaceLine(cq, s, replacement, counter)); writer.write(term)
            }
            while (true) {
                val n = reader.read(chunk)
                if (n < 0) break
                for (i in 0 until n) {
                    val c = chunk[i]
                    if (pendingCr) {
                        pendingCr = false
                        if (c == '\n') { emit("\r\n"); continue } else emit("\r")
                    }
                    when (c) {
                        '\r' -> pendingCr = true
                        '\n' -> emit("\n")
                        else -> { line.append(c); if (line.length > MAX_STREAM_LINE) throw IOException("line longer than $MAX_STREAM_LINE chars: refusing to replace") }
                    }
                }
                val now = System.nanoTime()
                if (progress != null && now - lastProgress > 80_000_000L) { lastProgress = now; progress((stream.pos.toFloat() / total).coerceAtMost(0.99f)) }
            }
            if (pendingCr) emit("\r") else if (line.isNotEmpty()) emit("")
            writer.flush(); writer.close()
            target.commit()
            progress?.invoke(1f)
            return StreamReplaceResult(true, written, counter[0], null)
        } catch (t: Throwable) {
            try { target.abort() } catch (_: Throwable) {}
            val err: Throwable = if (t is CharacterCodingException) IOException("file is not valid ${info.encoding.label}; refusing to rewrite it", t) else t
            return StreamReplaceResult(false, written, counter[0], err)
        }
    }
}
