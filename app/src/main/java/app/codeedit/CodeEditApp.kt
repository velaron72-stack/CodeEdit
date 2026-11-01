package app.codeedit

import android.app.Application
import android.os.Handler
import android.os.Looper
import app.codeedit.data.SessionFiles
import app.codeedit.data.SettingsRepo
import app.codeedit.editor.FontRegistry
import app.codeedit.highlight.AssetReader
import app.codeedit.highlight.HighlightService
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Process-wide singletons (no DI framework: there are only a handful). */
class CodeEditApp : Application() {
    lateinit var settingsRepo: SettingsRepo; private set
    lateinit var sessionFiles: SessionFiles; private set
    lateinit var fonts: FontRegistry; private set
    lateinit var ioPool: ExecutorService; private set
    val tempDir: File get() = File(cacheDir, "tmp").also { it.mkdirs() }
    val main = Handler(Looper.getMainLooper())

    /** null when the grammar package could not be read (assets missing); the editor then shows plain text and says so. */
    var highlight: HighlightService? = null; private set
    var highlightError: String? = null; private set

    override fun onCreate() {
        super.onCreate()
        settingsRepo = SettingsRepo(this)
        sessionFiles = SessionFiles(File(filesDir, "session"))
        fonts = FontRegistry(this)
        ioPool = Executors.newCachedThreadPool { r -> Thread(r, "io").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
        // stale temp files from a previous run (spool copies, interrupted saves) are not needed any more
        ioPool.execute { try { tempDir.listFiles()?.forEach { it.delete() } } catch (_: Throwable) {} }
        try {
            highlight = HighlightService(
                assets = AssetReader { path -> try { assets.open("textmate/$path").use { it.readBytes() } } catch (_: Throwable) { null } },
                uiExecutor = { r -> main.post(r) },
            )
        } catch (t: Throwable) { highlightError = t.message ?: t.javaClass.simpleName }
    }

    companion object { fun of(ctx: android.content.Context): CodeEditApp = ctx.applicationContext as CodeEditApp }
}
