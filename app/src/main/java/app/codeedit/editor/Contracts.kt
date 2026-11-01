package app.codeedit.editor

import app.codeedit.core.doc.Match

enum class CursorStyle { BAR, BLOCK, UNDERLINE }

/** Everything the editor view needs from user settings. Owned/produced by the shell (data layer), consumed by EditorView. */
data class EditorConfig(
    val fontFamilyId: String = "jetbrains-mono",     // id from FontRegistry (assets/fonts or user-imported), "monospace" = system
    val fontSizeSp: Float = 14f,
    val minFontSizeSp: Float = 6f, val maxFontSizeSp: Float = 48f,
    val lineSpacing: Float = 1.25f,                   // line height = fontHeight * lineSpacing
    val letterSpacingEm: Float = 0f,
    val boldText: Boolean = false,
    val ligatures: Boolean = false,
    val showLineNumbers: Boolean = true,
    val lineNumberBase: Int = 1,                      // 0 or 1
    val lineNumberMinDigits: Int = 3,
    val highlightCurrentLine: Boolean = true,
    val wordWrap: Boolean = false,
    val tabSize: Int = 4,
    val insertSpaces: Boolean = true,
    val autoIndent: Boolean = true,
    val autoCloseBrackets: Boolean = true,
    val matchBrackets: Boolean = true,
    val showWhitespace: Boolean = false,
    val indentGuides: Boolean = true,
    val folding: Boolean = true,
    val cursorStyle: CursorStyle = CursorStyle.BAR,
    val cursorBlink: Boolean = true,
    val pinchZoom: Boolean = true,
    val fastScroll: Boolean = true,
    val doubleTapSelectsWord: Boolean = true,
    val haptic: Boolean = true,
    val animations: Boolean = true,
    val maxTokenizeLineChars: Int = 10_000,
)

/** Supplies search hits for painting. Called per visible line while drawing: must be fast (cache internally). */
interface MatchHighlighter {
    /** Flat [col0,len0,col1,len1,...] of matches intersecting `line` (a multi-line match contributes its part on each line). */
    fun matchesInLine(line: Int): IntArray
    val current: Match?
}

/** Commands the shell can invoke on the active editor. Implemented by EditorView. */
interface EditorCommands {
    fun undo(); fun redo()
    fun copy(); fun cut(); fun paste()
    fun selectAll()
    fun gotoLine(line1Based: Int, col: Int = 0)
    /** Insert text at caret/selection like typed input (extra-keys row). Special token "\t" = Tab behaviour (indent selection / soft tab). */
    fun insertText(text: String)
    fun indent(); fun outdent()
    fun revealMatch(m: Match, select: Boolean)
    fun replaceRange(m: Match, text: String): Boolean
    fun selectedTextOrWordAtCaret(maxChars: Int = 500): String?
    fun setMatchHighlighter(h: MatchHighlighter?)
    fun requestFocusAndShowKeyboard()
    fun hideKeyboard()
}

/** Events from the editor to the shell. All on the UI thread. */
interface EditorListener {
    fun onSelectionChanged() {}
    /** isModified flipped or content changed (use to refresh the tab "unsaved" dot, schedule autosave). */
    fun onContentChanged() {}
    fun onFontSizeChanged(sp: Float) {}
    fun onRequestFind() {}
    fun onRequestReplace() {}
    fun onRequestSave() {}
    fun onRequestGoto() {}
    /** User tried to modify a VIEW-mode document. Shell should offer "enable editing". */
    fun onEditBlocked() {}
    fun onMessage(text: String) {}
    fun onScrollChanged() {}
}
