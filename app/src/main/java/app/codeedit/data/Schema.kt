package app.codeedit.data

import app.codeedit.core.util.MiniJson

enum class SType { BOOL, INT, FLOAT, ENUM, STRING }

enum class Section(val ru: String, val en: String, val id: String) {
    APPEARANCE("Внешний вид", "Appearance", "appearance"),
    FONT("Шрифт", "Font", "font"),
    EDITOR("Редактор", "Editor", "editor"),
    HIGHLIGHT("Подсветка", "Syntax highlighting", "highlight"),
    SEARCH("Поиск", "Search", "search"),
    FILES("Файлы", "Files", "files"),
    FAB("Плавающая кнопка", "Floating button", "fab"),
    GESTURES("Жесты и клавиши", "Gestures & keys", "gestures"),
    TABS("Вкладки", "Tabs", "tabs");
    val title: String get() = tr(ru, en)
}

class Opt(val value: String, val ru: String, val en: String) { val label: String get() = tr(ru, en) }

class SDef(
    val key: String, val section: Section, val type: SType, val default: Any,
    val ru: String, val en: String,
    val descRu: String = "", val descEn: String = "",
    val min: Double = 0.0, val max: Double = 0.0, val step: Double = 0.0,
    val options: List<Opt> = emptyList(),
    val keywords: String = "",
    /** Hidden from the settings screen (still exported/imported). */
    val hidden: Boolean = false,
) {
    val title: String get() = tr(ru, en)
    val desc: String get() = tr(descRu, descEn)
}

/** Setting keys. */
object K {
    const val THEME_MODE = "ui.themeMode"; const val AMOLED = "ui.amoled"; const val DYNAMIC = "ui.dynamicColor"
    const val ACCENT = "ui.accent"; const val ANIM = "ui.animations"; const val DENSITY = "ui.density"; const val LANGUAGE = "ui.language"
    const val KEEP_ON = "ui.keepScreenOn"; const val HIGH_REFRESH = "ui.highRefresh"
    const val FONT_FAMILY = "font.family"; const val FONT_SIZE = "font.size"; const val FONT_MIN = "font.min"; const val FONT_MAX = "font.max"
    const val FONT_STEP = "font.step"; const val LINE_SPACING = "font.lineSpacing"; const val LETTER_SPACING = "font.letterSpacing"
    const val BOLD = "font.bold"; const val LIGATURES = "font.ligatures"
    const val LINE_NUMBERS = "editor.lineNumbers"; const val LN_BASE = "editor.lineNumberBase"; const val LN_DIGITS = "editor.lineNumberDigits"
    const val CUR_LINE = "editor.currentLine"; const val WRAP = "editor.wordWrap"; const val TAB_SIZE = "editor.tabSize"
    const val INSERT_SPACES = "editor.insertSpaces"; const val AUTO_INDENT = "editor.autoIndent"; const val AUTO_CLOSE = "editor.autoClose"
    const val MATCH_BRACKETS = "editor.matchBrackets"; const val WHITESPACE = "editor.whitespace"; const val GUIDES = "editor.indentGuides"
    const val FOLDING = "editor.folding"; const val CURSOR_STYLE = "editor.cursorStyle"; const val CURSOR_BLINK = "editor.cursorBlink"
    const val HL_ENABLED = "hl.enabled"; const val HL_DARK = "hl.themeDark"; const val HL_LIGHT = "hl.themeLight"
    const val HL_MAX_LINE = "hl.maxLineChars"; const val HL_MAX_MB = "hl.maxFileMb"; const val LANG_ASSOC = "hl.assoc"
    const val S_CASE = "search.matchCase"; const val S_WORD = "search.wholeWord"; const val S_REGEX = "search.regex"
    const val S_WRAP = "search.wrapAround"; const val S_HL_ALL = "search.highlightAll"; const val S_KEEP_OPEN = "search.keepOpen"
    const val S_USE_SEL = "search.useSelection"
    const val F_ENC = "files.defaultEncoding"; const val F_EOL = "files.defaultEol"; const val F_AUTOSAVE = "files.autosave"
    const val F_AUTOSAVE_SEC = "files.autosaveSeconds"; const val F_RESTORE = "files.restoreTabs"; const val F_BACKUP = "files.backups"
    const val F_FINAL_NL = "files.finalNewline"; const val F_CONFIRM = "files.confirmDangerous"; const val F_EDIT_MB = "files.editMaxMb"
    const val FAB_MODE = "fab.mode"; const val FAB_SIZE = "fab.size"; const val FAB_OPACITY = "fab.opacity"; const val FAB_SIDE = "fab.side"
    const val FAB_Y = "fab.y"; const val FAB_ON_RETURN = "fab.onReturn"
    const val G_PINCH = "gestures.pinchZoom"; const val G_FAST = "gestures.fastScroll"; const val G_DOUBLE = "gestures.doubleTapWord"
    const val G_HAPTIC = "gestures.haptic"; const val G_KEYS = "gestures.extraKeys"; const val G_KEYS_SET = "gestures.extraKeysSet"
    const val T_MAX = "tabs.max"; const val T_LIMIT = "tabs.limitBehavior"; const val T_CONFIRM = "tabs.confirmClose"
    const val T_MEM = "tabs.inMemory"; const val T_CLOSE = "tabs.afterClose"
}

object Schema {
    const val FORMAT = "codeedit-settings"
    const val VERSION = 1
    const val DEFAULT_KEYS = "Tab { } ( ) [ ] ; \" ' < > / \\ = : , ."

    private fun b(key: String, s: Section, d: Boolean, ru: String, en: String, dRu: String = "", dEn: String = "", kw: String = "") =
        SDef(key, s, SType.BOOL, d, ru, en, dRu, dEn, keywords = kw)
    private fun i(key: String, s: Section, d: Int, min: Int, max: Int, ru: String, en: String, dRu: String = "", dEn: String = "", step: Int = 1, kw: String = "", hidden: Boolean = false) =
        SDef(key, s, SType.INT, d, ru, en, dRu, dEn, min.toDouble(), max.toDouble(), step.toDouble(), keywords = kw, hidden = hidden)
    private fun f(key: String, s: Section, d: Float, min: Double, max: Double, step: Double, ru: String, en: String, dRu: String = "", dEn: String = "", kw: String = "", hidden: Boolean = false) =
        SDef(key, s, SType.FLOAT, d, ru, en, dRu, dEn, min, max, step, keywords = kw, hidden = hidden)
    private fun e(key: String, s: Section, d: String, ru: String, en: String, opts: List<Opt>, dRu: String = "", dEn: String = "", kw: String = "") =
        SDef(key, s, SType.ENUM, d, ru, en, dRu, dEn, options = opts, keywords = kw)
    private fun st(key: String, s: Section, d: String, ru: String, en: String, dRu: String = "", dEn: String = "", kw: String = "", hidden: Boolean = false) =
        SDef(key, s, SType.STRING, d, ru, en, dRu, dEn, keywords = kw, hidden = hidden)

    val encodings = listOf("UTF-8", "UTF-16LE", "UTF-16BE", "windows-1251", "windows-1252", "ISO-8859-1", "KOI8-R", "IBM866", "GBK", "Big5", "Shift_JIS", "EUC-KR")

    val all: List<SDef> = listOf(
        // ---- appearance
        e(K.THEME_MODE, Section.APPEARANCE, "system", "Тема интерфейса", "App theme",
            listOf(Opt("system", "Системная", "System"), Opt("light", "Светлая", "Light"), Opt("dark", "Тёмная", "Dark")), kw = "dark light тема"),
        b(K.AMOLED, Section.APPEARANCE, false, "AMOLED (чисто чёрный)", "AMOLED (pure black)", "Чёрный фон в тёмной теме", "Pure black background in dark theme"),
        b(K.DYNAMIC, Section.APPEARANCE, false, "Material You", "Material You", "Цвета из обоев (Android 12+)", "Colours from wallpaper (Android 12+)"),
        e(K.ACCENT, Section.APPEARANCE, "periwinkle", "Акцентный цвет", "Accent colour",
            listOf(Opt("periwinkle", "Васильковый", "Periwinkle"), Opt("blue", "Синий", "Blue"), Opt("teal", "Бирюзовый", "Teal"), Opt("green", "Зелёный", "Green"),
                Opt("orange", "Оранжевый", "Orange"), Opt("pink", "Розовый", "Pink"), Opt("purple", "Фиолетовый", "Purple"), Opt("red", "Красный", "Red")), kw = "color цвет"),
        b(K.ANIM, Section.APPEARANCE, true, "Анимации", "Animations"),
        e(K.DENSITY, Section.APPEARANCE, "normal", "Плотность интерфейса", "UI density",
            listOf(Opt("compact", "Компактная", "Compact"), Opt("normal", "Обычная", "Normal"), Opt("comfortable", "Свободная", "Comfortable"))),
        e(K.LANGUAGE, Section.APPEARANCE, "auto", "Язык интерфейса", "Interface language",
            listOf(Opt("auto", "Как в системе", "System"), Opt("ru", "Русский", "Russian"), Opt("en", "English", "English")), kw = "language язык"),
        b(K.KEEP_ON, Section.APPEARANCE, false, "Не гасить экран", "Keep screen on"),
        b(K.HIGH_REFRESH, Section.APPEARANCE, true, "Максимальная частота экрана (90/120 Гц)", "Highest screen refresh rate (90/120 Hz)",
            "Просит у системы самый быстрый режим экрана, пока приложение открыто", "Asks the system for the fastest display mode while the app is open", kw = "fps hz герц частота плавность smooth"),

        // ---- font
        st(K.FONT_FAMILY, Section.FONT, "jetbrains-mono", "Шрифт", "Font family", kw = "font шрифт ttf otf import импорт", hidden = true),
        f(K.FONT_SIZE, Section.FONT, 14f, 6.0, 48.0, 0.5, "Размер шрифта (по умолчанию)", "Default font size", "Масштаб вкладки pinch-жестом хранится отдельно", "Per-tab pinch zoom is stored separately", kw = "size размер zoom"),
        f(K.FONT_MIN, Section.FONT, 6f, 4.0, 20.0, 1.0, "Минимальный размер", "Minimum size"),
        f(K.FONT_MAX, Section.FONT, 48f, 20.0, 96.0, 1.0, "Максимальный размер", "Maximum size"),
        f(K.FONT_STEP, Section.FONT, 1f, 0.5, 4.0, 0.5, "Шаг изменения размера", "Size step", "Для кнопок «+/−» в меню", "For the +/- buttons in the menu"),
        f(K.LINE_SPACING, Section.FONT, 1.0f, 0.8, 2.5, 0.05, "Межстрочный интервал", "Line spacing", "1.0 — естественная высота строки шрифта", "1.0 = the font's natural line height", kw = "line height интервал высота строки"),
        f(K.LETTER_SPACING, Section.FONT, 0f, -0.1, 0.5, 0.01, "Межбуквенный интервал", "Letter spacing"),
        b(K.BOLD, Section.FONT, false, "Жирный текст", "Bold text"),
        b(K.LIGATURES, Section.FONT, false, "Лигатуры", "Ligatures", "Для шрифтов с лигатурами (JetBrains Mono)", "For ligature fonts (JetBrains Mono)"),

        // ---- editor
        b(K.LINE_NUMBERS, Section.EDITOR, true, "Нумерация строк", "Line numbers"),
        e(K.LN_BASE, Section.EDITOR, "1", "Нумерация с", "Numbering starts at", listOf(Opt("1", "1", "1"), Opt("0", "0", "0"))),
        i(K.LN_DIGITS, Section.EDITOR, 2, 1, 8, "Минимальная ширина номера (цифр)", "Minimum number width (digits)"),
        b(K.CUR_LINE, Section.EDITOR, true, "Подсвечивать текущую строку", "Highlight current line"),
        b(K.WRAP, Section.EDITOR, false, "Перенос строк", "Word wrap"),
        i(K.TAB_SIZE, Section.EDITOR, 4, 1, 16, "Размер табуляции", "Tab size"),
        b(K.INSERT_SPACES, Section.EDITOR, true, "Пробелы вместо табуляции", "Insert spaces for Tab"),
        b(K.AUTO_INDENT, Section.EDITOR, true, "Автоотступы", "Auto-indent"),
        b(K.AUTO_CLOSE, Section.EDITOR, true, "Автозакрытие скобок и кавычек", "Auto-close brackets and quotes"),
        b(K.MATCH_BRACKETS, Section.EDITOR, true, "Подсветка парных скобок", "Match brackets"),
        b(K.WHITESPACE, Section.EDITOR, false, "Показывать невидимые символы", "Show whitespace"),
        b(K.GUIDES, Section.EDITOR, true, "Направляющие отступов", "Indent guides"),
        b(K.FOLDING, Section.EDITOR, true, "Сворачивание блоков", "Code folding"),
        e(K.CURSOR_STYLE, Section.EDITOR, "bar", "Стиль курсора", "Cursor style",
            listOf(Opt("bar", "Линия", "Bar"), Opt("block", "Блок", "Block"), Opt("underline", "Подчёркивание", "Underline"))),
        b(K.CURSOR_BLINK, Section.EDITOR, true, "Мигание курсора", "Cursor blink"),

        // ---- highlight
        b(K.HL_ENABLED, Section.HIGHLIGHT, true, "Подсветка синтаксиса", "Syntax highlighting", "Автоопределение языка по имени, расширению и первой строке", "Language auto-detection by name, extension and first line"),
        st(K.HL_DARK, Section.HIGHLIGHT, "dark-plus", "Тема подсветки (тёмная)", "Highlight theme (dark)", hidden = true),
        st(K.HL_LIGHT, Section.HIGHLIGHT, "light-plus", "Тема подсветки (светлая)", "Highlight theme (light)", hidden = true),
        i(K.HL_MAX_LINE, Section.HIGHLIGHT, 10_000, 200, 200_000, "Макс. длина подсвечиваемой строки", "Max highlighted line length", "Более длинные строки не разбираются (защита от лагов)", "Longer lines are not tokenized (protects against lag)", step = 100),
        i(K.HL_MAX_MB, Section.HIGHLIGHT, 4096, 1, 8192, "Подсвечивать файлы до (МБ)", "Highlight files up to (MB)", "Для больших файлов подсвечивается только видимая область", "Large files are highlighted lazily around the viewport", step = 16),
        st(K.LANG_ASSOC, Section.HIGHLIGHT, "{}", "Запомненные языки по расширению", "Remembered language per extension", hidden = true),

        // ---- search
        b(K.S_CASE, Section.SEARCH, false, "Учитывать регистр по умолчанию", "Match case by default"),
        b(K.S_WORD, Section.SEARCH, false, "Целые слова по умолчанию", "Whole words by default"),
        b(K.S_REGEX, Section.SEARCH, false, "Регулярные выражения по умолчанию", "Regex by default"),
        b(K.S_WRAP, Section.SEARCH, true, "Циклический переход", "Wrap around"),
        b(K.S_HL_ALL, Section.SEARCH, true, "Подсвечивать все совпадения", "Highlight all matches"),
        b(K.S_KEEP_OPEN, Section.SEARCH, true, "Панель остаётся открытой при переходе", "Keep panel open while navigating"),
        b(K.S_USE_SEL, Section.SEARCH, true, "Подставлять выделенный текст в поиск", "Prefill search with selection"),

        // ---- files
        e(K.F_ENC, Section.FILES, "auto", "Кодировка по умолчанию", "Default encoding",
            listOf(Opt("auto", "Определять автоматически", "Auto-detect")) + encodings.map { Opt(it, it, it) },
            "Применяется к новым файлам; для существующих кодировка определяется автоматически", "New files; existing files are auto-detected"),
        e(K.F_EOL, Section.FILES, "lf", "Окончания строк для новых файлов", "Line endings for new files",
            listOf(Opt("lf", "LF (Unix)", "LF (Unix)"), Opt("crlf", "CRLF (Windows)", "CRLF (Windows)"), Opt("cr", "CR (старый Mac)", "CR (classic Mac)"))),
        e(K.F_AUTOSAVE, Section.FILES, "drafts", "Автосохранение", "Autosave",
            listOf(Opt("off", "Выключено", "Off"), Opt("drafts", "Только черновики", "Drafts only"), Opt("file", "В сам файл", "Into the file")),
            "Черновики хранятся внутри приложения и не меняют исходный файл", "Drafts are stored inside the app and never touch the original file"),
        i(K.F_AUTOSAVE_SEC, Section.FILES, 5, 1, 600, "Интервал автосохранения (с)", "Autosave interval (s)"),
        b(K.F_RESTORE, Section.FILES, true, "Восстанавливать вкладки при запуске", "Restore tabs on launch"),
        b(K.F_BACKUP, Section.FILES, false, "Резервная копия перед сохранением", "Backup before saving", "Копия исходника в папке приложения (последние 5)", "Copy of the original in app storage (last 5)"),
        b(K.F_FINAL_NL, Section.FILES, false, "Добавлять перевод строки в конце файла", "Ensure final newline"),
        b(K.F_CONFIRM, Section.FILES, true, "Предупреждать об опасных операциях", "Warn about dangerous operations", "Замена во всём файле, закрытие с несохранёнными изменениями", "Replace all, closing with unsaved changes"),
        i(K.F_EDIT_MB, Section.FILES, 24, 1, 512, "Редактирование целиком до (МБ)", "Full editing up to (MB)", "Файлы больше открываются в режиме быстрого просмотра; режим можно переключить вручную", "Larger files open in fast view mode; you can switch manually", kw = "large большие режим mode"),

        // ---- floating button
        e(K.FAB_MODE, Section.FAB, "external", "Показывать кнопку возврата", "Show return button",
            listOf(Opt("external", "Для файлов из других приложений", "For files opened from other apps"), Opt("always", "Всегда", "Always"), Opt("never", "Никогда", "Never")),
            "Работает без разрешения «поверх других приложений»", "Works without the \"draw over other apps\" permission"),
        i(K.FAB_SIZE, Section.FAB, 48, 32, 80, "Размер кнопки (dp)", "Button size (dp)"),
        f(K.FAB_OPACITY, Section.FAB, 0.85f, 0.2, 1.0, 0.05, "Прозрачность", "Opacity"),
        e(K.FAB_SIDE, Section.FAB, "right", "Сторона привязки", "Snap side", listOf(Opt("left", "Слева", "Left"), Opt("right", "Справа", "Right"))),
        f(K.FAB_Y, Section.FAB, 0.55f, 0.0, 1.0, 0.01, "Положение по высоте", "Vertical position", hidden = true),
        e(K.FAB_ON_RETURN, Section.FAB, "draft", "При возврате", "On return",
            listOf(Opt("draft", "Сохранить черновик", "Keep a draft"), Opt("save", "Сохранить в файл", "Save into the file")),
            "«В файл» записывает изменения в исходный документ перед возвратом", "\"Into the file\" writes changes to the original document before returning"),

        // ---- gestures
        b(K.G_PINCH, Section.GESTURES, true, "Масштабирование щипком", "Pinch to zoom"),
        b(K.G_FAST, Section.GESTURES, true, "Быстрая прокрутка (ползунок)", "Fast-scroll thumb"),
        b(K.G_DOUBLE, Section.GESTURES, true, "Двойное касание выделяет слово", "Double tap selects a word"),
        b(K.G_HAPTIC, Section.GESTURES, true, "Тактильный отклик", "Haptic feedback"),
        b(K.G_KEYS, Section.GESTURES, true, "Дополнительный ряд клавиш", "Extra keys row"),
        st(K.G_KEYS_SET, Section.GESTURES, DEFAULT_KEYS, "Клавиши доп. ряда", "Extra keys layout",
            "Через пробел. Служебные: Tab Esc Left Right Up Down Home End PgUp PgDn Undo Redo Ctrl+<буква>. Остальное вставляется как текст.",
            "Space separated. Special: Tab Esc Left Right Up Down Home End PgUp PgDn Undo Redo Ctrl+<letter>. Anything else is inserted as text.", kw = "keys клавиши"),

        // ---- tabs
        i(K.T_MAX, Section.TABS, 30, 1, 200, "Максимум вкладок", "Maximum tabs"),
        e(K.T_LIMIT, Section.TABS, "closeOldest", "При превышении лимита", "When the limit is reached",
            listOf(Opt("closeOldest", "Закрыть самую старую без изменений", "Close the oldest unmodified"), Opt("block", "Не открывать новые", "Refuse to open more"))),
        b(K.T_CONFIRM, Section.TABS, true, "Подтверждать закрытие с изменениями", "Confirm closing modified tabs"),
        i(K.T_MEM, Section.TABS, 4, 1, 20, "Вкладок в памяти одновременно", "Tabs kept in memory", "Остальные выгружаются (несохранённое уходит в черновик)", "Others are unloaded (unsaved edits go to a draft)"),
        e(K.T_CLOSE, Section.TABS, "neighbor", "После закрытия активной вкладки", "After closing the active tab",
            listOf(Opt("neighbor", "Соседняя", "Neighbour"), Opt("recent", "Последняя использованная", "Most recently used"))),
    )

    val byKey: Map<String, SDef> = all.associateBy { it.key }

    fun default(key: String): Any = byKey[key]?.default ?: error("unknown setting $key")

    /** Coerces/validates one value; returns null when it is not acceptable for the definition. */
    fun coerce(def: SDef, raw: Any?): Any? {
        when (def.type) {
            SType.BOOL -> return raw as? Boolean
            SType.INT -> {
                val n = (raw as? Number) ?: return null
                val d = n.toDouble()
                if (d != Math.floor(d) || d.isNaN() || d.isInfinite()) return null
                if (d < def.min || d > def.max) return null
                return n.toInt()
            }
            SType.FLOAT -> {
                val n = (raw as? Number) ?: return null
                val d = n.toDouble()
                if (d.isNaN() || d.isInfinite() || d < def.min - 1e-9 || d > def.max + 1e-9) return null
                return d.toFloat()
            }
            SType.ENUM -> { val s = raw as? String ?: return null; return if (def.options.any { it.value == s }) s else null }
            SType.STRING -> { val s = raw as? String ?: return null; return if (s.length <= 2000) s else null }
        }
    }

    class ImportResult(val values: Map<String, Any>, val warnings: List<String>, val errors: List<String>) { val ok: Boolean get() = errors.isEmpty() }

    fun export(values: Map<String, Any>, appVersion: String): ByteArray {
        val settings = LinkedHashMap<String, Any?>()
        for (d in all) settings[d.key] = values[d.key] ?: d.default
        val root = LinkedHashMap<String, Any?>()
        root["format"] = FORMAT; root["version"] = VERSION; root["app"] = appVersion; root["settings"] = settings
        return MiniJson.write(root, pretty = true).toByteArray(Charsets.UTF_8)
    }

    /** Validates a whole file; nothing is applied unless errors is empty. Unknown keys are only warnings. */
    fun parseImport(bytes: ByteArray): ImportResult {
        val errors = ArrayList<String>(); val warnings = ArrayList<String>()
        val root = try { MiniJson.asObject(MiniJson.parse(bytes, false)) } catch (t: Throwable) {
            return ImportResult(emptyMap(), warnings, listOf(tr("Некорректный JSON: ", "Invalid JSON: ") + (t.message ?: ""))) }
        if (root == null) return ImportResult(emptyMap(), warnings, listOf(tr("Ожидался JSON-объект", "A JSON object was expected")))
        if (root["format"] != FORMAT) errors += tr("Это не файл настроек Code Edit (format)", "Not a Code Edit settings file (format)")
        val ver = (root["version"] as? Number)?.toInt()
        if (ver == null) errors += tr("Нет номера версии", "Missing version")
        else if (ver > VERSION) errors += tr("Файл создан более новой версией ($ver > $VERSION)", "File was created by a newer version ($ver > $VERSION)")
        else if (ver < 1) errors += tr("Некорректная версия $ver", "Invalid version $ver")
        val s = MiniJson.asObject(root["settings"])
        if (s == null) errors += tr("Нет раздела settings", "Missing settings section")
        val out = LinkedHashMap<String, Any>()
        if (s != null) for ((k, v) in s) {
            val def = byKey[k]
            if (def == null) { warnings += tr("Неизвестный параметр пропущен: $k", "Unknown setting ignored: $k"); continue }
            val c = coerce(def, v)
            if (c == null) errors += tr("Недопустимое значение для $k: $v", "Invalid value for $k: $v") else out[k] = c
        }
        // cross-field checks
        val mn = (out[K.FONT_MIN] as? Float) ?: default(K.FONT_MIN) as Float
        val mx = (out[K.FONT_MAX] as? Float) ?: default(K.FONT_MAX) as Float
        val sz = (out[K.FONT_SIZE] as? Float) ?: default(K.FONT_SIZE) as Float
        if (mn >= mx) errors += tr("font.min должен быть меньше font.max", "font.min must be below font.max")
        else if (sz < mn || sz > mx) errors += tr("font.size вне диапазона min..max", "font.size outside min..max")
        return ImportResult(out, warnings, errors)
    }
}

/** Immutable snapshot of all settings with typed access (falls back to the schema default). */
class Settings(val values: Map<String, Any> = emptyMap()) {
    fun bool(k: String): Boolean = values[k] as? Boolean ?: Schema.default(k) as Boolean
    fun int(k: String): Int = (values[k] as? Number)?.toInt() ?: Schema.default(k) as Int
    fun float(k: String): Float = (values[k] as? Number)?.toFloat() ?: Schema.default(k) as Float
    fun str(k: String): String = values[k] as? String ?: Schema.default(k) as String
    fun with(k: String, v: Any): Settings { val m = HashMap(values); m[k] = v; return Settings(m) }
    fun withAll(o: Map<String, Any>): Settings { val m = HashMap(values); m.putAll(o); return Settings(m) }
    override fun equals(other: Any?): Boolean = other is Settings && other.values == values
    override fun hashCode(): Int = values.hashCode()
}
