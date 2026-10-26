// TOML 1.0 reader producing a kotlinx JsonObject tree (replaces the Rust `toml` crate as used by
// figment's Toml provider). Datetimes are kept as strings. Errors carry line numbers.
package rtok.config

import kotlinx.serialization.json.*

class TomlException(message: String) : Exception(message)

/** Parsed document plus where each dotted key was defined (1-based line) and multi-line string spans. */
class TomlDoc(val root: JsonObject, val lines: Map<String, Int>, val mlStringLines: Set<Int>) {
    fun lineOf(dotted: String): Int = lines[dotted] ?: 1
}

object Toml {
    fun parse(text: String): JsonObject = Parser(text).parseDoc()
    fun parseDoc(text: String): TomlDoc { val p = Parser(text); val r = p.parseDoc(); return TomlDoc(r, p.keyLines, p.mlLines) }
    /** True for an integer literal in this tree (TOML keeps 1 and 1.0 apart). */
    fun isInteger(p: JsonPrimitive) = !p.isString && p.longOrNull != null && !p.content.contains('.') && !p.content.contains('e') && !p.content.contains('E')
    fun isFloat(p: JsonPrimitive) = !p.isString && !isInteger(p) && p.doubleOrNull != null
    fun isBool(p: JsonPrimitive) = !p.isString && p.booleanOrNull != null
}

private class Parser(val s: String) {
    var i = 0
    var line = 1
    // Mutable tree; converted to JsonObject at the end.
    val root = LinkedHashMap<String, Any>()
    val keyLines = HashMap<String, Int>()
    val mlLines = HashSet<Int>()
    var tablePath: List<String> = emptyList()
    val defined = HashSet<List<String>>()       // explicit [table] headers
    val inlineFrozen = HashSet<Any>()           // inline tables / static arrays cannot be extended

    fun err(msg: String): Nothing = throw TomlException("TOML parse error at line $line: $msg")
    fun peek(o: Int = 0) = if (i + o < s.length) s[i + o] else '\u0000'
    fun eof() = i >= s.length

    fun skipWs() { while (!eof() && (peek() == ' ' || peek() == '\t')) i++ }
    fun skipComment() { if (peek() == '#') while (!eof() && peek() != '\n') { val c = peek(); if (c.code < 0x20 && c != '\t' && c != '\r') err("control character in comment"); i++ } }
    fun newline(): Boolean {
        if (peek() == '\n') { i++; line++; return true }
        if (peek() == '\r' && peek(1) == '\n') { i += 2; line++; return true }
        return false
    }
    fun skipWsNl() { while (true) { skipWs(); skipComment(); if (!newline()) break } }
    fun endOfLine() { skipWs(); skipComment(); if (!eof() && !newline()) err("expected newline, found '${peek()}'") }

    fun parseDoc(): JsonObject {
        var current: MutableMap<String, Any> = root
        while (true) {
            skipWsNl()
            if (eof()) break
            if (peek() == '[') {
                if (peek(1) == '[') {
                    i += 2; skipWs(); val key = parseKey(); skipWs()
                    if (peek() != ']' || peek(1) != ']') err("expected ]]"); i += 2
                    keyLines.getOrPut(key.joinToString(".")) { line }; tablePath = key
                    current = arrayTable(key)
                } else {
                    i++; skipWs(); val key = parseKey(); skipWs()
                    if (peek() != ']') err("expected ]"); i++
                    keyLines.getOrPut(key.joinToString(".")) { line }; tablePath = key
                    current = table(key)
                }
                endOfLine()
            } else {
                parseKeyValue(current, tablePath); endOfLine()
            }
        }
        return toJson(root) as JsonObject
    }

    @Suppress("UNCHECKED_CAST")
    fun descend(start: MutableMap<String, Any>, path: List<String>, implicit: Boolean): MutableMap<String, Any> {
        var cur = start
        for (k in path) {
            val next = cur[k]
            cur = when (next) {
                null -> LinkedHashMap<String, Any>().also { cur[k] = it }
                is MutableMap<*, *> -> { if (next in inlineFrozen) err("cannot extend inline table '$k'"); next as MutableMap<String, Any> }
                is MutableList<*> -> { if (next in inlineFrozen) err("cannot extend array '$k'"); (next.last() as? MutableMap<String, Any>) ?: err("'$k' is not a table") }
                else -> err("key '$k' is already a value")
            }
        }
        return cur
    }

    fun table(key: List<String>): MutableMap<String, Any> {
        if (!defined.add(key)) err("table [${key.joinToString(".")}] defined twice")
        val parent = descend(root, key.dropLast(1), true)
        val last = key.last()
        return when (val ex = parent[last]) {
            null -> LinkedHashMap<String, Any>().also { parent[last] = it }
            is MutableMap<*, *> -> { if (ex in inlineFrozen || ex in dottedTables) err("table [${key.joinToString(".")}] defined twice"); @Suppress("UNCHECKED_CAST") (ex as MutableMap<String, Any>) }
            else -> err("table [${key.joinToString(".")}] conflicts with a value")
        }
    }

    val dottedTables = HashSet<Any>()

    fun arrayTable(key: List<String>): MutableMap<String, Any> {
        val parent = descend(root, key.dropLast(1), true)
        val last = key.last()
        val arr = when (val ex = parent[last]) {
            null -> ArrayList<Any>().also { parent[last] = it }
            is MutableList<*> -> { if (ex in inlineFrozen) err("cannot append to static array '$last'"); @Suppress("UNCHECKED_CAST") (ex as MutableList<Any>) }
            else -> err("'$last' is not an array of tables")
        }
        val t = LinkedHashMap<String, Any>(); arr.add(t)
        defined.removeAll { it.size > key.size && it.subList(0, key.size) == key }
        return t
    }

    fun parseKeyValue(target: MutableMap<String, Any>, prefix: List<String>) {
        val key = parseKey(); val keyLine = line; skipWs()
        for (n in 1..key.size) keyLines.getOrPut((prefix + key.subList(0, n)).joinToString(".")) { keyLine }
        valuePrefix = prefix + key
        if (peek() != '=') err("expected '=' after key"); i++; skipWs()
        val v = parseValue()
        var t = target
        for (k in key.dropLast(1)) {
            t = when (val ex = t[k]) {
                null -> LinkedHashMap<String, Any>().also { t[k] = it; dottedTables.add(it) }
                is MutableMap<*, *> -> { if (ex in inlineFrozen) err("cannot extend inline table '$k'"); @Suppress("UNCHECKED_CAST") (ex as MutableMap<String, Any>) }
                else -> err("key '$k' is already a value")
            }
        }
        val last = key.last()
        if (t.containsKey(last)) err("duplicate key '$last'")
        t[last] = v
    }

    var valuePrefix: List<String> = emptyList()

    fun parseKey(): List<String> {
        val parts = ArrayList<String>()
        while (true) {
            skipWs()
            parts += when (peek()) {
                '"' -> basicString()
                '\'' -> literalString()
                else -> { val st = i; while (!eof() && (peek().isLetterOrDigit() && peek().code < 128 || peek() == '_' || peek() == '-')) i++; if (st == i) err("expected a key"); s.substring(st, i) }
            }
            skipWs()
            if (peek() == '.') { i++; continue }
            return parts
        }
    }

    fun parseValue(): Any = when {
        s.startsWith("\"\"\"", i) -> mlBasic()
        s.startsWith("'''", i) -> mlLiteral()
        peek() == '"' -> basicString()
        peek() == '\'' -> literalString()
        s.startsWith("true", i) && !isBare(peek(4)) -> { i += 4; true }
        s.startsWith("false", i) && !isBare(peek(5)) -> { i += 5; false }
        peek() == '[' -> array()
        peek() == '{' -> inlineTable()
        else -> numberOrDate()
    }

    fun isBare(c: Char) = c.isLetterOrDigit() || c == '_' || c == '-'

    fun array(): MutableList<Any> {
        i++; val out = ArrayList<Any>()
        while (true) {
            skipWsNl()
            if (peek() == ']') { i++; break }
            out += parseValue(); skipWsNl()
            when (peek()) { ',' -> i++; ']' -> { i++; break }; else -> err("expected ',' or ']' in array") }
        }
        inlineFrozen.add(out); return out
    }

    fun inlineTable(): MutableMap<String, Any> {
        val here = valuePrefix
        i++; val t = LinkedHashMap<String, Any>(); skipWs()
        if (peek() == '}') { i++; inlineFrozen.add(t); return t }
        while (true) {
            skipWs(); parseKeyValue(t, here); skipWs()
            when (peek()) { ',' -> i++; '}' -> { i++; break }; else -> err("expected ',' or '}' in inline table") }
        }
        inlineFrozen.add(t); return t
    }

    fun escape(sb: StringBuilder) {
        i++
        when (val c = peek()) {
            'b' -> sb.append('\b'); 't' -> sb.append('\t'); 'n' -> sb.append('\n'); 'f' -> sb.append('\u000c')
            'r' -> sb.append('\r'); '"' -> sb.append('"'); '\\' -> sb.append('\\')
            'u', 'U' -> {
                val n = if (c == 'u') 4 else 8
                val hex = s.substring(i + 1, minOf(s.length, i + 1 + n)); if (hex.length != n) err("bad unicode escape")
                val cp = hex.toIntOrNull(16) ?: err("bad unicode escape")
                if (cp in 0xD800..0xDFFF || cp > 0x10FFFF) err("invalid unicode scalar")
                appendCodePoint(sb, cp); i += n
            }
            else -> err("invalid escape '\\$c'")
        }
        i++
    }

    fun appendCodePoint(sb: StringBuilder, cp: Int) {
        if (cp < 0x10000) sb.append(cp.toChar()) else { val v = cp - 0x10000; sb.append((0xD800 + (v shr 10)).toChar()); sb.append((0xDC00 + (v and 0x3FF)).toChar()) }
    }

    fun basicString(): String {
        i++; val sb = StringBuilder()
        while (true) {
            if (eof() || peek() == '\n') err("unterminated string")
            when (val c = peek()) { '"' -> { i++; return sb.toString() }; '\\' -> escape(sb); else -> { sb.append(c); i++ } }
        }
    }

    fun literalString(): String {
        i++; val st = i
        while (!eof() && peek() != '\'' && peek() != '\n') i++
        if (peek() != '\'') err("unterminated literal string")
        return s.substring(st, i).also { i++ }
    }

    fun mlBasic(): String {
        val startLine = line
        try { return mlBasic0() } finally { for (l in startLine until line) mlLines += l } // 0-based indices of lines after the opening one
    }

    fun mlBasic0(): String {
        i += 3; newline(); val sb = StringBuilder()
        while (true) {
            if (eof()) err("unterminated multi-line string")
            if (s.startsWith("\"\"\"", i)) {
                var q = 0; while (peek(q) == '"') q++
                if (q > 5) err("too many quotes")
                repeat(q - 3) { sb.append('"') }; i += q; return sb.toString()
            }
            val c = peek()
            if (c == '\\') {
                var j = i + 1; while (j < s.length && (s[j] == ' ' || s[j] == '\t')) j++
                if (j < s.length && (s[j] == '\n' || s[j] == '\r')) {
                    i = j; while (!eof() && (peek() == ' ' || peek() == '\t' || peek() == '\n' || peek() == '\r')) { if (peek() == '\n') line++; i++ }
                } else escape(sb)
            } else { if (c == '\n') line++; sb.append(c); i++ }
        }
    }

    fun mlLiteral(): String {
        val startLine = line
        try { return mlLiteral0() } finally { for (l in startLine until line) mlLines += l } // 0-based indices of lines after the opening one
    }

    fun mlLiteral0(): String {
        i += 3; newline(); val sb = StringBuilder()
        while (true) {
            if (eof()) err("unterminated multi-line literal string")
            if (s.startsWith("'''", i)) {
                var q = 0; while (peek(q) == '\'') q++
                if (q > 5) err("too many quotes")
                repeat(q - 3) { sb.append('\'') }; i += q; return sb.toString()
            }
            if (peek() == '\n') line++
            sb.append(peek()); i++
        }
    }

    fun numberOrDate(): Any {
        val st = i
        while (!eof() && peek() !in " \t\r\n,]}#") {
            // allow a single space between date and time: "1979-05-27 07:32:00"
            i++
        }
        if (peek() == ' ' && i - st == 10 && s[st + 4] == '-' && peek(1).isDigit()) { i++; while (!eof() && peek() !in " \t\r\n,]}#") i++ }
        val tok = s.substring(st, i)
        if (tok.isEmpty()) err("expected a value")
        when (tok) { "inf", "+inf" -> return Double.POSITIVE_INFINITY; "-inf" -> return Double.NEGATIVE_INFINITY; "nan", "+nan", "-nan" -> return Double.NaN }
        if (Regex("""\d{4}-\d{2}-\d{2}([Tt ]\d{2}:\d{2}:\d{2}(\.\d+)?)?([Zz]|[+-]\d{2}:\d{2})?|\d{2}:\d{2}:\d{2}(\.\d+)?""").matches(tok)) return TomlDate(tok)
        if (tok.contains("__") || tok.startsWith("_") || tok.endsWith("_")) err("invalid number '$tok'")
        val t = tok.replace("_", "")
        val body = t.removePrefix("+").removePrefix("-")
        val neg = t.startsWith("-")
        if (body.startsWith("0x") || body.startsWith("0o") || body.startsWith("0b")) {
            if (t.startsWith("+") || neg) err("sign not allowed on '$tok'")
            val radix = when (body[1]) { 'x' -> 16; 'o' -> 8; else -> 2 }
            return body.substring(2).toLongOrNull(radix) ?: err("invalid integer '$tok'")
        }
        if (Regex("""[+-]?(0|[1-9]\d*)""").matches(t)) return t.toLongOrNull() ?: err("integer out of range '$tok'")
        if (Regex("""[+-]?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?""").matches(t) && (t.contains('.') || t.contains('e') || t.contains('E')))
            return t.toDouble()
        err("invalid value '$tok'")
    }

    fun toJson(v: Any): JsonElement = when (v) {
        is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k as String to toJson(x!!) })
        is List<*> -> JsonArray(v.map { toJson(it!!) })
        is String -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Long -> JsonPrimitive(v)
        is Double -> JsonPrimitive(v)
        is TomlDate -> JsonPrimitive(v.text)
        else -> err("internal: ${v::class}")
    }
}

private class TomlDate(val text: String)
