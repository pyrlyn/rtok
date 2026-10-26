// Port of the markdown half of src/plugins/read/outline.rs (`markdown_headings`, `markdown_digest`).
// The tree-sitter symbol outline stays with the read plugin (pending Ivan's tree-sitter approval).
package rtok.plugins.read

import rtok.sys.rustLines

data class MdHeading(val line: Int, val level: Int, val text: String, var body: String? = null)

/** `#` headings with the first non-empty body line; fenced blocks skipped (T68.8). */
fun markdownHeadings(src: String): List<MdHeading> {
    val out = ArrayList<MdHeading>()
    var fenced = false; var slot: Int? = null
    for ((i, line) in src.rustLines().withIndex()) {
        val t = line.trim()
        when {
            t.startsWith("```") -> fenced = !fenced
            fenced -> {}
            t.startsWith('#') -> {
                val level = t.takeWhile { it == '#' }.length.coerceIn(1, 6)
                out += MdHeading(i + 1, level, t); slot = out.size - 1
            }
            slot != null && t.isNotEmpty() -> { out[slot].body = t; slot = null }
        }
    }
    return out
}

/** Heading map for skill digest: full heading line plus indented first body line. */
fun markdownDigest(src: String): String = buildString {
    for (h in markdownHeadings(src)) {
        append(h.text).append('\n')
        h.body?.let { append("  ").append(it).append('\n') }
    }
}
