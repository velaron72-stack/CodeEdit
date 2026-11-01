package app.codeedit.data

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SessionTests {
    private fun tmp(): File = Files.createTempDirectory("sess").toFile()

    @Test fun codecRoundTrip() {
        val t = TabRecord("a1", "content://x/y", "main.kt", "kotlin", 1, 2, 3, 4, 50, 12.5f, 7f, 15f, "windows-1251", false, "CRLF", "view", true, true, 1234, 99L, true, true)
        val t2 = TabRecord("b2", null, "untitled", null)
        val r = SessionCodec.decode(SessionCodec.encode(SessionRecord("a1", listOf(t, t2), listOf("b2", "a1"))))!!
        assertEquals("a1", r.activeId); assertEquals(listOf("b2", "a1"), r.recentOrder)
        assertEquals(t, r.tabs[0]); assertEquals(t2, r.tabs[1])
    }

    @Test fun decodeRejectsGarbageAndNewerVersions() {
        assertNull(SessionCodec.decode("garbage".toByteArray()))
        assertNull(SessionCodec.decode("""{"version":99,"tabs":[]}""".toByteArray()))
        assertNotNull(SessionCodec.decode("""{"version":1,"tabs":[{"id":"x"},{"nope":1}]}""".toByteArray()))
        assertEquals(1, SessionCodec.decode("""{"version":1,"tabs":[{"id":"x"},{"nope":1}]}""".toByteArray())!!.tabs.size)
    }

    @Test fun filesFallBackToBackupAndKeepDrafts() {
        val d = tmp(); val f = SessionFiles(d)
        f.writeSession(SessionRecord("a", listOf(TabRecord("a", null, "a")), emptyList()))
        f.writeSession(SessionRecord("b", listOf(TabRecord("b", null, "b")), emptyList()))
        assertEquals("b", f.readSession()!!.activeId)
        File(d, "session.json").writeText("{ broken")
        assertEquals("a", f.readSession()!!.activeId)               // previous good copy
        f.writeDraft("t1", "héllo\nмир"); f.writeDraft("t2", "x")
        assertEquals("héllo\nмир", f.readDraft("t1"))
        f.pruneOrphanDrafts(setOf("t1"))
        assertTrue(f.hasDraft("t1")); assertFalse(f.hasDraft("t2"))
        f.deleteDraft("t1"); assertNull(f.readDraft("t1"))
    }

    @Test fun recentListDedupesAndCaps() {
        val f = SessionFiles(tmp())
        f.addRecent(SessionFiles.Recent("u1", "a", 1)); f.addRecent(SessionFiles.Recent("u2", "b", 2)); f.addRecent(SessionFiles.Recent("u1", "a", 3))
        val l = f.readRecent()
        assertEquals(listOf("u1", "u2"), l.map { it.uri }); assertEquals(3L, l[0].time)
        for (i in 0 until 40) f.addRecent(SessionFiles.Recent("x$i", "n", i.toLong()), 30)
        assertEquals(30, f.readRecent().size)
        assertEquals(29, f.removeRecent("x39").size)
    }

    @Test fun extraKeysParse() {
        val k = ExtraKeysParser.parse(Schema.DEFAULT_KEYS)
        assertTrue(k[0] === ExtraKey.Tab); assertEquals("{", k[1].label); assertEquals("\"", k.first { it.label == "\"" }.label)
        val c = ExtraKeysParser.parse("Ctrl+z Left Undo Esc ab") 
        assertEquals(54, (c[0] as ExtraKey.Combo).keyCode)    // KEYCODE_Z
        assertEquals("Ctrl+Z", c[0].label); assertEquals("←", c[1].label); assertTrue(c[2] === ExtraKey.Undo); assertEquals("Esc", c[3].label)
        assertTrue(c[4] is ExtraKey.Text)
    }
}
