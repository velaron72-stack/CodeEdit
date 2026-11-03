package app.codeedit.highlight

import app.codeedit.core.util.MiniJson

/** The user's "extension -> language" choices ("remember for .plugin"), kept as one small JSON object in the settings (hl.assoc). */
object LanguageAssoc {
    private const val MAX_ENTRIES = 60
    private const val MAX_CHARS = 1900     // the settings schema accepts strings up to 2000 characters

    /** ".plugin" for "a.plugin", ".gitignore" for ".gitignore"; null when the name has no extension. */
    fun keyOf(fileName: String?): String? {
        val n = fileName?.substringAfterLast('/')?.trim()?.lowercase() ?: return null
        val i = n.lastIndexOf('.')
        if (i < 0 || i == n.length - 1) return null
        return n.substring(i)
    }

    fun parse(json: String?): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        if (json.isNullOrBlank()) return out
        try {
            val o = MiniJson.asObject(MiniJson.parse(json, false)) ?: return out
            for ((k, v) in o) if (k.startsWith(".") && v is String && v.isNotEmpty()) out[k.lowercase()] = v
        } catch (_: Throwable) { out.clear() }
        return out
    }

    fun get(json: String?, key: String): String? = parse(json)[key.lowercase()]

    /** Returns the new JSON; [languageId] == null removes the entry. */
    fun put(json: String?, key: String, languageId: String?): String {
        val m = parse(json)
        val k = key.lowercase()
        m.remove(k)
        if (languageId != null) m[k] = languageId
        while (m.size > MAX_ENTRIES) m.remove(m.keys.first())
        var s = MiniJson.write(LinkedHashMap<String, Any?>(m))
        while (s.length > MAX_CHARS && m.isNotEmpty()) { m.remove(m.keys.first()); s = MiniJson.write(LinkedHashMap<String, Any?>(m)) }
        return s
    }
}
