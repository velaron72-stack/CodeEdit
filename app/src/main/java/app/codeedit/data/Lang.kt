package app.codeedit.data

/** Tiny UI localisation: Russian (default for ru/uk/be locales) and English. Strings used from resources are in res/values*. */
object Lang {
    @Volatile var ru: Boolean = true
    fun init(localeLanguage: String, override: String) {
        ru = when (override) {
            "ru" -> true
            "en" -> false
            else -> localeLanguage == "ru" || localeLanguage == "uk" || localeLanguage == "be"
        }
    }
}

fun tr(ru: String, en: String): String = if (Lang.ru) ru else en
