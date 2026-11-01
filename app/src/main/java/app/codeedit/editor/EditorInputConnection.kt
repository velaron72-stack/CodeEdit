package app.codeedit.editor

import android.text.Editable
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import app.codeedit.core.doc.Coalesce
import app.codeedit.core.doc.EditableDocument
import app.codeedit.core.doc.Selection
import app.codeedit.core.edit.EditActions

/**
 * Soft-keyboard bridge. The document itself is the text model: composing text lives in the document between
 * v.composeStart and v.composeEnd (offsets), so there is no second copy of the text and nothing to keep in sync.
 */
internal class EditorInputConnection(private val v: EditorView) : BaseInputConnection(v, false) {
    private var batch = 0
    private val dummy = SpannableStringBuilder()

    private val doc: EditableDocument? get() = v.session?.editable

    override fun getEditable(): Editable = dummy

    private fun clampOff(d: EditableDocument, off: Int) = off.coerceIn(0, d.length)

    override fun beginBatchEdit(): Boolean { batch++; return true }
    override fun endBatchEdit(): Boolean {
        if (batch > 0) batch--
        if (batch == 0) v.imeUpdateSelection()
        return batch > 0
    }

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? {
        val d = doc ?: return null
        val s = v.session ?: return null
        val off = d.offsetOf(s.selection.start)
        val from = maxOf(0, off - n.coerceAtLeast(0))
        return d.getText(d.posOf(from), s.selection.start, n.coerceAtLeast(0) + 1)
    }

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? {
        val d = doc ?: return null
        val s = v.session ?: return null
        val off = d.offsetOf(s.selection.end)
        val to = minOf(d.length, off + n.coerceAtLeast(0))
        return d.getText(s.selection.end, d.posOf(to), n.coerceAtLeast(0) + 1)
    }

    override fun getSelectedText(flags: Int): CharSequence? {
        val d = doc ?: return null
        val s = v.session ?: return null
        if (s.selection.isEmpty) return null
        return d.getText(s.selection.start, s.selection.end, 100_000)
    }

    override fun getCursorCapsMode(reqModes: Int): Int = 0
    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? = null
    override fun clearMetaKeyStates(states: Int): Boolean = false
    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = false
    override fun commitCompletion(text: android.view.inputmethod.CompletionInfo?): Boolean = false
    override fun commitCorrection(info: android.view.inputmethod.CorrectionInfo?): Boolean = false

    /** Replace document offsets [a,b) with text (one undo-coalescing "typing" step). Returns the new end offset. */
    private fun replaceOffsets(d: EditableDocument, a: Int, b: Int, text: String, coalesce: Coalesce): Int {
        val s = v.session!!
        val pa = d.posOf(a); val pb = d.posOf(b)
        val endPos = EditActions.endOf(pa, text)
        d.replace(pa, pb, text, s.selection, Selection.caret(endPos), coalesce)
        return a + text.length
    }

    private fun caretAt(d: EditableDocument, off: Int) = Selection.caret(d.posOf(clampOff(d, off)))

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val d = doc ?: run { v.listener?.onEditBlocked(); return false }
        val s = v.session ?: return false
        val str = EditActions.normalizeEol(text?.toString() ?: "")
        var a: Int; var b: Int
        if (v.composeStart >= 0) { a = clampOff(d, v.composeStart); b = clampOff(d, v.composeEnd) }
        else { a = d.offsetOf(s.selection.start); b = d.offsetOf(s.selection.end) }
        if (b < a) { val t = a; a = b; b = t }
        if (str.isEmpty() && a == b) { v.composeStart = -1; v.composeEnd = -1; return true }
        val newEnd = replaceOffsets(d, a, b, str, Coalesce.TYPING)
        if (str.isEmpty()) { v.composeStart = -1; v.composeEnd = -1 } else { v.composeStart = a; v.composeEnd = newEnd }
        val cursor = if (newCursorPosition > 0) newEnd + newCursorPosition - 1 else a + newCursorPosition
        v.afterEdit(caretAt(d, cursor))
        return true
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        val d = doc ?: return false
        val a = clampOff(d, minOf(start, end)); val b = clampOff(d, maxOf(start, end))
        if (a == b) { v.composeStart = -1; v.composeEnd = -1 } else { v.composeStart = a; v.composeEnd = b }
        return true
    }

    override fun finishComposingText(): Boolean { v.composeStart = -1; v.composeEnd = -1; return true }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val d = doc ?: run { v.listener?.onEditBlocked(); return false }
        val s = v.session ?: return false
        val str = EditActions.normalizeEol(text?.toString() ?: "")
        if (v.composeStart >= 0) {
            val a = clampOff(d, minOf(v.composeStart, v.composeEnd)); val b = clampOff(d, maxOf(v.composeStart, v.composeEnd))
            v.composeStart = -1; v.composeEnd = -1
            val newEnd = if (str.isEmpty() && a == b) a else replaceOffsets(d, a, b, str, Coalesce.TYPING)
            val cursor = if (newCursorPosition > 0) newEnd + newCursorPosition - 1 else a + newCursorPosition
            v.afterEdit(caretAt(d, cursor))
            return true
        }
        if (str.isEmpty()) return true
        v.edit { dd, sel ->
            if (str == "\n") EditActions.newline(dd, sel, v.editOptions)
            else EditActions.insertText(dd, sel, str, v.editOptions, typed = str.codePointCount(0, str.length) == 1)
        }
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        val d = doc ?: run { v.listener?.onEditBlocked(); return false }
        val s = v.session ?: return false
        val sel = s.selection
        if (beforeLength == 1 && afterLength == 0 && sel.isEmpty && v.composeStart < 0) {
            v.edit { dd, sl -> EditActions.backspace(dd, sl, v.editOptions) }
            return true
        }
        var a = clampOff(d, d.offsetOf(sel.start) - beforeLength.coerceAtLeast(0))
        var b = clampOff(d, d.offsetOf(sel.end) + afterLength.coerceAtLeast(0))
        if (a > 0 && a < d.length && Character.isLowSurrogate(d.charAt(a)) && Character.isHighSurrogate(d.charAt(a - 1))) a--
        if (b > 0 && b < d.length && Character.isLowSurrogate(d.charAt(b)) && Character.isHighSurrogate(d.charAt(b - 1))) b++
        if (a == b) return true
        val cs = v.composeStart; val ce = v.composeEnd
        d.replace(d.posOf(a), d.posOf(b), "", sel, caretAt(d, a), Coalesce.DELETING)
        if (cs >= 0) {
            if (a >= cs && b <= ce) v.composeEnd = ce - (b - a)
            else { v.composeStart = -1; v.composeEnd = -1 }
        }
        v.afterEdit(caretAt(d, a))
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        val d = doc ?: return false
        val s = v.session ?: return false
        var before = 0; var after = 0
        var off = d.offsetOf(s.selection.start)
        var n = beforeLength
        while (n > 0 && off > 0) {
            off--; before++
            if (off > 0 && Character.isLowSurrogate(d.charAt(off)) && Character.isHighSurrogate(d.charAt(off - 1))) { off--; before++ }
            n--
        }
        off = d.offsetOf(s.selection.end); n = afterLength
        while (n > 0 && off < d.length) {
            if (Character.isHighSurrogate(d.charAt(off)) && off + 1 < d.length && Character.isLowSurrogate(d.charAt(off + 1))) { off++; after++ }
            off++; after++; n--
        }
        return deleteSurroundingText(before, after)
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        val d = doc ?: return false
        val s = v.session ?: return false
        val sel = Selection(d.posOf(clampOff(d, start)), d.posOf(clampOff(d, end)))
        s.selection = s.clampSelection(sel)
        v.goalCells = -1
        v.updateBracket(); v.resetBlink(); v.ensureCaretVisible()
        v.listener?.onSelectionChanged()
        return true
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            v.composeStart = -1; v.composeEnd = -1
            EditorKeys.handle(v, event.keyCode, event.isCtrlPressed, event.isShiftPressed, event.isAltPressed, event.unicodeChar)
        }
        return true
    }

    override fun performEditorAction(editorAction: Int): Boolean {
        v.composeStart = -1; v.composeEnd = -1
        v.insertText("\n")
        return true
    }

    override fun performContextMenuAction(id: Int): Boolean {
        when (id) {
            android.R.id.selectAll -> v.selectAll()
            android.R.id.cut -> v.cut()
            android.R.id.copy -> v.copy()
            android.R.id.paste -> v.paste()
            else -> return false
        }
        return true
    }

    override fun closeConnection() {
        v.composeStart = -1; v.composeEnd = -1
        super.closeConnection()
    }
}
