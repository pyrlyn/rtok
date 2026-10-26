// Port of the line split in src/agents/aider/mod.rs (`split_key`, `split_comment`, `unquote`): one top-level-style
// `key: value  # comment` YAML line read without a YAML parser, so the rest of the file is never rewritten.
package rtok.agents

/** A `<indent><key>: <value> [# comment]` line; [value] is unquoted. */
class YamlKeyLine(
    val indent: String,
    val value: String,
    val comment: String?,
) {
    companion object {
        /** The line as [key]'s entry, or null for any other line. */
        fun parse(
            line: String,
            key: String,
        ): YamlKeyLine? {
            val m = Regex("""^([ \t]*)${Regex.escape(key)}[ \t]*:(.*)$""").matchEntire(line) ?: return null
            val tail = m.groupValues[2]
            val hash = tail.indices.firstOrNull { tail[it] == '#' && (it == 0 || tail[it - 1].isWhitespace()) }
            val (value, comment) = if (hash == null) tail to null else tail.substring(0, hash).trimEnd() to tail.substring(hash).trim()
            return YamlKeyLine(m.groupValues[1], unquote(value.trim()), comment)
        }

        private fun unquote(v: String): String {
            val quoted = v.length >= 2 && v.first() in "\"'" && v.last() == v.first()
            return if (quoted) v.substring(1, v.length - 1) else v
        }
    }
}
