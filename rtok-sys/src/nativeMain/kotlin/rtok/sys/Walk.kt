// Directory walk with the `ignore::WalkBuilder` defaults rtok uses (`hidden(false)`): inside a git
// repository, `.gitignore`/`.ignore` files from the repository root down and `.git/info/exclude`
// apply; `.git` is never entered; siblings come sorted by name, depth first.
package rtok.sys

class WalkEntry(
    val path: String,
    val depth: Int,
    val isDir: Boolean,
    val isFile: Boolean,
)

/**
 * Walks [root] (yielded first, depth 0). [maxDepth] limits depth like `max_depth`; [filter] prunes an
 * entry (and its subtree for directories) like `filter_entry`. Paths are yielded under [root]'s spelling;
 * ignore rules match against the canonical path.
 */
fun walk(
    root: String,
    maxDepth: Int? = null,
    gitIgnore: Boolean = true,
    filter: (WalkEntry) -> Boolean = { true },
): Sequence<WalkEntry> =
    sequence {
        val rootEntry = WalkEntry(root, 0, isDir(root), isFile(root))
        if (!filter(rootEntry)) return@sequence
        yield(rootEntry)
        if (!rootEntry.isDir) return@sequence
        val canonRoot = canon(root)
        val repo = if (gitIgnore) ancestors(canonRoot).firstOrNull { exists(join(it, ".git")) } else null
        val inherited = ArrayList<Gitignore>()
        if (repo != null) {
            readIgnore(join(repo, ".git/info/exclude"), repo)?.let(inherited::add)
            for (dir in ancestors(canonRoot).reversed()) if (dir != canonRoot && pathStartsWith(dir, repo)) inherited += dirRules(dir)
        }

        fun canonOf(path: String) = if (path == root) canonRoot else canonRoot + path.substring(root.length)

        suspend fun SequenceScope<WalkEntry>.visit(
            dir: String,
            depth: Int,
            rules: List<Gitignore>,
        ) {
            if (maxDepth != null && depth >= maxDepth) return
            val local = if (repo != null) rules + dirRules(canonOf(dir)) else rules
            for (name in listDir(dir).sorted()) {
                if (name == ".git") continue
                val path = if (dir.isEmpty()) name else "${dir.trimEnd('/')}/$name"
                val d = isDir(path)
                if (repo != null && ignored(local, canonOf(path), d)) continue
                val e = WalkEntry(path, depth + 1, d, !d && isFile(path))
                if (!filter(e)) continue
                yield(e)
                if (d) visit(path, depth + 1, local)
            }
        }
        visit(root, 0, inherited)
    }

private fun dirRules(dir: String): List<Gitignore> = listOfNotNull(readIgnore(join(dir, ".gitignore"), dir), readIgnore(join(dir, ".ignore"), dir))

/** Deepest file wins, and within a file the last matching rule (see [Gitignore.matched]). */
private fun ignored(
    rules: List<Gitignore>,
    path: String,
    isDir: Boolean,
): Boolean {
    for (r in rules.asReversed()) r.matched(path, isDir)?.let { return it }
    return false
}

private fun readIgnore(
    file: String,
    base: String,
): Gitignore? = if (isFile(file)) runCatching { Gitignore(base, readToString(file)) }.getOrNull() else null
