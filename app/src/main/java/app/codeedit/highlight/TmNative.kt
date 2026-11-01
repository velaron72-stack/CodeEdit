package app.codeedit.highlight

/**
 * JNI surface of the native TextMate engine (`libcodeedit_native.so`, see app/src/main/cpp and docs/NATIVE.md).
 * The functions are NOT thread-safe per session/registry: [NativeBackend] callers serialise access.
 */
object TmNative {
    @Volatile private var loaded = false
    @Volatile private var loadError: Throwable? = null

    /** Loads the library once. Returns false (and keeps the reason in [loadFailure]) if it cannot be loaded. */
    @JvmStatic @Synchronized
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        if (loadError != null) return false
        try {
            val path = System.getProperty("codeedit.native.lib")   // host tests: absolute path of the CMake host build
            if (path != null) System.load(path) else System.loadLibrary("codeedit_native")
            loaded = true
        } catch (t: Throwable) {
            loadError = t
        }
        return loaded
    }

    val loadFailure: Throwable? get() = loadError

    @JvmStatic external fun nativeVersion(): String
    /** Reads languages[].scope/file/injectTo from the manifest; grammar files come through [provider]. 0 on failure ([lastError]). */
    @JvmStatic external fun createRegistry(provider: GrammarProvider, manifestJson: ByteArray): Long
    @JvmStatic external fun destroyRegistry(registry: Long)
    /** Theme JSON (tokenColors/settings). Returns the ARGB colour table (index = foreground id in token meta) or null. */
    @JvmStatic external fun setTheme(registry: Long, themeJson: ByteArray, defaultFg: Int, defaultBg: Int): IntArray?
    /** 0 on failure; then [lastError] has the reason. */
    @JvmStatic external fun createSession(registry: Long, rootScope: String, maxLineChars: Int, checkpointStride: Int, maxCheckpoints: Int): Long
    @JvmStatic external fun destroySession(session: Long)
    @JvmStatic external fun lastError(): String?
    @JvmStatic external fun invalidateFrom(session: Long, line: Int)
    @JvmStatic external fun nearestCheckpoint(session: Long, line: Int): Int
    @JvmStatic external fun maxCheckpoint(session: Long): Int
    @JvmStatic external fun checkpointCount(session: Long): Int
    /**
     * Line i = chars[offsets[i] until offsets[i+1]]. Start state = checkpoint at [startLine] (0 = document start); none and
     * !cold => null; cold => approximate start, nothing recorded. Emits [n, start0, meta0, ...] per line with index >= [emitFrom].
     */
    @JvmStatic external fun tokenize(
        session: Long, startLine: Int, chars: CharArray, offsets: IntArray, lineCount: Int,
        emitFrom: Int, cold: Boolean, record: Boolean, timeLimitMs: Int,
    ): IntArray?
}
