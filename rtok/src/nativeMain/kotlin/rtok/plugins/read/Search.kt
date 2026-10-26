// Port of src/plugins/read/search.rs (T4.5): gitignore-aware regex hits and a size listing over `rtok.sys.walk`.
package rtok.plugins.read

import rtok.Expand
import rtok.HostPaths
import rtok.config.HostFs
import rtok.config.ReadFs
import rtok.plugin.sdk.Ctx
import rtok.sys.*

object Search {
    /** Canonical walk base, computed once per call (T59.2); per-row [displayRel] never touches the disk. */
    internal fun canonicalBase(fs: ReadFs, cwd: String) = fs.canonicalize(cwd) ?: cwd

    /** Relative to the canonical cwd, else to the walk root, else the raw path; the root itself shows its name. */
    internal fun displayRel(path: String, root: String, base: String): String {
        val rel = stripPrefix(path, base) ?: stripPrefix(path, root) ?: path
        return rel.ifEmpty { fileName(path).orEmpty() }
    }

    /**
     * Walk filter: no linked worktree below the root (another checkout's files, T660.3), and with credential files
     * refused in this process (`rtok mcp --http`) no credential file either. `.git` is never entered by the walk.
     */
    internal fun walkFilter(denySecrets: Boolean): (WalkEntry) -> Boolean = { e ->
        !(e.depth > 0 && e.isDir && HostPaths.isLinkedWorktree(e.path)) && !(denySecrets && HostPaths.isSecret(e.path))
    }

    /** Regular files the walk reaches as `(path, text)`, one at a time; over [maxBytes] or not UTF-8 is skipped unread (T55.5). */
    internal fun textFiles(walk: Sequence<WalkEntry>, maxBytes: ULong): Sequence<Pair<String, String>> =
        walk.filter { it.isFile }.mapNotNull { e ->
            val len = fileLen(e.path) ?: return@mapNotNull null
            if (len.toULong() > maxBytes) return@mapNotNull null
            val text = runCatching { readBytes(e.path).decodeToString(throwOnInvalidSequence = true) }.getOrNull() ?: return@mapNotNull null
            e.path to text
        }

    /** `path:line: snippet` rows (snippet trimmed, 120 chars), at most [cap]; a full set never costs another read. */
    internal fun hits(files: Sequence<Pair<String, String>>, re: Regex, cap: Int, rel: (String) -> String): List<String> {
        val hits = ArrayList<String>()
        val it = files.iterator()
        while (hits.size < cap && it.hasNext()) {
            val (path, text) = it.next()
            val shown = rel(path)
            for ((i, line) in text.rustLines().withIndex()) {
                if (hits.size >= cap) break
                if (!re.containsMatchIn(line)) continue
                hits += "$shown:${i + 1}: ${line.trim().takeScalars(120)}"
            }
        }
        return hits
    }

    /** `path:line: snippet` rows, at most [max] (default `plugins.read.search_max`). */
    fun search(cx: Ctx, pattern: String, path: String, max: UInt?): String {
        val cfg = readCfg(cx)
        val cwd = Roots.cwd()
        // A pathless search from `$HOME` or `/` searches the client root that lies in a repository.
        val noPath = path.isEmpty() || path == "."
        val root = if (noPath && isUnwalkableRoot(cwd, homeDir())) rtok.projectDir(cwd, Roots.clientRoots())
            else resolve(cwd, path.ifEmpty { "." }, cfg.allowPaths)
        walkRootOk(root)
        val cap = (max ?: cfg.searchMax).coerceAtLeast(1u).toInt()
        // Same grammar as `expand --grep`: an invalid regex searches literally.
        val re = Expand.regexOrLiteral(pattern)
        val base = canonicalBase(HostFs, cwd)
        val files = textFiles(walk(root, filter = walkFilter(Roots.secretsDenied())), cfg.searchMaxBytes)
        return capRecording(cx, hits(files, re, cap) { displayRel(it, root, base) }.joinToString("\n"), "search_cap")
    }

    /** Compact listing `path size` down to [depth] (default `plugins.read.tree_depth`). */
    fun tree(cx: Ctx, path: String, depth: UInt?): String {
        val cfg = readCfg(cx)
        val cwd = Roots.cwd()
        val root = resolve(cwd, path.ifEmpty { "." }, cfg.allowPaths)
        walkRootOk(root)
        val d = (depth ?: cfg.treeDepth).coerceAtLeast(1u).toInt()
        val base = canonicalBase(HostFs, cwd)
        val rows = walk(root, maxDepth = d, filter = walkFilter(false)).filter { it.path != root }
            .map { "${displayRel(it.path, root, base)} ${fileLen(it.path) ?: 0}" }.toList().sortedWith(::compareUtf8)
        return capRecording(cx, rows.joinToString("\n"), "tree_cap")
    }
}
