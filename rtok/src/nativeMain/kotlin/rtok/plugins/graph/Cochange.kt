// Port of src/plugins/graph/cochange.rs (T371): files that change together in git history, for `impact` and the
// repo map. Pairs are counted from `git log`, kept as one `kv` document per root and recounted only when HEAD moves.
package rtok.plugins.graph

import kotlinx.serialization.json.*
import rtok.plugin.sdk.Ctx
import rtok.sys.isFile
import rtok.sys.join

/** Two files (`a < b`) and the number of commits that touched both. */
data class CochangePair(
    val a: String,
    val b: String,
    val count: Int,
)

object Cochange {
    /** Commits read. A longer window mostly finds pairs from files that no longer exist. */
    private const val COMMITS = "300"

    /** A commit touching more files than this is a rename, a format run or a vendor drop. */
    internal const val MAX_FILES = 20

    /** One shared commit is chance. */
    private const val MIN_COUNT = 2

    /** Partners listed after an `impact` answer. */
    internal const val TOP_PARTNERS = 5

    /** Marks the start of a commit in `git log` output; no file name contains it. */
    private const val COMMIT_MARK = '\u0001'

    internal fun key(root: String) = "cochange:${Index.canon(root)}"

    /** The co-change pairs of [root], most shared commits first; empty when it is not a git repo or git fails. */
    fun pairs(
        cx: Ctx,
        root: String,
    ): List<CochangePair> {
        val head = Git.stdout(root, listOf("rev-parse", "HEAD"))?.decodeToString()?.trim() ?: return emptyList()
        val k = key(root)
        decode(runCatching { cx.pluginStateGet("graph", k) }.getOrNull())?.takeIf { it.first == head }?.let { return it.second }
        // quotepath off keeps non-ASCII names literal; `--relative` makes paths match the index's.
        val log =
            Git.stdout(
                root,
                listOf("-c", "core.quotepath=false", "log", "--name-only", "--relative", "--no-renames", "--format=%x01%H", "-n", COMMITS),
            ) ?: return emptyList()
        val pairs = count(log.decodeToString())
        // A failed write only means the next call recounts.
        runCatching { cx.pluginStateSet("graph", k, encode(head, pairs)) }
        return pairs
    }

    /** The stored document, `{"head":…,"pairs":[[a,b,n],…]}` as the Rust serde tuple layout writes it. */
    internal fun encode(
        head: String,
        pairs: List<CochangePair>,
    ): String =
        buildJsonObject {
            put("head", head)
            putJsonArray("pairs") {
                pairs.forEach { p ->
                    addJsonArray {
                        add(p.a)
                        add(p.b)
                        add(p.count)
                    }
                }
            }
        }.toString()

    internal fun decode(text: String?): Pair<String, List<CochangePair>>? =
        runCatching {
            val o = Json.parseToJsonElement(text ?: return null).jsonObject
            val pairs =
                o["pairs"]!!.jsonArray.map { e ->
                    val t = e.jsonArray
                    CochangePair(t[0].jsonPrimitive.content, t[1].jsonPrimitive.content, t[2].jsonPrimitive.int)
                }
            o["head"]!!.jsonPrimitive.content to pairs
        }.getOrNull()

    /** Pair counts from `git log --name-only --format=%x01%H` output. */
    internal fun count(log: String): List<CochangePair> = countPairs(commits(log))

    /** The file list of each commit, newest first; commits with fewer than 2 or more than [MAX_FILES] files are dropped. */
    internal fun commits(log: String): List<List<String>> =
        log
            .split(COMMIT_MARK)
            .map { commit ->
                // Git quotes names it cannot print plainly; they match no indexed path.
                commit
                    .lines()
                    .drop(1)
                    .filter { it.isNotEmpty() && !it.startsWith('"') }
                    .distinct()
                    .sorted()
            }.filter { it.size in 2..MAX_FILES }

    /** Pairs shared by at least [MIN_COUNT] commits, by count then path, so the document is stable. */
    internal fun countPairs(commits: List<List<String>>): List<CochangePair> {
        val counts = HashMap<Pair<String, String>, Int>()
        for (files in commits) {
            for (i in files.indices) {
                for (j in i + 1 until files.size) {
                    val k = files[i] to files[j]
                    counts[k] = (counts[k] ?: 0) + 1
                }
            }
        }
        return counts
            .filter { it.value >= MIN_COUNT }
            .map { (k, n) -> CochangePair(k.first, k.second, n) }
            .sortedWith(compareByDescending<CochangePair> { it.count }.thenBy { it.a }.thenBy { it.b })
    }

    /** `changes with: a.rs (7), b.rs (4)` for [file]'s top partners, or null without history. Vanished partners are skipped. */
    fun changesWith(
        cx: Ctx,
        root: String,
        file: String,
    ): String? {
        val shown =
            partners(pairs(cx, root), file)
                .filter { isFile(join(root, it.first)) }
                .take(TOP_PARTNERS)
                .map { (other, n) -> "$other ($n)" }
                .toList()
        return if (shown.isEmpty()) null else "changes with: ${shown.joinToString(", ")}"
    }

    /** Who changes with [file], most often first ([pairs] is already in that order). */
    internal fun partners(
        pairs: List<CochangePair>,
        file: String,
    ): Sequence<Pair<String, Int>> =
        pairs.asSequence().mapNotNull { p ->
            when (file) {
                p.a -> p.b to p.count
                p.b -> p.a to p.count
                else -> null
            }
        }
}
