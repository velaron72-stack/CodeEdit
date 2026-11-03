package app.codeedit.data

import org.junit.Assert.*
import org.junit.Test

class SchemaTests {
    @Test fun defaultsAreValidAndUniqueKeys() {
        assertEquals(Schema.all.size, Schema.byKey.size)
        for (d in Schema.all) assertNotNull("default of ${d.key}", Schema.coerce(d, d.default))
        for (d in Schema.all) if (d.type == SType.ENUM) assertTrue(d.key, d.options.any { it.value == d.default })
    }

    @Test fun exportThenImportRoundTrips() {
        val s = Settings().with(K.FONT_SIZE, 17.5f).with(K.TAB_SIZE, 2).with(K.WRAP, true).with(K.THEME_MODE, "dark").with(K.G_KEYS_SET, "Tab ( ) Ctrl+Z")
        val bytes = Schema.export(s.values, "1.0.0")
        val r = Schema.parseImport(bytes)
        assertTrue(r.errors.toString(), r.ok)
        val back = Settings().withAll(r.values)
        assertEquals(17.5f, back.float(K.FONT_SIZE), 0f)
        assertEquals(2, back.int(K.TAB_SIZE)); assertTrue(back.bool(K.WRAP)); assertEquals("dark", back.str(K.THEME_MODE))
        assertEquals("Tab ( ) Ctrl+Z", back.str(K.G_KEYS_SET))
    }

    @Test fun rejectsBadStructureVersionAndValues() {
        assertFalse(Schema.parseImport("not json".toByteArray()).ok)
        assertFalse(Schema.parseImport("[1,2]".toByteArray()).ok)
        assertFalse(Schema.parseImport("""{"format":"other","version":1,"settings":{}}""".toByteArray()).ok)
        assertFalse(Schema.parseImport("""{"format":"codeedit-settings","version":99,"settings":{}}""".toByteArray()).ok)
        assertFalse(Schema.parseImport("""{"format":"codeedit-settings","version":1,"settings":{"editor.tabSize":99}}""".toByteArray()).ok)
        assertFalse(Schema.parseImport("""{"format":"codeedit-settings","version":1,"settings":{"editor.tabSize":2.5}}""".toByteArray()).ok)
        assertFalse(Schema.parseImport("""{"format":"codeedit-settings","version":1,"settings":{"ui.themeMode":"purple"}}""".toByteArray()).ok)
        assertFalse(Schema.parseImport("""{"format":"codeedit-settings","version":1,"settings":{"editor.wordWrap":"yes"}}""".toByteArray()).ok)
        assertFalse(Schema.parseImport("""{"format":"codeedit-settings","version":1,"settings":{"font.min":30,"font.max":20}}""".toByteArray()).ok)
    }

    @Test fun unknownKeysAreWarningsOnly() {
        val r = Schema.parseImport("""{"format":"codeedit-settings","version":1,"settings":{"future.thing":1,"editor.tabSize":8}}""".toByteArray())
        assertTrue(r.ok); assertEquals(1, r.warnings.size); assertEquals(8, r.values["editor.tabSize"])
    }

    @Test fun tightLayoutAndHighRefreshDefaults() {
        val s = Settings()
        assertEquals(1.0f, s.float(K.LINE_SPACING), 0f)
        assertEquals(2, s.int(K.LN_DIGITS))
        assertTrue(s.bool(K.HIGH_REFRESH))
        assertEquals("{}", s.str(K.LANG_ASSOC))
        assertTrue(Schema.byKey[K.LANG_ASSOC]!!.hidden)
        val d = Schema.byKey[K.LINE_SPACING]!!
        assertEquals(0.8, d.min, 0.0)
        assertEquals(0.8f, Schema.coerce(d, 0.8f) as Float, 0f)       // tighter than the natural line height is allowed
        assertNull(Schema.coerce(d, 0.7f))
        assertNotNull(Schema.coerce(Schema.byKey[K.LANG_ASSOC]!!, "x".repeat(1900)))
    }

    @Test fun partialImportKeepsOthers() {
        val cur = Settings().with(K.TAB_SIZE, 3)
        val r = Schema.parseImport("""{"format":"codeedit-settings","version":1,"settings":{"editor.wordWrap":true}}""".toByteArray())
        val merged = cur.withAll(r.values)
        assertEquals(3, merged.int(K.TAB_SIZE)); assertTrue(merged.bool(K.WRAP))
    }
}
