package app.codeedit.highlight

/**
 * Chooses a grammar for a file whose name gives no answer (no/unknown extension, e.g. `.plugin`, `document:1234`) from the first
 * lines of its text. This only PICKS one of the bundled TextMate grammars; all colouring is still done by the TextMate engine.
 *
 * Deliberately conservative: it answers null unless one language clearly wins, so prose, logs, Markdown (fenced code) and YAML
 * (which often embeds shell) stay plain text. Measured on ~3900 real files (see docs/HIGHLIGHT.md): ~0.1 % wrong answers on source
 * files; everything it is unsure about is "null". Returned values are language ids of the bundled manifest.
 */
object ContentSniffer {
    const val MAX_LINES = 80
    const val MAX_CHARS = 6000
    private const val MIN_SCORE = 6.0
    private const val MIN_MARGIN = 3.0

    private class Rule(val re: Regex, val weight: Double, val maxHits: Int)

    private fun r(pattern: String, weight: Double, maxHits: Int = 1, ci: Boolean = false) =
        Rule(Regex(pattern, if (ci) setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE) else setOf(RegexOption.MULTILINE)), weight, maxHits)

    private val python = listOf(
        r("^[ \\t]*(?:from[ \\t]+[\\w.]+[ \\t]+import[ \\t]+\\S.*|import[ \\t]+[\\w.]+(?:[ \\t]+as[ \\t]+\\w+)?(?:[ \\t]*,[ \\t]*[\\w.]+)*)[ \\t]*\$", 2.0, 4),
        r("^[ \\t]*(?:async[ \\t]+)?def[ \\t]+\\w+[ \\t]*\\(.*\\)[ \\t]*(?:->[^:]+)?:[ \\t]*(?:#.*)?\$", 3.0, 3),
        r("^[ \\t]*class[ \\t]+\\w+[ \\t]*(?:\\([^)]*\\))?[ \\t]*:[ \\t]*(?:#.*)?\$", 3.0, 2),
        r("^[ \\t]*(?:elif\\b.*|else|try|finally|except\\b.*|with\\b.*|for\\b.*\\bin\\b.*|while\\b.*|if\\b.*)[ \\t]*:[ \\t]*(?:#.*)?\$", 1.5, 4),
        r("\\bif[ \\t]+__name__[ \\t]*==[ \\t]*[\"']__main__[\"']", 6.0),
        r("^[ \\t]*__\\w+__[ \\t]*=", 3.0, 2),
        r("\\bself\\.\\w+", 1.0, 3),
        r("^[ \\t]*@\\w[\\w.]*(?:\\(.*\\))?[ \\t]*\$", 1.0, 2),
        r("\\b(?:None|True|False)\\b", 0.5, 4),
    )

    private val javascript = listOf(
        r("^[ \\t]*(?:const|let|var)[ \\t]+[\\w\$]+[ \\t]*=", 2.0, 4),
        r("\\bfunction\\b[ \\t]*[\\w\$]*[ \\t]*\\(", 2.0, 3),
        r("=>", 1.5, 3),
        r("^[ \\t]*(?:import[ \\t]+.+[ \\t]+from[ \\t]+[\"'][^\"']+[\"']|import[ \\t]+[\"'][^\"']+[\"'])[ \\t]*;?[ \\t]*\$", 3.0, 2),
        r("\\brequire\\([ \\t]*[\"']", 3.0, 2),
        r("\\bmodule\\.exports\\b|\\bexports\\.\\w+[ \\t]*=", 4.0),
        r("\\bconsole\\.(?:log|warn|error|info)\\(", 3.0, 2),
        r("^[ \\t]*export[ \\t]+(?:default|const|function|class|async)\\b", 3.0),
        r("===|!==", 1.5, 2),
    )
    private val typescriptMarkers = listOf(
        r("^[ \\t]*(?:export[ \\t]+)?(?:declare[ \\t]+)?interface[ \\t]+\\w+[^{\\n]*\\{", 3.0, 2),
        r("^[ \\t]*(?:export[ \\t]+)?(?:declare[ \\t]+)?type[ \\t]+\\w+(?:<[^>\\n]*>)?[ \\t]*=", 3.0, 2),
        r("^[ \\t]*(?:export[ \\t]+)?declare[ \\t]+(?:function|const|let|var|class|namespace|module|global|enum|abstract)\\b", 3.0, 2),
        r("^[ \\t]*import[ \\t]+type\\b|^[ \\t]*export[ \\t]+type[ \\t]*\\{", 3.0),
        r(":[ \\t]*(?:string|number|boolean|void|any|unknown|never)\\b", 1.0, 3),
        r("\\bas[ \\t]+(?:string|number|any|unknown|const)\\b", 1.0, 2),
        r("^[ \\t]*(?:public|private|protected)[ \\t]+(?:static[ \\t]+)?(?:readonly[ \\t]+)?\\w+\\??[ \\t]*:[ \\t]*\\w", 2.0, 2),
    )

    private val shell = listOf(
        r("^[ \\t]*(?:echo|export(?![ \\t]+(?:[{*]|(?:default|const|let|var|function|class|interface|type|enum|async|declare|abstract|namespace|from)\\b))|cd|chmod|chown|mkdir|rm|cp|mv|sudo|apt(?:-get)?|pkg|pip3?|npm|git|curl|wget|source|exit)[ \\t]", 1.5, 5),
        r("^[ \\t]*(?:fi|done|esac|then|do)[ \\t]*\$|^[ \\t]*if[ \\t]+\\[\\[?[ \\t].*\\]\\]?[ \\t]*;[ \\t]*then", 3.0, 3),
        r("\\$\\{?\\w+\\}?", 0.7, 4),
        r("^[ \\t]*[A-Z_][A-Z0-9_]*=\\S*", 1.0, 3),
    )

    private val cFamily = listOf(
        r("^[ \\t]*#[ \\t]*include[ \\t]*[<\"][^>\"]+[>\"]", 4.0, 3),
        r("^[ \\t]*#[ \\t]*(?:define|ifdef|ifndef|endif|pragma)\\b", 2.0, 2),
        r("\\bint[ \\t]+main[ \\t]*\\(", 4.0),
        r("\\b(?:printf|scanf|malloc|free|sizeof)[ \\t]*\\(", 1.0, 3),
    )
    private val cppMarkers = Regex("\\bstd::\\w+|\\bnamespace[ \\t]+\\w+|\\btemplate[ \\t]*<|\\bcout[ \\t]*<<|#[ \\t]*include[ \\t]*<(?:iostream|vector|string|map|memory)>|\\bclass[ \\t]+\\w+[ \\t]*(?::[ \\t]*(?:public|private|protected)[ \\t]+\\w+[ \\t]*)?\\{")

    private val java = listOf(
        r("^[ \\t]*package[ \\t]+[\\w.]+[ \\t]*;", 4.0),
        r("^[ \\t]*import[ \\t]+(?:static[ \\t]+)?[\\w.]+(?:\\.\\*)?[ \\t]*;", 3.0, 3),
        r("\\b(?:public|private|protected)[ \\t]+(?:static[ \\t]+)?(?:final[ \\t]+)?(?:class|interface|enum|void|[A-Z]\\w*(?:<.*>)?)[ \\t]+\\w+", 2.5, 3),
        r("System\\.out\\.print(?:ln)?\\(", 4.0),
        r("@Override\\b", 2.0),
    )

    private val kotlin = listOf(
        r("^[ \\t]*package[ \\t]+[\\w.]+[ \\t]*\$", 3.0),
        r("^[ \\t]*import[ \\t]+(?:kotlin|kotlinx|android|androidx|java|javax|org|com)\\.[\\w.]+(?:\\.\\*)?[ \\t]*\$", 3.0, 3),
        r("\\bfun[ \\t]+(?:<.*>[ \\t]*)?[\\w.]+[ \\t]*\\(", 4.0, 3),
        r("^[ \\t]*(?:private[ \\t]+|internal[ \\t]+|public[ \\t]+)?(?:val|var)[ \\t]+\\w+(?:[ \\t]*:[ \\t]*[\\w<>?,. ]+)?[ \\t]*=", 1.5, 3),
        r("\\b(?:data[ \\t]+class|companion[ \\t]+object|sealed[ \\t]+class|object[ \\t]+\\w+[ \\t]*[:{])", 3.0, 2),
        r("\\bwhen[ \\t]*(?:\\(.*\\))?[ \\t]*\\{", 2.0),
    )

    private val csharp = listOf(
        r("^[ \\t]*using[ \\t]+[\\w.]+[ \\t]*;", 4.0, 3),
        r("^[ \\t]*namespace[ \\t]+[\\w.]+[ \\t]*(?:\\{|;)?[ \\t]*\$", 3.0),
        r("\\bConsole\\.Write(?:Line)?\\(", 4.0),
        r("\\b(?:public|private|internal|protected)[ \\t]+(?:static[ \\t]+)?(?:override[ \\t]+|virtual[ \\t]+|sealed[ \\t]+|abstract[ \\t]+)?(?:class|void|string|int|bool|float)\\b", 2.5, 3),
        r("\\[(?:SerializeField|Header|Range|Tooltip)\\b|:[ \\t]*MonoBehaviour\\b", 4.0),
        r("\\bvar[ \\t]+\\w+[ \\t]*=[ \\t]*new\\b", 2.0),
    )

    private val lua = listOf(
        r("^[ \\t]*local[ \\t]+(?:function\\b|\\w+(?:[ \\t]*,[ \\t]*\\w+)*[ \\t]+=(?!=))", 3.0, 3),
        r("^[ \\t]*end[ \\t]*[,)]*[ \\t]*\$", 3.0, 3),
        r("\\bfunction[ \\t]+[\\w.:]+[ \\t]*\\(.*\\)[ \\t]*\$", 2.0, 2),
        r("\\b(?:then|do)[ \\t]*\$", 2.0, 3),
        r("~=", 2.0),
        r("^[ \\t]*--(?!\\[\\[).*", 1.0, 3),
        r("\\bnil\\b", 1.0, 3),
        r("\\belseif\\b", 3.0),
    )

    // `class Foo {`, `export interface X {`, `else {` look like CSS selectors and `id: number;` like a declaration: keyword-led selectors,
    // assignments (`x = {`) and type-looking values are excluded so other languages do not collect CSS points.
    // (No `(?:a|b)*` loops over long text: java.util.regex recurses once per iteration and overflows the stack on long lines.)
    private const val CODE_KW = "export|import|public|private|protected|internal|static|final|abstract|sealed|override|class|interface|enum|struct|union|" +
        "namespace|object|module|function|fun|func|def|else|try|finally|do|switch|default|case|extern|template|trait|impl|async|const|let|var|val|" +
        "if|for|while|catch|return|new|package|data|companion|init|constructor|type|typedef|using|where|local|repeat|begin|then"
    private const val TYPE_WORDS = "string|number|boolean|void|any|unknown|never|object|null|undefined|int|float|double|bool|char|long|byte|short|String|Int|Long|Boolean"
    private val cssDecl = r("^[ \\t]*(?!(?:default|case|public|private|protected|else|try|finally|do|goto)\\b)[a-z-]+[ \\t]*:(?!:)(?![ \\t]*(?:$TYPE_WORDS)\\b)[ \\t]*[^;{}]+;[ \\t]*\$", 1.5, 5)
    private val css = listOf(
        r("^[ \\t]*(?!(?:$CODE_KW)\\b)(?![^\\n{]*[ \\t]=)[.#@]?[\\w-][\\w.#:\\s>+~,*\\[\\]=\"'-]*\\{[ \\t]*\$", 2.0, 4),
        cssDecl,
        r("^[ \\t]*@(?:media|font-face|keyframes|import|supports|charset|page)\\b", 3.0, 2),
        r("^[ \\t]*\\}[ \\t]*\$", 0.5, 3),
    )

    private val sql = listOf(
        r("^[ \\t]*(?:SELECT|INSERT[ \\t]+INTO|UPDATE|DELETE[ \\t]+FROM|CREATE[ \\t]+(?:TABLE|INDEX|VIEW)|ALTER[ \\t]+TABLE|DROP[ \\t]+TABLE)\\b", 4.0, 3, ci = true),
        r("\\b(?:FROM|WHERE|GROUP[ \\t]+BY|ORDER[ \\t]+BY|JOIN|VALUES)\\b", 1.0, 4, ci = true),
    )

    private val jsonKey = Regex("\"[^\"\\n]{1,200}\"[ \\t]*:")
    private val tag = Regex("</?[A-Za-z][\\w:.-]*(?:[ \\t\\r\\n][^<>]*)?/?>")
    private val htmlTag = Regex("</?(?:html|head|body|div|span|p|a|script|style|ul|li|table|tr|td|h[1-6]|img|br|form|input|button|meta|link)\\b", RegexOption.IGNORE_CASE)

    private val fence = Regex("^[ \\t]*(?:```|~~~)", RegexOption.MULTILINE)
    private val blockOpener = Regex("^(?:else|try|finally|default|do|case)\\b")

    private val yamlLine = Regex("^[ \\t]*(?:-[ \\t]+)?[A-Za-z_][\\w.-]*:(?:[ \\t]+\\S.*|[ \\t]*)\$", RegexOption.MULTILINE)

    private fun yamlish(line: String): Boolean {
        val v = line.trimEnd()
        if (blockOpener.containsMatchIn(v.trimStart())) return false
        val last = v.lastOrNull() ?: return false
        return last != ';' && last != ',' && last != '{' && last != '[' && last != '(' && !v.contains(" = ")
    }

    /**
     * A YAML document: it starts with `---` or a `key:` line and has several such lines. (Not Python `else:` / `x: int = 1`,
     * not CSS `color: red;`, not a JS object `a: 1,`.) Such text often embeds shell or code and must not be sniffed as that.
     */
    private fun looksLikeYaml(text: String): Boolean {
        val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return false
        val startsLikeYaml = first == "---" || first.startsWith("--- ") || (yamlLine.matches(first) && yamlish(first))
        if (!startsLikeYaml) return false
        var keys = 0
        for (m in yamlLine.findAll(text)) if (yamlish(m.value) && ++keys >= 3) return true
        return false
    }

    private fun score(text: String, rules: List<Rule>): Double {
        var total = 0.0
        for (rule in rules) {
            var hits = 0
            for (m in rule.re.findAll(text)) { hits++; if (hits >= rule.maxHits) break }
            total += hits * rule.weight
        }
        return total
    }

    /** [lines] are the first lines of the document (without line terminators). Returns a bundled language id or null. Never throws. */
    fun guess(lines: List<String>): String? =
        try { guessImpl(lines) } catch (_: StackOverflowError) { null } catch (_: RuntimeException) { null }

    private fun guessImpl(lines: List<String>): String? {
        if (lines.isEmpty()) return null
        val sb = StringBuilder()
        for (l in lines) {
            if (sb.length >= MAX_CHARS) break
            sb.append(l.take(400)).append('\n')
        }
        val text = sb.toString()
        val trimmed = text.trimStart()
        if (trimmed.isEmpty()) return null

        // ---- structured formats first: they are unambiguous
        if (trimmed[0] == '{' || trimmed[0] == '[') {
            if (jsonKey.findAll(trimmed).take(2).count() >= 2) return "json"
        }
        if (trimmed.startsWith("<?xml")) return "xml"
        if (trimmed[0] == '<' && tag.findAll(trimmed).take(8).count() >= 3) {
            return if (htmlTag.findAll(trimmed).take(6).count() >= 3) "html" else "xml"
        }

        // ---- documents that merely CONTAIN code: Markdown (fenced blocks) and YAML (key: value) stay plain text
        if (fence.containsMatchIn(text) || looksLikeYaml(text)) return null

        // ---- source languages: score each, the winner must be clear
        val scores = LinkedHashMap<String, Double>()
        scores["python"] = score(text, python)
        val js = score(text, javascript)
        val tsOnly = score(text, typescriptMarkers)
        if (tsOnly >= 3.0) scores["typescript"] = js + tsOnly else scores["javascript"] = js
        scores["shellscript"] = score(text, shell)
        scores["java"] = score(text, java)
        scores["kotlin"] = score(text, kotlin)
        scores["csharp"] = score(text, csharp)
        scores["lua"] = score(text, lua)
        scores["css"] = if (score(text, listOf(cssDecl)) > 0.0) score(text, css) else 0.0     // selectors alone (`declare {`) are not CSS
        scores["sql"] = score(text, sql)
        val cf = score(text, cFamily)
        scores["c"] = cf
        var best: String? = null; var bestScore = 0.0; var second = 0.0
        for ((k, v) in scores) {
            if (v > bestScore) { second = bestScore; bestScore = v; best = k } else if (v > second) second = v
        }
        if (best == null || bestScore < MIN_SCORE || bestScore - second < MIN_MARGIN) return null
        return when (best) {
            "c" -> if (cppMarkers.findAll(text).take(2).count() >= 2) "cpp" else "c"
            else -> best
        }
    }
}
