package app.codeedit.core

import app.codeedit.core.doc.*
import app.codeedit.core.io.*
import app.codeedit.core.search.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

private val direct = Executor { Thread(it).apply { isDaemon = true }.start() }
private fun waitDone(s: SearchSession) { val t = System.currentTimeMillis(); while (!s.isComplete && System.currentTimeMillis() - t < 20000) Thread.sleep(2) }
private fun viewOf(text: String, enc: TextEncoding = TextEncoding.UTF8): ViewDocument {
    val b = text.toByteArray(TextCodec.charset(enc))
    val d = ViewDocument(MemSource(b), DocInfo(DocMode.VIEW, enc, Eol.LF, false, b.size.toLong(), false)); waitIndex(d); return d
}

class CompiledQueryTests {
    @Test fun literalCaseAndWord() {
        assertEquals((4L shl 32) or 7L, CompiledQuery(SearchQuery("foo", matchCase = true)).find("abc foo", 0))
        assertEquals(-1L, CompiledQuery(SearchQuery("FOO", matchCase = true)).find("abc foo", 0))
        assertEquals((4L shl 32) or 7L, CompiledQuery(SearchQuery("FOO")).find("abc foo", 0))
        val w = CompiledQuery(SearchQuery("foo", wholeWord = true))
        assertEquals(-1L, w.find("foobar xfoo foo_", 0)); assertEquals((8L shl 32) or 11L, w.find("foobar  foo", 0))
        assertArrayEquals(intArrayOf(0, 2, 6, 2), CompiledQuery(SearchQuery("ab")).matchesInLine("ab xx ab"))
    }
    @Test fun unicodeCaseInsensitive() { assertEquals(0L shl 32 or 6L, CompiledQuery(SearchQuery("ПРИВЕТ")).find("привет мир", 0)) }
    @Test fun regexGroupsAndEmptyMatches() {
        val q = CompiledQuery(SearchQuery("a*", regex = true))
        assertArrayEquals(intArrayOf(1, 2, 5, 1), q.matchesInLine("baacda"))
        assertTrue(CompiledQuery(SearchQuery("^x", regex = true)).find("a\nx", 0) >= 0)
    }
    @Test(expected = QueryException::class) fun badRegex() { CompiledQuery(SearchQuery("(", regex = true)) }
    @Test fun catastrophicRegexTimesOut() {
        val q = CompiledQuery(SearchQuery("(a+)+\\1c", regex = true))
        try { q.find("a".repeat(40) + "b", 0, System.nanoTime() + 200_000_000L); fail("expected timeout") } catch (_: SearchTimeout) {}
    }
}

class SearchSessionTests {
    @Test fun viewScanFindsAllAndOrdinals() {
        val text = (0 until 2000).joinToString("\n") { if (it % 10 == 0) "xx needle yy needle" else "plain $it" }
        val d = viewOf(text)
        val s = SearchSession(d, SearchQuery("needle"), direct); s.start(); waitDone(s)
        assertEquals(400L, s.matchCount); assertTrue(s.isComplete)
        assertEquals(1L, s.ordinalOf(Match(Pos(0, 3), Pos(0, 9)))); assertEquals(2L, s.ordinalOf(Match(Pos(0, 13), Pos(0, 19)))); assertEquals(3L, s.ordinalOf(Match(Pos(10, 3), Pos(10, 9))))
        assertEquals(-1L, s.ordinalOf(Match(Pos(1, 0), Pos(1, 1))))
        s.close()
    }
    @Test fun findNextPreviousWrapView() {
        val d = viewOf("a needle\nnone\nneedle b\nend")
        val s = SearchSession(d, SearchQuery("needle"), direct)
        assertEquals(Pos(0, 2), s.findNext(Pos(0, 0))!!.start)
        assertEquals(Pos(2, 0), s.findNext(Pos(0, 2))!!.start)
        assertEquals(Pos(0, 2), s.findNext(Pos(2, 0))!!.start)              // wrap
        assertNull(s.findNext(Pos(2, 0), wrap = false))
        assertEquals(Pos(2, 0), s.findNext(Pos(2, 0), inclusive = true)!!.start)
        assertEquals(Pos(0, 2), s.findPrevious(Pos(2, 0))!!.start)
        assertEquals(Pos(2, 0), s.findPrevious(Pos(0, 2))!!.start)          // wrap backwards
    }
    @Test fun editSnapshotMultilineRegex() {
        val d = EditDocument.fromString("foo(\n  bar)\nbaz foo(\n bar)")
        val s = SearchSession(d, SearchQuery("foo\\([\\s\\S]*?\\)", regex = true), direct); s.start(); waitDone(s)
        assertEquals(2L, s.matchCount)
        val m = s.findNext(Pos(0, 0), inclusive = true)!!
        assertEquals(Pos(0, 0), m.start); assertEquals(Pos(1, 6), m.end)
        assertEquals(Pos(2, 4), s.findNext(m.start)!!.start)
        assertEquals(2L, s.ordinalOf(Match(Pos(2, 4), Pos(3, 5))))
    }
    @Test fun editRestartAfterChange() {
        val d = EditDocument.fromString("a b a")
        val s = SearchSession(d, SearchQuery("a"), direct); s.start(); waitDone(s); assertEquals(2L, s.matchCount)
        d.replace(Pos(0, 5), Pos(0, 5), " a"); s.onDocumentChanged(0, 1, 1); assertTrue(s.needsRestart); assertEquals(-1L, s.ordinalOf(Match(Pos(0, 0), Pos(0, 1))))
        s.start(); waitDone(s); assertEquals(3L, s.matchCount); assertEquals(3L, s.ordinalOf(Match(Pos(0, 6), Pos(0, 7))))
        assertEquals(Pos(0, 6), s.findPrevious(Pos(0, 100))!!.start)
    }
    @Test fun matchesInLineForPainting() {
        val s = SearchSession(EditDocument.fromString("ab ab\nxx"), SearchQuery("ab"), direct)
        assertArrayEquals(intArrayOf(0, 2, 3, 2), s.matchesInLine(0)); assertEquals(0, s.matchesInLine(1).size)
    }
    @Test fun scanWaitsForGrowingIndexAndFindsAcrossBlocks() {
        val sb = StringBuilder(); repeat(200_000) { sb.append("line number ").append(it).append('\n') }
        val d = viewOf(sb.toString())
        val s = SearchSession(d, SearchQuery("number 199999"), direct); s.start(); waitDone(s)
        assertEquals(1L, s.matchCount); assertEquals(199_999, s.findNext(Pos(0, 0))!!.start.line)
        assertEquals(199_999, s.findPrevious(Pos(0, 0))!!.start.line)
    }
    @Test fun cancelStopsScan() {
        val d = viewOf("x\n".repeat(100000)); val s = SearchSession(d, SearchQuery("x"), direct); s.start(); s.cancel(); Thread.sleep(50); s.start(); waitDone(s); assertEquals(100000L, s.matchCount)
    }
}

class ReplacerTests {
    @Test fun templates() {
        val q = SearchQuery("(\\w+)@(\\w+)", regex = true)
        assertEquals("b at a\n", Replacer.expandAt(q, "a@b", 0, 3, "\$2 at \$1\\n"))
        assertEquals("[a@b]", Replacer.expandAt(q, "a@b", 0, 3, "[\\0]"))
        assertEquals("\$1", Replacer.expandAt(SearchQuery("x"), "x", 0, 1, "\$1"))
        assertEquals("price \$5", Replacer.expandAt(SearchQuery("p", regex = true), "p", 0, 1, "price \\\$5"))
    }
    @Test fun replaceAllOneUndoStep() {
        val d = EditDocument.fromString("foo bar\nbaz foo\nfoo")
        assertEquals(3, Replacer.replaceAll(d, SearchQuery("foo"), "X", null, null))
        assertEquals("X bar\nbaz X\nX", d.fullText()); d.undo(); assertEquals("foo bar\nbaz foo\nfoo", d.fullText()); assertFalse(d.canUndo)
    }
    @Test fun replaceAllRegexGroupsMultiline() {
        val d = EditDocument.fromString("k1=v1\nk2=v2\nnothing")
        assertEquals(2, Replacer.replaceAll(d, SearchQuery("(\\w+)=(\\w+)", regex = true), "\$2:\$1", null, null))
        assertEquals("v1:k1\nv2:k2\nnothing", d.fullText())
        assertEquals(0, Replacer.replaceAll(d, SearchQuery("zzz"), "q", null, null))
    }
    @Test fun streamReplacePreservesEolAndBom() {
        val data = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "foo a\r\nb foo foo\rc\nfoo".toByteArray()
        val info = DocInfo(DocMode.VIEW, TextEncoding("UTF-8", true), Eol.CRLF, true, data.size.toLong(), false)
        val t = MemTarget()
        val r = Replacer.streamReplaceAll(MemSource(data), info, SearchQuery("foo"), "ZZ", t, null, null)
        assertTrue(r.ok); assertEquals(4L, r.replacements); assertTrue(t.committed)
        assertArrayEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "ZZ a\r\nb ZZ ZZ\rc\nZZ".toByteArray(), t.bos.toByteArray())
    }
    @Test fun streamReplaceUtf16() {
        val data = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "привет\nмир привет".toByteArray(Charsets.UTF_16LE)
        val info = DocInfo(DocMode.VIEW, TextEncoding("UTF-16LE", true), Eol.LF, false, data.size.toLong(), false)
        val t = MemTarget(); val r = Replacer.streamReplaceAll(MemSource(data), info, SearchQuery("привет"), "hi", t, null, null)
        assertTrue(r.ok); assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "hi\nмир hi".toByteArray(Charsets.UTF_16LE), t.bos.toByteArray())
    }
    @Test fun streamReplaceFailureLeavesTargetUncommitted() {
        val bad = "foo ÿþ bad".toByteArray(Charsets.ISO_8859_1)   // invalid UTF-8
        val info = DocInfo(DocMode.VIEW, TextEncoding.UTF8, Eol.LF, false, bad.size.toLong(), false)
        val t = MemTarget(); val r = Replacer.streamReplaceAll(MemSource(bad), info, SearchQuery("foo"), "x", t, null, null)
        assertFalse(r.ok); assertTrue(t.aborted); assertFalse(t.committed)
        // cancel
        val big = "foo\n".repeat(500000).toByteArray(); val flag = AtomicBoolean(true)
        val t2 = MemTarget(); val r2 = Replacer.streamReplaceAll(MemSource(big), info, SearchQuery("foo"), "x", t2, null, flag)
        assertFalse(r2.ok); assertTrue(t2.aborted)
    }
    @Test fun streamReplaceCp1251AndEmptyLines() {
        val cs = charset("windows-1251")
        val data = "мир\n\nмир мир\n".toByteArray(cs)
        val info = DocInfo(DocMode.VIEW, TextEncoding("windows-1251"), Eol.LF, false, data.size.toLong(), true)
        val t = MemTarget(); val r = Replacer.streamReplaceAll(MemSource(data), info, SearchQuery("мир"), "м", t, null, null)
        assertTrue(r.ok); assertArrayEquals("м\n\nм м\n".toByteArray(cs), t.bos.toByteArray())
    }
}
