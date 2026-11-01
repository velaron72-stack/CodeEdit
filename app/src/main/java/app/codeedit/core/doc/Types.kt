package app.codeedit.core.doc

/** Zero-based line, zero-based UTF-16 column. */
data class Pos(@JvmField val line: Int, @JvmField val col: Int) : Comparable<Pos> {
    override fun compareTo(other: Pos): Int = if (line != other.line) line.compareTo(other.line) else col.compareTo(other.col)
    companion object { @JvmField val ZERO = Pos(0, 0) }
}

/** anchor = fixed end, head = moving end (caret). */
data class Selection(@JvmField val anchor: Pos, @JvmField val head: Pos) {
    val isEmpty: Boolean get() = anchor == head
    val start: Pos get() = if (anchor <= head) anchor else head
    val end: Pos get() = if (anchor <= head) head else anchor
    companion object { fun caret(p: Pos) = Selection(p, p); @JvmField val ZERO = Selection(Pos.ZERO, Pos.ZERO) }
}

enum class Eol(val str: String, val label: String) { LF("\n", "LF"), CRLF("\r\n", "CRLF"), CR("\r", "CR") }

enum class DocMode { VIEW, EDIT }

/** charsetName is a java.nio Charset name ("UTF-8", "UTF-16LE", "UTF-16BE", "windows-1251", ...). */
data class TextEncoding(val charsetName: String, val bom: Boolean = false) {
    val label: String get() = charsetName + if (bom) " (BOM)" else ""
    companion object { @JvmField val UTF8 = TextEncoding("UTF-8", false) }
}

/** Facts discovered when a document is opened. Immutable; user overrides live in EditorSession. */
data class DocInfo(
    val mode: DocMode,
    val encoding: TextEncoding,
    val eol: Eol,
    val eolMixed: Boolean,
    val sizeBytes: Long,
    val endsWithNewline: Boolean,
    /** true when the sniffed prefix contained a very long line (> ~100k chars) -> prefer VIEW mode. */
    val hasVeryLongLines: Boolean = false,
)

/** A search hit: [start, end) in document coordinates (end exclusive; may span lines in EDIT mode). */
data class Match(val start: Pos, val end: Pos)
