package app.codeedit.data

/** One button of the extra-keys row. */
sealed class ExtraKey(val label: String) {
    class Text(val text: String) : ExtraKey(text)
    object Tab : ExtraKey("Tab")
    class Nav(label: String, val keyCode: Int, val ctrl: Boolean = false) : ExtraKey(label)
    class Combo(label: String, val keyCode: Int, val ctrl: Boolean) : ExtraKey(label)
    object Undo : ExtraKey("Undo")
    object Redo : ExtraKey("Redo")
}

/** Parser of the user-editable layout string ("Tab { } ( ) Ctrl+Z ..."). Android key codes are passed in to stay JVM-testable. */
object ExtraKeysParser {
    const val DPAD_LEFT = 21; const val DPAD_RIGHT = 22; const val DPAD_UP = 19; const val DPAD_DOWN = 20
    const val HOME = 122; const val END = 123; const val PGUP = 92; const val PGDN = 93; const val ESC = 111
    const val KEYCODE_A = 29

    fun parse(layout: String): List<ExtraKey> {
        val out = ArrayList<ExtraKey>()
        for (tok in layout.trim().split(Regex("\\s+"))) {
            if (tok.isEmpty()) continue
            out += when {
                tok == "Tab" -> ExtraKey.Tab
                tok == "Esc" -> ExtraKey.Nav("Esc", ESC)
                tok == "Left" -> ExtraKey.Nav("←", DPAD_LEFT)
                tok == "Right" -> ExtraKey.Nav("→", DPAD_RIGHT)
                tok == "Up" -> ExtraKey.Nav("↑", DPAD_UP)
                tok == "Down" -> ExtraKey.Nav("↓", DPAD_DOWN)
                tok == "Home" -> ExtraKey.Nav("Home", HOME)
                tok == "End" -> ExtraKey.Nav("End", END)
                tok == "PgUp" -> ExtraKey.Nav("PgUp", PGUP)
                tok == "PgDn" -> ExtraKey.Nav("PgDn", PGDN)
                tok == "Undo" -> ExtraKey.Undo
                tok == "Redo" -> ExtraKey.Redo
                tok.length == 6 && tok.startsWith("Ctrl+") && tok[5].uppercaseChar() in 'A'..'Z' ->
                    ExtraKey.Combo("Ctrl+" + tok[5].uppercaseChar(), KEYCODE_A + (tok[5].uppercaseChar() - 'A'), true)
                else -> ExtraKey.Text(tok)
            }
        }
        return out
    }
}
