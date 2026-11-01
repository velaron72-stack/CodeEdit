package app.codeedit.core.doc

import app.codeedit.core.io.RandomAccessSource
import app.codeedit.core.io.TextCodec
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Executor

data class OpenOptions(
    val forceMode: DocMode? = null,
    val forceEncoding: TextEncoding? = null,
    /** Files larger than this open in VIEW mode unless forceMode == EDIT. */
    val editMaxBytes: Long = 24L * 1024 * 1024,
    /** Rough heap the editable copy may use. */
    val heapBudgetBytes: Long = Runtime.getRuntime().maxMemory() / 3,
    val indexExecutor: Executor? = null,
)

sealed class OpenResult {
    class Ok(val doc: LineStore, val info: DocInfo) : OpenResult()
    class Binary(val reason: String) : OpenResult()
    class Failure(val error: Throwable) : OpenResult()
}

class OutOfBudgetException(message: String) : Exception(message)

object DocumentOpener {
    private const val SNIFF = 65536

    /** Sniffs the head of the file (never reads all of it) and opens it in VIEW or EDIT mode. */
    fun open(source: RandomAccessSource, fileName: String?, options: OpenOptions = OpenOptions()): OpenResult {
        try {
            val buf = ByteArray(SNIFF)
            var got = 0
            while (got < SNIFF) {
                val r = source.read(got.toLong(), buf, got, SNIFF - got)
                if (r < 0) break
                if (r == 0) { if (!source.awaitData(got.toLong(), 2000)) break; continue }
                got += r
            }
            val atEof = source.isFinal && got.toLong() >= source.size
            var sn = TextCodec.sniff(buf, got, atEof)
            if (options.forceEncoding != null) sn = TextCodec.analyzeWith(buf, got, options.forceEncoding)
            else if (sn.binaryReason != null) return OpenResult.Binary(sn.binaryReason)
            val size = source.size
            val info0 = DocInfo(
                mode = DocMode.VIEW, encoding = sn.encoding, eol = sn.eol, eolMixed = sn.eolMixed, sizeBytes = size,
                endsWithNewline = endsWithNewline(source, sn.encoding), hasVeryLongLines = sn.hasVeryLongLines,
            )
            var mode = options.forceMode
            if (mode == null) {
                mode = if (size > options.editMaxBytes || sn.hasVeryLongLines || !source.isFinal || estimateHeap(size, sn.encoding) > options.heapBudgetBytes) DocMode.VIEW else DocMode.EDIT
            }
            if (mode == DocMode.EDIT) {
                val r = openForEdit(source, info0, options)
                if (r is OpenResult.Failure && r.error is OutOfBudgetException && options.forceMode == null) { /* fall through to VIEW */ }
                else return r
            }
            val bom = if (info0.encoding.bom) TextCodec.bomBytes(info0.encoding).size.toLong() else 0L
            val info = info0.copy(mode = DocMode.VIEW)
            return OpenResult.Ok(ViewDocument(source, info, bom, options.indexExecutor), info)
        } catch (t: Throwable) {
            return OpenResult.Failure(t)
        }
    }

    private fun estimateHeap(size: Long, enc: TextEncoding): Long {
        val chars = if (TextCodec.isUtf16(enc)) size / 2 else size
        return chars * 2 * 3 / 2 + (size / 24) * 4 + 8L * 1024 * 1024
    }

    private fun endsWithNewline(source: RandomAccessSource, enc: TextEncoding): Boolean {
        val size = source.size
        val n = if (TextCodec.isUtf16(enc)) 2 else 1
        if (size < n) return false
        val b = ByteArray(n)
        val r = source.read(size - n, b, 0, n)
        if (r < n) return false
        return if (n == 1) b[0].toInt() == 10 || b[0].toInt() == 13
        else { val le = enc.charsetName.equals("UTF-16LE", true); val lo = if (le) b[0] else b[1]; val hi = if (le) b[1] else b[0]; hi.toInt() == 0 && (lo.toInt() == 10 || lo.toInt() == 13) }
    }

    /** Whole file -> EditDocument (EOLs normalised to '\n'). Failure(OutOfBudgetException) when it does not fit. */
    fun openForEdit(source: RandomAccessSource, info: DocInfo, options: OpenOptions = OpenOptions()): OpenResult {
        try {
            // wait until a growing source is complete
            while (!source.isFinal) source.awaitData(source.size, 200)
            val size = source.size
            val est = estimateHeap(size, info.encoding)
            if (est > options.heapBudgetBytes && options.forceMode != DocMode.EDIT)
                return OpenResult.Failure(OutOfBudgetException("file needs ~${est shr 20} MB, budget ${options.heapBudgetBytes shr 20} MB"))
            val hardCap = Runtime.getRuntime().maxMemory() * 3 / 4
            if (est > hardCap) return OpenResult.Failure(OutOfBudgetException("file does not fit in memory"))
            val bom = if (info.encoding.bom) TextCodec.bomBytes(info.encoding).size.toLong() else 0L
            val dec = TextCodec.charset(info.encoding).newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
            val chars = if (TextCodec.isUtf16(info.encoding)) size / 2 else size
            val builder = EditDocument.Builder(chars.coerceAtMost(Int.MAX_VALUE / 4L).toInt())
            val bb = ByteBuffer.allocate(1 shl 20)
            val cb = CharBuffer.allocate(1 shl 20)
            var pos = bom
            var eof = false
            while (!eof) {
                val r = source.read(pos, bb.array(), bb.position(), bb.remaining())
                if (r < 0) eof = true else { bb.position(bb.position() + r); pos += r }
                bb.flip()
                dec.decode(bb, cb, eof)
                cb.flip(); builder.append(cb.array(), 0, cb.limit()); cb.clear()
                bb.compact()
                if (builder.length.toLong() * 2 > hardCap) return OpenResult.Failure(OutOfBudgetException("out of memory while loading"))
            }
            dec.flush(cb); cb.flip(); builder.append(cb.array(), 0, cb.limit())
            val doc = builder.finish()
            return OpenResult.Ok(doc, info.copy(mode = DocMode.EDIT, sizeBytes = size))
        } catch (e: OutOfMemoryError) {
            return OpenResult.Failure(OutOfBudgetException("out of memory"))
        } catch (t: Throwable) {
            return OpenResult.Failure(t)
        }
    }

    fun newDocument(text: String = "", eol: Eol = Eol.LF): Pair<EditableDocument, DocInfo> {
        val d = EditDocument.fromString(EditDocument.normalizeEol(text))
        return d to DocInfo(DocMode.EDIT, TextEncoding.UTF8, eol, false, 0, text.endsWith("\n"))
    }

    /** Draft restore: `text` is already decoded; EOLs are normalised. */
    fun fromText(text: String, info: DocInfo): EditableDocument = EditDocument.fromString(EditDocument.normalizeEol(text))
}
