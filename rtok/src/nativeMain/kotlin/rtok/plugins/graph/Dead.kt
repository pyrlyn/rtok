// Port of `dead` (T52.4) and `affected` (T68.5, T372) from src/plugins/graph/mod.rs: unreferenced private
// definitions, and the tests that reach changed files.
package rtok.plugins.graph

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import rtok.plugin.sdk.Ctx
import rtok.plugins.read.Languages
import rtok.sys.readBytes
import rtok.sys.rustLines

/** One `dead()` row: an unreferenced private definition's location. */
@Serializable
data class DeadRow(
    val path: String,
    val line: Int,
    val kind: String,
    val name: String,
)

/** Affected tests as `(file, via symbol)`; read back sorted. */
typealias Hits = MutableSet<Pair<String, String>>

object Dead {
    /** [GraphCore.indexFor], then [candidates]: the computation behind `dead()` and `graph dead --json`. */
    fun rows(
        cx: Ctx,
        root: String,
    ): List<DeadRow> {
        // T329.10: a text search has no reference edges, so "unreferenced" would be a guess.
        if (Text.applies(cx, root, Tools.lsp::probe)) throw IllegalStateException("dead: ${Text.UNAVAILABLE}")
        GraphCore.indexFor(cx, root)
        return candidates(cx, root)
    }

    /** Unreferenced private definitions read from the store as it stands; no index walk. */
    fun candidates(
        cx: Ctx,
        root: String,
    ): List<DeadRow> {
        val files = HashMap<String, List<String>>()
        val out = ArrayList<DeadRow>()
        for (c in cx.symbolDeadCandidates(Index.canon(root))) {
            if (c.kind == "macro" || c.name == "main" || isTestPath(c.path)) continue
            val src = files.getOrPut(c.path) { runCatching { readBytes("$root/${c.path}").decodeToString() }.getOrDefault("").rustLines() }
            val at = maxOf(c.line, 1) - 1
            val def = src.getOrNull(at).orEmpty().trimStart()
            if (def.startsWith("pub ") || def.startsWith("pub(") || def == "pub") continue
            if (hasTestAttr(src.subList(0, minOf(at, src.size)))) continue
            if (c.path.endsWith(".rs")) {
                val (ranges, types) = rustImpls(src.joinToString("\n"))
                if (c.name in types) continue
                if (c.kind == "method" && ranges.any { (s, e) -> c.line in s..e }) continue
            }
            out += DeadRow(c.path, c.line, c.kind, c.name)
        }
        return out
    }

    /** Whether the attribute/comment block right above a definition marks a test. */
    fun hasTestAttr(above: List<String>): Boolean =
        above
            .asReversed()
            .map { it.trimStart() }
            .takeWhile { it.startsWith("#[") || it.startsWith("//") }
            .any { t -> listOf("#[test", "#[cfg(test", "#[rstest", "#[case").any(t::startsWith) || t.split('(', ']').first().endsWith("::test") }

    /** `path:line kind name` lines, capped. */
    fun dead(
        cx: Ctx,
        root: String,
    ): String {
        if (Text.applies(cx, root, Tools.lsp::probe)) return "(text)\ndead: ${Text.UNAVAILABLE}"
        val rows = rows(cx, root)
        if (rows.isEmpty()) return "no dead code in $root"
        return Tools.cap(cx, rows.joinToString("") { "${it.path}:${it.line} ${it.kind} ${it.name}\n" })
    }

    /** Test files by path: `tests/` dirs and test-named files. */
    fun isTestPath(path: String): Boolean =
        path == "tests" || path.startsWith("tests/") || path.contains("/tests/") || path.contains("/__tests__/") ||
            path.substringAfterLast('/').let { f -> f.startsWith("test_") || f.startsWith("_test") || f.contains("_test.") || f.contains(".test.") || f.contains(".spec.") }

    /** Fixtures, benchmarks and examples by directory: code that ships to no user. */
    fun isSupportPath(path: String): Boolean = path.split('/').dropLast(1).any { it in setOf("fixtures", "fixture", "testdata", "bench", "benches", "examples") }

    /** 1-based line ranges of trait impls and trait bodies, plus every `impl` target type name. */
    fun rustImpls(src: String): Pair<List<Pair<Int, Int>>, List<String>> {
        val ranges = ArrayList<Pair<Int, Int>>()
        val types = ArrayList<String>()
        val bytes = src.encodeToByteArray()
        val tree = Languages.grammar("rs")?.parse(bytes) ?: return ranges to types
        tree.use {
            fun walk(n: rtok.treesitter.Node) {
                if (n.kind == "impl_item") {
                    n.childByFieldName("type")?.let { t ->
                        types +=
                            bytes
                                .copyOfRange(t.startByte, t.endByte)
                                .decodeToString()
                                .substringBefore('<')
                                .trim()
                    }
                    if (n.childByFieldName("trait") != null) ranges += (n.start.row + 1) to (n.end.row + 1)
                }
                if (n.kind == "trait_item") ranges += (n.start.row + 1) to (n.end.row + 1)
                for (c in n.children()) walk(c)
            }
            walk(it.root)
        }
        return ranges to types
    }
}

object Affected {
    const val EMPTY = "no indexed test reaches the change; run the suite"

    fun relOf(
        root: String,
        path: String,
    ): String {
        val p = path.replace('\\', '/').removePrefix("./")
        // `Path::join` keeps an absolute path as it is.
        val abs = rtok.sys.canonicalize(if (p.startsWith("/")) p else "$root/$p")
        val r = rtok.sys.canonicalize(root)
        if (abs == null || r == null) return p
        return if (abs.startsWith("$r/")) abs.substring(r.length + 1) else p
    }

    /** Tests that reach files changed in git (`git diff --name-only`). No Measurement. */
    fun affected(
        cx: Ctx,
        root: String,
        since: String?,
        staged: Boolean,
        json: Boolean,
    ) = fromPaths(cx, root, gitChangedFiles(root, since, staged), 3, json)

    fun fromPaths(
        cx: Ctx,
        root: String,
        paths: List<String>,
        depth: Int,
        json: Boolean,
    ): String {
        GraphCore.indexFor(cx, root)
        val key = Index.canon(root)
        val (hits, starts) = changedStarts(cx, root, key, paths)
        for (name in starts) for (r in Impact.bfs(cx, key, name, depth)) if (Dead.isTestPath(r.path)) hits += r.path to viaOf(name, r.scope)
        tallyTests(root, hits)
        return format(hits, json)
    }

    fun tallyTests(
        root: String,
        hits: Set<Pair<String, String>>,
    ) {
        if (hits.isNotEmpty()) Tally.hit(root, "", hits.map { it.first })
    }

    private val order = compareBy<Pair<String, String>> { it.first }.thenBy { it.second }

    fun newHits(): Hits = HashSet()

    fun sorted(hits: Set<Pair<String, String>>) = hits.sortedWith(order)

    /** The test files changed paths hit directly or by name, and the definitions the walk climbs from. */
    fun changedStarts(
        cx: Ctx,
        root: String,
        key: String,
        paths: List<String>,
    ): Pair<Hits, Set<String>> {
        val indexed = cx.symbolStats(key).keys
        val hits = newHits()
        val starts = LinkedHashSet<String>()
        for (raw in paths) {
            val rel = relOf(root, raw)
            for (c in nameLinkedTests(rel)) if (c in indexed && Dead.isTestPath(c)) hits += c to "(by name)"
            for (name in defsInPath(cx, root, key, rel)) {
                if (Dead.isTestPath(rel)) hits += rel to name
                starts += name
            }
        }
        return hits to starts
    }

    fun viaOf(
        name: String,
        scope: String,
    ) = scope.ifEmpty { name }

    /** T372: candidate test paths linked by naming convention to [rel]. */
    fun nameLinkedTests(rel: String): List<String> {
        val file = rel.substringAfterLast('/')
        val parent = rel.substringBeforeLast('/', "").takeIf { it != "." }.orEmpty()
        val dot = file.lastIndexOf('.')
        val stem = if (dot > 0) file.substring(0, dot) else file
        if (stem.isEmpty()) return emptyList()
        val ext = if (dot > 0) file.substring(dot + 1).lowercase() else ""

        fun join(f: String) = if (parent.isEmpty()) f else "$parent/$f"
        return when (ext) {
            "rs" -> listOf("tests/$stem.rs", join("${stem}_test.rs"))
            "ts", "tsx", "js", "jsx", "mjs", "cjs" -> listOf(join("$stem.test.$ext"), join("$stem.spec.$ext"), join("__tests__/$stem.$ext"))
            "py" -> listOf(join("test_$stem.py"), join("${stem}_test.py"))
            "go" -> listOf(join("${stem}_test.go"))
            else -> emptyList()
        }
    }

    private fun defsInPath(
        cx: Ctx,
        root: String,
        key: String,
        rel: String,
    ): List<String> {
        val abs = "$root/$rel"
        val src = runCatching { readBytes(abs).decodeToString() }.getOrDefault("")
        val seen = HashSet<String>()
        return rtok.plugins.read
            .tags(abs, src)
            .filter { h -> h.isDef && seen.add(h.name) && cx.symbolDefs(key, h.name).any { it.a == rel } }
            .map { it.name }
    }

    /** `git diff` argv: `diff`, then [extra], then `--cached` and the ref. */
    fun gitDiffArgs(
        since: String?,
        staged: Boolean,
        extra: List<String>,
    ) = buildList {
        add("diff")
        addAll(extra)
        if (staged) add("--cached")
        if (since != null) add(since)
    }

    fun gitChangedFiles(
        root: String,
        since: String?,
        staged: Boolean,
    ): List<String> =
        (Git.stdout(root, gitDiffArgs(since, staged, listOf("--name-only", "--relative", "-z"))) ?: ByteArray(0))
            .decodeToString()
            .split('\u0000')
            .filter { it.isNotEmpty() }

    fun testCommand(
        path: String,
        name: String,
    ): String? =
        when (path.substringAfterLast('/').substringAfterLast('.', "")) {
            "rs" -> "cargo test $name"
            "py" -> "pytest $path::$name"
            "go" -> "go test -run $name"
            "ts", "tsx", "js", "mjs", "cjs", "jsx" -> "vitest $path"
            else -> null
        }

    fun testsJson(hits: Set<Pair<String, String>>) =
        buildJsonArray {
            for ((file, symbol) in sorted(hits)) {
                // serde_json without preserve_order writes keys sorted.
                add(
                    buildJsonObject {
                        put("command", testCommand(file, symbol)?.let(::JsonPrimitive) ?: JsonNull)
                        put("file", file)
                        put("symbol", symbol)
                    },
                )
            }
        }

    fun format(
        hits: Set<Pair<String, String>>,
        json: Boolean,
    ): String {
        if (json) {
            val tests = testsJson(hits)
            return (
                if (tests.isEmpty()) {
                    buildJsonObject {
                        put("message", EMPTY)
                        put("tests", tests)
                    }
                } else {
                    buildJsonObject { put("tests", tests) }
                }
            ).toString()
        }
        if (hits.isEmpty()) return EMPTY
        return sorted(hits).joinToString("") { (f, s) -> "$f ← via $s\n" + (testCommand(f, s)?.let { "$it\n" } ?: "") }
    }
}
