// Port of src/plugins/graph/review.rs (T454): a risk-ranked reading list for a git diff. Score endpoints
// follow code-review-graph `changes.py` `compute_risk_score` (flow and community terms omitted). Region
// budgets follow `tools/review.py`: whole hunks, round-robin, one shared line budget. Writes no
// `Measurement`; a shortened answer is capped like the other tools.
package rtok.plugins.graph

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.plugin.sdk.Ctx
import rtok.plugin.sdk.SymDef
import kotlin.math.round

/** One changed region in a file, 1-based inclusive lines in the new file. */
data class Region(
    val start: Int,
    val end: Int,
)

data class FileReview(
    val path: String,
    /** Highest score among scored defs in this file; 0.0 if none were scored. */
    val score: Double,
    /** Changed defs for which no test path is reached. */
    val untested: List<String>,
    var regions: List<Region>,
    /** Regions that lost the shared line budget. */
    var regionsOmitted: Int = 0,
)

object Review {
    /** Shared source lines for one review (`tools/review.py` `_MAX_REVIEW_SOURCE_LINES`). */
    const val SOURCE_BUDGET = 800

    /** One file holds at most this share of [SOURCE_BUDGET]. */
    const val FILE_SHARE = 0.4

    /** Lines either side of a hunk that is not widened to its definition. */
    const val REGION_CONTEXT = 4

    /** A definition at most this many lines is shown whole when a hunk lands in it. */
    const val MAX_WIDEN_SPAN = 40

    /** One granted region, so a single added block cannot take the file share. */
    const val MAX_REGION_LINES = 120

    /** Hard per-file line cap used when computing the share. */
    const val MAX_LINES_PER_FILE = 500

    /** Defs scored in one file before the next file is visited. */
    const val MAX_RISK_NODES_PER_FILE = 8

    /** Defs scored across the whole diff. */
    const val MAX_RISK_SCORED_NODES = 400
    private const val WALK_DEPTH = 3

    /** Whole-token keywords. Substring matches (`sign` inside `assign`) are not hits. */
    private val SECURITY =
        setOf(
            "auth",
            "login",
            "password",
            "token",
            "session",
            "crypt",
            "secret",
            "credential",
            "permission",
            "sql",
            "query",
            "execute",
            "connect",
            "socket",
            "request",
            "http",
            "sanitize",
            "validate",
            "encrypt",
            "decrypt",
            "hash",
            "sign",
            "verify",
            "admin",
            "privilege",
        )

    /** [callers] is depth-1 impact rows, [tested] test-path reachability, [security] a whole-token keyword hit. */
    fun riskScore(
        callers: Int,
        tested: Boolean,
        security: Boolean,
    ): Double {
        var score = if (tested) 0.05 else 0.30
        if (security) score += 0.20
        score += minOf(callers / 20.0, 0.10)
        return round(score.coerceIn(0.0, 1.0) * 10_000.0) / 10_000.0
    }

    fun level(score: Double) =
        when {
            score > 0.7 -> "high"
            score > 0.4 -> "medium"
            else -> "low"
        }

    /** `true` when a snake_case or camelCase token is a security keyword. */
    fun securityToken(name: String) = identifierTokens(name).any { it in SECURITY }

    private fun identifierTokens(name: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()

        fun push() {
            if (cur.isNotEmpty()) out += cur.toString().lowercase()
            cur.clear()
        }
        for (c in name) {
            if (c == '_' || c == '-') {
                push()
                continue
            }
            if (c.isUpperCase() && cur.isNotEmpty()) push()
            cur.append(c)
        }
        push()
        return out
    }

    /** Parses `git diff -U0` hunk headers; keys are repo-relative paths using `/`, in order. */
    fun parseHunks(diff: String): Map<String, List<Pair<Int, Int>>> {
        val out = HashMap<String, MutableList<Pair<Int, Int>>>()
        var path: String? = null
        for (line in diff.lines()) {
            if (line.startsWith("+++ ")) {
                val rest = line.removePrefix("+++ ")
                val raw = rest.split(' ', '\t').firstOrNull { it.isNotEmpty() } ?: rest
                path = if (raw == "/dev/null") null else raw.removePrefix("b/").replace('\\', '/')
                continue
            }
            if (!line.startsWith("@@ ")) continue
            val p = path ?: continue
            hunkHeader(line.removePrefix("@@ "))?.let { out.getOrPut(p) { ArrayList() } += it }
        }
        return out.entries.sortedBy { it.key }.associate { it.key to it.value }
    }

    private fun hunkHeader(rest: String): Pair<Int, Int>? {
        val plus = rest.indexOf('+').takeIf { it >= 0 } ?: return null
        val spec = rest.substring(plus + 1).split(' ', '\t').firstOrNull { it.isNotEmpty() } ?: return null
        val parts = spec.split(',')
        val start = parts[0].toUIntOrNull()?.toInt()
        val len = if (parts.size > 1) parts[1].toUIntOrNull()?.toInt() else 1
        return if (start == null || len == null || start == 0 || len == 0) null else start to start + len - 1
    }

    private fun regionLen(r: Region) = (r.end - r.start).coerceAtLeast(0) + 1

    /** Widens each hunk to an enclosing definition of at most [MAX_WIDEN_SPAN] lines, else pads it; caps a region. */
    fun widen(
        hunks: List<Pair<Int, Int>>,
        defs: List<SymDef>,
    ): List<Region> {
        val regions = ArrayList<Region>()
        for ((hs, he) in hunks) {
            var start = maxOf(hs - REGION_CONTEXT, 1)
            var end = he + REGION_CONTEXT
            val best =
                defs
                    .filter { it.line >= 1 && it.endLine >= it.line && it.line <= hs && it.endLine >= he && it.endLine - it.line + 1 <= MAX_WIDEN_SPAN }
                    .fold(null as Pair<Int, Int>?) { b, d ->
                        if (b == null || d.endLine - d.line + 1 < b.second - b.first + 1) d.line to d.endLine else b
                    }
            if (best != null) {
                start = best.first
                end = best.second
            }
            if (end - start + 1 > MAX_REGION_LINES) {
                end = he
                start = maxOf(end - (MAX_REGION_LINES - 1), 1)
                if (start > hs) {
                    start = hs
                    end = start + MAX_REGION_LINES - 1
                }
            }
            regions += Region(start, end)
        }
        return merge(regions)
    }

    private fun merge(regions: List<Region>): List<Region> {
        val merged = ArrayList<Region>()
        for (r in regions.sortedWith(compareBy({ it.start }, { it.end }))) {
            val last = merged.lastOrNull()
            if (last != null && r.start <= last.end + 1) {
                merged[merged.size - 1] = last.copy(end = maxOf(last.end, r.end))
            } else {
                merged += r
            }
        }
        return merged
    }

    private fun shareCap(total: Int) = (total * FILE_SHARE).toInt().coerceIn(1, MAX_LINES_PER_FILE)

    /** Grants whole regions from [totalBudget], round-robin in the order of [files] (highest score first). */
    fun allocateBudget(
        files: List<FileReview>,
        totalBudget: Int,
    ) {
        val pending = files.map { it.regions }
        for (f in files) {
            f.regions = emptyList()
            f.regionsOmitted = 0
        }
        if (files.isEmpty() || totalBudget == 0) {
            files.forEachIndexed { i, f -> f.regionsOmitted = pending[i].size }
            return
        }
        val share = shareCap(totalBudget)
        val regionCap = minOf(MAX_REGION_LINES, share)
        val used = IntArray(files.size)
        var remaining = totalBudget
        val granted = List(files.size) { ArrayList<Region>() }
        for (depth in 0 until (pending.maxOfOrNull { it.size } ?: 0)) {
            if (remaining == 0) break
            for (i in files.indices) {
                val region = pending[i].getOrNull(depth) ?: continue
                if (remaining == 0) continue
                val cost = minOf(regionLen(region), regionCap)
                if (cost > minOf(remaining, (share - used[i]).coerceAtLeast(0))) continue
                granted[i] += Region(region.start, region.start + cost - 1)
                used[i] += cost
                remaining -= cost
            }
        }
        files.forEachIndexed { i, f ->
            f.regionsOmitted = (pending[i].size - granted[i].size).coerceAtLeast(0)
            f.regions = granted[i].sortedWith(compareBy({ it.start }, { it.end }))
        }
    }

    fun allocate(files: List<FileReview>) = allocateBudget(files, SOURCE_BUDGET)

    private fun changedFiles(
        root: String,
        since: String?,
        staged: Boolean,
    ): List<String> =
        Git
            .stdoutResult(root, Affected.gitDiffArgs(since, staged, listOf("--name-only", "--relative", "-z")))
            .getOrThrow()
            .decodeToString()
            .split('\u0000')
            .filter { it.isNotEmpty() }
            .map { it.replace('\\', '/') }

    private fun hunksOf(
        root: String,
        since: String?,
        staged: Boolean,
    ): Map<String, List<Pair<Int, Int>>> {
        val out = Git.stdoutResult(root, Affected.gitDiffArgs(since, staged, listOf("-U0", "--relative"))).getOrThrow()
        val text = runCatching { out.decodeToString(throwOnInvalidSequence = true) }.getOrElse { throw IllegalStateException("git diff failed: $it") }
        return parseHunks(text)
    }

    private fun scoreFile(
        cx: Ctx,
        key: String,
        path: String,
        names: List<String>,
        memo: WalkMemo,
    ): Pair<Double, List<String>> {
        var score = 0.0
        val untested = ArrayList<String>()
        val inTest = Dead.isTestPath(path)
        for (name in names) {
            val rows = Impact.walkRoots(cx, listOf(key), name, WALK_DEPTH, true, memo)
            val callers = rows.count { it.depth == 1 }
            val tested = inTest || rows.any { Dead.isTestPath(it.path) }
            score = maxOf(score, riskScore(callers, tested, securityToken(name)))
            if (!tested) untested += name
        }
        return score to untested
    }

    private fun render(
        files: List<FileReview>,
        json: Boolean,
    ): String {
        val overall = files.maxOfOrNull { it.score }?.coerceAtLeast(0.0) ?: 0.0
        if (json) {
            // serde_json without `preserve_order` writes keys sorted.
            val body =
                JsonObject(
                    sortedKeys(
                        "level" to JsonPrimitive(level(overall)),
                        "score" to JsonPrimitive(overall),
                        "files" to JsonArray(files.map(::fileJson)),
                    ),
                )
            return "$body\n"
        }
        val out = StringBuilder("review: ${level(overall)} ${Draw.fixed(overall, 4)}\n")
        for (f in files) {
            out.append("${f.path} ${Draw.fixed(f.score, 4)}")
            if (f.untested.isNotEmpty()) out.append(" untested: ").append(f.untested.joinToString(", "))
            out.append('\n')
            for (r in f.regions) out.append("  L${r.start}-${r.end}\n")
        }
        for (f in files) {
            if (f.regionsOmitted > 0) out.append("omitted: ${f.path} ${f.regionsOmitted} ${if (f.regionsOmitted == 1) "region" else "regions"}\n")
        }
        return out.toString()
    }

    /** serde_json without `preserve_order` writes object keys sorted. */
    private fun sortedKeys(vararg kv: Pair<String, kotlinx.serialization.json.JsonElement>) = kv.sortedBy { it.first }.toMap()

    private fun fileJson(f: FileReview) =
        JsonObject(
            sortedKeys(
                "path" to JsonPrimitive(f.path),
                "score" to JsonPrimitive(f.score),
                "untested" to JsonArray(f.untested.map(::JsonPrimitive)),
                "regions" to JsonArray(f.regions.map { JsonObject(sortedKeys("end" to JsonPrimitive(it.end), "start" to JsonPrimitive(it.start))) }),
                "regions_omitted" to JsonPrimitive(f.regionsOmitted),
            ),
        )

    /** Index [root], read the diff, score, allocate, render. An empty diff is the line `no changes`. */
    fun review(
        cx: Ctx,
        root: String,
        since: String?,
        staged: Boolean,
        json: Boolean,
    ): String {
        val paths = changedFiles(root, since, staged)
        if (paths.isEmpty()) return "no changes\n"
        GraphCore.indexFor(cx, root)
        val key = Index.canon(root)
        val hunks = hunksOf(root, since, staged)
        var remaining = MAX_RISK_SCORED_NODES
        // The index is not written again before `review` returns, so every walk may share answers.
        val memo = WalkMemo()
        val files =
            paths
                .map { path ->
                    val stored = cx.symbolFileDefs(key, path)
                    val names =
                        stored
                            .filter { it.kind in GraphDiffs.SCORED_KINDS }
                            .take(MAX_RISK_NODES_PER_FILE)
                            .map { it.a }
                            .take(remaining)
                    remaining -= names.size
                    val (score, untested) = if (names.isEmpty()) 0.0 to emptyList() else scoreFile(cx, key, path, names, memo)
                    FileReview(path, score, untested, widen(hunks[path].orEmpty(), stored))
                }.sortedByDescending { it.score }
        allocate(files)
        return Tools.cap(cx, render(files, json))
    }
}
