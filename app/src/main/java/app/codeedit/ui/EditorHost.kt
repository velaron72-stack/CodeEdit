package app.codeedit.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import app.codeedit.data.K
import app.codeedit.data.Settings
import app.codeedit.editor.CursorStyle
import app.codeedit.editor.EditorConfig
import app.codeedit.editor.EditorView
import app.codeedit.highlight.EditorColors

fun buildConfig(s: Settings): EditorConfig = EditorConfig(
    fontFamilyId = s.str(K.FONT_FAMILY),
    fontSizeSp = s.float(K.FONT_SIZE), minFontSizeSp = s.float(K.FONT_MIN), maxFontSizeSp = s.float(K.FONT_MAX),
    lineSpacing = s.float(K.LINE_SPACING), letterSpacingEm = s.float(K.LETTER_SPACING),
    boldText = s.bool(K.BOLD), ligatures = s.bool(K.LIGATURES),
    showLineNumbers = s.bool(K.LINE_NUMBERS), lineNumberBase = s.str(K.LN_BASE).toIntOrNull() ?: 1, lineNumberMinDigits = s.int(K.LN_DIGITS),
    highlightCurrentLine = s.bool(K.CUR_LINE), wordWrap = s.bool(K.WRAP), tabSize = s.int(K.TAB_SIZE),
    insertSpaces = s.bool(K.INSERT_SPACES), autoIndent = s.bool(K.AUTO_INDENT), autoCloseBrackets = s.bool(K.AUTO_CLOSE),
    matchBrackets = s.bool(K.MATCH_BRACKETS), showWhitespace = s.bool(K.WHITESPACE), indentGuides = s.bool(K.GUIDES),
    folding = s.bool(K.FOLDING),
    cursorStyle = when (s.str(K.CURSOR_STYLE)) { "block" -> CursorStyle.BLOCK; "underline" -> CursorStyle.UNDERLINE; else -> CursorStyle.BAR },
    cursorBlink = s.bool(K.CURSOR_BLINK), pinchZoom = s.bool(K.G_PINCH), fastScroll = s.bool(K.G_FAST),
    doubleTapSelectsWord = s.bool(K.G_DOUBLE), haptic = s.bool(K.G_HAPTIC), animations = s.bool(K.ANIM),
    maxTokenizeLineChars = s.int(K.HL_MAX_LINE),
)

/** The one EditorView of the app, hosted in Compose. It is re-bound to whichever tab is active. */
@Composable
fun EditorHost(vm: EditorViewModel, tab: Tab, colors: EditorColors, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            EditorView(ctx).also { v ->
                v.listener = vm.editorListener
                v.fontRegistry = vm.app.fonts
                vm.editor = v
            }
        },
        update = { v ->
            // reads below subscribe this block to the relevant state
            val cfg = buildConfig(vm.settings)
            val session = if (tab.state == TabState.READY) tab.session else null
            @Suppress("UNUSED_VARIABLE") val version = tab.sessionVersion
            if (v.config != cfg) v.config = cfg
            if (v.colors != colors) v.colors = colors
            if (v.boundSession !== session) {
                v.bind(session)
                vm.search.onEditorRebound()
            }
        },
        onRelease = { v ->
            if (vm.editor === v) vm.editor = null
            v.listener = null
            v.bind(null)
        },
    )
}
