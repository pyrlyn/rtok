// The block structure of a document over its content lines: which line starts a node, how deep it reaches.
package yamlaot

internal class Reader(
    private val lines: MutableList<Line>,
    private val raw: List<String>,
) {
    private var pos = 0

    private fun fail(
        line: Line,
        why: String,
    ): Nothing = throw YamlAotException("line ${line.no}: $why")

    fun document(): YamlValue {
        val first = lines.firstOrNull()
        if (first != null && Syntax.isDocStart(first)) {
            val rest = first.text.removePrefix("---").trim()
            if (rest.isEmpty()) lines.removeAt(0) else lines[0] = Line(first.no, 0, rest)
        }
        if (lines.lastOrNull()?.text == "...") lines.removeAt(lines.lastIndex)
        if (lines.isEmpty()) return YamlValue.Null
        val v = node(lines[0].indent)
        if (pos < lines.size) fail(lines[pos], "unexpected content (`${lines[pos].text}`)")
        return v
    }

    /** The node whose first line is at [pos], indented exactly [indent]. */
    private fun node(indent: Int): YamlValue {
        val l = lines[pos]
        refuse(l, l.text)
        return when {
            Syntax.isItem(l.text) -> seq(indent)
            Syntax.keyOf(l) != null -> map(indent)
            else -> scalarLines(l, indent - 1)
        }
    }

    private fun refuse(
        l: Line,
        text: String,
    ) {
        if (text.first() in "&*!?%") fail(l, "anchors, aliases, tags, directives and complex keys are not supported")
    }

    private fun seq(indent: Int): YamlValue {
        val items = mutableListOf<YamlValue>()
        while (pos < lines.size && lines[pos].indent == indent && Syntax.isItem(lines[pos].text)) {
            val l = lines[pos]
            val rest = l.text.removePrefix("-")
            val body = rest.trimStart()
            if (body.isEmpty()) {
                pos++
                items += child(indent, allowSameIndentSeq = false)
            } else {
                // `- key: v`: the rest of the line is a node of its own, its column the item's indent.
                lines[pos] = Line(l.no, indent + 1 + rest.length - body.length, body)
                items += node(lines[pos].indent)
            }
        }
        return YamlValue.Seq(items)
    }

    private fun map(indent: Int): YamlValue {
        val entries = LinkedHashMap<String, YamlValue>()
        while (pos < lines.size && lines[pos].indent == indent) {
            val l = lines[pos]
            refuse(l, l.text)
            val (key, rest) = Syntax.keyOf(l) ?: fail(l, "expected `key: value`")
            if (key in entries) fail(l, "duplicate key `$key`")
            entries[key] = value(l, indent, rest)
        }
        if (pos < lines.size && lines[pos].indent > indent) fail(lines[pos], "bad indentation")
        return YamlValue.Map(entries)
    }

    /** The value after `key:` on [l]. */
    private fun value(
        l: Line,
        indent: Int,
        rest: String,
    ): YamlValue =
        when {
            rest.isEmpty() -> {
                pos++
                child(indent, allowSameIndentSeq = true)
            }

            rest.first() == '|' || rest.first() == '>' -> {
                blockScalar(l, indent, rest)
            }

            else -> {
                refuse(l, rest)
                scalarLines(Line(l.no, indent, rest), indent)
            }
        }

    /** What follows a bare `key:` or `-`: a deeper node, a sequence at the key's own indent, or null. */
    private fun child(
        indent: Int,
        allowSameIndentSeq: Boolean,
    ): YamlValue {
        val next = lines.getOrNull(pos) ?: return YamlValue.Null
        return when {
            next.indent > indent -> node(next.indent)
            allowSameIndentSeq && next.indent == indent && Syntax.isItem(next.text) -> seq(indent)
            else -> YamlValue.Null
        }
    }

    /**
     * A scalar or flow value starting on [first], continued by the lines deeper than [parent]: a flow collection or
     * quoted string runs until it closes, a plain scalar folds them in with spaces.
     */
    private fun scalarLines(
        first: Line,
        parent: Int,
    ): YamlValue {
        pos++
        var text = first.text
        val multi = text.first() in "[{\"'"
        while (pos < lines.size && lines[pos].indent > parent && continues(text, first.no, multi)) {
            text += " " + lines[pos].text
            pos++
        }
        return if (multi) Flow(text, first.no).all() else YamlValue.plain(text)
    }

    private fun continues(
        text: String,
        no: Int,
        multi: Boolean,
    ): Boolean = if (multi) Flow(text, no).end(0) < 0 else Syntax.keyOf(lines[pos]) == null && !Syntax.isItem(lines[pos].text)

    private fun blockScalar(
        l: Line,
        indent: Int,
        header: String,
    ): YamlValue {
        if (header.substring(1).trim().any { it !in "+-123456789" }) fail(l, "bad block scalar header `$header`")
        val end = Syntax.blockEnd(raw, l.no, indent)
        while (pos < lines.size && lines[pos].no <= end) pos++
        return YamlValue.Str(Syntax.blockScalar(header, indent, raw.subList(l.no, end)))
    }
}
