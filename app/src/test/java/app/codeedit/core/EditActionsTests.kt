package app.codeedit.core

import app.codeedit.core.doc.*
import app.codeedit.core.edit.*
import org.junit.Assert.*
import org.junit.Test

class EditActionsTests {
    private val o = EditOptions(tabSize = 4, insertSpaces = true, autoIndent = true, autoCloseBrackets = true)
    private fun doc(s: String) = EditDocument.fromString(s)
    private fun caret(l: Int, c: Int) = Selection.caret(Pos(l, c))

    @Test fun typingCoalescesIntoOneUndoStep() {
        val d = doc("")
        var s = Selection.ZERO
        for (c in "hello") s = EditActions.insertText(d, s, c.toString(), o)
        assertEquals("hello", d.fullText()); assertEquals(caret(0, 5), s)
        val back = d.undo()
        assertEquals("", d.fullText()); assertNotNull(back)
    }

    @Test fun autoClosesBracketsAndSkipsOverTheCloser() {
        val d = doc("")
        var s = EditActions.insertText(d, Selection.ZERO, "(", o)
        assertEquals("()", d.fullText()); assertEquals(caret(0, 1), s)
        s = EditActions.insertText(d, s, "x", o)
        s = EditActions.insertText(d, s, ")", o)
        assertEquals("(x)", d.fullText()); assertEquals(caret(0, 3), s)
    }

    @Test fun quotesPairOnlyOutsideWords() {
        val d = doc("don")
        val s = EditActions.insertText(d, caret(0, 3), "'", o)
        assertEquals("don'", d.fullText()); assertEquals(caret(0, 4), s)
        val d2 = doc("")
        EditActions.insertText(d2, Selection.ZERO, "\"", o)
        assertEquals("\"\"", d2.fullText())
    }

    @Test fun backspaceRemovesEmptyPair() {
        val d = doc("()")
        val s = EditActions.backspace(d, caret(0, 1), o)
        assertEquals("", d.fullText()); assertEquals(caret(0, 0), s)
    }

    @Test fun surroundSelectionWithBrackets() {
        val d = doc("abc")
        val s = EditActions.insertText(d, Selection(Pos(0, 0), Pos(0, 3)), "[", o)
        assertEquals("[abc]", d.fullText()); assertEquals(Selection(Pos(0, 1), Pos(0, 4)), s)
    }

    @Test fun newlineKeepsIndentAndAddsOneAfterOpeningBrace() {
        val d = doc("    if (x) {")
        val s = EditActions.newline(d, caret(0, 12), o)
        assertEquals("    if (x) {\n        ", d.fullText()); assertEquals(caret(1, 8), s)
    }

    @Test fun newlineBetweenBracesOpensABlock() {
        val d = doc("fn() {}")
        val s = EditActions.newline(d, caret(0, 6), o)
        assertEquals("fn() {\n    \n}", d.fullText()); assertEquals(caret(1, 4), s)
    }

    @Test fun newlineWithoutAutoIndent() {
        val d = doc("  a")
        val s = EditActions.newline(d, caret(0, 3), o.copy(autoIndent = false))
        assertEquals("  a\n", d.fullText()); assertEquals(caret(1, 0), s)
    }

    @Test fun smartBackspaceRemovesToPreviousTabStop() {
        val d = doc("      x")   // 6 spaces
        val s = EditActions.backspace(d, caret(0, 6), o)
        assertEquals("    x", d.fullText()); assertEquals(caret(0, 4), s)
        val s2 = EditActions.backspace(d, s, o)
        assertEquals("x", d.fullText()); assertEquals(caret(0, 0), s2)
    }

    @Test fun backspaceJoinsLinesAndDeleteForwardToo() {
        val d = doc("ab\ncd")
        var s = EditActions.backspace(d, caret(1, 0), o)
        assertEquals("abcd", d.fullText()); assertEquals(caret(0, 2), s)
        val d2 = doc("ab\ncd")
        s = EditActions.deleteForward(d2, caret(0, 2))
        assertEquals("abcd", d2.fullText()); assertEquals(caret(0, 2), s)
    }

    @Test fun backspaceDeletesWholeSurrogatePair() {
        val d = doc("a😀")
        val s = EditActions.backspace(d, caret(0, 3), o)
        assertEquals("a", d.fullText()); assertEquals(caret(0, 1), s)
    }

    @Test fun tabInsertsToNextStopAndShiftTabOutdents() {
        val d = doc("ab")
        var s = EditActions.indent(d, caret(0, 2), o, outdent = false)
        assertEquals("ab  ", d.fullText()); assertEquals(caret(0, 4), s)
        val d2 = doc("    x\n  y")
        s = EditActions.indent(d2, Selection(Pos(0, 4), Pos(1, 3)), o, outdent = true)
        assertEquals("x\ny", d2.fullText())
        assertEquals(Selection(Pos(0, 0), Pos(1, 1)), s)
    }

    @Test fun indentSelectedLinesIsOneUndoStep() {
        val d = doc("a\n\nb")
        val s = EditActions.indent(d, Selection(Pos(0, 0), Pos(2, 1)), o, outdent = false)
        assertEquals("    a\n\n    b", d.fullText())
        assertEquals(Selection(Pos(0, 4), Pos(2, 5)), s)
        d.undo()
        assertEquals("a\n\nb", d.fullText())
    }

    @Test fun duplicateMoveAndDeleteLines() {
        val d = doc("a\nb\nc")
        var s = EditActions.duplicateLines(d, caret(1, 1))
        assertEquals("a\nb\nb\nc", d.fullText()); assertEquals(caret(2, 1), s)
        s = EditActions.moveLines(d, s, up = true)
        assertEquals("a\nb\nb\nc", d.fullText()); assertEquals(caret(1, 1), s)
        s = EditActions.moveLines(d, caret(0, 0), up = false)
        assertEquals("b\na\nb\nc", d.fullText()); assertEquals(caret(1, 0), s)
        s = EditActions.deleteLines(d, caret(3, 0))
        assertEquals("b\na\nb", d.fullText()); assertEquals(caret(2, 0), s)
    }

    @Test fun wordDeletion() {
        val d = doc("foo bar baz")
        var s = EditActions.deleteWordBackward(d, caret(0, 7))
        assertEquals("foo  baz", d.fullText()); assertEquals(caret(0, 4), s)
        s = EditActions.deleteWordForward(d, caret(0, 4))
        assertEquals("foo baz", d.fullText())
    }

    @Test fun pasteNormalisesEol() {
        val d = doc("")
        val s = EditActions.insertText(d, Selection.ZERO, "a\r\nb\rc", o, typed = false)
        assertEquals("a\nb\nc", d.fullText()); assertEquals(caret(2, 1), s)
    }
}

class CursorMotionTests {
    private fun doc(s: String) = EditDocument.fromString(s)

    @Test fun horizontalAcrossLinesAndSurrogates() {
        val d = doc("a😀\nb")
        assertEquals(Pos(0, 3), CursorMotion.right(d, Pos(0, 1)))
        assertEquals(Pos(0, 1), CursorMotion.left(d, Pos(0, 3)))
        assertEquals(Pos(1, 0), CursorMotion.right(d, Pos(0, 3)))
        assertEquals(Pos(0, 3), CursorMotion.left(d, Pos(1, 0)))
    }

    @Test fun verticalKeepsGoalColumnWithTabs() {
        val d = doc("\tab\nxxxxxxxx\n")
        val goal = CursorMotion.cellColumn(d, Pos(0, 2), 4)   // tab(4) + 'a' = 5
        assertEquals(5, goal)
        assertEquals(Pos(1, 5), CursorMotion.vertical(d, Pos(0, 2), +1, goal, 4))
        assertEquals(Pos(2, 0), CursorMotion.vertical(d, Pos(1, 5), +1, goal, 4))
        assertEquals(Pos(0, 0), CursorMotion.vertical(d, Pos(0, 2), -1, goal, 4))
    }

    @Test fun smartHomeToggles() {
        val d = doc("    code")
        assertEquals(Pos(0, 4), CursorMotion.home(d, Pos(0, 8), true))
        assertEquals(Pos(0, 0), CursorMotion.home(d, Pos(0, 4), true))
        assertEquals(Pos(0, 4), CursorMotion.home(d, Pos(0, 0), true))
    }

    @Test fun wordMotion() {
        val d = doc("foo bar\nbaz")
        assertEquals(Pos(0, 4), CursorMotion.wordRight(d, Pos(0, 0)))
        assertEquals(Pos(0, 7), CursorMotion.wordRight(d, Pos(0, 4)))
        assertEquals(Pos(1, 0), CursorMotion.wordRight(d, Pos(0, 7)))
        assertEquals(Pos(0, 4), CursorMotion.wordLeft(d, Pos(0, 7)))
        assertEquals(Pos(0, 7), CursorMotion.wordLeft(d, Pos(1, 0)))
    }

    @Test fun bracketMatchingAcrossLines() {
        val d = doc("fun f() {\n  call(a[1])\n}")
        val m = BracketMatcher.find(d, Pos(0, 9))!!          // after '{'
        assertEquals(Pos(0, 8) to Pos(2, 0), m)
        val m2 = BracketMatcher.find(d, Pos(1, 7))!!         // just after '('
        assertEquals(Pos(1, 6) to Pos(1, 11), m2)
        assertNull(BracketMatcher.find(d, Pos(0, 1)))
    }
}

class FoldTests {
    private fun doc(s: String) = EditDocument.fromString(s)

    @Test fun indentationBlocks() {
        val d = doc("def f():\n    a\n\n    b\nx\n  y")
        assertEquals(3, FoldFinder.endOfBlock(d, 0))
        assertEquals(-1, FoldFinder.endOfBlock(d, 1))
        assertEquals(5, FoldFinder.endOfBlock(d, 4))
    }

    @Test fun foldStateHidesAndShifts() {
        val f = FoldState()
        f.fold(2, 5); f.fold(10, 12)
        assertTrue(f.isHidden(3)); assertFalse(f.isHidden(2)); assertTrue(f.isHidden(5)); assertFalse(f.isHidden(6))
        assertEquals(6, f.nextVisible(2)); assertEquals(2, f.prevVisible(6)); assertEquals(2, f.visibleAnchor(4))
        assertEquals(5, f.hiddenBefore(20))
        f.onContentChanged(0, 1, 3)               // two lines inserted above
        assertEquals(listOf(listOf(4, 7), listOf(12, 14)), f.regions().map { it.toList() })
        f.onContentChanged(5, 1, 1)               // edit inside the first region unfolds it
        assertEquals(1, f.count)
        f.fold(0, 20)                             // swallows the remaining one
        assertEquals(1, f.count)
        assertTrue(f.reveal(7)); assertEquals(0, f.count)
    }
}
