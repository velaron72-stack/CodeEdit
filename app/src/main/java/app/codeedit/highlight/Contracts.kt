package app.codeedit.highlight

import java.io.Closeable

/** Tokens of one line. Token i spans [starts[i], starts[i+1]) (last token: to end of line). meta = vscode-textmate encoded attributes. */
class LineTokens(@JvmField val count: Int, @JvmField val starts: IntArray, @JvmField val meta: IntArray)

/**
 * Bit layout of vscode-textmate EncodedTokenAttributes (src/encodedTokenAttributes.ts):
 * languageId 8 | tokenType 2 (bits 8-9) | fontStyle 4 (bits 11-14) | foreground 9 (bits 15-23) | background 9 (bits 24-32)
 * ("containsBalancedBrackets" bit 10). The exact shifts MUST be taken from the TS file; these helpers must match it.
 */
object TokenMeta {
    @JvmStatic fun foreground(m: Int): Int = (m ushr 15) and 0x1FF
    @JvmStatic fun fontStyle(m: Int): Int = (m ushr 11) and 0xF       // 1 italic, 2 bold, 4 underline, 8 strikethrough
    @JvmStatic fun tokenType(m: Int): Int = (m ushr 8) and 0x3         // 0 other, 1 comment, 2 string, 3 regex
    const val ITALIC = 1; const val BOLD = 2; const val UNDERLINE = 4; const val STRIKE = 8
}

fun interface HighlightListener {
    /** Tokens for lines [firstLine, lastLine] changed; called on the UI thread. */
    fun onTokensChanged(firstLine: Int, lastLine: Int)
}

interface SyntaxHighlighter : Closeable {
    val languageId: String
    /** ARGB colours indexed by the foreground id found in token meta. */
    val colorMap: IntArray
    var listener: HighlightListener?
    /** Cached tokens or null (not yet available). Never blocks. Cheap enough to call per visible line per frame. */
    fun tokensFor(line: Int): LineTokens?
    /** Ask for background tokenization of lines [firstLine, lastLine] (visible range + margin). Non-blocking. */
    fun requestRange(firstLine: Int, lastLine: Int)
    /** Tokenizes [firstLine, lastLine] right now on the calling (UI) thread when that is cheap (a checkpoint is close), so the very first frame is already coloured. Optional. */
    fun prefetch(firstLine: Int, lastLine: Int) {}
    /** Registered automatically as a DocumentListener by HighlightService.createHighlighter (callers need not call it). UI thread. Invalidates caches/checkpoints and synchronously re-tokenizes the edited lines. */
    fun onDocumentChanged(startLine: Int, oldLineCount: Int, newLineCount: Int)
    fun invalidateAll()
    /** true if lines near `line` may be tokenized from a cold/approximate state (large file not yet indexed up to here). */
    fun isApproximate(line: Int): Boolean
    /** Highest line (exclusive) for which states were computed sequentially from the document start. */
    val exactFrontier: Int
}

data class EditorColors(
    val isDark: Boolean,
    val background: Int, val foreground: Int,
    val selection: Int, val lineHighlight: Int, val cursor: Int,
    val lineNumber: Int, val lineNumberActive: Int,
    val indentGuide: Int, val indentGuideActive: Int,
    val whitespace: Int, val bracketMatch: Int,
    val findMatch: Int, val findMatchCurrent: Int,
    val scrollbarThumb: Int, val gutterBackground: Int,
)

data class ThemeInfo(val id: String, val name: String, val isDark: Boolean, val colors: EditorColors)

data class LanguageInfo(
    val id: String, val name: String, val scope: String?, val file: String?,
    val aliases: List<String>, val extensions: List<String>, val filenames: List<String>,
    val firstLine: String?, val hidden: Boolean,
)
data class ThemeEntry(val id: String, val name: String, val isDark: Boolean, val file: String, val license: String)

/** Reads files below assets/textmate/, e.g. "manifest.json", "grammars/python.json", "themes/monokai.json". */
fun interface AssetReader { fun read(path: String): ByteArray? }

const val PLAIN_TEXT_ID = "plaintext"
