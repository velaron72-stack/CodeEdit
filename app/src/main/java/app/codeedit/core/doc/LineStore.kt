package app.codeedit.core.doc

/**
 * Read-only line-oriented view of a document. Implemented by ViewDocument (streaming, read-only, any size)
 * and EditDocument (in-memory, editable). Lines never contain EOL characters.
 *
 * Threading: reads are safe from any thread for ViewDocument; for EditDocument all access (reads and writes)
 * must happen on the UI thread unless a method says otherwise. Listeners are invoked on the thread that caused
 * the event (ViewDocument index progress: a background thread; EditDocument edits: the caller's thread).
 */
interface LineStore {
    /** Always >= 1 (an empty document has one empty line). While !isIndexComplete it is a lower bound that grows. */
    val lineCount: Int
    val isIndexComplete: Boolean
    /** 0..1 while indexing, 1 when complete. */
    val indexProgress: Float
    /** Best estimate of the final line count (== lineCount when complete). Used for the fast-scroll thumb. */
    val estimatedLineCount: Long
    /** Incremented on every content modification (never by index growth). */
    val contentVersion: Long

    /** Length in UTF-16 chars of the (possibly capped, see ViewDocument.MAX_LINE_CHARS) line. */
    fun lineLength(line: Int): Int
    fun getLine(line: Int): String
    /** Copies chars [from,to) of the line into dst; returns number copied (clamped). */
    fun copyLine(line: Int, from: Int, to: Int, dst: CharArray, dstOff: Int): Int
    /** Text between two positions, lines joined with '\n'. Stops after maxChars (result may be shorter). */
    fun getText(from: Pos, to: Pos, maxChars: Int = Int.MAX_VALUE): String

    fun addListener(l: DocumentListener)
    fun removeListener(l: DocumentListener)
    fun close()
}

interface DocumentListener {
    /** Lines [startLine, startLine+oldLineCount) were replaced by [startLine, startLine+newLineCount). */
    fun onContentChanged(startLine: Int, oldLineCount: Int, newLineCount: Int) {}
    /** ViewDocument indexing progress (background thread). */
    fun onIndexProgress(lineCount: Int, complete: Boolean) {}
}

enum class Coalesce { NONE, TYPING, DELETING }

/** In-memory editable document. Internally lines are separated by '\n'; original EOL is restored on save. */
interface EditableDocument : LineStore {
    /** Total chars including one '\n' between lines. */
    val length: Int
    fun offsetOf(p: Pos): Int
    fun posOf(offset: Int): Pos
    fun charAt(offset: Int): Char

    /**
     * Replaces [from,to) with text ('\n' only; callers normalise \r\n/\r). Records undo.
     * selBefore/selAfter are restored by undo()/redo(). Returns the position just after the inserted text.
     */
    fun replace(from: Pos, to: Pos, text: String, selBefore: Selection? = null, selAfter: Selection? = null,
                coalesce: Coalesce = Coalesce.NONE): Pos

    /** Group several replace() calls into one undo step. Nestable. */
    fun beginCompound()
    fun endCompound()

    val canUndo: Boolean
    val canRedo: Boolean
    /** Returns the selection to restore, or null if nothing to undo. */
    fun undo(): Selection?
    fun redo(): Selection?

    val isModified: Boolean
    fun markSaved()
    /** Whole text with '\n' separators (only call for documents that fit in memory, i.e. always for EditDocument). */
    fun fullText(): String
}
