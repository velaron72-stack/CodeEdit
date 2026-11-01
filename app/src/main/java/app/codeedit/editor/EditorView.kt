package app.codeedit.editor

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.ActionMode
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import app.codeedit.R
import app.codeedit.core.doc.Coalesce
import app.codeedit.core.doc.DocumentListener
import app.codeedit.core.doc.EditableDocument
import app.codeedit.core.doc.LineStore
import app.codeedit.core.doc.Match
import app.codeedit.core.doc.Pos
import app.codeedit.core.doc.Selection
import app.codeedit.core.edit.BracketMatcher
import app.codeedit.core.edit.CursorMotion
import app.codeedit.core.edit.EditActions
import app.codeedit.core.edit.EditOptions
import app.codeedit.core.edit.EditorSession
import app.codeedit.core.edit.FoldFinder
import app.codeedit.core.edit.TextMetrics
import app.codeedit.core.edit.WordUtil
import app.codeedit.highlight.EditorColors
import app.codeedit.highlight.HighlightListener
import app.codeedit.highlight.SyntaxHighlighter

/**
 * The text surface. Everything is drawn here (no EditText/TextView): only visible rows are laid out, so a 1 GB
 * document costs the same as a small one. Scroll position lives in [EditorSession] (topLine, topOffsetPx).
 * UI thread only.
 */
@SuppressLint("ClickableViewAccessibility")
class EditorView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs), EditorCommands {

    // ------------------------------------------------------------------ public configuration
    var listener: EditorListener? = null

    var config: EditorConfig = EditorConfig()
        set(value) { field = value; relayout() }

    var colors: EditorColors = DEFAULT_COLORS
        set(value) { field = value; invalidate() }

    var fontRegistry: FontRegistry? = null
        set(value) { field = value; relayout() }

    /** Space at the bottom that must not hide the caret/last lines (e.g. a floating bar). */
    var extraBottomInsetPx: Int = 0
        set(value) { field = value; invalidate() }

    val boundSession: EditorSession? get() = session

    // ------------------------------------------------------------------ state shared with painter/touch/input
    internal var session: EditorSession? = null
    internal var layout: RowLayout = RowLayout(EMPTY_STORE)
    internal val density: Float = resources.displayMetrics.density
    internal val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    internal val paintBold = Paint(Paint.ANTI_ALIAS_FLAG)
    internal val lnPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    internal var charW = 10f
    internal var lineH = 20f
    internal var textBaseline = 15f
    internal var lnCharW = 8f
    internal var gutterW = 0f
    internal var textLeft = 0f
    internal var foldColW = 0f
    internal var lnDigits = 3
    internal var goalCells = -1
    internal var bracketPair: Pair<Pos, Pos>? = null
    internal var matchHighlighter: MatchHighlighter? = null
    internal var composeStart = -1          // document offsets of the IME composing region, or -1
    internal var composeEnd = -1
    internal var caretVisible = true
    internal var maxCellsSeen = 0
    internal var showCaretHandle = false
    internal val utilBuf = LineBuf()

    internal val painter = EditorPainter(this)
    internal val touch = EditorTouch(this)

    private var boundStore: LineStore? = null
    private var boundHighlighter: SyntaxHighlighter? = null
    private var actionMode: ActionMode? = null
    private val imm: InputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private val clipboard: ClipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private var lastBlinkReset = 0L
    private var lastReqFirst = -1
    private var lastReqLast = -1
    private var lastReqTime = 0L

    private val hlListener = HighlightListener { _, _ -> postInvalidateOnAnimation() }

    private val docListener = object : DocumentListener {
        override fun onContentChanged(startLine: Int, oldLineCount: Int, newLineCount: Int) {
            layout.onContentChanged(startLine, oldLineCount, newLineCount)
            session?.folds?.onContentChanged(startLine, oldLineCount, newLineCount)
            postInvalidateOnAnimation()
        }
        override fun onIndexProgress(lineCount: Int, complete: Boolean) { postInvalidateOnAnimation() }
    }

    private val blink = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            if (config.cursorBlink && hasFocus()) {
                val idle = SystemClock.uptimeMillis() - lastBlinkReset
                caretVisible = if (idle < BLINK_MS) true else !caretVisible
                invalidate()
            } else if (!caretVisible) { caretVisible = true; invalidate() }
            postDelayed(this, BLINK_MS)
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        isLongClickable = true
        overScrollMode = OVER_SCROLL_NEVER
        applyMetrics()
    }

    // ------------------------------------------------------------------ binding
    fun bind(s: EditorSession?) {
        closeActionMode()
        unbindCurrent()
        session = s
        if (s != null) bindCurrent(s)
        touch.reset()
        restartInput()
        invalidate()
    }

    private fun unbindCurrent() {
        boundStore?.removeListener(docListener)
        boundHighlighter?.let { if (it.listener === hlListener) it.listener = null }
        boundStore = null; boundHighlighter = null
        composeStart = -1; composeEnd = -1
        bracketPair = null; goalCells = -1; showCaretHandle = false
    }

    private fun bindCurrent(s: EditorSession) {
        boundStore = s.store
        boundHighlighter = s.highlighter
        layout = RowLayout(s.store)
        s.store.addListener(docListener)
        s.highlighter?.listener = hlListener
        applyMetrics()
        updateWrap()
        updateBracket()
        lastReqFirst = -1
    }

    /** Called at the start of every draw/input: tolerates the shell swapping the store (VIEW<->EDIT) or the highlighter. */
    internal fun ensureBound(): Boolean {
        val s = session ?: return false
        if (s.store !== boundStore || s.highlighter !== boundHighlighter) {
            unbindCurrent(); bindCurrent(s); restartInput()
        }
        return true
    }

    // ------------------------------------------------------------------ metrics / layout
    internal fun effectiveSp(): Float {
        val z = session?.fontSizeSp
        return (if (z != null && !z.isNaN()) z else config.fontSizeSp).coerceIn(config.minFontSizeSp, config.maxFontSizeSp)
    }

    internal fun dp(v: Float) = v * density

    /** Re-reads font/size/gutter/wrap after any change to config, session zoom or font registry. */
    fun relayout() {
        applyMetrics(); updateWrap(); invalidate()
    }

    internal fun applyMetrics() {
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, effectiveSp(), resources.displayMetrics)
        val font = fontRegistry?.get(config.fontFamilyId)
        val regular = if (config.boldText) (font?.bold ?: android.graphics.Typeface.DEFAULT_BOLD) else (font?.regular ?: android.graphics.Typeface.MONOSPACE)
        val bold = font?.bold ?: android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        val features = if (config.ligatures) "'liga' 1, 'calt' 1" else "'liga' 0, 'calt' 0"
        for ((p, tf) in arrayOf(paint to regular, paintBold to bold)) {
            p.typeface = tf; p.textSize = px; p.letterSpacing = config.letterSpacingEm
            p.fontFeatureSettings = features; p.isSubpixelText = true
        }
        charW = paint.measureText("0").coerceAtLeast(1f)
        val fm = paint.fontMetrics
        val fh = fm.descent - fm.ascent
        lineH = Math.ceil((fh * config.lineSpacing.coerceIn(1.0f, 3f)).toDouble()).toFloat().coerceAtLeast(4f)
        textBaseline = (lineH - fh) / 2f - fm.ascent
        lnPaint.typeface = font?.regular ?: android.graphics.Typeface.MONOSPACE
        lnPaint.textSize = px * 0.86f
        lnPaint.fontFeatureSettings = "'liga' 0, 'calt' 0"
        lnCharW = lnPaint.measureText("0").coerceAtLeast(1f)
        layoutGutter()
    }

    private fun layoutGutter() {
        val store = session?.store
        val total = if (store == null) 1L else maxOf(store.lineCount.toLong(), store.estimatedLineCount)
        val maxNum = total + config.lineNumberBase
        lnDigits = maxOf(config.lineNumberMinDigits, maxNum.toString().length)
        foldColW = if (config.folding) dp(16f) else 0f
        gutterW = if (!config.showLineNumbers && !config.folding) dp(4f)
        else dp(6f) + (if (config.showLineNumbers) lnDigits * lnCharW + dp(8f) else 0f) + foldColW + dp(2f)
        textLeft = gutterW + dp(6f)
    }

    /** Keeps the gutter width in step with a growing document. Returns true if it changed. */
    internal fun checkGutter(): Boolean {
        val old = gutterW; val oldDigits = lnDigits
        layoutGutter()
        if (old != gutterW || oldDigits != lnDigits) { updateWrap(); return true }
        return false
    }

    internal fun isWrapping(): Boolean = session?.wrapOverride ?: config.wordWrap

    internal fun updateWrap() {
        val s = session
        val wrap = isWrapping()
        val textW = width - textLeft - dp(10f)
        val cells = if (wrap && width > 0) maxOf(2, (textW / charW).toInt()) else 0
        layout.configure(cells, config.tabSize)
        if (s != null) {
            if (wrap) s.scrollX = 0f
            ViewportMath.normalize(s, layout, lineH)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateWrap()
        if (oldh in 1 until h || (oldh > h && hasFocus())) ensureCaretVisible()
    }

    internal fun viewportH(): Float = (height - extraBottomInsetPx).toFloat().coerceAtLeast(lineH)
    internal fun textWidthPx(): Float = (width - textLeft - dp(10f)).coerceAtLeast(charW)

    // ------------------------------------------------------------------ attach / draw
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(blink); postDelayed(blink, BLINK_MS)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(blink)
        touch.reset()
        closeActionMode()
        super.onDetachedFromWindow()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        resetBlink(); invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!ensureBound()) { canvas.drawColor(colors.background); return }
        painter.draw(canvas)
    }

    internal fun requestHighlight(first: Int, last: Int, anyMissing: Boolean) {
        val hl = session?.highlighter ?: return
        val now = SystemClock.uptimeMillis()
        if (first != lastReqFirst || last != lastReqLast || (anyMissing && now - lastReqTime > 120)) {
            lastReqFirst = first; lastReqLast = last; lastReqTime = now
            hl.requestRange(first, last)
        }
    }

    internal fun resetBlink() { lastBlinkReset = SystemClock.uptimeMillis(); caretVisible = true }

    override fun computeScroll() { touch.computeFling() }

    override fun onCheckIsTextEditor(): Boolean = session?.editable != null

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val s = session ?: return null
        val d = s.editable ?: return null
        outAttrs.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        val sel = s.selection
        outAttrs.initialSelStart = d.offsetOf(sel.start)
        outAttrs.initialSelEnd = d.offsetOf(sel.end)
        return EditorInputConnection(this)
    }

    internal fun restartInput() { try { imm.restartInput(this) } catch (_: Throwable) {} }

    /** Tell the IME where the selection/composition is after a change that did not originate from it. */
    internal fun imeUpdateSelection() {
        val s = session ?: return
        val d = s.editable ?: return
        try {
            imm.updateSelection(this, d.offsetOf(s.selection.start), d.offsetOf(s.selection.end), composeStart, composeEnd)
        } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------------ geometry helpers
    internal fun cellX(cells: Int): Float = textLeft - (session?.scrollX ?: 0f) + cells * charW

    /** Document position under view coordinates. */
    internal fun posAt(x: Float, y: Float): Pos {
        val s = session ?: return Pos.ZERO
        val store = s.store
        val hit = ViewportMath.hit(s, layout, lineH, y)
        val line = hit.line
        val rows = layout.rowStarts(line)
        val len = utilBuf.fill(store, line)
        if (hit.beyondEnd && y >= hit.lineTopY + rows.size * lineH) return Pos(line, len)
        val from = rows[hit.row]
        val to = if (hit.row + 1 < rows.size) rows[hit.row + 1] else len
        val target = Math.floor(((x - textLeft + s.scrollX) / charW + 0.5f).toDouble()).toInt().coerceAtLeast(0)
        var col = TextMetrics.colAtVisual(utilBuf.chars, from, to, target, config.tabSize)
        if (hit.row + 1 < rows.size && col >= to && to > from) {
            // a caret at the end of a wrapped row would be drawn at the start of the next one: stay on the last char
            col = if (to - 2 >= from && Character.isLowSurrogate(utilBuf.chars[to - 1]) && Character.isHighSurrogate(utilBuf.chars[to - 2])) to - 2 else to - 1
        }
        return Pos(line, col.coerceIn(0, len))
    }

    /** Row/cells of a position: returns (row index inside its line) and fills [out][0]=cells from row start. */
    internal fun rowAndCells(p: Pos, out: IntArray): Int {
        val s = session ?: return 0
        val len = utilBuf.fill(s.store, p.line)
        val rows = layout.rowStarts(p.line)
        val col = p.col.coerceIn(0, len)
        val r = RowLayout.rowOfCol(rows, col)
        out[0] = TextMetrics.cells(utilBuf.chars, rows[r], col, config.tabSize)
        return r
    }

    private val rc = IntArray(1)

    /** Scrolls the minimum needed to show the caret (vertical and, without wrap, horizontal). */
    fun ensureCaretVisible() {
        val s = session ?: return
        if (!ensureBound() || height <= 0) return
        var head = s.clampPos(s.selection.head)
        if (s.folds.isHidden(head.line)) s.folds.reveal(head.line)
        val row = rowAndCells(head, rc)
        val cells = rc[0]
        val vh = viewportH()
        val m = minOf(lineH, vh / 4f)
        ViewportMath.ensureRowVisible(s, layout, lineH, vh, head.line, row, m * 0.5f, m * 0.5f)
        if (!layout.wrapping) {
            val x = cells * charW
            val w = textWidthPx()
            val margin = minOf(charW * 3, w / 4)
            if (x - s.scrollX < margin) s.scrollX = (x - margin).coerceAtLeast(0f)
            else if (x + charW - s.scrollX > w - margin) s.scrollX = x + charW - w + margin
        } else s.scrollX = 0f
        listener?.onScrollChanged()
        invalidate()
    }

    internal fun clampScrollX() {
        val s = session ?: return
        if (layout.wrapping) { s.scrollX = 0f; return }
        val max = ((maxCellsSeen + 2) * charW - textWidthPx()).coerceAtLeast(0f)
        s.scrollX = s.scrollX.coerceIn(0f, max)
    }

    // ------------------------------------------------------------------ selection / caret
    internal fun updateBracket() {
        val s = session
        bracketPair = if (s == null || !config.matchBrackets || !s.selection.isEmpty) null
        else try { BracketMatcher.find(s.store, s.selection.head) } catch (_: Throwable) { null }
    }

    /** Central place to change the selection from user actions. */
    internal fun setSelection(sel: Selection, keepGoal: Boolean = false, scroll: Boolean = true) {
        val s = session ?: return
        val c = s.clampSelection(sel)
        val changed = c != s.selection
        s.selection = c
        if (!keepGoal) goalCells = -1
        if (changed) { updateBracket(); if (c.isEmpty) closeActionMode() }
        if (changed && composeStart >= 0) { composeStart = -1; composeEnd = -1; restartInput() }
        resetBlink()
        if (scroll) ensureCaretVisible() else invalidate()
        if (changed) { imeUpdateSelection(); listener?.onSelectionChanged() }
    }

    internal fun afterEdit(sel: Selection) {
        val s = session ?: return
        s.selection = s.clampSelection(sel)
        goalCells = -1
        showCaretHandle = false
        updateBracket()
        resetBlink()
        ensureCaretVisible()
        imeUpdateSelection()
        listener?.onContentChanged()
        listener?.onSelectionChanged()
        closeActionMode()
    }

    internal val editOptions: EditOptions
        get() = EditOptions(config.tabSize, config.insertSpaces, config.autoIndent, config.autoCloseBrackets)

    /** Runs [block] on the editable document or reports a blocked edit. */
    internal inline fun edit(block: (EditableDocument, Selection) -> Selection?) {
        val s = session ?: return
        val d = s.editable
        if (d == null) { listener?.onEditBlocked(); return }
        composeStart = -1; composeEnd = -1
        val after = block(d, s.selection) ?: return
        afterEdit(after)
    }

    // ------------------------------------------------------------------ vertical motion (wrap aware)
    internal fun moveVertical(p: Pos, dir: Int): Pos {
        val s = session ?: return p
        val store = s.store
        val last = store.lineCount - 1
        if (goalCells < 0) {
            if (layout.wrapping) { rowAndCells(p, rc); goalCells = rc[0] }
            else goalCells = CursorMotion.cellColumn(store, p, config.tabSize)
        }
        if (layout.wrapping) {
            val rows = layout.rowStarts(p.line)
            val len = utilBuf.fill(store, p.line)
            val r = RowLayout.rowOfCol(rows, p.col.coerceIn(0, len))
            val targetLine: Int; val targetRow: Int
            if (dir < 0 && r > 0) { targetLine = p.line; targetRow = r - 1 }
            else if (dir > 0 && r < rows.size - 1) { targetLine = p.line; targetRow = r + 1 }
            else {
                targetLine = if (dir < 0) s.folds.prevVisible(p.line) else s.folds.nextVisible(p.line)
                if (targetLine < 0) return Pos(0, 0)
                if (targetLine > last) return Pos(last, store.lineLength(last))
                targetRow = if (dir < 0) layout.rowCount(targetLine) - 1 else 0
            }
            val tLen = utilBuf.fill(store, targetLine)
            val tRows = layout.rowStarts(targetLine)
            val from = tRows[targetRow]
            val to = if (targetRow + 1 < tRows.size) tRows[targetRow + 1] else tLen
            var col = TextMetrics.colAtVisual(utilBuf.chars, from, to, goalCells, config.tabSize)
            if (targetRow + 1 < tRows.size && col >= to && to > from) col = to - 1
            return Pos(targetLine, col)
        }
        val tl = if (dir < 0) s.folds.prevVisible(p.line) else s.folds.nextVisible(p.line)
        if (tl < 0) return Pos(0, 0)
        if (tl > last) return Pos(last, store.lineLength(last))
        return CursorMotion.atCells(store, tl, goalCells, config.tabSize)
    }

    // ------------------------------------------------------------------ EditorCommands
    override fun undo() {
        val s = session ?: return
        val d = s.editable ?: run { listener?.onEditBlocked(); return }
        composeStart = -1; composeEnd = -1
        val sel = d.undo() ?: return
        afterEdit(sel)
    }

    override fun redo() {
        val s = session ?: return
        val d = s.editable ?: run { listener?.onEditBlocked(); return }
        composeStart = -1; composeEnd = -1
        val sel = d.redo() ?: return
        afterEdit(sel)
    }

    private fun selectionRange(): Pair<Pos, Pos>? {
        val s = session ?: return null
        val sel = s.selection
        if (!sel.isEmpty) return sel.start to sel.end
        val line = sel.head.line
        return if (line + 1 < s.store.lineCount) Pos(line, 0) to Pos(line + 1, 0) else Pos(line, 0) to Pos(line, s.store.lineLength(line))
    }

    private fun textOfSelection(): String? {
        val s = session ?: return null
        val (a, b) = selectionRange() ?: return null
        val t = s.store.getText(a, b, MAX_CLIP + 1)
        if (t.length > MAX_CLIP) { listener?.onMessage(context.getString(R.string.editor_clip_too_large)); return null }
        return t
    }

    override fun copy() {
        val t = textOfSelection() ?: return
        try { clipboard.setPrimaryClip(ClipData.newPlainText("text", t)) } catch (_: Throwable) { listener?.onMessage(context.getString(R.string.editor_clip_too_large)) }
    }

    override fun cut() {
        val s = session ?: return
        if (s.editable == null) { copy(); listener?.onEditBlocked(); return }
        val t = textOfSelection() ?: return
        try { clipboard.setPrimaryClip(ClipData.newPlainText("text", t)) } catch (_: Throwable) { listener?.onMessage(context.getString(R.string.editor_clip_too_large)); return }
        edit { d, sel ->
            if (sel.isEmpty) EditActions.deleteLines(d, sel) else EditActions.deleteSelection(d, sel)
        }
    }

    override fun paste() {
        val clip = clipboard.primaryClip
        if (clip == null || clip.itemCount == 0) return
        val text = try { clip.getItemAt(0).coerceToText(context)?.toString() } catch (_: Throwable) { null }
        if (text.isNullOrEmpty()) return
        edit { d, sel -> EditActions.insertText(d, sel, text, editOptions, typed = false) }
    }

    override fun selectAll() {
        val s = session ?: return
        val store = s.store
        val last = store.lineCount - 1
        setSelection(Selection(Pos(0, 0), Pos(last, store.lineLength(last))), scroll = false)
        showCaretHandle = false
        startSelectionActionMode()
    }

    override fun gotoLine(line1Based: Int, col: Int) {
        val s = session ?: return
        val line = (line1Based - 1).coerceIn(0, (s.store.lineCount - 1).coerceAtLeast(0))
        s.folds.reveal(line)
        val c = col.coerceIn(0, s.store.lineLength(line))
        s.selection = Selection.caret(Pos(line, c))
        goalCells = -1
        updateBracket()
        ensureBound()
        ViewportMath.centerOn(s, layout, lineH, viewportH(), line)
        ensureCaretVisible()
        imeUpdateSelection()
        listener?.onSelectionChanged()
    }

    override fun insertText(text: String) {
        if (text == "\t") { indent(); return }
        if (text.isEmpty()) return
        edit { d, sel ->
            if (text == "\n") EditActions.newline(d, sel, editOptions)
            else EditActions.insertText(d, sel, text, editOptions, typed = text.codePointCount(0, text.length) == 1)
        }
    }

    override fun indent() { edit { d, sel -> EditActions.indent(d, sel, editOptions, outdent = false) } }
    override fun outdent() { edit { d, sel -> EditActions.indent(d, sel, editOptions, outdent = true) } }

    override fun revealMatch(m: Match, select: Boolean) {
        val s = session ?: return
        if (!ensureBound()) return
        s.folds.reveal(m.start.line)
        if (select) { s.selection = s.clampSelection(Selection(m.start, m.end)); goalCells = -1; bracketPair = null; imeUpdateSelection(); listener?.onSelectionChanged() }
        val y = ViewportMath.yOfLineTop(s, layout, lineH, m.start.line)
        val vh = viewportH()
        if (y == null || y < lineH || y > vh - 3 * lineH) ViewportMath.centerOn(s, layout, lineH, vh, m.start.line)
        if (!layout.wrapping) {
            val len = utilBuf.fill(s.store, m.start.line)
            val x = TextMetrics.cells(utilBuf.chars, 0, m.start.col.coerceIn(0, len), config.tabSize) * charW
            if (x - s.scrollX < 0 || x - s.scrollX > textWidthPx() - charW * 4) s.scrollX = (x - textWidthPx() / 3).coerceAtLeast(0f)
        }
        listener?.onScrollChanged()
        invalidate()
    }

    override fun replaceRange(m: Match, text: String): Boolean {
        val s = session ?: return false
        val d = s.editable ?: run { listener?.onEditBlocked(); return false }
        composeStart = -1; composeEnd = -1
        val before = s.selection
        val end = EditActions.endOf(m.start, EditActions.normalizeEol(text))
        val after = Selection.caret(end)
        d.replace(m.start, m.end, EditActions.normalizeEol(text), before, after, Coalesce.NONE)
        s.selection = s.clampSelection(after)
        updateBracket(); invalidate()
        listener?.onContentChanged()
        return true
    }

    override fun selectedTextOrWordAtCaret(maxChars: Int): String? {
        val s = session ?: return null
        val sel = s.selection
        if (!sel.isEmpty) {
            if (sel.start.line != sel.end.line) return null
            val t = s.store.getText(sel.start, sel.end, maxChars + 1)
            return if (t.length <= maxChars) t else null
        }
        val line = sel.head.line
        val len = utilBuf.fill(s.store, line)
        if (len == 0) return null
        val packed = WordUtil.wordRangeAt(utilBuf.chars, len, sel.head.col)
        val a = (packed ushr 32).toInt(); val b = (packed and 0xFFFFFFFFL).toInt()
        if (b <= a || !WordUtil.isWordChar(utilBuf.chars[a])) return null
        return String(utilBuf.chars, a, minOf(b - a, maxChars))
    }

    override fun setMatchHighlighter(h: MatchHighlighter?) { matchHighlighter = h; invalidate() }

    override fun requestFocusAndShowKeyboard() {
        requestFocus()
        if (session?.editable == null) return
        post { try { imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT) } catch (_: Throwable) {} }
    }

    override fun hideKeyboard() { try { imm.hideSoftInputFromWindow(windowToken, 0) } catch (_: Throwable) {} }

    /** Key injection for the extra-keys row (same behaviour as a hardware key press). */
    fun sendKey(keyCode: Int, ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false): Boolean =
        EditorKeys.handle(this, keyCode, ctrl, shift, alt, 0)

    fun foldToggleAt(line: Int) {
        val s = session ?: return
        if (s.folds.unfold(line)) { invalidate(); return }
        val end = FoldFinder.endOfBlock(s.store, line, config.tabSize)
        if (end > line) {
            s.folds.fold(line, end)
            val head = s.selection.head
            if (s.folds.isHidden(head.line)) setSelection(Selection.caret(Pos(line, s.store.lineLength(line))), scroll = false)
            ViewportMath.normalize(s, layout, lineH)
            invalidate()
        }
    }

    /** The document was modified outside the view's own edit paths (replace-all): refresh derived state. */
    fun notifyExternalEdit() {
        val s = session ?: return
        s.selection = s.clampSelection(s.selection)
        layout.invalidateAll()
        composeStart = -1; composeEnd = -1
        updateBracket(); restartInput(); ensureCaretVisible(); invalidate()
        listener?.onContentChanged(); listener?.onSelectionChanged()
    }

    fun unfoldAll() { session?.folds?.unfoldAll(); invalidate() }

    // ------------------------------------------------------------------ action mode (cut/copy/paste)
    internal fun startSelectionActionMode() {
        if (actionMode != null) { actionMode?.invalidate(); return }
        if (session == null) return
        val cb = object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(0, ID_CUT, 0, R.string.action_cut).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                menu.add(0, ID_COPY, 1, R.string.action_copy).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                menu.add(0, ID_PASTE, 2, R.string.action_paste).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                menu.add(0, ID_ALL, 3, R.string.action_select_all).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                return true
            }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                val s = session
                val editable = s?.editable != null
                val hasSel = s?.selection?.isEmpty == false
                menu.findItem(ID_CUT)?.isVisible = editable && hasSel
                menu.findItem(ID_COPY)?.isVisible = hasSel
                menu.findItem(ID_PASTE)?.isVisible = editable
                return true
            }
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                when (item.itemId) {
                    ID_CUT -> cut(); ID_COPY -> copy(); ID_PASTE -> paste(); ID_ALL -> selectAll()
                }
                if (item.itemId != ID_ALL) mode.finish()
                return true
            }
            override fun onDestroyActionMode(mode: ActionMode) { if (actionMode === mode) actionMode = null }
            override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) { touch.selectionRect(outRect) }
        }
        actionMode = startActionMode(cb, ActionMode.TYPE_FLOATING)
    }

    internal fun closeActionMode() { actionMode?.finish(); actionMode = null }

    internal fun haptic() { if (config.haptic) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }

    // ------------------------------------------------------------------ input events
    override fun onTouchEvent(event: MotionEvent): Boolean = touch.onTouchEvent(event) || super.onTouchEvent(event)

    override fun onGenericMotionEvent(event: MotionEvent): Boolean = touch.onGenericMotion(event) || super.onGenericMotionEvent(event)

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (EditorKeys.onKeyEvent(this, event)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (EditorKeys.isHandled(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    companion object {
        internal const val BLINK_MS = 530L
        internal const val MAX_CLIP = 1_000_000
        private const val ID_CUT = 1; private const val ID_COPY = 2; private const val ID_PASTE = 3; private const val ID_ALL = 4

        private val EMPTY_STORE: LineStore = object : LineStore {
            override val lineCount = 1
            override val isIndexComplete = true
            override val indexProgress = 1f
            override val estimatedLineCount = 1L
            override val contentVersion = 0L
            override fun lineLength(line: Int) = 0
            override fun getLine(line: Int) = ""
            override fun copyLine(line: Int, from: Int, to: Int, dst: CharArray, dstOff: Int) = 0
            override fun getText(from: Pos, to: Pos, maxChars: Int) = ""
            override fun addListener(l: DocumentListener) {}
            override fun removeListener(l: DocumentListener) {}
            override fun close() {}
        }

        val DEFAULT_COLORS = EditorColors(
            isDark = true, background = 0xFF0A0A0A.toInt(), foreground = 0xFFD4D4D4.toInt(),
            selection = 0x66446688, lineHighlight = 0x14FFFFFF, cursor = 0xFFFFFFFF.toInt(),
            lineNumber = 0xFF6B6B6B.toInt(), lineNumberActive = 0xFFC8C8C8.toInt(),
            indentGuide = 0x22FFFFFF, indentGuideActive = 0x44FFFFFF, whitespace = 0x33FFFFFF,
            bracketMatch = 0x55888888, findMatch = 0x55A07800, findMatchCurrent = 0xAAD08A00.toInt(),
            scrollbarThumb = 0xAA8FA8FF.toInt(), gutterBackground = 0xFF0A0A0A.toInt(),
        )
    }
}
