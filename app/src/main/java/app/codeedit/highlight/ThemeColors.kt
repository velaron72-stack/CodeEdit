package app.codeedit.highlight

import app.codeedit.core.util.MiniJson

/** Colour helpers + derivation of [EditorColors] from a VS Code / tm-themes JSON theme (`colors` + token defaults). */
object ThemeColors {
    /** "#RGB", "#RGBA", "#RRGGBB", "#RRGGBBAA" -> ARGB, or null if malformed. */
    @JvmStatic
    fun parseColor(s: String?): Int? {
        if (s == null || s.length < 4 || s[0] != '#') return null
        val hex = s.substring(1)
        if (hex.any { Character.digit(it, 16) < 0 }) return null
        fun h(i: Int) = Character.digit(hex[i], 16)
        return when (hex.length) {
            3 -> argb(255, h(0) * 17, h(1) * 17, h(2) * 17)
            4 -> argb(h(3) * 17, h(0) * 17, h(1) * 17, h(2) * 17)
            6 -> argb(255, h(0) * 16 + h(1), h(2) * 16 + h(3), h(4) * 16 + h(5))
            8 -> argb(h(6) * 16 + h(7), h(0) * 16 + h(1), h(2) * 16 + h(3), h(4) * 16 + h(5))
            else -> null
        }
    }

    @JvmStatic fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b

    @JvmStatic fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)

    /** Draws `fg` (with its own alpha) over opaque `bg`. */
    @JvmStatic fun over(bg: Int, fg: Int): Int {
        val a = (fg ushr 24) and 255
        if (a == 255) return fg
        fun ch(shift: Int): Int { val f = (fg ushr shift) and 255; val b = (bg ushr shift) and 255; return (f * a + b * (255 - a) + 127) / 255 }
        return argb(255, ch(16), ch(8), ch(0))
    }

    /** Result of reading a theme document. [defaultFg]/[defaultBg] are opaque and are what the native engine uses as global colours. */
    class Parsed(val colors: EditorColors, val defaultFg: Int, val defaultBg: Int)

    @JvmStatic
    fun parse(themeJson: ByteArray, typeHint: String?): Parsed {
        val root = MiniJson.asObject(MiniJson.parse(themeJson, lenient = true)) ?: emptyMap()
        val cols = MiniJson.asObject(root["colors"]) ?: emptyMap()
        val type = (root["type"] as? String) ?: typeHint
        val dark = !type.equals("light", ignoreCase = true)
        fun c(vararg keys: String): Int? { for (k in keys) parseColor(cols[k] as? String)?.let { return it }; return null }
        // Global token colours from tokenColors/settings entries without a scope.
        var tokFg: Int? = null; var tokBg: Int? = null
        val rules = MiniJson.asArray(root["tokenColors"]) ?: MiniJson.asArray(root["settings"]) ?: emptyList()
        for (r in rules) {
            val o = MiniJson.asObject(r) ?: continue
            if (o["scope"] != null) continue
            val st = MiniJson.asObject(o["settings"]) ?: continue
            tokFg = parseColor(st["foreground"] as? String) ?: tokFg
            tokBg = parseColor(st["background"] as? String) ?: tokBg
        }
        val bg = opaque(c("editor.background") ?: parseColor(root["bg"] as? String) ?: tokBg ?: if (dark) 0xFF1E1E1E.toInt() else 0xFFFFFFFF.toInt(), if (dark) 0xFF1E1E1E.toInt() else 0xFFFFFFFF.toInt())
        val fg = opaque(c("editor.foreground", "foreground") ?: parseColor(root["fg"] as? String) ?: tokFg ?: if (dark) 0xFFD4D4D4.toInt() else 0xFF333333.toInt(), bg)
        val colors = EditorColors(
            isDark = dark,
            background = bg,
            foreground = fg,
            selection = c("editor.selectionBackground") ?: withAlpha(fg, 0x40),
            lineHighlight = c("editor.lineHighlightBackground") ?: withAlpha(fg, 0x14),
            cursor = c("editorCursor.foreground") ?: fg,
            lineNumber = c("editorLineNumber.foreground") ?: withAlpha(fg, 0x80),
            lineNumberActive = c("editorLineNumber.activeForeground") ?: fg,
            indentGuide = c("editorIndentGuide.background1", "editorIndentGuide.background") ?: withAlpha(fg, 0x26),
            indentGuideActive = c("editorIndentGuide.activeBackground1", "editorIndentGuide.activeBackground") ?: withAlpha(fg, 0x55),
            whitespace = c("editorWhitespace.foreground") ?: withAlpha(fg, 0x40),
            bracketMatch = c("editorBracketMatch.background") ?: withAlpha(fg, 0x30),
            findMatch = c("editor.findMatchHighlightBackground") ?: withAlpha(0xFFEAC200.toInt(), 0x55),
            findMatchCurrent = c("editor.findMatchBackground") ?: withAlpha(0xFFFF8C00.toInt(), 0x99),
            scrollbarThumb = c("scrollbarSlider.background") ?: withAlpha(fg, 0x40),
            gutterBackground = c("editorGutter.background") ?: bg,
        )
        return Parsed(colors, fg, bg)
    }

    private fun opaque(color: Int, under: Int): Int = if ((color ushr 24) == 255) color else over(under, color)
}
