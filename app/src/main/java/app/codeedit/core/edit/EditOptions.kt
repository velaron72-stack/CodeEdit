package app.codeedit.core.edit

/** Pure subset of editor settings that influences editing logic (mapped from EditorConfig by the view). */
data class EditOptions(
    val tabSize: Int = 4,
    val insertSpaces: Boolean = true,
    val autoIndent: Boolean = true,
    val autoCloseBrackets: Boolean = true,
    val surroundSelection: Boolean = true,
    val smartHome: Boolean = true,
) {
    val indentUnit: String get() = if (insertSpaces) " ".repeat(tabSize.coerceIn(1, 16)) else "\t"
}
