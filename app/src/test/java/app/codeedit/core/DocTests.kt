package app.codeedit.core

import app.codeedit.core.doc.*
import app.codeedit.core.io.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption

class MemSource(private val data: ByteArray) : RandomAccessSource {
    override val size get() = data.size.toLong()
    override val isFinal get() = true
    override val lastModified get() = 0L
    override fun read(position: Long, dst: ByteArray, off: Int, len: Int): Int {
        if (position >= data.size) return -1
        val n = minOf(len.toLong(), data.size - position).toInt()
        System.arraycopy(data, position.toInt(), dst, off, n); return n
    }
    override fun close() {}
}

class MemTarget : SaveTarget {
    val bos = ByteArrayOutputStream(); var committed = false; var aborted = false
    override fun openOutput(): OutputStream = bos
    override fun commit() { committed = true }
    override fun abort() { aborted = true }
}

fun waitIndex(d: LineStore) { val t = System.currentTimeMillis(); while (!d.isIndexComplete && System.currentTimeMillis() - t < 20000) Thread.sleep(2) }

class EditDocumentTests {
    private fun doc(s: String) = EditDocument.fromString(s)
    @Test fun basicLines() {
        val d = doc("a\nbb\n\nccc")
        assertEquals(4, d.lineCount); assertEquals("bb", d.getLine(1)); assertEquals("", d.getLine(2)); assertEquals(3, d.lineLength(3))
        assertEquals(Pos(3, 2), d.posOf(d.offsetOf(Pos(3, 2)))); assertEquals(9, d.length)
    }
    @Test fun emptyDocHasOneLine() { val d = doc(""); assertEquals(1, d.lineCount); assertEquals("", d.getLine(0)) }
    @Test fun insertDeleteAndLineTable() {
        val d = doc("hello\nworld")
        d.replace(Pos(0, 5), Pos(0, 5), "\nbig\nlittle")
        assertEquals("hello\nbig\nlittle\nworld", d.fullText()); assertEquals(4, d.lineCount)
        d.replace(Pos(0, 3), Pos(2, 3), "")
        assertEquals("heltle\nworld", d.fullText()); assertEquals(2, d.lineCount); assertEquals("world", d.getLine(1))
        assertEquals(Pos(1, 5), d.posOf(d.length))
    }
    @Test fun listenerArgs() {
        val d = doc("a\nb\nc")
        var got: Triple<Int, Int, Int>? = null
        d.addListener(object : DocumentListener { override fun onContentChanged(s: Int, o: Int, n: Int) { got = Triple(s, o, n) } })
        d.replace(Pos(0, 1), Pos(2, 0), "x\ny\nz")
        assertEquals(Triple(0, 3, 3), got); assertEquals("ax\ny\nzc", d.fullText())
    }
    @Test fun crlfNormalised() { assertEquals("a\nb\nc\n", doc(EditDocument.normalizeEol("a\r\nb\rc\n")).fullText()) }
    @Test fun undoRedoAndModified() {
        val d = doc("abc")
        assertFalse(d.isModified)
        d.replace(Pos(0, 3), Pos(0, 3), "d", Selection.caret(Pos(0, 3)), Selection.caret(Pos(0, 4)), Coalesce.TYPING)
        d.replace(Pos(0, 4), Pos(0, 4), "e", null, Selection.caret(Pos(0, 5)), Coalesce.TYPING)
        assertEquals("abcde", d.fullText()); assertTrue(d.isModified)
        val s = d.undo(); assertEquals("abc", d.fullText()); assertEquals(Selection.caret(Pos(0, 3)), s); assertFalse(d.isModified)
        assertFalse(d.canUndo); assertTrue(d.canRedo)
        d.redo(); assertEquals("abcde", d.fullText()); assertTrue(d.isModified)
    }
    @Test fun savedStateTracking() {
        val d = doc("x")
        d.replace(Pos(0, 1), Pos(0, 1), "y", null, null, Coalesce.TYPING)
        d.markSaved(); assertFalse(d.isModified)
        d.replace(Pos(0, 2), Pos(0, 2), "z", null, null, Coalesce.TYPING) // must NOT merge into saved group
        assertTrue(d.isModified); d.undo(); assertFalse(d.isModified); assertEquals("xy", d.fullText())
        d.undo(); assertTrue(d.isModified); d.redo(); assertFalse(d.isModified)
        d.replace(Pos(0, 0), Pos(0, 0), "q"); d.undo(); d.markSaved(); d.replace(Pos(0, 0), Pos(0, 0), "w"); d.undo(); assertFalse(d.isModified)
    }
    @Test fun compoundIsOneStep() {
        val d = doc("a b c")
        d.beginCompound(); d.replace(Pos(0, 0), Pos(0, 1), "X"); d.replace(Pos(0, 2), Pos(0, 3), "Y"); d.replace(Pos(0, 4), Pos(0, 5), "Z"); d.endCompound()
        assertEquals("X Y Z", d.fullText()); d.undo(); assertEquals("a b c", d.fullText()); assertFalse(d.canUndo)
    }
    @Test fun deletingCoalesces() {
        val d = doc("abcdef")
        d.replace(Pos(0, 5), Pos(0, 6), "", null, null, Coalesce.DELETING)
        d.replace(Pos(0, 4), Pos(0, 5), "", null, null, Coalesce.DELETING)
        d.replace(Pos(0, 3), Pos(0, 4), "", null, null, Coalesce.DELETING)
        assertEquals("abc", d.fullText()); d.undo(); assertEquals("abcdef", d.fullText()); assertFalse(d.canUndo)
    }
    @Test fun randomEditsMatchStringModel() {
        val rnd = java.util.Random(42)
        val d = doc("")
        val model = StringBuilder()
        repeat(3000) {
            val len = model.length
            val a = rnd.nextInt(len + 1); val b = minOf(len, a + rnd.nextInt(6))
            val sb = StringBuilder(); repeat(rnd.nextInt(5)) { sb.append(if (rnd.nextInt(4) == 0) '\n' else ('a' + rnd.nextInt(26))) }
            d.replace(d.posOf(a), d.posOf(b), sb.toString()); model.replace(a, b, sb.toString())
            if (rnd.nextInt(10) == 0 && d.canUndo) { d.undo(); d.redo() }
        }
        assertEquals(model.toString(), d.fullText())
        val lines = model.toString().split("\n")
        assertEquals(lines.size, d.lineCount)
        for (i in lines.indices) assertEquals(lines[i], d.getLine(i))
        while (d.canUndo) d.undo()
        assertEquals("", d.fullText())
    }
    @Test fun copyLineAcrossGap() {
        val d = doc("0123456789\nabc")
        d.replace(Pos(0, 5), Pos(0, 5), "XX") // gap now at 7
        val dst = CharArray(8); val n = d.copyLine(0, 3, 9, dst, 1)
        assertEquals(6, n); assertEquals("34XX56", String(dst, 1, 6))
    }
}

class ViewDocumentTests {
    private fun view(bytes: ByteArray, enc: TextEncoding = TextEncoding.UTF8, bom: Long = 0): ViewDocument {
        val info = DocInfo(DocMode.VIEW, enc, Eol.LF, false, bytes.size.toLong(), false)
        return ViewDocument(MemSource(bytes), info, bom)
    }
    private fun lines(d: ViewDocument): List<String> { waitIndex(d); return (0 until d.lineCount).map { d.getLine(it) } }

    @Test fun lfCrlfCrMixed() {
        assertEquals(listOf("a", "b", "", "c"), lines(view("a\nb\n\nc".toByteArray())))
        assertEquals(listOf("a", "b", ""), lines(view("a\r\nb\r\n".toByteArray())))
        assertEquals(listOf("a", "b", "c"), lines(view("a\rb\rc".toByteArray())))
        assertEquals(listOf("a", "b", "c", "d", ""), lines(view("a\nb\r\nc\rd\n".toByteArray())))
    }
    @Test fun emptyAndSingle() { assertEquals(listOf(""), lines(view(ByteArray(0)))); assertEquals(listOf("x"), lines(view("x".toByteArray()))) }
    @Test fun utf8Multibyte() { assertEquals(listOf("привет", "мир ☃ 😀"), lines(view("привет\nмир ☃ 😀".toByteArray()))) }
    @Test fun utf16le() {
        val s = "ab\r\ncд\n\ne"; val b = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + s.toByteArray(Charsets.UTF_16LE)
        assertEquals(listOf("ab", "cд", "", "e"), lines(view(b, TextEncoding("UTF-16LE", true), 2)))
    }
    @Test fun utf16be() {
        val s = "ab\ncд\r"; val b = s.toByteArray(Charsets.UTF_16BE)
        assertEquals(listOf("ab", "cд", ""), lines(view(b, TextEncoding("UTF-16BE", false), 0)))
    }
    @Test fun cp1251() { assertEquals(listOf("Привет", "мир"), lines(view("Привет\nмир".toByteArray(charset("windows-1251")), TextEncoding("windows-1251")))) }
    @Test fun linesAcrossBlockBoundaries() {
        val sb = StringBuilder(); val expect = ArrayList<String>()
        val rnd = java.util.Random(7)
        repeat(30000) { val l = "line$it-" + "x".repeat(rnd.nextInt(60)) + "é"; expect.add(l); sb.append(l).append(if (it % 3 == 0) "\r\n" else "\n") }
        expect.add("")
        val d = view(sb.toString().toByteArray())
        assertEquals(expect, lines(d))
        // random access after indexing, including anchor-boundary lines
        for (i in listOf(0, 63, 64, 65, 127, 128, 12345, 29999, 30000)) assertEquals(expect[i], d.getLine(i))
        assertEquals(expect.size.toLong(), d.estimatedLineCount)
    }
    @Test fun crlfSplitAcrossReadBuffers() {
        // force a CR at the end of the 1 MB indexing buffer with LF as the first byte of the next
        val pad = "y".repeat((1 shl 20) - 1)
        val d = view((pad + "\r\nnext\r\nz").toByteArray())
        assertEquals(listOf(pad.take(ViewDocument.MAX_LINE_CHARS), "next", "z"), lines(d))
    }
    @Test fun veryLongLineIsTruncated() {
        val d = view(("a".repeat(300_000) + "\nshort").toByteArray())
        waitIndex(d); assertEquals(2, d.lineCount)
        assertEquals(ViewDocument.MAX_LINE_CHARS, d.getLine(0).length); assertTrue(d.isTruncated(0)); assertEquals("short", d.getLine(1)); assertFalse(d.isTruncated(1))
    }
    @Test fun forEachLineMatchesGetLine() {
        val text = (0 until 5000).joinToString("\n") { "row $it" }
        val d = view(text.toByteArray()); waitIndex(d)
        val seen = ArrayList<String>(); d.forEachLine(100, 4000) { _, s -> seen.add(s); true }
        assertEquals((100 until 4000).map { "row $it" }, seen)
    }
    @Test fun getTextJoinsLines() {
        val d = view("abc\ndef\nghi".toByteArray()); waitIndex(d)
        assertEquals("bc\ndef\ng", d.getText(Pos(0, 1), Pos(2, 1)))
    }
}

class OpenerSaverTests {
    private fun tmp(bytes: ByteArray): File { val f = File.createTempFile("tmp", ".txt"); f.writeBytes(bytes); f.deleteOnExit(); return f }
    private fun src(f: File) = FileChannelSource(FileChannel.open(f.toPath(), StandardOpenOption.READ))

    @Test fun smallFileOpensInEditMode() {
        val r = DocumentOpener.open(MemSource("héllo\r\nworld\r\n".toByteArray()), "a.txt") as OpenResult.Ok
        assertEquals(DocMode.EDIT, r.info.mode); assertEquals(Eol.CRLF, r.info.eol); assertTrue(r.info.endsWithNewline)
        assertEquals("héllo", r.doc.getLine(0)); assertEquals(3, r.doc.lineCount)
    }
    @Test fun binaryDetected() {
        assertTrue(DocumentOpener.open(MemSource(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 13, 10, 26, 10, 0, 0, 0, 13)), "x.png") is OpenResult.Binary)
        assertTrue(DocumentOpener.open(MemSource(ByteArray(2000) { (it % 7).toByte() }), null) is OpenResult.Binary)
        assertTrue(DocumentOpener.open(MemSource("plain text\n".toByteArray()), null) is OpenResult.Ok)
        assertTrue(DocumentOpener.open(MemSource(ByteArray(0)), null) is OpenResult.Ok)
    }
    @Test fun encodingDetection() {
        fun enc(b: ByteArray) = (DocumentOpener.open(MemSource(b), null) as OpenResult.Ok).info.encoding
        assertEquals(TextEncoding("UTF-8", true), enc(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hi".toByteArray()))
        assertEquals(TextEncoding("UTF-16LE", true), enc(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "hi there".toByteArray(Charsets.UTF_16LE)))
        assertEquals("UTF-16LE", enc("hello world, this is text".toByteArray(Charsets.UTF_16LE)).charsetName)
        assertEquals("UTF-16BE", enc("hello world, this is text".toByteArray(Charsets.UTF_16BE)).charsetName)
        assertEquals("windows-1251", enc("Привет, как дела? Это текст на русском языке.".toByteArray(charset("windows-1251"))).charsetName)
        assertEquals("UTF-8", enc("Привет, как дела?".toByteArray()).charsetName)
        assertEquals("windows-1252", enc("café résumé naïve".toByteArray(charset("windows-1252"))).charsetName)
    }
    @Test fun largeOrLongLinesGoToViewMode() {
        val big = DocumentOpener.open(MemSource("x\n".repeat(1000).toByteArray()), null, OpenOptions(editMaxBytes = 100)) as OpenResult.Ok
        assertEquals(DocMode.VIEW, big.info.mode); assertTrue(big.doc is ViewDocument)
        val long = DocumentOpener.open(MemSource(("z".repeat(40_000) + "\n").toByteArray()), null) as OpenResult.Ok
        assertEquals(DocMode.VIEW, long.info.mode)
        val forced = DocumentOpener.open(MemSource("x\n".repeat(1000).toByteArray()), null, OpenOptions(forceMode = DocMode.EDIT, editMaxBytes = 100)) as OpenResult.Ok
        assertEquals(DocMode.EDIT, forced.info.mode)
    }
    @Test fun openForEditUtf16AndCr() {
        val b = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "a\rb\r\nc".toByteArray(Charsets.UTF_16BE)
        val r = DocumentOpener.open(MemSource(b), null, OpenOptions(forceMode = DocMode.EDIT)) as OpenResult.Ok
        assertEquals("a\nb\nc", (r.doc as EditableDocument).fullText())
    }
    @Test fun saveRoundTripKeepsEncodingEolBom() {
        val orig = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "α\r\nβ\r\n".toByteArray()
        val r = DocumentOpener.open(MemSource(orig), null) as OpenResult.Ok
        val t = MemTarget()
        val res = DocumentSaver.save(r.doc as EditableDocument, r.info.encoding, r.info.eol, false, t)
        assertTrue(res.ok); assertTrue(t.committed); assertArrayEquals(orig, t.bos.toByteArray())
    }
    @Test fun saveUnencodableFailsAndAborts() {
        val d = EditDocument.fromString("привет 😀")
        val t = MemTarget(); val res = DocumentSaver.save(d, TextEncoding("windows-1251"), Eol.LF, false, t)
        assertFalse(res.ok); assertTrue(res.error is UnencodableException); assertTrue(t.aborted); assertFalse(t.committed)
    }
    @Test fun ensureFinalNewlineAndEolConversion() {
        val d = EditDocument.fromString("a\nb")
        val t = MemTarget(); DocumentSaver.save(d, TextEncoding.UTF8, Eol.CRLF, true, t)
        assertEquals("a\r\nb\r\n", String(t.bos.toByteArray()))
    }
    @Test fun fileSaveTargetIsAtomic() {
        val f = tmp("old".toByteArray())
        val d = EditDocument.fromString("new content")
        val res = DocumentSaver.save(d, TextEncoding.UTF8, Eol.LF, false, FileSaveTarget(f, keepBackup = true))
        assertTrue(res.ok); assertEquals("new content", f.readText()); assertEquals("old", File(f.path + ".bak").readText())
        File(f.path + ".bak").delete()
        // failing save leaves the original
        val d2 = EditDocument.fromString("日本語")
        val res2 = DocumentSaver.save(d2, TextEncoding("ISO-8859-1"), Eol.LF, false, FileSaveTarget(f))
        assertFalse(res2.ok); assertEquals("new content", f.readText())
        assertEquals(0, f.parentFile.listFiles { x -> x.name.startsWith("." + f.name) }!!.size)
    }
    @Test fun fileChannelSourceAndSpooling() {
        val data = ByteArray(700_000) { (it * 31).toByte() }
        val f = tmp(data); val s = src(f)
        val buf = ByteArray(1000); assertEquals(1000, s.read(699_000, buf, 0, 1000)); assertEquals(data[699_500], buf[500]); assertEquals(-1, s.read(700_000, buf, 0, 10))
        s.close()
        val sp = SpoolingSource(java.io.ByteArrayInputStream(data), f.parentFile)
        assertTrue(sp.awaitData(699_999, 5000)); val t = System.currentTimeMillis(); while (!sp.isFinal && System.currentTimeMillis() - t < 5000) Thread.sleep(2)
        assertTrue(sp.isFinal); assertEquals(700_000L, sp.size); assertEquals(1000, sp.read(100, buf, 0, 1000)); assertEquals(data[600], buf[500]); sp.close()
    }
    @Test fun viewDocumentOnGrowingSource() {
        val text = (0 until 20000).joinToString("\n") { "line $it" }.toByteArray()
        val pipe = java.io.PipedInputStream(1 shl 16); val po = java.io.PipedOutputStream(pipe)
        val sp = SpoolingSource(pipe, tmp(ByteArray(0)).parentFile)
        val th = Thread { var p = 0; while (p < text.size) { val n = minOf(5000, text.size - p); po.write(text, p, n); p += n; Thread.sleep(1) }; po.close() }; th.start()
        val r = DocumentOpener.open(sp, null) as OpenResult.Ok
        assertEquals(DocMode.VIEW, r.info.mode)
        waitIndex(r.doc); assertEquals(20000, r.doc.lineCount); assertEquals("line 19999", r.doc.getLine(19999)); r.doc.close()
    }
    @Test fun copyBytesStreamsRaw() {
        val data = ByteArray(3_000_000) { it.toByte() }; val t = MemTarget()
        assertTrue(DocumentSaver.copyBytes(MemSource(data), t).ok); assertArrayEquals(data, t.bos.toByteArray())
    }
}
