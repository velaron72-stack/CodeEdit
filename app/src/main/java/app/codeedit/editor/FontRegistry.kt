package app.codeedit.editor

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import java.io.File

/**
 * Monospace fonts: bundled JetBrains Mono, the system monospace, and user-imported .ttf/.otf files
 * (copied into filesDir/fonts so no storage permission is needed later). Only fixed-pitch fonts are accepted,
 * because the editor lays text out on a cell grid.
 */
class FontRegistry(private val context: Context) {
    class Font(val id: String, val name: String, val regular: Typeface, val bold: Typeface, val builtin: Boolean)

    private val dir = File(context.filesDir, "fonts")
    private val cache = HashMap<String, Font>()

    private fun builtinList(): List<Font> {
        val out = ArrayList<Font>(2)
        cache[JETBRAINS]?.let { out.add(it) } ?: try {
            val am = context.assets
            val f = Font(JETBRAINS, "JetBrains Mono", Typeface.createFromAsset(am, "fonts/JetBrainsMono-Regular.ttf"),
                Typeface.createFromAsset(am, "fonts/JetBrainsMono-Bold.ttf"), true)
            cache[JETBRAINS] = f; out.add(f)
        } catch (_: Throwable) { /* asset missing: fall back to the system font only */ }
        out.add(system())
        return out
    }

    private fun system(): Font = cache.getOrPut(SYSTEM) {
        Font(SYSTEM, "System monospace", Typeface.MONOSPACE, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD), true)
    }

    fun list(): List<Font> {
        val out = ArrayList(builtinList())
        dir.listFiles { f -> f.isFile && (f.name.endsWith(".ttf", true) || f.name.endsWith(".otf", true)) }
            ?.sortedBy { it.name.lowercase() }?.forEach { f -> load(f)?.let { out.add(it) } }
        return out
    }

    fun get(id: String): Font {
        if (id == SYSTEM) return system()
        if (id == JETBRAINS) return builtinList().first()
        if (id.startsWith(USER_PREFIX)) {
            val f = File(dir, id.removePrefix(USER_PREFIX))
            if (f.isFile) load(f)?.let { return it }
        }
        return builtinList().first()
    }

    private fun load(f: File): Font? {
        val id = USER_PREFIX + f.name
        cache[id]?.let { return it }
        return try {
            val tf = Typeface.createFromFile(f)
            val font = Font(id, f.nameWithoutExtension, tf, tf, false)
            cache[id] = font
            font
        } catch (_: Throwable) { null }
    }

    /** Result: the imported font, or an error message (never throws). */
    fun importFont(uri: Uri, displayName: String?): Pair<Font?, String?> {
        return try {
            dir.mkdirs()
            val base = (displayName ?: "font.ttf").replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
            val name = if (base.endsWith(".ttf", true) || base.endsWith(".otf", true)) base else "$base.ttf"
            val tmp = File(dir, "$name.tmp")
            val input = context.contentResolver.openInputStream(uri) ?: return null to "open failed"
            input.use { ins -> tmp.outputStream().use { o -> ins.copyTo(o, 64 * 1024) } }
            if (tmp.length() > 32L * 1024 * 1024) { tmp.delete(); return null to "too large" }
            val tf = try { Typeface.createFromFile(tmp) } catch (_: Throwable) { null }
            if (tf == null) { tmp.delete(); return null to "not a font" }
            val p = Paint().apply { typeface = tf; textSize = 40f }
            val wi = p.measureText("i"); val wm = p.measureText("W"); val w0 = p.measureText("0")
            if (w0 <= 0f || Math.abs(wi - wm) > w0 * 0.02f) { tmp.delete(); return null to "not monospaced" }
            val dst = File(dir, name)
            if (dst.exists()) dst.delete()
            if (!tmp.renameTo(dst)) { tmp.delete(); return null to "save failed" }
            cache.remove(USER_PREFIX + name)
            load(dst) to null
        } catch (t: Throwable) { null to (t.message ?: t.javaClass.simpleName) }
    }

    fun remove(id: String): Boolean {
        if (!id.startsWith(USER_PREFIX)) return false
        cache.remove(id)
        return File(dir, id.removePrefix(USER_PREFIX)).delete()
    }

    companion object {
        const val JETBRAINS = "jetbrains-mono"
        const val SYSTEM = "monospace"
        const val USER_PREFIX = "user:"
    }
}
