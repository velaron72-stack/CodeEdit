package app.codeedit.core.doc

import app.codeedit.core.io.RandomAccessSource
import app.codeedit.core.io.SaveResult
import app.codeedit.core.io.SaveTarget
import app.codeedit.core.io.TextCodec
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

object DocumentSaver {
    private const val CHUNK = 32 * 1024

    /**
     * Encodes the whole document (EOL conversion + BOM) into target. The destination is only replaced by target.commit()
     * after everything was written; any error (including characters the encoding cannot represent) => abort(), the
     * original stays untouched and ok=false.
     */
    fun save(
        doc: EditableDocument, encoding: TextEncoding, eol: Eol, ensureFinalNewline: Boolean, target: SaveTarget,
        progress: ((Float) -> Unit)? = null, cancel: AtomicBoolean? = null,
    ): SaveResult {
        var written = 0L
        try {
            val cs = TextCodec.charset(encoding)
            val enc = cs.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val out = target.openOutput()
            val bom = TextCodec.bomBytes(encoding)
            if (bom.isNotEmpty()) { out.write(bom); written += bom.size }
            val total = doc.length
            var a = 0
            var lastProgress = 0L
            while (a < total) {
                if (cancel?.get() == true) throw CancellationException("cancelled")
                var b = minOf(total, a + CHUNK)
                if (b < total && Character.isHighSurrogate(doc.charAt(b - 1))) b++
                var s = doc.getText(doc.posOf(a), doc.posOf(b))
                if (eol != Eol.LF) s = s.replace("\n", eol.str)
                val bytes = enc.reset().encode(CharBuffer.wrap(s))
                val n = bytes.remaining()
                out.write(bytes.array(), bytes.arrayOffset() + bytes.position(), n)
                written += n
                a = b
                val now = System.nanoTime()
                if (progress != null && now - lastProgress > 50_000_000L) { lastProgress = now; progress(a.toFloat() / total) }
            }
            if (ensureFinalNewline && doc.lineLength(doc.lineCount - 1) > 0) {
                val bytes = enc.reset().encode(CharBuffer.wrap(eol.str))
                out.write(bytes.array(), bytes.arrayOffset() + bytes.position(), bytes.remaining())
                written += bytes.remaining()
            }
            out.flush()
            out.close()
            target.commit()
            progress?.invoke(1f)
            return SaveResult(true, written, null)
        } catch (t: Throwable) {
            try { target.abort() } catch (_: Throwable) {}
            return SaveResult(false, written, if (t is CharacterCodingException) UnencodableException(encoding, t) else t)
        }
    }

    /** Streams raw bytes unchanged (save a copy of a big VIEW-mode file). */
    fun copyBytes(source: RandomAccessSource, target: SaveTarget, progress: ((Float) -> Unit)? = null, cancel: AtomicBoolean? = null): SaveResult {
        var written = 0L
        try {
            while (!source.isFinal) source.awaitData(source.size, 200)
            val total = source.size
            val out = target.openOutput()
            val buf = ByteArray(1 shl 20)
            var pos = 0L
            var lastProgress = 0L
            while (pos < total) {
                if (cancel?.get() == true) throw CancellationException("cancelled")
                val r = source.read(pos, buf, 0, buf.size)
                if (r < 0) break
                if (r == 0) { source.awaitData(pos, 100); continue }
                out.write(buf, 0, r); pos += r; written += r
                val now = System.nanoTime()
                if (progress != null && now - lastProgress > 50_000_000L) { lastProgress = now; progress(pos.toFloat() / total) }
            }
            out.flush(); out.close(); target.commit()
            progress?.invoke(1f)
            return SaveResult(true, written, null)
        } catch (t: Throwable) {
            try { target.abort() } catch (_: Throwable) {}
            return SaveResult(false, written, t)
        }
    }
}

class UnencodableException(val encoding: TextEncoding, cause: Throwable) :
    Exception("Text contains characters that cannot be represented in ${encoding.label}", cause)
