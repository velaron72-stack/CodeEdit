package app.codeedit.core.io

import app.codeedit.core.doc.Eol
import app.codeedit.core.doc.TextEncoding
import java.nio.charset.Charset

/** Result of sniffing the first bytes of a file. */
class Sniff(
    val encoding: TextEncoding,
    val eol: Eol,
    val eolMixed: Boolean,
    /** Non-null => file is not text; human readable reason. */
    val binaryReason: String?,
    val hasVeryLongLines: Boolean,
    /** Number of BOM bytes at the start. */
    val bomLength: Int,
)

object TextCodec {
    val commonEncodings: List<TextEncoding> = listOf(
        TextEncoding("UTF-8", false), TextEncoding("UTF-8", true),
        TextEncoding("UTF-16LE", true), TextEncoding("UTF-16BE", true),
        TextEncoding("windows-1251", false), TextEncoding("windows-1252", false),
        TextEncoding("ISO-8859-1", false), TextEncoding("ISO-8859-2", false), TextEncoding("ISO-8859-5", false),
        TextEncoding("KOI8-R", false), TextEncoding("KOI8-U", false), TextEncoding("IBM866", false),
        TextEncoding("windows-1250", false), TextEncoding("windows-1253", false), TextEncoding("windows-1254", false),
        TextEncoding("Shift_JIS", false), TextEncoding("EUC-JP", false), TextEncoding("GBK", false),
        TextEncoding("Big5", false), TextEncoding("EUC-KR", false),
    )

    fun encodingFromName(name: String): TextEncoding? {
        val n = name.trim()
        if (n.isEmpty()) return null
        val bom = n.endsWith("BOM", true) || n.endsWith("(BOM)", true)
        val cs = n.removeSuffix("(BOM)").removeSuffix("BOM").trim().removeSuffix("with").trim()
        return try {
            val c = Charset.forName(cs)
            TextEncoding(c.name(), bom)
        } catch (_: Throwable) { null }
    }

    fun charset(enc: TextEncoding): Charset = Charset.forName(enc.charsetName)

    fun bomBytes(enc: TextEncoding): ByteArray {
        if (!enc.bom) return ByteArray(0)
        return when (enc.charsetName.uppercase()) {
            "UTF-8" -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
            "UTF-16LE" -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
            "UTF-16BE" -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
            else -> ByteArray(0)
        }
    }

    fun isUtf16(enc: TextEncoding) = enc.charsetName.equals("UTF-16LE", true) || enc.charsetName.equals("UTF-16BE", true)

    /** True if every byte < 0x80 is a plain ASCII character in this encoding (all but UTF-16/32, EBCDIC...). */
    fun isAsciiCompatible(enc: TextEncoding): Boolean {
        val n = enc.charsetName.uppercase()
        return !(n.startsWith("UTF-16") || n.startsWith("UTF-32") || n.startsWith("IBM037") || n.startsWith("CP037"))
    }

    private val SIGNATURES: List<Pair<ByteArray, String>> = listOf(
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) to "PNG image",
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) to "JPEG image",
        "GIF8".toByteArray() to "GIF image",
        byteArrayOf(0x50, 0x4B, 0x03, 0x04) to "ZIP/APK/JAR archive",
        byteArrayOf(0x1F, 0x8B.toByte()) to "gzip archive",
        byteArrayOf(0x7F, 0x45, 0x4C, 0x46) to "ELF binary",
        "%PDF".toByteArray() to "PDF document",
        "dex\n".toByteArray() to "DEX bytecode",
        "RIFF".toByteArray() to "RIFF media",
        "OggS".toByteArray() to "Ogg media",
        "Rar!".toByteArray() to "RAR archive",
        byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte()) to "7z archive",
        byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()) to "Java class / Mach-O",
        byteArrayOf(0x00, 0x00, 0x01, 0x00) to "ICO image",
        byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0x00) to "xz archive",
        byteArrayOf(0x42, 0x5A, 0x68) to "bzip2 archive",
        byteArrayOf(0x4D, 0x5A) to "Windows executable",
        byteArrayOf(0x00, 0x61, 0x73, 0x6D) to "WebAssembly module",
        byteArrayOf(0x03, 0x00, 0x08, 0x00) to "Android binary XML (AXML)",
        "SQLite format 3".toByteArray() to "SQLite database",
    )

    private fun startsWith(buf: ByteArray, len: Int, sig: ByteArray): Boolean {
        if (len < sig.size) return false
        for (i in sig.indices) if (buf[i] != sig[i]) return false
        return true
    }

    /** Looks at at most the first 64 KB. `atEof` tells whether buf holds the whole file (affects UTF-8 tail tolerance). */
    fun sniff(buf: ByteArray, len: Int, atEof: Boolean): Sniff {
        if (len == 0) return Sniff(TextEncoding.UTF8, Eol.LF, false, null, false, 0)
        // BOMs
        var enc: TextEncoding? = null
        var bom = 0
        if (len >= 3 && buf[0] == 0xEF.toByte() && buf[1] == 0xBB.toByte() && buf[2] == 0xBF.toByte()) { enc = TextEncoding("UTF-8", true); bom = 3 }
        else if (len >= 2 && buf[0] == 0xFF.toByte() && buf[1] == 0xFE.toByte()) { enc = TextEncoding("UTF-16LE", true); bom = 2 }
        else if (len >= 2 && buf[0] == 0xFE.toByte() && buf[1] == 0xFF.toByte()) { enc = TextEncoding("UTF-16BE", true); bom = 2 }
        if (enc == null) {
            for ((sig, name) in SIGNATURES) {
                if (startsWith(buf, len, sig)) {
                    // "MZ" and "BZh" are short: require binary-looking content to avoid false positives on text.
                    if (sig.size <= 3 && !looksBinary(buf, len)) continue
                    return Sniff(TextEncoding.UTF8, Eol.LF, false, name, false, 0)
                }
            }
            if (len >= 12 && buf[4] == 0x66.toByte() && buf[5] == 0x74.toByte() && buf[6] == 0x79.toByte() && buf[7] == 0x70.toByte())
                return Sniff(TextEncoding.UTF8, Eol.LF, false, "MP4/ISO media", false, 0)
        }
        if (enc == null) {
            val u16 = guessUtf16(buf, len)
            if (u16 != null) enc = TextEncoding(u16, false)
        }
        if (enc == null) {
            // NUL bytes in non-UTF-16 text => binary
            var nul = 0
            for (i in 0 until len) if (buf[i] == 0.toByte()) nul++
            if (nul > 0 && !(atEof && len == nul)) {
                // a single trailing NUL is tolerated
                if (nul > 1 || buf[len - 1] != 0.toByte()) return Sniff(TextEncoding.UTF8, Eol.LF, false, "contains NUL bytes", false, 0)
            }
            if (controlRatio(buf, len, bom) > 0.10) return Sniff(TextEncoding.UTF8, Eol.LF, false, "too many control characters", false, 0)
            enc = detectByteEncoding(buf, len, atEof)
        } else if (controlRatioUtf16(buf, len, bom, enc.charsetName.equals("UTF-16LE", true)) > 0.10) {
            return Sniff(enc, Eol.LF, false, "too many control characters", false, bom)
        }
        val text = try { String(buf, bom, len - bom, charset(enc)) } catch (_: Throwable) { "" }
        return analyzeText(text, enc, bom, len >= 65536)
    }

    /** Re-analyses EOL / long lines for a user-forced encoding. Detects a matching BOM. */
    fun analyzeWith(buf: ByteArray, len: Int, enc: TextEncoding): Sniff {
        val boms = bomBytes(TextEncoding(enc.charsetName, true))
        var bom = 0
        if (boms.isNotEmpty() && len >= boms.size && boms.indices.all { buf[it] == boms[it] }) bom = boms.size
        val e = TextEncoding(enc.charsetName, bom > 0)
        val text = try { String(buf, bom, len - bom, charset(e)) } catch (_: Throwable) { "" }
        return analyzeText(text, e, bom, len >= 65536)
    }

    private fun analyzeText(text: String, enc: TextEncoding, bom: Int, bufferFull: Boolean): Sniff {
        var crlf = 0; var lf = 0; var cr = 0
        var i = 0; val n = text.length
        var lineStart = 0; var longest = 0
        while (i < n) {
            val c = text[i]
            if (c == '\n') { lf++; longest = maxOf(longest, i - lineStart); lineStart = i + 1 }
            else if (c == '\r') {
                if (i + 1 < n && text[i + 1] == '\n') { crlf++; longest = maxOf(longest, i - lineStart); i++; lineStart = i + 1 }
                else if (i + 1 < n) { cr++; longest = maxOf(longest, i - lineStart); lineStart = i + 1 }
            }
            i++
        }
        longest = maxOf(longest, n - lineStart)
        val kinds = (if (crlf > 0) 1 else 0) + (if (lf > 0) 1 else 0) + (if (cr > 0) 1 else 0)
        val eol = when {
            crlf >= lf && crlf >= cr && crlf > 0 -> Eol.CRLF
            cr > lf && cr > crlf -> Eol.CR
            else -> Eol.LF
        }
        val noEolInFullBuffer = bufferFull && crlf + lf + cr == 0
        return Sniff(enc, eol, kinds > 1, null, longest >= 20_000 || noEolInFullBuffer, bom)
    }

    private fun looksBinary(buf: ByteArray, len: Int): Boolean {
        var bad = 0
        val n = minOf(len, 4096)
        for (i in 0 until n) { val b = buf[i].toInt() and 0xFF; if (b == 0 || (b < 9) || (b in 14..31 && b != 27)) bad++ }
        return bad * 20 > n
    }

    private fun controlRatio(buf: ByteArray, len: Int, from: Int): Double {
        var c = 0
        for (i in from until len) { val b = buf[i].toInt() and 0xFF; if (b < 9 || (b in 14..31 && b != 27)) c++ }
        return c.toDouble() / maxOf(1, len - from)
    }

    private fun controlRatioUtf16(buf: ByteArray, len: Int, from: Int, le: Boolean): Double {
        var c = 0; var n = 0
        var i = from
        while (i + 1 < len) {
            val v = if (le) (buf[i].toInt() and 0xFF) or ((buf[i + 1].toInt() and 0xFF) shl 8) else ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            if (v < 9 || (v in 14..31 && v != 27)) c++
            n++; i += 2
        }
        return c.toDouble() / maxOf(1, n)
    }

    /** BOM-less UTF-16 guess: ASCII-heavy text has NULs at every other byte. */
    private fun guessUtf16(buf: ByteArray, len: Int): String? {
        if (len < 4) return null
        var even0 = 0; var odd0 = 0
        val pairs = len / 2
        var i = 0
        while (i + 1 < len) {
            if (buf[i] == 0.toByte()) even0++
            if (buf[i + 1] == 0.toByte()) odd0++
            i += 2
        }
        if (odd0 * 10 > pairs * 3 && even0 * 50 < pairs) return "UTF-16LE"
        if (even0 * 10 > pairs * 3 && odd0 * 50 < pairs) return "UTF-16BE"
        return null
    }

    /** UTF-8 if valid, else a single-byte guess (cp1251 vs cp1252). */
    private fun detectByteEncoding(buf: ByteArray, len: Int, atEof: Boolean): TextEncoding {
        var i = 0
        var high = 0
        var invalid = false
        while (i < len) {
            val b = buf[i].toInt() and 0xFF
            if (b < 0x80) { i++; continue }
            high++
            val need = when {
                b in 0xC2..0xDF -> 1
                b in 0xE0..0xEF -> 2
                b in 0xF0..0xF4 -> 3
                else -> { invalid = true; break }
            }
            if (i + need >= len) { // sequence runs past the buffer end: tolerated unless this is the real EOF
                if (atEof) invalid = true
                break
            }
            for (k in 1..need) {
                if ((buf[i + k].toInt() and 0xC0) != 0x80) { invalid = true; break }
            }
            if (invalid) break
            i += need + 1
        }
        if (!invalid) return TextEncoding.UTF8
        // single-byte heuristics: runs of >=3 bytes in 0xC0..0xFF look like Cyrillic words in cp1251
        var runs = 0; var run = 0
        for (k in 0 until len) {
            val b = buf[k].toInt() and 0xFF
            if (b >= 0xC0) { run++ } else { if (run >= 3) runs++; run = 0 }
        }
        if (run >= 3) runs++
        return if (runs >= 2) TextEncoding("windows-1251", false) else TextEncoding("windows-1252", false)
    }
}
