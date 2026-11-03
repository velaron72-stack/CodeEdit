package app.codeedit.highlight

import app.codeedit.core.doc.LineStore
import app.codeedit.core.util.MiniJson
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantReadWriteLock

data class PackageInfo(
    val grammarName: String, val grammarVersion: String, val themeName: String, val themeVersion: String,
    val engine: String, val languageCount: Int, val themeCount: Int, val licenses: List<String>,
)

/**
 * Entry point of syntax highlighting: reads `assets/textmate/manifest.json`, knows every bundled language and theme, detects
 * languages and creates [SyntaxHighlighter]s that run the native TextMate engine with the bundled grammars/themes ONLY.
 *
 * Thread-safety: [setTheme], [createHighlighter] and the lookups can be called from any thread. Highlighters must be closed
 * before the service.
 */
class HighlightService(
    private val assets: AssetReader,
    private val uiExecutor: Executor,
    workerExecutor: Executor? = null,
    private val backend: TokenizerBackend = NativeBackend,
    private val nativeAvailable: () -> Boolean = { TmNative.ensureLoaded() },
    /** Lines longer than this are not tokenized (one plain token). */
    @Volatile var maxTokenizeLineChars: Int = 10_000,
) : Closeable {

    private val manifestBytes: ByteArray = assets.read("manifest.json") ?: throw IllegalStateException("assets/textmate/manifest.json missing")
    private val manifest: Map<String, Any?> = MiniJson.asObject(MiniJson.parse(manifestBytes)) ?: throw IllegalStateException("manifest.json is not an object")

    private val plainText = LanguageInfo(PLAIN_TEXT_ID, "Plain text", null, null, emptyList(), listOf(".txt", ".text"), emptyList(), null, false)

    /** Every language including hidden helper grammars; first = plain text. */
    val allLanguages: List<LanguageInfo>
    /** Visible languages sorted by name; first = plain text. */
    val languages: List<LanguageInfo>
    val themes: List<ThemeEntry>
    val packageInfo: PackageInfo
    private val detector: LanguageDetector
    private val byId = HashMap<String, LanguageInfo>()

    init {
        val langs = ArrayList<LanguageInfo>()
        for (o in MiniJson.asArray(manifest["languages"]) ?: emptyList()) {
            val m = MiniJson.asObject(o) ?: continue
            val id = m["id"] as? String ?: continue
            fun strs(k: String): List<String> = (MiniJson.asArray(m[k]) ?: emptyList()).filterIsInstance<String>()
            langs += LanguageInfo(
                id = id, name = m["name"] as? String ?: id, scope = m["scope"] as? String, file = m["file"] as? String,
                aliases = strs("aliases"), extensions = strs("extensions"), filenames = strs("filenames"),
                firstLine = m["firstLine"] as? String, hidden = m["hidden"] == true,
            )
        }
        allLanguages = listOf(plainText) + langs
        languages = listOf(plainText) + langs.filter { !it.hidden }.sortedBy { it.name.lowercase() }
        for (l in allLanguages) byId.putIfAbsent(l.id, l)
        detector = LanguageDetector(langs.filter { !it.hidden } + langs.filter { it.hidden }, EXTRA_EXTENSIONS)
        val th = ArrayList<ThemeEntry>()
        for (o in MiniJson.asArray(manifest["themes"]) ?: emptyList()) {
            val m = MiniJson.asObject(o) ?: continue
            val id = m["id"] as? String ?: continue
            th += ThemeEntry(id, m["name"] as? String ?: id, (m["type"] as? String) != "light", m["file"] as? String ?: "$id.json", m["license"] as? String ?: "MIT")
        }
        themes = th.sortedBy { it.name.lowercase() }
        val gp = MiniJson.asObject(manifest["grammarPackage"]) ?: emptyMap()
        val tp = MiniJson.asObject(manifest["themePackage"]) ?: emptyMap()
        val lic = linkedSetOf<String>()
        (MiniJson.asArray(manifest["languages"]) ?: emptyList()).forEach { (MiniJson.asObject(it)?.get("license") as? String)?.let(lic::add) }
        th.forEach { it.license.let(lic::add) }
        packageInfo = PackageInfo(
            grammarName = gp["name"] as? String ?: "tm-grammars", grammarVersion = gp["version"] as? String ?: "?",
            themeName = tp["name"] as? String ?: "tm-themes", themeVersion = tp["version"] as? String ?: "?",
            engine = manifest["engine"] as? String ?: "vscode-textmate (C++ port) + Oniguruma",
            languageCount = langs.size, themeCount = th.size, licenses = lic.toList(),
        )
    }

    private val ownedPool: ExecutorService? = if (workerExecutor == null) Executors.newFixedThreadPool(2) { r ->
        // Normal priority on purpose: on Android a Java priority below 5 puts the thread into the background group (little cores,
        // a few percent of the CPU while the UI is busy), which made highlighting several times slower than the engine can run.
        // The frontier job is throttled by its own duty cycle instead (NativeHighlighter.pump).
        Thread(r, "tm-highlight").apply { isDaemon = true; priority = Thread.NORM_PRIORITY }
    } else null
    private val worker: Executor = workerExecutor ?: ownedPool!!

    private val registryLock = ReentrantReadWriteLock()
    private var registry = 0L                       // guarded by registryLock
    private var nativeOk: Boolean? = null
    private val live = CopyOnWriteArrayList<NativeHighlighter>()

    @Volatile private var colorMap: IntArray = IntArray(0)
    @Volatile private var themeInfo: ThemeInfo
    private var themeBytes: ByteArray = ByteArray(0)
    private var themeParsed: ThemeColors.Parsed

    init {
        val first = themes.firstOrNull { it.id == "dark-plus" } ?: themes.firstOrNull { it.isDark } ?: themes.firstOrNull()
        val (info, parsed, bytes) = loadTheme(first?.id)
        themeInfo = info; themeParsed = parsed; themeBytes = bytes
    }

    val theme: ThemeInfo get() = themeInfo

    fun languageById(id: String): LanguageInfo? = byId[id]

    /** By id, then by alias (case-insensitive): "py", "javascript", ... */
    fun languageByName(name: String): LanguageInfo? {
        byId[name]?.let { return it }
        val n = name.lowercase()
        return allLanguages.firstOrNull { it.id == n || it.name.lowercase() == n || it.aliases.any { a -> a.lowercase() == n } }
    }

    fun detectLanguage(fileName: String?, firstLine: String?): LanguageInfo? = detector.detect(fileName, firstLine)

    /**
     * Last resort when the name and first line say nothing: picks a bundled grammar from the first lines of the text
     * ([ContentSniffer]); the colouring itself is still the TextMate engine. Plain-text extensions are never sniffed.
     */
    fun guessLanguage(fileName: String?, firstLines: List<String>): LanguageInfo? {
        val key = LanguageAssoc.keyOf(fileName)
        if (key != null && key in NEVER_SNIFF) return null
        val id = ContentSniffer.guess(firstLines) ?: return null
        return byId[id]?.takeIf { it.scope != null }
    }

    /** Selects a bundled theme (any thread). Unknown id: unchanged. Every live highlighter is invalidated. */
    fun setTheme(themeId: String): ThemeInfo {
        if (themes.none { it.id == themeId }) return themeInfo
        val (info, parsed, bytes) = loadTheme(themeId)
        registryLock.writeLock().lock()
        try {
            themeInfo = info; themeParsed = parsed; themeBytes = bytes
            if (registry != 0L) applyThemeLocked()
        } finally { registryLock.writeLock().unlock() }
        val map = colorMap
        for (h in live) h.onThemeChanged(map)
        return info
    }

    /** Null: plain text, unknown language, or the native engine is unavailable. */
    fun createHighlighter(store: LineStore, languageId: String): SyntaxHighlighter? {
        val lang = byId[languageId] ?: return null
        val scope = lang.scope ?: return null
        val reg = ensureRegistry()
        if (reg == 0L) return null
        val h = NativeHighlighter(backend, reg, store, lang.id, scope, worker, uiExecutor, colorMap, maxTokenizeLineChars) { live.remove(it) }
        live.add(h)
        return h
    }

    /** True when the native engine could be loaded and the registry created. */
    val engineAvailable: Boolean get() = ensureRegistry() != 0L

    private fun ensureRegistry(): Long {
        registryLock.readLock().lock()
        try { if (registry != 0L) return registry } finally { registryLock.readLock().unlock() }
        registryLock.writeLock().lock()
        try {
            if (registry != 0L) return registry
            if (nativeOk == false) return 0L
            if (nativeOk == null) nativeOk = try { nativeAvailable() } catch (_: Throwable) { false }
            if (nativeOk != true) return 0L
            val provider = GrammarProvider { file -> assets.read(file) }
            val r = backend.createRegistry(provider, manifestBytes)
            if (r == 0L) { nativeOk = false; return 0L }
            registry = r
            applyThemeLocked()
            return r
        } finally { registryLock.writeLock().unlock() }
    }

    /** registryLock (write) held. */
    private fun applyThemeLocked() {
        val map = backend.setTheme(registry, themeBytes, themeParsed.defaultFg, themeParsed.defaultBg)
        if (map != null) colorMap = map
    }

    private fun loadTheme(id: String?): Triple<ThemeInfo, ThemeColors.Parsed, ByteArray> {
        val entry = themes.firstOrNull { it.id == id }
        val bytes = entry?.let { assets.read("themes/${it.file}") } ?: ByteArray(0)
        val parsed = ThemeColors.parse(if (bytes.isEmpty()) "{}".toByteArray() else bytes, if (entry?.isDark == false) "light" else "dark")
        return Triple(ThemeInfo(entry?.id ?: "default", entry?.name ?: "Default", parsed.colors.isDark, parsed.colors), parsed, bytes)
    }

    companion object {
        /** Extensions absent from the grammar package that hold a bundled language (e.g. exteraGram plugins are Python). */
        val EXTRA_EXTENSIONS: Map<String, String> = mapOf(".plugin" to "python", ".mcmeta" to "json")
        private val NEVER_SNIFF = setOf(".txt", ".text", ".csv", ".tsv", ".dat", ".bin", ".rtf")
    }

    override fun close() {
        for (h in live.toList()) h.close()
        registryLock.writeLock().lock()
        try {
            if (registry != 0L) { backend.destroyRegistry(registry); registry = 0L }
            nativeOk = false
        } finally { registryLock.writeLock().unlock() }
        ownedPool?.shutdownNow()
    }
}
