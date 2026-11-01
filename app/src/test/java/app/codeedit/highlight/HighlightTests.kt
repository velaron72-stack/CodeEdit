package app.codeedit.highlight

import app.codeedit.core.MemSource
import app.codeedit.core.doc.*
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File
import java.util.concurrent.Executor

/** Engine double with the exact contract of TmNative: a line starting inside a block comment is "comment" (fg id 1). */
class FakeBackend : TokenizerBackend {
    class Session(val stride: Int) { val cps = java.util.TreeMap<Int, Boolean>().apply { put(0, false) } }
    val sessions = HashMap<Long, Session>()
    private var next = 1L
    val calls = ArrayList<String>()
    override fun createRegistry(provider: GrammarProvider, manifestJson: ByteArray) = 1L
    override fun destroyRegistry(registry: Long) {}
    override fun setTheme(registry: Long, themeJson: ByteArray, defaultFg: Int, defaultBg: Int) = intArrayOf(0, 0xFF111111.toInt(), 0xFF00AA00.toInt())
    override fun createSession(registry: Long, rootScope: String, maxLineChars: Int, checkpointStride: Int, maxCheckpoints: Int): Long {
        val id = next++; sessions[id] = Session(checkpointStride); return id
    }
    override fun destroySession(session: Long) { sessions.remove(session) }
    override fun lastError(): String? = null
    override fun invalidateFrom(session: Long, line: Int) { sessions[session]!!.cps.keys.removeAll { it > maxOf(line, 0) } }   // line 0 (document start) is always valid
    override fun nearestCheckpoint(session: Long, line: Int): Int = sessions[session]!!.cps.floorKey(line)
    override fun maxCheckpoint(session: Long): Int = sessions[session]!!.cps.lastKey()
    override fun tokenize(session: Long, startLine: Int, chars: CharArray, offsets: IntArray, lineCount: Int,
                          emitFrom: Int, cold: Boolean, record: Boolean, timeLimitMs: Int): IntArray? {
        val s = sessions[session]!!
        calls += "tokenize($startLine,n=$lineCount,emit=$emitFrom,cold=$cold,rec=$record)"
        var inComment: Boolean
        if (startLine == 0) inComment = false
        else if (s.cps.containsKey(startLine)) inComment = s.cps[startLine]!!
        else if (cold) { inComment = false } else return null
        val out = ArrayList<Int>()
        for (i in 0 until lineCount) {
            val line = startLine + i
            if (record && !(cold) && i > 0 && line % s.stride == 0) s.cps[line] = inComment
            val text = String(chars, offsets[i], offsets[i + 1] - offsets[i])
            val startState = inComment
            var st = inComment
            if (!st && text.contains("/*") && !text.contains("*/")) st = true
            else if (st && text.contains("*/")) st = false
            inComment = st
            if (line >= emitFrom) { out += 1; out += 0; out += (if (startState || text.contains("/*")) 1 else 0) shl 15 }
        }
        val end = startLine + lineCount
        if (record && !cold && lineCount > 0 && end % s.stride == 0) s.cps[end] = inComment
        return out.toIntArray()
    }
}

class QueueExecutor : Executor {
    val q = ArrayDeque<Runnable>()
    override fun execute(command: Runnable) { q.addLast(command) }
    fun runAll() { while (q.isNotEmpty()) q.removeFirst().run() }
}

class NativeHighlighterTests {
    init { NativeHighlighter.errorSink = { it.printStackTrace() } }
    private val direct = Executor { it.run() }

    private fun assets(): AssetReader {
        val manifest = """{"format":1,"engine":"fake","languages":[{"id":"lang","name":"Lang","scope":"source.lang","file":"lang.json","extensions":[".lang"]}],
            "themes":[{"id":"t1","name":"T1","type":"dark","file":"t1.json"},{"id":"t2","name":"T2","type":"light","file":"t2.json"}]}"""
        return AssetReader { p ->
            when (p) {
                "manifest.json" -> manifest.toByteArray()
                "themes/t1.json" -> """{"type":"dark","colors":{"editor.background":"#101010","editor.foreground":"#EEEEEE"},"tokenColors":[]}""".toByteArray()
                "themes/t2.json" -> """{"type":"light","colors":{"editor.background":"#FFFFFF"},"tokenColors":[]}""".toByteArray()
                else -> null
            }
        }
    }

    private fun service(backend: FakeBackend, worker: Executor = direct): HighlightService =
        HighlightService(assets(), direct, worker, backend, { true })

    private fun text(n: Int, commentFrom: Int = -1, commentTo: Int = -1): String =
        (0 until n).joinToString("\n") { i -> if (i == commentFrom) "/* open $i" else if (i == commentTo) "close */" else "line $i" }

    private fun fg(t: LineTokens?) = t?.let { TokenMeta.foreground(it.meta[0]) } ?: -1

    @Test fun stridesGrowWithDocumentSize() {
        assertEquals(64, NativeHighlighter.chooseStride(100))
        assertEquals(64, NativeHighlighter.chooseStride(200_000))
        assertTrue(NativeHighlighter.chooseStride(25_000_000) in 1024..2048)
    }

    @Test fun tokensAppearAfterRequestAndMatchEngineState() {
        val be = FakeBackend()
        val svc = service(be)
        val doc = EditDocument.fromString(text(300, 10, 20))
        val h = svc.createHighlighter(doc, "lang")!!
        var notified = 0
        h.listener = HighlightListener { _, _ -> notified++ }
        h.requestRange(0, 40)
        assertNotNull(h.tokensFor(5)); assertEquals(0, fg(h.tokensFor(5)))
        assertEquals(1, fg(h.tokensFor(15)))        // inside the comment
        assertEquals(0, fg(h.tokensFor(25)))
        assertTrue(notified > 0)
        h.close(); svc.close()
    }

    @Test fun editOpeningACommentRecolorsTheFollowingLines() {
        val be = FakeBackend()
        val svc = service(be)
        val doc = EditDocument.fromString(text(300))
        val h = svc.createHighlighter(doc, "lang")!!
        h.requestRange(0, 60)
        assertEquals(0, fg(h.tokensFor(30)))
        doc.replace(Pos(10, 0), Pos(10, 0), "/* ")       // opens a comment that never closes
        h.requestRange(0, 60)
        assertEquals(1, fg(h.tokensFor(30)))
        assertEquals(1, fg(h.tokensFor(59)))
        // closing it again restores
        doc.replace(Pos(10, 0), Pos(10, 3), "")
        h.requestRange(0, 60)
        assertEquals(0, fg(h.tokensFor(30)))
        h.close(); svc.close()
    }

    @Test fun insertedLinesShiftExistingTokens() {
        val be = FakeBackend()
        val svc = service(be)
        val doc = EditDocument.fromString(text(200, 100, 110))
        val h = svc.createHighlighter(doc, "lang")!!
        h.requestRange(90, 130)
        assertEquals(1, fg(h.tokensFor(105)))
        doc.replace(Pos(0, 0), Pos(0, 0), "a\nb\nc\n")      // 3 lines inserted at the top
        h.requestRange(90, 130)
        assertEquals(1, fg(h.tokensFor(108)))              // the comment moved down by 3
        assertEquals(0, fg(h.tokensFor(105 - 3)))
        h.close(); svc.close()
    }

    @Test fun farLinesStartColdThenBecomeExact() {
        val be = FakeBackend()
        val q = QueueExecutor()
        val svc = HighlightService(assets(), direct, java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }, be, { true })
        // 60_000 lines: comment opened at line 100 and closed at line 55_000 => line 50_000 is a comment
        val doc = EditDocument.fromString(text(60_000, 100, 55_000))
        val h = svc.createHighlighter(doc, "lang")!!
        h.requestRange(50_000, 50_020)
        val deadline = System.currentTimeMillis() + 20_000
        while ((h.tokensFor(50_000) == null) && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertNotNull(h.tokensFor(50_000))
        // the first tokenization of that block must have been a cold start (checkpoint 0 is 50k lines away)
        assertTrue(be.calls.any { it.contains("cold=true") })
        // the frontier eventually reaches the block and replaces the approximation with the exact state (comment)
        while ((h.isApproximate(50_000) || fg(h.tokensFor(50_000)) != 1) && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertFalse(h.isApproximate(50_000))
        assertEquals(1, fg(h.tokensFor(50_000)))
        h.close(); svc.close(); q.runAll()
    }

    @Test fun viewDocumentIsReadOnTheWorker() {
        val be = FakeBackend()
        val svc = HighlightService(assets(), direct, java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }, be, { true })
        val bytes = text(5000, 10, 4000).toByteArray()
        val r = DocumentOpener.open(MemSource(bytes), "x.lang", OpenOptions(forceMode = DocMode.VIEW)) as OpenResult.Ok
        val doc = r.doc
        val t0 = System.currentTimeMillis()
        while (!doc.isIndexComplete && System.currentTimeMillis() - t0 < 10_000) Thread.sleep(2)
        val h = svc.createHighlighter(doc, "lang")!!
        h.requestRange(3000, 3030)
        val deadline = System.currentTimeMillis() + 10_000
        while (h.tokensFor(3000) == null && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(1, fg(h.tokensFor(3000)))
        h.close(); svc.close(); doc.close()
    }

    @Test fun themeChangeSwapsColourMapAndNotifies() {
        val be = FakeBackend()
        val svc = service(be)
        val doc = EditDocument.fromString(text(50))
        val h = svc.createHighlighter(doc, "lang")!!
        h.requestRange(0, 10)
        assertEquals(0xFF00AA00.toInt(), h.colorMap[2])
        var lastRange: Pair<Int, Int>? = null
        h.listener = HighlightListener { a, b -> lastRange = a to b }
        val info = svc.setTheme("t2")
        assertFalse(info.isDark)
        assertEquals(0 to Int.MAX_VALUE, lastRange)
        assertEquals("t2", svc.theme.id)
        h.close(); svc.close()
    }

    @Test fun unknownLanguageAndPlainTextGiveNoHighlighter() {
        val svc = service(FakeBackend())
        val doc = EditDocument.fromString("x")
        assertNull(svc.createHighlighter(doc, PLAIN_TEXT_ID))
        assertNull(svc.createHighlighter(doc, "nope"))
        svc.close()
    }

    @Test fun engineUnavailableGivesNoHighlighter() {
        val svc = HighlightService(assets(), direct, null, FakeBackend(), { false })
        assertNull(svc.createHighlighter(EditDocument.fromString("x"), "lang"))
        assertFalse(svc.engineAvailable)
        svc.close()
    }
}

class ThemeColorsTests {
    @Test fun parsesColourForms() {
        assertEquals(0xFF112233.toInt(), ThemeColors.parseColor("#123"))
        assertEquals(0xFF123456.toInt(), ThemeColors.parseColor("#123456"))
        assertEquals(0x80123456.toInt(), ThemeColors.parseColor("#12345680"))
        assertEquals(0x44112233, ThemeColors.parseColor("#1234"))
        assertNull(ThemeColors.parseColor("red")); assertNull(ThemeColors.parseColor("#12")); assertNull(ThemeColors.parseColor("#GGGGGG")); assertNull(ThemeColors.parseColor(null))
    }

    @Test fun derivesEditorColoursWithFallbacks() {
        val json = """{"type":"light","colors":{"editor.background":"#FFFFFF","editor.foreground":"#222222","editor.selectionBackground":"#ADD6FF"},"tokenColors":[]}"""
        val p = ThemeColors.parse(json.toByteArray(), null)
        assertFalse(p.colors.isDark)
        assertEquals(0xFFFFFFFF.toInt(), p.colors.background)
        assertEquals(0xFF222222.toInt(), p.colors.foreground)
        assertEquals(0xFFADD6FF.toInt(), p.colors.selection)
        assertEquals(0xFF222222.toInt(), p.colors.cursor)                 // falls back to the foreground
        assertEquals(0x14, p.colors.lineHighlight ushr 24)               // derived translucent colour
    }

    @Test fun usesGlobalTokenColoursWhenEditorColoursAreMissing() {
        val json = """{"type":"dark","tokenColors":[{"settings":{"foreground":"#C0C0C0","background":"#202020"}},{"scope":"x","settings":{"foreground":"#FF0000"}}]}"""
        val p = ThemeColors.parse(json.toByteArray(), null)
        assertEquals(0xFFC0C0C0.toInt(), p.defaultFg); assertEquals(0xFF202020.toInt(), p.defaultBg)
    }

    @Test fun blendsTranslucentBackgroundOverTheEditorBackground() {
        val json = """{"type":"dark","colors":{"editor.background":"#000000"},"tokenColors":[]}"""
        val p = ThemeColors.parse(json.toByteArray(), null)
        assertEquals(0xFF000000.toInt(), p.colors.background)
        assertEquals(255, ThemeColors.over(0xFF000000.toInt(), 0x80FFFFFF.toInt()) ushr 24)
    }
}

/** Uses the real bundled manifest/grammars/themes and (when available) the real native engine. */
class BundledAssetsTests {
    private fun assetsDir(): File? {
        var d: File? = File("").absoluteFile
        repeat(4) { val c = File(d, "app/src/main/assets/textmate"); if (c.isDirectory) return c; val c2 = File(d, "src/main/assets/textmate"); if (c2.isDirectory) return c2; d = d?.parentFile ?: return null }
        return null
    }
    private fun reader(): AssetReader? {
        val dir = assetsDir() ?: return null
        return AssetReader { p -> File(dir, p).takeIf { it.isFile }?.readBytes() }
    }

    @Test fun manifestListsAllBundledLanguagesAndThemes() {
        val r = reader(); assumeNotNull(r)
        val svc = HighlightService(r!!, Executor { it.run() }, null, FakeBackend(), { false })
        assertEquals(260, svc.packageInfo.languageCount)
        assertEquals(65, svc.packageInfo.themeCount)
        assertEquals(PLAIN_TEXT_ID, svc.languages.first().id)
        assertTrue(svc.languages.none { it.hidden })
        // every grammar and theme file named by the manifest exists
        for (l in svc.allLanguages) l.file?.let { assertNotNull("grammar ${l.id}", r.read("grammars/$it")) }
        for (t in svc.themes) assertNotNull("theme ${t.id}", r.read("themes/${t.file}"))
        svc.close()
    }

    @Test fun detectsLanguagesByNameExtensionAndShebang() {
        val r = reader(); assumeNotNull(r)
        val svc = HighlightService(r!!, Executor { it.run() }, null, FakeBackend(), { false })
        fun id(f: String?, first: String? = null) = svc.detectLanguage(f, first)?.id
        assertEquals("python", id("main.py"))
        assertEquals("typescript", id("types.d.ts"))
        assertEquals("kotlin", id("A.kt"))
        assertEquals("java", id("A.java"))
        assertEquals("json", id("a.json"))
        assertEquals("make", id("Makefile"))
        assertEquals("docker", id("Dockerfile"))
        assertEquals("xml", id("AndroidManifest.xml"))
        assertEquals("python", id("script", "#!/usr/bin/env python3"))
        assertEquals("shellscript", id("run", "#!/bin/bash"))
        assertNull(id("notes.unknownext"))
        svc.close()
    }

    @Test fun realEngineHighlightsPythonWithTheBundledTheme() {
        val r = reader(); assumeNotNull(r)
        val lib = System.getProperty("codeedit.native.lib"); assumeNotNull(lib)
        val svc = HighlightService(r!!, Executor { it.run() })
        assertTrue("engine must load: ${TmNative.loadFailure}", svc.engineAvailable)
        svc.setTheme("dark-plus")
        val doc = EditDocument.fromString("# comment\ndef f(x):\n    return 'str' + x\n\"\"\"doc\nstring\"\"\"\nz = 1\n")
        val h = svc.createHighlighter(doc, "python")!!
        h.requestRange(0, 5)
        val deadline = System.currentTimeMillis() + 15_000
        while (h.tokensFor(5) == null && System.currentTimeMillis() < deadline) Thread.sleep(5)
        val map = h.colorMap
        fun colorAt(line: Int, col: Int): Int {
            val t = h.tokensFor(line)!!
            var k = 0
            for (i in 0 until t.count) if (t.starts[i] <= col) k = i
            return map[TokenMeta.foreground(t.meta[k])]
        }
        assertEquals("comment", 0xFF6A9955.toInt(), colorAt(0, 2))
        assertEquals("def keyword", 0xFF569CD6.toInt(), colorAt(1, 0))
        assertEquals("string", 0xFFCE9178.toInt(), colorAt(2, 12))
        assertEquals("multi-line string continues on the next line", 0xFFCE9178.toInt(), colorAt(4, 2))
        assertNotEquals("plain code differs from string", 0xFFCE9178.toInt(), colorAt(5, 0))
        // an edit that opens a string recolours the lines below
        doc.replace(Pos(5, 4), Pos(5, 4), "'")
        h.requestRange(0, 5)
        val d2 = System.currentTimeMillis() + 15_000
        while (colorAt(5, 5) != 0xFFB5CEA8.toInt() && colorAt(5, 5) != 0xFFCE9178.toInt() && System.currentTimeMillis() < d2) Thread.sleep(5)
        h.close(); svc.close()
    }
}
