// Port of src/plugins/cmd/catfile.rs (T674): `cat <source file>` and `sed -n a,bp <file>`. A long source
// file prints as its definitions with line spans instead of a head/tail cut, so the agent sees every
// function and can read one body with `sed -n a,bp`. A file printed again in the same session is a pointer
// when unchanged and a diff when it changed (the read cache `rtok read` uses).
package rtok.plugins.cmd

import rtok.cmdStem
import rtok.plugins.read.ReadCache
import rtok.plugins.read.supported
import rtok.plugins.read.tags
import rtok.sys.rustLines

object CatFile {
    /** Files at or under this many lines print whole: an outline would not be shorter. */
    private const val MIN_LINES = 80

    /** Config and markup files have a grammar but no definitions worth an outline. */
    private val DATA_EXTENSIONS = setOf("toml", "json", "yaml", "yml", "md", "markdown", "html", "htm", "css", "xml", "ini", "lock")

    /** The outline of a long source file named by a bare `cat <file>`; null leaves the `cat` rule to apply. */
    fun outlineOf(
        argv: List<String>,
        output: String,
    ): String? {
        if (argv.size != 2) return null
        val (cat, file) = argv
        if (cmdStem(cat) != "cat" || file.startsWith("-")) return null
        val total = output.rustLines().size
        val name = file.substringAfterLast('/')
        val ext = if ('.' in name.drop(1)) name.substringAfterLast('.').lowercase() else ""
        if (total <= MIN_LINES || ext in DATA_EXTENSIONS || !supported(file)) return null
        val defs =
            runCatching { tags(file, output) }
                .getOrNull()
                ?.filter { it.isDef && it.kind != "import" }
                ?.sortedWith(compareBy({ it.line }, { -it.endLine }))
                ?.distinctBy { it.line to it.endLine } ?: return null
        if (defs.isEmpty()) return null
        val out = mutableListOf("$file: $total lines, definitions as start-end (body: sed -n 'A,Bp' $file)")
        val open = ArrayList<Int>()
        for (h in defs) {
            while (open.isNotEmpty() && open.last() < h.line) open.removeAt(open.size - 1)
            out += "  ".repeat(open.size) + "${h.line}-${h.endLine} ${h.lineText.trim()}"
            open += h.endLine
        }
        return out.joinToString("\n")
    }

    /** The read-cache key of `cat [-n] f` or `sed -n A,Bp f`, with the absolute path; null for anything else. */
    fun rereadKey(argv: List<String>): Pair<String, String>? {
        val stem = cmdStem(argv.firstOrNull() ?: return null)
        val rest = argv.drop(1)
        val (file, range) =
            when {
                stem == "cat" && rest.size == 1 && !rest[0].startsWith("-") -> rest[0] to null
                stem == "cat" && rest.size == 2 && rest[0] == "-n" && !rest[1].startsWith("-") -> rest[1] to null
                stem == "sed" && rest.size == 3 && rest[0] == "-n" && isPrintRange(rest[1]) && !rest[2].startsWith("-") -> rest[2] to rest[1]
                else -> return null
            }
        val path = if (file.startsWith("/")) file else "${rtok.sys.cwd()}/$file"
        return path to ReadCache.key(path, "cmd", range)
    }

    /** `5,20p` / `5p`. */
    private fun isPrintRange(spec: String): Boolean {
        if (!spec.endsWith("p")) return false
        val r = spec.dropLast(1)
        val parts = r.split(',')
        return r.isNotEmpty() && parts.size <= 2 && parts.all { it.toUIntOrNull() != null }
    }
}
