// Port of src/measure/codex.rs: the transcript walk shared by the readers (`jsonl_paths`, `paths_with_ext`) and
// Codex rollouts' `token_count` requests (`requests`).
package rtok.measure

import rtok.sys.EntryKind
import rtok.sys.canonicalize
import rtok.sys.entryKind
import rtok.sys.extension
import rtok.sys.fileName
import rtok.sys.fileStat
import rtok.sys.isDir
import rtok.sys.join
import rtok.sys.listDir
import rtok.sys.readToString

/** Every `*.jsonl` under [dir] (recursive) modified at or after [cutoff] (unix seconds). */
fun jsonlPaths(
    dir: String,
    cutoff: Long,
): List<String> = pathsWithExt(dir, cutoff, "jsonl")

/**
 * [jsonlPaths] for any one extension. Symlinks are followed, but every directory and file is identified by its
 * canonical path, so an alias never yields the same transcript twice and a cycle ends; real entries win over links.
 */
fun pathsWithExt(
    dir: String,
    cutoff: Long,
    ext: String,
): List<String> = canonicalize(dir)?.let { TreeWalk(cutoff * NANOS, ext).run(dir, it) } ?: emptyList()

private const val NANOS = 1_000_000_000L

/** The state of one [pathsWithExt] walk: real directories are drained before linked ones, file links come last. */
private class TreeWalk(
    val cutoffNanos: Long,
    val ext: String,
) {
    val seenDirs = HashSet<String>()
    val seenFiles = HashSet<String>()
    val out = ArrayList<String>()
    val late = ArrayList<Pair<String, String>>()
    val real = ArrayList<Pair<String, String>>()
    val linked = ArrayList<Pair<String, String>>()

    fun run(
        dir: String,
        root: String,
    ): List<String> {
        real += dir to root
        while (true) {
            val (d, canon) = real.removeLastOrNull() ?: linked.removeLastOrNull() ?: break
            if (seenDirs.add(canon)) runCatching { listDir(d) }.getOrNull()?.forEach { visit(join(d, it), join(canon, it)) }
        }
        late.filter { seenFiles.add(it.second) }.mapTo(out) { it.first }
        return out
    }

    /** One entry: a directory is queued, a matching file recorded; a dangling or unreadable entry is skipped. */
    fun visit(
        p: String,
        canonJoined: String,
    ) {
        val kind = entryKind(p)
        val isLink = kind == EntryKind.Symlink
        val mtime = fileStat(p)?.first
        val canon = if (isLink) canonicalize(p) else canonJoined
        when {
            kind == null || mtime == null || canon == null -> Unit
            if (isLink) isDir(p) else kind == EntryKind.Dir -> (if (isLink) linked else real) += p to canon
            extension(p) != ext || mtime < cutoffNanos -> Unit
            isLink -> late += p to canon
            seenFiles.add(canon) -> out += p
        }
    }
}

/** One `token_count` line in the proxy's vocabulary (`input` is the uncached part). */
data class Request(
    val session: String,
    val model: String?,
    val ts: Long,
    val input: Long,
    val cacheRead: Long,
    val cacheCreate: Long,
    val output: Long,
)

/** Every `token_count` request under [dir], oldest file first, and the first rollout unreadable or with no JSON line. */
fun requests(
    dir: String,
    cutoff: Long,
): Pair<List<Request>, String?> {
    val out = ArrayList<Request>()
    var unreadable: String? = null
    for (p in jsonlPaths(dir, cutoff).sorted()) {
        val text = runCatching { readToString(p) }.getOrNull()
        val jsonLines = text?.let { rolloutRequests(p, it, out) }
        if (jsonLines == null || (jsonLines == 0 && text.isNotBlank())) unreadable = unreadable ?: p
    }
    return out to unreadable
}

/** One rollout's requests into [out]; the number of JSON lines it held. */
private fun rolloutRequests(
    path: String,
    text: String,
    out: MutableList<Request>,
): Int {
    var jsonLines = 0
    var session = fileName(path)!!.removeSuffix(".jsonl")
    var model: String? = null
    for (v in text.lines().mapNotNull(::parseJson)) {
        jsonLines++
        v.pointer("/payload/model").str()?.let { model = it }
        if (v.field("type").str() == "session_meta") v.pointer("/payload/id").str()?.let { session = it }
        val u = v.pointer("/payload/info/last_token_usage")?.takeIf { v.pointer("/payload/type").str() == "token_count" }
        if (u != null) {
            fun n(k: String) = u.field(k).int() ?: 0
            val cached = n("cached_input_tokens")
            out += Request(session, model, lineTs(v), n("input_tokens") - cached, cached, n("cache_write_input_tokens"), n("output_tokens"))
        }
    }
    return jsonLines
}
