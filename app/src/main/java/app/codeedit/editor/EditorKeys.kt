package app.codeedit.editor

import android.view.KeyCharacterMap
import android.view.KeyEvent
import app.codeedit.core.doc.Pos
import app.codeedit.core.doc.Selection
import app.codeedit.core.edit.CursorMotion
import app.codeedit.core.edit.EditActions

/** Hardware keyboard / extra-keys / IME key events -> editor actions. */
internal object EditorKeys {
    private val NAV = setOf(
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
        KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_ESCAPE,
    )

    fun isHandled(keyCode: Int): Boolean = keyCode in NAV

    fun onKeyEvent(v: EditorView, e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN && e.action != KeyEvent.ACTION_MULTIPLE) return false
        if (e.action == KeyEvent.ACTION_MULTIPLE && e.keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            val s = e.characters
            if (!s.isNullOrEmpty()) { v.insertText(s); return true }
            return false
        }
        return handle(v, e.keyCode, e.isCtrlPressed, e.isShiftPressed, e.isAltPressed, e.unicodeChar)
    }

    private fun move(v: EditorView, target: Pos, extend: Boolean, keepGoal: Boolean = false, scroll: Boolean = true) {
        val s = v.session ?: return
        val sel = s.selection
        v.setSelection(if (extend) Selection(sel.anchor, target) else Selection.caret(target), keepGoal, scroll)
    }

    fun handle(v: EditorView, keyCode: Int, ctrl: Boolean, shift: Boolean, alt: Boolean, unicode: Int): Boolean {
        val s = v.session ?: return false
        if (!v.ensureBound()) return false
        val store = s.store
        val sel = s.selection
        val head = s.clampPos(sel.head)
        val tab = v.config.tabSize
        val opts = v.editOptions
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val t = when { !shift && !sel.isEmpty && !ctrl -> sel.start; ctrl -> CursorMotion.wordLeft(store, head); else -> CursorMotion.left(store, head) }
                move(v, t, shift); return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val t = when { !shift && !sel.isEmpty && !ctrl -> sel.end; ctrl -> CursorMotion.wordRight(store, head); else -> CursorMotion.right(store, head) }
                move(v, t, shift); return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val up = keyCode == KeyEvent.KEYCODE_DPAD_UP
                if (alt) { v.edit { d, sl -> EditActions.moveLines(d, sl, up) }; return true }
                if (ctrl) { v.touch.scrollVertical(if (up) -v.lineH else v.lineH); return true }
                move(v, v.moveVertical(head, if (up) -1 else 1), shift, keepGoal = true); return true
            }
            KeyEvent.KEYCODE_MOVE_HOME -> { move(v, if (ctrl) CursorMotion.docStart() else CursorMotion.home(store, head, opts.smartHome), shift); return true }
            KeyEvent.KEYCODE_MOVE_END -> { move(v, if (ctrl) CursorMotion.docEnd(store) else CursorMotion.end(store, head), shift); return true }
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN -> {
                val dir = if (keyCode == KeyEvent.KEYCODE_PAGE_UP) -1 else 1
                val rows = ((v.viewportH() / v.lineH).toInt() - 2).coerceIn(1, 200)
                var p = head
                for (i in 0 until rows) p = v.moveVertical(p, dir)
                v.touch.scrollVertical(dir * rows * v.lineH)
                move(v, p, shift, keepGoal = true, scroll = false)
                return true
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                if (!sel.isEmpty) v.setSelection(Selection.caret(sel.head), scroll = false)
                v.closeActionMode()
                return true
            }
            KeyEvent.KEYCODE_DEL -> {
                v.edit { d, sl -> if (ctrl) EditActions.deleteWordBackward(d, sl) else EditActions.backspace(d, sl, opts) }
                return true
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> {
                v.edit { d, sl -> if (ctrl) EditActions.deleteWordForward(d, sl) else EditActions.deleteForward(d, sl) }
                return true
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                v.edit { d, sl -> EditActions.newline(d, sl, opts) }
                return true
            }
            KeyEvent.KEYCODE_TAB -> {
                if (ctrl) return false
                v.edit { d, sl -> EditActions.indent(d, sl, opts, outdent = shift) }
                return true
            }
        }
        if (ctrl) {
            when (keyCode) {
                KeyEvent.KEYCODE_A -> { v.selectAll(); return true }
                KeyEvent.KEYCODE_C -> { v.copy(); return true }
                KeyEvent.KEYCODE_X -> { v.cut(); return true }
                KeyEvent.KEYCODE_V -> { v.paste(); return true }
                KeyEvent.KEYCODE_Z -> { if (shift) v.redo() else v.undo(); return true }
                KeyEvent.KEYCODE_Y -> { v.redo(); return true }
                KeyEvent.KEYCODE_S -> { v.listener?.onRequestSave(); return true }
                KeyEvent.KEYCODE_F -> { v.listener?.onRequestFind(); return true }
                KeyEvent.KEYCODE_H -> { v.listener?.onRequestReplace(); return true }
                KeyEvent.KEYCODE_G -> { v.listener?.onRequestGoto(); return true }
                KeyEvent.KEYCODE_D -> { v.edit { d, sl -> EditActions.duplicateLines(d, sl) }; return true }
                KeyEvent.KEYCODE_K -> { if (shift) { v.edit { d, sl -> EditActions.deleteLines(d, sl) }; return true } }
                KeyEvent.KEYCODE_RIGHT_BRACKET -> { v.indent(); return true }
                KeyEvent.KEYCODE_LEFT_BRACKET -> { v.outdent(); return true }
            }
            return false
        }
        if (unicode != 0 && (unicode and KeyCharacterMap.COMBINING_ACCENT) == 0) {
            val cp = unicode and KeyCharacterMap.COMBINING_ACCENT_MASK
            if (cp >= 0x20 && cp != 0x7F) { v.insertText(String(Character.toChars(cp))); return true }
        }
        return false
    }
}
