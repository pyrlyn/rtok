// Port of src/plugins/graph/walk.rs: gitignore-style `include`/`exclude` rules plus an extension → grammar
// map, shared by the indexer and the watcher (T68.10). `exclude` always wins over `include` (T544).
package rtok.plugins.graph

import rtok.HostPaths
import rtok.config.Graph
import rtok.plugins.read.supported
import rtok.plugins.read.supportedWith
import rtok.sys.*

class Matcher(private val root: String, cfg: Graph) {
    /** `include` patterns; a trailing `/` names the whole subtree. */
    private val include = Gitignore(root, cfg.include.joinToString("\n") { if (it.endsWith("/")) "$it**" else it })
    private val exclude = Gitignore(root, cfg.exclude.joinToString("\n"))
    private val hasInclude = cfg.include.isNotEmpty()
    /** Where each `include` pattern's walk starts, outermost first; one inside an earlier one is walked by it. */
    private val includeStarts: List<String> = cfg.include.map(::includeStart).sorted().fold(ArrayList()) { acc, s ->
        if (acc.none { pathStartsWith(s, it) || it.isEmpty() }) acc += s; acc
    }
    val extensions: Map<String, String> = cfg.extensions

    /** Every entry of the root: the ordinary walk, then the subtrees `include` names with ignore files off, each file once. */
    fun walk(): Sequence<WalkEntry> = sequence {
        val seen = HashSet<String>()
        for (e in walk(root, filter = ::keep)) { if (hasInclude && e.isFile && isIncludedRaw(e.path)) seen += e.path; yield(e) }
        for (start in includeStarts) {
            val dir = if (start.isEmpty()) root else join(root, start)
            for (e in walk(dir, gitIgnore = false, filter = ::keep)) if (e.isFile && isIncluded(e.path) && seen.add(e.path)) yield(e)
        }
    }

    private fun keep(e: WalkEntry) = !(e.depth > 0 && e.isDir && HostPaths.isLinkedWorktree(e.path)) && !excludedEntry(e.path, e.isDir)

    private fun isIncludedRaw(path: String) = include.matched(path, false) == true

    /** Whether an `include` pattern names [path] and no `exclude` covers it. */
    fun isIncluded(path: String) = isIncludedRaw(path) && !isExcluded(path)

    /** Whether [path] is inside a linked worktree nested below the root; a watcher event there is not this index's. */
    fun inLinkedWorktree(path: String): Boolean {
        val rel = stripPrefix(path, root) ?: return false
        var cur = root
        for (c in components(rel).dropLast(1)) { cur = join(cur, c); if (HostPaths.isLinkedWorktree(cur)) return true }
        return HostPaths.isLinkedWorktree(path) && path != root
    }

    private fun excludedEntry(path: String, isDir: Boolean) = exclude.matched(path, isDir) == true

    /** `exclude` against the path or any parent directory below the root (`matched_path_or_any_parents`). */
    fun isExcluded(path: String): Boolean {
        val rel = stripPrefix(path, root) ?: if (path.startsWith("/")) return false else path
        val parts = components(rel)
        for (i in parts.indices) {
            val p = join(root, parts.subList(0, i + 1).joinToString("/"))
            if (exclude.matched(p, i < parts.size - 1) == true) return true
        }
        return false
    }

    fun hasSupportedExt(path: String) = supportedWith(path, extensions)

    /** Whether a file should be indexed: `exclude`, then a built-in or mapped extension. */
    fun indexable(path: String) = !isExcluded(path) && hasSupportedExt(path)

    /** True when [path] is indexable only because of `extensions`. */
    fun usesExtensionMap(path: String): Boolean {
        val ext = extension(path) ?: return false
        return ext in extensions && !supported(path)
    }

    companion object {
        /** Grammar names accepted in `[plugins.graph.extensions]`. */
        val GRAMMAR_NAMES = listOf("rust", "ts", "tsx", "js", "mjs", "cjs", "py", "dart", "c", "h", "go")

        /** Leading literal components of an anchored pattern, else the root (an unanchored pattern matches anywhere). */
        internal fun includeStart(pat: String): String {
            val body = pat.trimEnd('/')
            if ('/' !in body) return ""
            return body.trimStart('/').split('/').takeWhile { it.isNotEmpty() && it.none { c -> c in "*?[{\\" } }.joinToString("/")
        }
    }
}
