// gitignore matching (the part of the `ignore` crate rtok uses): one Gitignore per file, rules relative
// to the file's directory, last match wins, `!` re-includes, trailing `/` = directories only.
package rtok.sys

class Gitignore(
    private val base: String,
    text: String,
) {
    private class Rule(
        val re: Regex,
        val negate: Boolean,
        val dirOnly: Boolean,
    )

    private val rules: List<Rule> = text.rustLines().mapNotNull(::parse)

    private fun parse(line0: String): Rule? {
        var line = if (line0.endsWith("\\ ")) line0 else line0.trimEnd(' ')
        if (line.isEmpty() || line.startsWith("#")) return null
        var negate = false
        if (line.startsWith("!")) {
            negate = true
            line = line.substring(1)
        } else if (line.startsWith("\\!") || line.startsWith("\\#")) {
            line = line.substring(1)
        }
        val dirOnly = line.endsWith("/")
        line = line.trimEnd('/')
        if (line.isEmpty()) return null
        val anchored = line.contains('/')
        line = line.trimStart('/')
        val body = globToRegex(line)
        val re = if (anchored) "^$body$" else "^(?:.*/)?$body$"
        return Rule(Regex(re), negate, dirOnly)
    }

    /** `true` ignored, `false` explicitly re-included, `null` no rule matched. [rel] is relative to [base]. */
    fun matched(
        path: String,
        isDir: Boolean,
    ): Boolean? {
        val rel = relative(path) ?: return null
        for (r in rules.asReversed()) {
            if (r.dirOnly && !isDir) continue
            if (r.re.matches(rel)) return !r.negate
        }
        return null
    }

    private fun relative(path: String): String? =
        when {
            base.isEmpty() -> path
            path == base -> null
            path.startsWith("$base/") -> path.substring(base.length + 1)
            else -> null
        }
}

/** gitignore glob → regex body: `**` spans directories, `*`/`?` stay within one, `[...]` classes. */
fun globToRegex(glob: String): String =
    buildString {
        var i = 0
        while (i < glob.length) {
            val c = glob[i]
            when {
                glob.startsWith("**/", i) -> {
                    append("(?:.*/)?")
                    i += 3
                    continue
                }

                glob.startsWith("/**", i) && i + 3 == glob.length -> {
                    append("/.*")
                    i += 3
                    continue
                }

                glob.startsWith("**", i) -> {
                    append(".*")
                    i += 2
                    continue
                }

                c == '*' -> {
                    append("[^/]*")
                }

                c == '?' -> {
                    append("[^/]")
                }

                c == '[' -> {
                    val end = glob.indexOf(']', i + 2).takeIf { it > 0 }
                    if (end == null) {
                        append("\\[")
                    } else {
                        val cls = glob.substring(i + 1, end).let { if (it.startsWith("!")) "^" + it.substring(1) else it }
                        append('[').append(cls.replace("\\", "\\\\")).append(']')
                        i = end
                    }
                }

                c == '\\' && i + 1 < glob.length -> {
                    append(Regex.escape(glob[i + 1].toString()))
                    i++
                }

                else -> {
                    append(Regex.escape(c.toString()))
                }
            }
            i++
        }
    }
