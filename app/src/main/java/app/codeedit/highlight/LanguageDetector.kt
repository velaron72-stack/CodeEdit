package app.codeedit.highlight

/** Picks a language for a file name / first line using the manifest (exact filenames, longest extension, first-line patterns). */
internal class LanguageDetector(languages: List<LanguageInfo>, extraExtensions: Map<String, String> = emptyMap()) {
    private val byFilename = HashMap<String, LanguageInfo>()
    private val byFilenameLower = HashMap<String, LanguageInfo>()
    private val byExtension = HashMap<String, LanguageInfo>()   // lower-case, with the leading dot
    private var maxExtLen = 0
    private val firstLine: List<Pair<Regex, LanguageInfo>>

    init {
        // manifest order wins on ties; visible languages win over hidden helpers
        val ordered = languages.filter { it.scope != null }.sortedBy { if (it.hidden) 1 else 0 }
        for (l in ordered) {
            for (f in l.filenames) { byFilename.putIfAbsent(f, l); byFilenameLower.putIfAbsent(f.lowercase(), l) }
            for (e in l.extensions) {
                val k = e.lowercase()
                if (byExtension.putIfAbsent(k, l) == null && k.length > maxExtLen) maxExtLen = k.length
            }
        }
        // extensions the grammar package does not list but that are known to hold one of its languages (manifest entries win)
        val byId = ordered.associateBy { it.id }
        for ((ext, id) in extraExtensions) {
            val l = byId[id] ?: continue
            val k = ext.lowercase()
            if (byExtension.putIfAbsent(k, l) == null && k.length > maxExtLen) maxExtLen = k.length
        }
        val fl = ArrayList<Pair<Regex, LanguageInfo>>()
        for (l in ordered) {
            val p = l.firstLine ?: continue
            try { fl += Regex(p) to l } catch (_: Throwable) { /* pattern written for another regex flavour: ignore */ }
        }
        firstLine = fl
    }

    fun detect(fileName: String?, firstLineText: String?): LanguageInfo? {
        if (fileName != null) {
            val name = fileName.substringAfterLast('/')
            byFilename[name]?.let { return it }
            byFilenameLower[name.lowercase()]?.let { return it }
            // longest matching extension: "x.d.ts" -> ".d.ts" before ".ts"
            val lower = name.lowercase()
            var dot = lower.indexOf('.')
            while (dot >= 0) {
                val ext = lower.substring(dot)
                if (ext.length <= maxExtLen) byExtension[ext]?.let { return it }
                dot = lower.indexOf('.', dot + 1)
            }
        }
        if (firstLineText != null) {
            val line = firstLineText.take(300)
            for ((re, l) in firstLine) if (re.containsMatchIn(line)) return l
        }
        return null
    }
}
