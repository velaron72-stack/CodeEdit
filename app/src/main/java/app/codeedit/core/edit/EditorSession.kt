package app.codeedit.core.edit

import app.codeedit.core.doc.DocInfo
import app.codeedit.core.doc.DocMode
import app.codeedit.core.doc.EditableDocument
import app.codeedit.core.doc.Eol
import app.codeedit.core.doc.LineStore
import app.codeedit.core.doc.Pos
import app.codeedit.core.doc.Selection
import app.codeedit.core.doc.TextEncoding
import app.codeedit.highlight.PLAIN_TEXT_ID
import app.codeedit.highlight.SyntaxHighlighter
import java.io.Closeable

/**
 * Per-tab editor state shared between the shell (persistence, tabs) and EditorView (rendering/input).
 * Pure Kotlin: no android.* imports. All members are meant for the UI thread.
 */
class EditorSession(
    val id: String,
    store: LineStore,
    info: DocInfo,
    var languageId: String = PLAIN_TEXT_ID,
) : Closeable {
    var store: LineStore = store
        private set
    var info: DocInfo = info
        private set

    /** Closing the session closes it. */
    var highlighter: SyntaxHighlighter? = null

    var selection: Selection = Selection.ZERO

    /** Scroll position = (logical top line, pixel offset inside that line's block). */
    @JvmField var topLine: Int = 0
    @JvmField var topOffsetPx: Float = 0f
    @JvmField var scrollX: Float = 0f

    /** Per-tab zoom in sp; Float.NaN => use EditorConfig.fontSizeSp. */
    @JvmField var fontSizeSp: Float = Float.NaN

    /** User-selected save settings (start = what was detected on open). */
    var encoding: TextEncoding = info.encoding
    var eol: Eol = info.eol

    /** null => EditorConfig.wordWrap. */
    var wrapOverride: Boolean? = null

    /** Folded regions (view state, not persisted). */
    val folds: FoldState = FoldState()

    val editable: EditableDocument? get() = store as? EditableDocument
    val isModified: Boolean get() = editable?.isModified ?: false
    val mode: DocMode get() = info.mode

    /**
     * VIEW<->EDIT switch: keeps selection (clamped), scroll and zoom. The previous store is NOT closed here:
     * the caller owns it (it may still back the new one). The highlighter (bound to the old store) is closed and
     * must be recreated by the shell for the new store.
     */
    fun replaceStore(newStore: LineStore, newInfo: DocInfo) {
        highlighter?.close()
        highlighter = null
        store = newStore
        info = newInfo
        folds.clear()
        selection = clampSelection(selection)
        topLine = topLine.coerceIn(0, (newStore.lineCount - 1).coerceAtLeast(0))
    }

    fun clampPos(p: Pos): Pos {
        val last = (store.lineCount - 1).coerceAtLeast(0)
        val line = p.line.coerceIn(0, last)
        val col = p.col.coerceIn(0, store.lineLength(line))
        return if (line == p.line && col == p.col) p else Pos(line, col)
    }

    fun clampSelection(s: Selection): Selection {
        val a = clampPos(s.anchor); val h = clampPos(s.head)
        return if (a === s.anchor && h === s.head) s else Selection(a, h)
    }

    /** Idempotent. Closes the highlighter and the store. */
    override fun close() {
        try { highlighter?.close() } catch (_: Throwable) {}
        highlighter = null
        try { store.close() } catch (_: Throwable) {}
    }
}
