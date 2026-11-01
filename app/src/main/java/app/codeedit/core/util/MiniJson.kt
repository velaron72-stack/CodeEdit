package app.codeedit.core.util

import java.math.BigDecimal
import java.math.BigInteger

/** Thrown for malformed JSON (offset = char index in the parsed text, -1 when unknown) or unserialisable values. */
class JsonException(message: String, val offset: Int = -1) : RuntimeException(message)

/**
 * Small strict JSON reader/writer, dependency-free (pure JVM, also used on Android for manifests, themes and
 * settings import/export).
 *
 * Parse result types: `Map<String, Any?>` (insertion ordered, duplicate keys: last wins), `List<Any?>`, `String`,
 * `Long` (integers that fit in a Long), `Double` (fractions/exponents and integers outside the Long range —
 * the range overflow never throws), `Boolean`, `null`.
 *
 * Strict means RFC 8259: no comments, no trailing commas, no NaN/Infinity, no leading zeros, no control chars in
 * strings. `lenient = true` additionally accepts `//` and `/* */` comments and trailing commas (tsconfig/VS Code style).
 * Nesting is limited to 512 levels so hostile input cannot overflow the stack.
 */
object MiniJson {
    private const val MAX_DEPTH = 512

    @JvmStatic @JvmOverloads
    fun parse(bytes: ByteArray, lenient: Boolean = false): Any? {
        var off = 0
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) off = 3
        return parse(String(bytes, off, bytes.size - off, Charsets.UTF_8), lenient)
    }

    @JvmStatic @JvmOverloads
    fun parse(text: String, lenient: Boolean = false): Any? {
        val p = Parser(text, lenient)
        p.skipWs()
        if (p.i >= text.length) throw JsonException("empty document", 0)
        val v = p.value(0)
        p.skipWs()
        if (p.i != text.length) throw JsonException("unexpected trailing data", p.i)
        return v
    }

    /** `v as Map<String, Any?>` or null. */
    @Suppress("UNCHECKED_CAST")
    @JvmStatic fun asObject(v: Any?): Map<String, Any?>? = v as? Map<String, Any?>

    /** `v as List<Any?>` or null. */
    @Suppress("UNCHECKED_CAST")
    @JvmStatic fun asArray(v: Any?): List<Any?>? = v as? List<Any?>

    /**
     * Serialises Map / Iterable / Array / primitive arrays / String / Number / Boolean / null. Map keys use toString().
     * NaN and Infinity are rejected (JsonException). `pretty` indents with two spaces.
     */
    @JvmStatic @JvmOverloads
    fun write(value: Any?, pretty: Boolean = false): String {
        val sb = StringBuilder(256)
        Writer(sb, pretty).value(value, 0)
        return sb.toString()
    }

    private class Parser(val s: String, val lenient: Boolean) {
        var i = 0

        fun skipWs() {
            while (i < s.length) {
                val c = s[i]
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') { i++; continue }
                if (lenient && c == '/' && i + 1 < s.length) {
                    val d = s[i + 1]
                    if (d == '/') { i += 2; while (i < s.length && s[i] != '\n') i++; continue }
                    if (d == '*') {
                        val e = s.indexOf("*/", i + 2)
                        if (e < 0) throw JsonException("unterminated comment", i)
                        i = e + 2; continue
                    }
                }
                break
            }
        }

        fun fail(msg: String): Nothing = throw JsonException(msg, i)

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("nesting too deep")
            if (i >= s.length) fail("unexpected end of input")
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || (c in '0'..'9')) num() else fail("unexpected character '$c'")
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail("invalid literal")
            i += word.length
            return v
        }

        fun obj(depth: Int): Map<String, Any?> {
            i++ // {
            val m = LinkedHashMap<String, Any?>()
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                skipWs()
                if (i >= s.length) fail("unterminated object")
                if (s[i] != '"') fail("object key must be a string")
                val k = str()
                skipWs()
                if (i >= s.length || s[i] != ':') fail("expected ':'")
                i++
                skipWs()
                m[k] = value(depth + 1)
                skipWs()
                if (i >= s.length) fail("unterminated object")
                when (s[i]) {
                    ',' -> {
                        i++
                        if (lenient) { skipWs(); if (i < s.length && s[i] == '}') { i++; return m } }
                    }
                    '}' -> { i++; return m }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        fun arr(depth: Int): List<Any?> {
            i++ // [
            val l = ArrayList<Any?>()
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                skipWs()
                l.add(value(depth + 1))
                skipWs()
                if (i >= s.length) fail("unterminated array")
                when (s[i]) {
                    ',' -> {
                        i++
                        if (lenient) { skipWs(); if (i < s.length && s[i] == ']') { i++; return l } }
                    }
                    ']' -> { i++; return l }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        fun str(): String {
            i++ // opening quote
            val start = i
            // fast path: no escapes
            while (i < s.length) {
                val c = s[i]
                if (c == '"') { val r = s.substring(start, i); i++; return r }
                if (c == '\\') break
                if (c < ' ') fail("control character in string")
                i++
            }
            if (i >= s.length) fail("unterminated string")
            val sb = StringBuilder(i - start + 16)
            sb.append(s, start, i)
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '"' -> { i++; return sb.toString() }
                    c == '\\' -> {
                        i++
                        if (i >= s.length) fail("unterminated escape")
                        when (val e = s[i]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 >= s.length) fail("bad \\u escape")
                                var cp = 0
                                for (k in 1..4) {
                                    val d = Character.digit(s[i + k], 16)
                                    if (d < 0) { i += k; fail("bad \\u escape") }
                                    cp = cp * 16 + d
                                }
                                sb.append(cp.toChar()); i += 4
                            }
                            else -> fail("invalid escape '\\$e'")
                        }
                        i++
                    }
                    c < ' ' -> fail("control character in string")
                    else -> { sb.append(c); i++ }
                }
            }
            fail("unterminated string")
        }

        fun num(): Any {
            val start = i
            if (s[i] == '-') i++
            if (i >= s.length) fail("bad number")
            if (s[i] == '0') {
                i++
                if (i < s.length && s[i] in '0'..'9') fail("leading zero")
            } else if (s[i] in '1'..'9') {
                while (i < s.length && s[i] in '0'..'9') i++
            } else fail("bad number")
            var isInt = true
            if (i < s.length && s[i] == '.') {
                isInt = false; i++
                val d = i
                while (i < s.length && s[i] in '0'..'9') i++
                if (i == d) fail("digits expected after '.'")
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                isInt = false; i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                val d = i
                while (i < s.length && s[i] in '0'..'9') i++
                if (i == d) fail("digits expected in exponent")
            }
            val t = s.substring(start, i)
            if (isInt) {
                if (t.length <= 18) return t.toLong()
                return try { t.toLong() } catch (_: NumberFormatException) { t.toDouble() }
            }
            return t.toDouble()
        }
    }

    private class Writer(val sb: StringBuilder, val pretty: Boolean) {
        fun nl(depth: Int) { if (pretty) { sb.append('\n'); repeat(depth) { sb.append("  ") } } }

        fun value(v: Any?, depth: Int) {
            when (v) {
                null -> sb.append("null")
                is String -> string(v)
                is Boolean -> sb.append(if (v) "true" else "false")
                is Int, is Long, is Short, is Byte -> sb.append(v.toString())
                is BigInteger, is BigDecimal -> sb.append(v.toString())
                is Double -> { if (v.isNaN() || v.isInfinite()) throw JsonException("non-finite number"); sb.append(v.toString()) }
                is Float -> { if (v.isNaN() || v.isInfinite()) throw JsonException("non-finite number"); sb.append(v.toString()) }
                is Map<*, *> -> {
                    if (v.isEmpty()) { sb.append("{}"); return }
                    sb.append('{')
                    var first = true
                    for ((k, x) in v) {
                        if (!first) sb.append(',')
                        first = false
                        nl(depth + 1)
                        string(k.toString()); sb.append(if (pretty) ": " else ":")
                        value(x, depth + 1)
                    }
                    nl(depth); sb.append('}')
                }
                is Iterable<*> -> list(v.iterator(), depth)
                is Array<*> -> list(v.iterator(), depth)
                is IntArray -> list(v.iterator(), depth)
                is LongArray -> list(v.iterator(), depth)
                is DoubleArray -> list(v.iterator(), depth)
                is BooleanArray -> list(v.iterator(), depth)
                else -> throw JsonException("unsupported type ${v.javaClass.name}")
            }
        }

        fun list(it: Iterator<*>, depth: Int) {
            if (!it.hasNext()) { sb.append("[]"); return }
            sb.append('[')
            var first = true
            while (it.hasNext()) {
                if (!first) sb.append(',')
                first = false
                nl(depth + 1)
                value(it.next(), depth + 1)
            }
            nl(depth); sb.append(']')
        }

        fun string(s: String) {
            sb.append('"')
            for (c in s) {
                when {
                    c == '"' -> sb.append("\\\"")
                    c == '\\' -> sb.append("\\\\")
                    c == '\n' -> sb.append("\\n")
                    c == '\r' -> sb.append("\\r")
                    c == '\t' -> sb.append("\\t")
                    c == '\b' -> sb.append("\\b")
                    c == '\u000C' -> sb.append("\\f")
                    c < ' ' || c == ' ' || c == ' ' -> { sb.append("\\u"); sb.append(String.format("%04x", c.code)) }
                    else -> sb.append(c)
                }
            }
            sb.append('"')
        }
    }
}
