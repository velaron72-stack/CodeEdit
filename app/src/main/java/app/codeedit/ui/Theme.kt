package app.codeedit.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.codeedit.data.K
import app.codeedit.data.Settings
import app.codeedit.editor.EditorView
import app.codeedit.highlight.EditorColors

/** Chrome colours derived from the active syntax theme, so panels and text area always look like one surface. */
@Immutable
class AppColors(
    val dark: Boolean, val bg: Color, val surface: Color, val surface2: Color, val border: Color,
    val text: Color, val muted: Color, val accent: Color, val onAccent: Color, val danger: Color,
    val editor: EditorColors, val scale: Float, val anim: Boolean,
)

val LocalAppColors = staticCompositionLocalOf { buildAppColors(EditorView.DEFAULT_COLORS, false, "periwinkle", 1f, true, null) }

private fun accentOf(id: String, dark: Boolean): Color = when (id) {
    "blue" -> if (dark) Color(0xFF6AA2FF) else Color(0xFF1E64E6)
    "teal" -> if (dark) Color(0xFF3FD0BD) else Color(0xFF008A7B)
    "green" -> if (dark) Color(0xFF6BD97A) else Color(0xFF1F8F3A)
    "orange" -> if (dark) Color(0xFFFFAE5C) else Color(0xFFC25E00)
    "pink" -> if (dark) Color(0xFFFF84BE) else Color(0xFFC2287A)
    "purple" -> if (dark) Color(0xFFBE9BFF) else Color(0xFF7440D6)
    "red" -> if (dark) Color(0xFFFF7A7A) else Color(0xFFC62828)
    else -> if (dark) Color(0xFF8FA8FF) else Color(0xFF3F5BD9)
}

fun buildAppColors(ec: EditorColors, amoled: Boolean, accentId: String, scale: Float, anim: Boolean, scheme: ColorScheme?): AppColors {
    val dark = ec.isDark
    if (scheme != null) {
        return AppColors(dark, scheme.background, scheme.surfaceContainer, scheme.surfaceContainerHigh, scheme.outlineVariant,
            scheme.onSurface, scheme.onSurfaceVariant, scheme.primary, scheme.onPrimary, scheme.error, ec, scale, anim)
    }
    val bg = if (amoled && dark) Color.Black else Color(ec.background)
    val fg = Color(ec.foreground)
    val accent = accentOf(accentId, dark)
    val onAccent = if (accent.red * 0.299f + accent.green * 0.587f + accent.blue * 0.114f > 0.6f) Color(0xFF101010) else Color.White
    return AppColors(
        dark, bg, lerp(bg, fg, if (dark) 0.07f else 0.045f), lerp(bg, fg, if (dark) 0.13f else 0.09f), lerp(bg, fg, if (dark) 0.2f else 0.16f),
        lerp(fg, bg, 0.04f), lerp(fg, bg, 0.5f), accent, onAccent, if (dark) Color(0xFFFF6B6B) else Color(0xFFC62828), ec, scale, anim,
    )
}

@Composable
fun AppTheme(editorColors: EditorColors, settings: Settings, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dynamic = settings.bool(K.DYNAMIC) && Build.VERSION.SDK_INT >= 31
    val dark = editorColors.isDark
    val dyn = if (dynamic) (if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)) else null
    val scale = when (settings.str(K.DENSITY)) { "compact" -> 0.86f; "comfortable" -> 1.14f; else -> 1f }
    val c = buildAppColors(editorColors, settings.bool(K.AMOLED), settings.str(K.ACCENT), scale, settings.bool(K.ANIM), dyn)
    val scheme = if (dark) darkColorScheme(
        primary = c.accent, onPrimary = c.onAccent, background = c.bg, onBackground = c.text, surface = c.bg, onSurface = c.text,
        surfaceVariant = c.surface2, onSurfaceVariant = c.muted, outline = c.border, outlineVariant = c.border, error = c.danger,
        secondaryContainer = c.surface2, onSecondaryContainer = c.text, surfaceContainer = c.surface, surfaceContainerHigh = c.surface,
        surfaceContainerHighest = c.surface2, surfaceContainerLow = c.bg, surfaceContainerLowest = c.bg, surfaceTint = c.accent,
    ) else lightColorScheme(
        primary = c.accent, onPrimary = c.onAccent, background = c.bg, onBackground = c.text, surface = c.bg, onSurface = c.text,
        surfaceVariant = c.surface2, onSurfaceVariant = c.muted, outline = c.border, outlineVariant = c.border, error = c.danger,
        secondaryContainer = c.surface2, onSecondaryContainer = c.text, surfaceContainer = c.surface, surfaceContainerHigh = c.surface,
        surfaceContainerHighest = c.surface2, surfaceContainerLow = c.bg, surfaceContainerLowest = c.bg, surfaceTint = c.accent,
    )
    val shapes = Shapes(
        extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
        medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp), large = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    )
    CompositionLocalProvider(LocalAppColors provides c) {
        MaterialTheme(colorScheme = scheme, shapes = shapes, content = content)
    }
}

@Composable fun appIsDark(settings: Settings): Boolean = when (settings.str(K.THEME_MODE)) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
