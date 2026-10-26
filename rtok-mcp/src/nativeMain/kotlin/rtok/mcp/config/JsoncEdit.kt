// The lossless JSONC editor config.rs gets from jsonc-parser's CST: set or remove one property under a key path and
// leave every other byte (comments, trailing commas, formatting) as it was.
package rtok.mcp.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object JsoncEdit {
    private class Member(
        val key: String,
        val keyStart: Int,
        val valueEnd: Int,
        val value: Span,
    )

    private open class Span(
        val start: Int,
        val end: Int,
    )

    private class Obj(
        start: Int,
        end: Int,
        val members: List<Member>,
    ) : Span(start, end)

    /** [text] with `keyPath.<name>` set to [entry], or removed when [entry] is `null`. */
    fun set(
        path: String,
        text: String,
        keyPath: List<String>,
        name: String,
        entry: JsonObject?,
    ): String =
        when {
            !text.isBlank() -> edit(path, text, keyPath, name, entry)
            entry == null -> text
            else -> render(nest(keyPath, name, entry), "") + "\n"
        }

    private fun edit(
        path: String,
        text: String,
        keyPath: List<String>,
        name: String,
        entry: JsonObject?,
    ): String {
        val root = Parser(path, text).root() as? Obj ?: throw McpConfigException("$path: root is not an object")
        val (obj, missing) = walk(path, root, keyPath)
        val have = obj.members.lastOrNull { it.key == name }
        return when {
            missing >= 0 -> if (entry == null) text else insert(text, obj, keyPath[missing], nest(keyPath.drop(missing + 1), name, entry))
            entry == null -> if (have == null) text else remove(text, obj, have)
            have == null -> insert(text, obj, name, entry)
            else -> text.replaceRange(have.value.start, have.value.end, render(entry, lineIndent(text, have.keyStart)))
        }
    }

    /** The object at [keyPath] and `-1`, or the deepest one found and the index of the first missing key. */
    private fun walk(
        path: String,
        root: Obj,
        keyPath: List<String>,
    ): Pair<Obj, Int> {
        var obj = root
        for ((i, k) in keyPath.withIndex()) {
            val m = obj.members.lastOrNull { it.key == k } ?: return obj to i
            obj = m.value as? Obj ?: throw McpConfigException("$path: $k is not an object")
        }
        return obj to -1
    }

    private fun nest(
        keyPath: List<String>,
        name: String,
        entry: JsonElement,
    ): JsonElement = keyPath.foldRight(JsonObject(mapOf(name to entry))) { k, inner -> JsonObject(mapOf(k to inner)) }

    private fun insert(
        text: String,
        obj: Obj,
        key: String,
        value: JsonElement,
    ): String {
        val first = obj.members.firstOrNull()
        val indent = first?.let { lineIndent(text, it.keyStart) } ?: (lineIndent(text, obj.start) + "  ")
        val member = "${Json.encodeToString(JsonElement.serializer(), JsonPrimitive(key))}: ${render(value, indent)}"
        val last = obj.members.lastOrNull()
        return if (last != null) {
            text.replaceRange(last.valueEnd, last.valueEnd, ",\n$indent$member")
        } else {
            text.replaceRange(obj.start + 1, obj.start + 1, "\n$indent$member\n${lineIndent(text, obj.start)}")
        }
    }

    private fun remove(
        text: String,
        obj: Obj,
        m: Member,
    ): String {
        val i = obj.members.indexOf(m)
        val next = obj.members.getOrNull(i + 1)
        val prev = obj.members.getOrNull(i - 1)
        return when {
            next != null -> {
                text.removeRange(m.keyStart, next.keyStart)
            }

            prev != null -> {
                text.removeRange(prev.valueEnd, m.valueEnd)
            }

            else -> {
                var end = m.valueEnd
                while (end < obj.end - 1 && text[end].isWhitespace()) end++
                if (text[end] == ',') end++
                text.removeRange(m.keyStart, end)
            }
        }
    }

    private fun render(
        v: JsonElement,
        indent: String,
    ): String = McpConfig.pretty.encodeToString(JsonElement.serializer(), v).replace("\n", "\n$indent")

    private fun lineIndent(
        text: String,
        at: Int,
    ): String {
        val start = text.lastIndexOf('\n', at - 1) + 1
        return text.substring(start).takeWhile { it == ' ' || it == '\t' }
    }

    private class Parser(
        val path: String,
        val s: String,
    ) {
        var i = 0

        fun root(): Span {
            val v = value()
            skip()
            if (i < s.length) fail("trailing content")
            return v
        }

        private fun fail(what: String): Nothing = throw McpConfigException("$path: invalid JSONC at offset $i: $what")

        private fun skip() {
            while (i < s.length) {
                when {
                    s[i].isWhitespace() -> i++
                    s.startsWith("//", i) -> i = s.indexOf('\n', i).let { if (it < 0) s.length else it }
                    s.startsWith("/*", i) -> i = s.indexOf("*/", i + 2).let { if (it < 0) fail("unclosed comment") else it + 2 }
                    else -> return
                }
            }
        }

        private fun value(): Span {
            skip()
            if (i >= s.length) fail("unexpected end")
            return when (s[i]) {
                '{' -> obj()
                '[' -> array()
                '"' -> Span(i, string().let { i })
                else -> literal()
            }
        }

        private fun string(): String {
            val start = i++
            while (i < s.length && s[i] != '"') i += if (s[i] == '\\') 2 else 1
            if (i >= s.length) fail("unclosed string")
            i++
            return (Json.parseToJsonElement(s.substring(start, i)) as JsonPrimitive).content
        }

        private fun literal(): Span {
            val start = i
            while (i < s.length && s[i] !in ",}]/" && !s[i].isWhitespace()) i++
            if (i == start) fail("unexpected '${s[i]}'")
            return Span(start, i)
        }

        private fun array(): Span {
            val start = i++
            while (true) {
                skip()
                if (i >= s.length) fail("unclosed array")
                if (s[i] == ']') return Span(start, ++i)
                value()
                skip()
                if (i < s.length && s[i] == ',') i++
            }
        }

        private fun obj(): Obj {
            val start = i++
            val members = ArrayList<Member>()
            while (true) {
                skip()
                if (i >= s.length) fail("unclosed object")
                if (s[i] == '}') return Obj(start, ++i, members)
                if (s[i] != '"') fail("expected a property name")
                val keyStart = i
                val key = string()
                skip()
                if (i >= s.length || s[i] != ':') fail("expected ':'")
                i++
                val v = value()
                members += Member(key, keyStart, v.end, v)
                skip()
                if (i < s.length && s[i] == ',') i++
            }
        }
    }
}
