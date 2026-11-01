package app.codeedit.highlight

/** Called by the native engine (any thread) to load a file below `assets/textmate/`, e.g. "grammars/python.json". */
fun interface GrammarProvider {
    fun loadGrammar(file: String): ByteArray?
}
