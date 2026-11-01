package app.codeedit.highlight

/** Abstraction over [TmNative] so the highlighter logic can be unit-tested with a fake engine. */
interface TokenizerBackend {
    fun createRegistry(provider: GrammarProvider, manifestJson: ByteArray): Long
    fun destroyRegistry(registry: Long)
    fun setTheme(registry: Long, themeJson: ByteArray, defaultFg: Int, defaultBg: Int): IntArray?
    fun createSession(registry: Long, rootScope: String, maxLineChars: Int, checkpointStride: Int, maxCheckpoints: Int): Long
    fun destroySession(session: Long)
    fun lastError(): String?
    fun invalidateFrom(session: Long, line: Int)
    fun nearestCheckpoint(session: Long, line: Int): Int
    fun maxCheckpoint(session: Long): Int
    fun tokenize(session: Long, startLine: Int, chars: CharArray, offsets: IntArray, lineCount: Int,
                 emitFrom: Int, cold: Boolean, record: Boolean, timeLimitMs: Int): IntArray?
}

object NativeBackend : TokenizerBackend {
    override fun createRegistry(provider: GrammarProvider, manifestJson: ByteArray) = TmNative.createRegistry(provider, manifestJson)
    override fun destroyRegistry(registry: Long) = TmNative.destroyRegistry(registry)
    override fun setTheme(registry: Long, themeJson: ByteArray, defaultFg: Int, defaultBg: Int) = TmNative.setTheme(registry, themeJson, defaultFg, defaultBg)
    override fun createSession(registry: Long, rootScope: String, maxLineChars: Int, checkpointStride: Int, maxCheckpoints: Int) =
        TmNative.createSession(registry, rootScope, maxLineChars, checkpointStride, maxCheckpoints)
    override fun destroySession(session: Long) = TmNative.destroySession(session)
    override fun lastError(): String? = TmNative.lastError()
    override fun invalidateFrom(session: Long, line: Int) = TmNative.invalidateFrom(session, line)
    override fun nearestCheckpoint(session: Long, line: Int) = TmNative.nearestCheckpoint(session, line)
    override fun maxCheckpoint(session: Long) = TmNative.maxCheckpoint(session)
    override fun tokenize(session: Long, startLine: Int, chars: CharArray, offsets: IntArray, lineCount: Int,
                          emitFrom: Int, cold: Boolean, record: Boolean, timeLimitMs: Int) =
        TmNative.tokenize(session, startLine, chars, offsets, lineCount, emitFrom, cold, record, timeLimitMs)
}
