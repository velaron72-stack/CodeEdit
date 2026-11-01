package app.codeedit.core.edit

import app.codeedit.core.doc.Coalesce
import app.codeedit.core.doc.EditableDocument
import app.codeedit.core.doc.Pos
import app.codeedit.core.doc.Selection

/**
 * Text editing commands on an [EditableDocument]. Every function performs its edits (one undo step each, typing/deleting
 * coalesce) and returns the new selection. Pure Kotlin, UI thread (the document is UI-thread only).
 */
object EditActions {
    private val PAIRS = mapOf('(' to ')', '[' to ']', '{' to '}', '"' to '"', '\'' to '\'', '`' to '`')
    private const val CLOSING = ")]}"

    @JvmStatic fun normalizeEol(s: String): String =
        if (s.indexOf('\r') < 0) s else s.replace("\r\n", "\n").replace('\r', '\n')

    /** Position after inserting [text] ('\n' separated) at [start]. */
    @JvmStatic fun endOf(start: Pos, text: String): Pos {
        var nl = 0
        var last = -1
        for (i in text.indices) if (text[i] == '\n') { nl++; last = i }
        return if (nl == 0) Pos(start.line, start.col + text.length) else Pos(start.line + nl, text.length - last - 1)
    }

    private fun leadingWs(line: String): String {
        var i = 0
        while (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
        return line.substring(0, i)
    }

    /** Replaces the selection with [text] (no smart behaviour). The caret ends after the text. */
    @JvmStatic
    fun replaceSelection(doc: EditableDocument, sel: Selection, text: String, coalesce: Coalesce = Coalesce.NONE): Selection {
        val s = sel.start
        val after = Selection.caret(endOf(s, text))
        doc.replace(s, sel.end, text, sel, after, coalesce)
        return after
    }

    /** Typing / pasting / IME commit. [typed] enables auto-pairs and typing-coalescing (single keystrokes). */
    @JvmStatic
    fun insertText(doc: EditableDocument, sel: Selection, raw: String, o: EditOptions, typed: Boolean = true): Selection {
        val text = normalizeEol(raw)
        if (text.isEmpty()) return sel
        if (text == "\n") return newline(doc, sel, o)
        if (typed && text.length == 1 && o.autoCloseBrackets) {
            val c = text[0]
            val close = PAIRS[c]
            if (sel.isEmpty) {
                val p = sel.head
                val line = doc.getLine(p.line)
                val next = line.getOrNull(p.col)
                val prev = line.getOrNull(p.col - 1)
                if (next == c && (c == '"' || c == '\'' || c == '`' || CLOSING.indexOf(c) >= 0)) return Selection.caret(Pos(p.line, p.col + 1))
                if (close != null) {
                    val quote = c == close
                    val nextOk = next == null || next.isWhitespace() || CLOSING.indexOf(next) >= 0 || next == ',' || next == ';'
                    val prevOk = !quote || prev == null || !(WordUtil.isWordChar(prev) || prev == c || prev == '\\')
                    if (nextOk && prevOk) {
                        val after = Selection.caret(Pos(p.line, p.col + 1))
                        doc.replace(p, p, "$c$close", sel, after, Coalesce.NONE)
                        return after
                    }
                }
            } else if (o.surroundSelection && close != null && sel.start.line == sel.end.line) {
                val s = sel.start
                val inner = doc.getText(s, sel.end)
                val after = Selection(Pos(s.line, s.col + 1), Pos(s.line, s.col + 1 + inner.length))
                doc.replace(s, sel.end, "$c$inner$close", sel, after, Coalesce.NONE)
                return after
            }
        }
        return replaceSelection(doc, sel, text, if (typed && text.length <= 2) Coalesce.TYPING else Coalesce.NONE)
    }

    @JvmStatic
    fun newline(doc: EditableDocument, sel: Selection, o: EditOptions): Selection {
        val s = sel.start
        if (!o.autoIndent) return replaceSelection(doc, sel, "\n")
        val line = doc.getLine(s.line)
        val before = line.substring(0, s.col.coerceAtMost(line.length))
        val ws = leadingWs(line).let { if (it.length > before.length) before else it }
        val trimmed = before.trimEnd()
        val last = trimmed.lastOrNull()
        val after = if (sel.end.line == s.line) line.substring(sel.end.col.coerceAtMost(line.length)) else doc.getLine(sel.end.line).let { it.substring(sel.end.col.coerceAtMost(it.length)) }
        val opens = last != null && (last == '{' || last == '(' || last == '[')
        val extra = if (opens) o.indentUnit else ""
        val between = opens && after.isNotEmpty() && (after[0] == '}' || after[0] == ')' || after[0] == ']') && last == matching(after[0])
        val text: String
        val caret: Pos
        if (between) {
            text = "\n$ws$extra\n$ws"
            caret = Pos(s.line + 1, ws.length + extra.length)
        } else {
            text = "\n$ws$extra"
            caret = Pos(s.line + 1, ws.length + extra.length)
        }
        val res = Selection.caret(caret)
        doc.replace(s, sel.end, text, sel, res, Coalesce.NONE)
        return res
    }

    private fun matching(close: Char): Char = when (close) { '}' -> '{'; ')' -> '('; ']' -> '['; else -> close }

    @JvmStatic
    fun backspace(doc: EditableDocument, sel: Selection, o: EditOptions): Selection {
        if (!sel.isEmpty) return deleteSelection(doc, sel)
        val p = sel.head
        if (p.col == 0) {
            if (p.line == 0) return sel
            val to = p.line - 1
            val from = Pos(to, doc.lineLength(to))
            val after = Selection.caret(from)
            doc.replace(from, p, "", sel, after, Coalesce.DELETING)
            return after
        }
        val line = doc.getLine(p.line)
        val col = p.col.coerceAtMost(line.length)
        // delete an empty auto-pair: ( | )
        if (o.autoCloseBrackets && col < line.length) {
            val c = line[col - 1]
            if (PAIRS[c] == line[col]) {
                val after = Selection.caret(Pos(p.line, col - 1))
                doc.replace(Pos(p.line, col - 1), Pos(p.line, col + 1), "", sel, after, Coalesce.DELETING)
                return after
            }
        }
        // smart backspace: inside leading spaces remove up to the previous tab stop
        var n = 1
        if (o.insertSpaces && line[col - 1] == ' ') {
            var allSpaces = true
            for (i in 0 until col) if (line[i] != ' ') { allSpaces = false; break }
            if (allSpaces) { n = col % o.tabSize.coerceAtLeast(1); if (n == 0) n = o.tabSize.coerceAtLeast(1); n = minOf(n, col) }
        } else if (col >= 2 && Character.isLowSurrogate(line[col - 1]) && Character.isHighSurrogate(line[col - 2])) n = 2
        val from = Pos(p.line, col - n)
        val after = Selection.caret(from)
        doc.replace(from, Pos(p.line, col), "", sel, after, Coalesce.DELETING)
        return after
    }

    @JvmStatic
    fun deleteForward(doc: EditableDocument, sel: Selection): Selection {
        if (!sel.isEmpty) return deleteSelection(doc, sel)
        val p = sel.head
        val len = doc.lineLength(p.line)
        val to = if (p.col >= len) { if (p.line + 1 >= doc.lineCount) return sel; Pos(p.line + 1, 0) } else {
            val line = doc.getLine(p.line)
            val n = if (Character.isHighSurrogate(line[p.col]) && p.col + 1 < line.length && Character.isLowSurrogate(line[p.col + 1])) 2 else 1
            Pos(p.line, p.col + n)
        }
        val after = Selection.caret(p)
        doc.replace(p, to, "", sel, after, Coalesce.DELETING)
        return after
    }

    @JvmStatic
    fun deleteWordBackward(doc: EditableDocument, sel: Selection): Selection {
        if (!sel.isEmpty) return deleteSelection(doc, sel)
        val p = sel.head
        if (p.col == 0) return backspace(doc, sel, EditOptions(autoCloseBrackets = false, insertSpaces = false))
        val chars = doc.getLine(p.line).toCharArray()
        val from = Pos(p.line, WordUtil.prevWordBoundary(chars, chars.size, p.col))
        val after = Selection.caret(from)
        doc.replace(from, p, "", sel, after, Coalesce.NONE)
        return after
    }

    @JvmStatic
    fun deleteWordForward(doc: EditableDocument, sel: Selection): Selection {
        if (!sel.isEmpty) return deleteSelection(doc, sel)
        val p = sel.head
        val chars = doc.getLine(p.line).toCharArray()
        if (p.col >= chars.size) return deleteForward(doc, sel)
        val to = Pos(p.line, WordUtil.nextWordBoundary(chars, chars.size, p.col))
        val after = Selection.caret(p)
        doc.replace(p, to, "", sel, after, Coalesce.NONE)
        return after
    }

    @JvmStatic
    fun deleteSelection(doc: EditableDocument, sel: Selection): Selection {
        if (sel.isEmpty) return sel
        val after = Selection.caret(sel.start)
        doc.replace(sel.start, sel.end, "", sel, after, Coalesce.NONE)
        return after
    }

    /** First and last line touched by the selection; a selection ending at column 0 of a later line does not include that line. */
    @JvmStatic
    fun lineRange(sel: Selection): IntArray {
        val s = sel.start; val e = sel.end
        val last = if (e.col == 0 && e.line > s.line) e.line - 1 else e.line
        return intArrayOf(s.line, last)
    }

    /** Tab / Shift+Tab. Single caret: soft tab or tab char; multi-line selection: (out)indent every line. */
    @JvmStatic
    fun indent(doc: EditableDocument, sel: Selection, o: EditOptions, outdent: Boolean): Selection {
        val multi = sel.start.line != sel.end.line
        if (!outdent && !multi) {
            val p = sel.start
            val text = if (o.insertSpaces) " ".repeat(o.tabSize - (visualCol(doc.getLine(p.line), p.col, o.tabSize) % o.tabSize)) else "\t"
            return replaceSelection(doc, sel, text, Coalesce.NONE)
        }
        val r = lineRange(sel)
        val unit = o.indentUnit
        var aDelta = 0; var hDelta = 0
        var aLine = sel.anchor.line; var hLine = sel.head.line
        doc.beginCompound()
        try {
            for (line in r[0]..r[1]) {
                val text = doc.getLine(line)
                if (!outdent) {
                    if (text.isEmpty() && r[0] != r[1]) continue
                    doc.replace(Pos(line, 0), Pos(line, 0), unit, if (line == r[0]) sel else null, null, Coalesce.NONE)
                    if (line == aLine) aDelta = unit.length
                    if (line == hLine) hDelta = unit.length
                } else {
                    var n = 0
                    if (text.startsWith("\t")) n = 1
                    else { while (n < o.tabSize && n < text.length && text[n] == ' ') n++ }
                    if (n == 0) continue
                    doc.replace(Pos(line, 0), Pos(line, n), "", if (line == r[0]) sel else null, null, Coalesce.NONE)
                    if (line == aLine) aDelta = -minOf(n, sel.anchor.col)
                    if (line == hLine) hDelta = -minOf(n, sel.head.col)
                }
            }
        } finally { doc.endCompound() }
        return Selection(Pos(aLine, (sel.anchor.col + aDelta).coerceAtLeast(0)), Pos(hLine, (sel.head.col + hDelta).coerceAtLeast(0)))
    }

    private fun visualCol(line: String, col: Int, tab: Int): Int {
        var c = 0
        for (i in 0 until minOf(col, line.length)) c = if (line[i] == '\t') (c / tab + 1) * tab else c + 1
        return c
    }

    @JvmStatic
    fun duplicateLines(doc: EditableDocument, sel: Selection): Selection {
        val r = lineRange(sel)
        val block = doc.getText(Pos(r[0], 0), Pos(r[1], doc.lineLength(r[1])))
        val at = Pos(r[1], doc.lineLength(r[1]))
        val n = r[1] - r[0] + 1
        val after = Selection(Pos(sel.anchor.line + n, sel.anchor.col), Pos(sel.head.line + n, sel.head.col))
        doc.replace(at, at, "\n$block", sel, after, Coalesce.NONE)
        return after
    }

    @JvmStatic
    fun deleteLines(doc: EditableDocument, sel: Selection): Selection {
        val r = lineRange(sel)
        val from: Pos; val to: Pos
        if (r[1] + 1 < doc.lineCount) { from = Pos(r[0], 0); to = Pos(r[1] + 1, 0) }
        else if (r[0] > 0) { from = Pos(r[0] - 1, doc.lineLength(r[0] - 1)); to = Pos(r[1], doc.lineLength(r[1])) }
        else { from = Pos(0, 0); to = Pos(r[1], doc.lineLength(r[1])) }
        val line = if (r[1] + 1 < doc.lineCount) r[0] else (r[0] - 1).coerceAtLeast(0)
        val after = Selection.caret(Pos(line, 0))
        doc.replace(from, to, "", sel, after, Coalesce.NONE)
        return after
    }

    @JvmStatic
    fun moveLines(doc: EditableDocument, sel: Selection, up: Boolean): Selection {
        val r = lineRange(sel)
        if (up && r[0] == 0) return sel
        if (!up && r[1] + 1 >= doc.lineCount) return sel
        val from: Pos; val to: Pos; val text: String
        val d = if (up) -1 else 1
        if (up) {
            from = Pos(r[0] - 1, 0); to = Pos(r[1], doc.lineLength(r[1]))
            text = doc.getText(Pos(r[0], 0), to) + "\n" + doc.getLine(r[0] - 1)
        } else {
            from = Pos(r[0], 0); to = Pos(r[1] + 1, doc.lineLength(r[1] + 1))
            text = doc.getLine(r[1] + 1) + "\n" + doc.getText(Pos(r[0], 0), Pos(r[1], doc.lineLength(r[1])))
        }
        val after = Selection(Pos(sel.anchor.line + d, sel.anchor.col), Pos(sel.head.line + d, sel.head.col))
        doc.replace(from, to, text, sel, after, Coalesce.NONE)
        return after
    }

    @JvmStatic
    fun selectAll(doc: EditableDocument): Selection {
        val last = doc.lineCount - 1
        return Selection(Pos(0, 0), Pos(last, doc.lineLength(last)))
    }

    /** Word (or run of punctuation/space) at [p] for double tap. */
    @JvmStatic
    fun wordAt(doc: EditableDocument, p: Pos): Selection? {
        val chars = doc.getLine(p.line).toCharArray()
        if (chars.isEmpty()) return null
        val packed = WordUtil.wordRangeAt(chars, chars.size, p.col)
        return Selection(Pos(p.line, (packed ushr 32).toInt()), Pos(p.line, (packed and 0xFFFFFFFFL).toInt()))
    }

    @JvmStatic
    fun lineSelection(doc: EditableDocument, line: Int): Selection =
        if (line + 1 < doc.lineCount) Selection(Pos(line, 0), Pos(line + 1, 0)) else Selection(Pos(line, 0), Pos(line, doc.lineLength(line)))
}
