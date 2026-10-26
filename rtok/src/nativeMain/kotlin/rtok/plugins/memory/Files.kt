// Port of src/plugins/memory/files.rs (T374, T452, T660.2): which project files a note, a prompt or
// the session is about. Untrusted text only yields a path once it is relative, free of `..` and
// inside the project root; paths are stored root-relative.
package rtok.plugins.memory

import rtok.config.ProjectRoot
import rtok.config.contain
import rtok.plugin.sdk.Ctx
import rtok.plugin.sdk.NoteHit
import rtok.plugins.checkpoint.lastPaths
import rtok.projectName
import rtok.store.rrfMergeLists
import rtok.sys.*

/** Candidate tokens statted per text: the hook path must stay cheap. */
internal const val MAX_CANDIDATES = 32

/** Files in play per recall; bounds the store's `IN (…)` list. */
private const val MAX_FILES = 256

/** Named-file notes recalled without a text match, per prompt. */
private const val MAX_NAMED_EXTRA = 2

/** The checkout [cwd] lies in, else [cwd] itself. */
internal fun projectRoot(cwd: String?): String? {
    val dir = cwd ?: runCatching { rtok.sys.cwd() }.getOrNull() ?: return null
    return ProjectRoot.find(dir)?.checkout ?: dir
}

/** Root plus its canonical form, as every lookup below needs both. */
private class RootPair(
    val root: String,
    val canon: String,
)

private fun rootOf(cx: Ctx): RootPair? = projectRoot(cx.cwd())?.let { RootPair(it, canonicalize(it) ?: it) }

/** [raw] as a root-relative `/`-separated path, or null when it is outside [root] or climbs. */
internal fun relative(
    root: String,
    canon: String,
    raw: String,
): String? {
    val rel = if (raw.startsWith('/')) stripPrefix(raw, root) ?: stripPrefix(raw, canon) ?: return null else raw
    val parts = mutableListOf<String>()
    for (c in rel.split('/')) {
        when (c) {
            "", "." -> {}

            ".." -> {
                return null
            }

            else -> {
                parts += c
            }
        }
    }
    if (parts.isEmpty()) return null
    val joined = parts.joinToString("/")
    // The component check is lexical; a symlink inside the project can still point out of it.
    try {
        contain(root, joined)
    } catch (_: IoException) {
        return null
    }
    return joined
}

/** Strip `:12`, `:12-30`, `#L12` and prose punctuation; a leading `.` is path syntax. */
internal fun clean(token: String): String {
    var t = token.trimStart { it in ":,;!?" }.trimEnd { it in ".:,;!?" }
    t.indexOf("#L").takeIf { it >= 0 }?.let { t = t.substring(0, it) }
    val colon = t.indexOf(':')
    if (colon >= 0 && t.getOrNull(colon + 1)?.let { it in '0'..'9' } == true) t = t.substring(0, colon)
    return t
}

private fun MutableList<String>.addFile(
    root: String,
    rel: String?,
) {
    if (rel != null && isFile("$root/$rel") && rel !in this) add(rel)
}

/** Root-relative paths among [raw] that exist as files under the project. */
internal fun existingRels(
    cx: Ctx,
    raw: List<String>,
): List<String> {
    val r = rootOf(cx) ?: return emptyList()
    return buildList { raw.forEach { addFile(r.root, relative(r.root, r.canon, it)) } }.take(MAX_FILES)
}

/** Files the prompt names that exist under the project, root-relative. */
internal fun promptPaths(
    cx: Ctx,
    prompt: String,
): List<String> = rootOf(cx)?.let { mentioned(it.root, it.canon, prompt) } ?: emptyList()

private const val TOKEN_SEPS = "`'\"()[]{}<>,|"

/** Existing files under [root] that [text] names, in order of first mention. */
internal fun mentioned(
    root: String,
    canon: String,
    text: String,
): List<String> {
    val out = mutableListOf<String>()
    var seen = 0
    val tokens = text.split { it.isWhitespace() || it in TOKEN_SEPS }
    for (token in tokens) {
        val t = clean(token)
        if ('/' !in t && '.' !in t) continue
        if (seen == MAX_CANDIDATES) break
        seen++
        out.addFile(root, relative(root, canon, t))
    }
    return out
}

private inline fun String.split(crossinline sep: (Char) -> Boolean): List<String> {
    val out = mutableListOf<String>()
    var start = 0
    for (i in indices) {
        if (sep(this[i])) {
            out += substring(start, i)
            start = i + 1
        }
    }
    out += substring(start)
    return out
}

/** The path inside one read-cache key: `path\tmode\trange` or `read\tpath…`; `bash` names none. */
internal fun keyPath(key: String): String? {
    val parts = key.split('\t')
    return when (parts[0]) {
        "bash" -> null
        "read" -> parts.getOrNull(1)
        else -> parts[0]
    }
}

/** Link note [id] to the files [body] names and the session checkpoint's paths. Fails open. */
internal fun linkNote(
    cx: Ctx,
    id: Int,
    body: String,
) {
    val r = rootOf(cx) ?: return
    val files = mentioned(r.root, r.canon, body).toMutableList()
    for (p in lastPaths(cx, r.root)) relative(r.root, r.canon, p)?.let { if (it !in files) files += it }
    runCatching { cx.setNoteFiles(id, files.take(MAX_FILES)) }
}

/** At most [n] hits for [prompt]: read files re-rank [text]; named files add up to two notes. */
internal fun recallHits(
    cx: Ctx,
    prompt: String,
    text: List<NoteHit>,
    n: Int,
): List<NoteHit> {
    val r = rootOf(cx) ?: return boost(text, emptyList(), n)
    val named = mentioned(r.root, r.canon, prompt)
    val files = named.toMutableList()
    for (key in runCatching { cx.readCacheKeys() }.getOrDefault(emptyList())) {
        keyPath(key)?.let { relative(r.root, r.canon, it) }?.let { if (it !in files) files += it }
    }
    val project = projectName(r.root)
    val limit = (n.toLong() * 2).coerceAtMost(UInt.MAX_VALUE.toLong()).toUInt()

    fun notes(f: List<String>) = runCatching { cx.notesForFiles(project, f, limit) }.getOrDefault(emptyList())

    fun inText(h: NoteHit) = text.any { it.id == h.id }
    val extra = notes(named).filterNot(::inText).take(minOf(MAX_NAMED_EXTRA, n))
    val linked = notes(files.take(MAX_FILES)).filter(::inText)
    val hits = if (n > extra.size) boost(text, linked, n - extra.size).toMutableList() else mutableListOf()
    return hits + extra
}

/** Text hits with file-linked notes fused in (RRF); with none linked the text hits come back untouched. */
internal fun boost(
    text: List<NoteHit>,
    linked: List<NoteHit>,
    n: Int,
): List<NoteHit> = if (linked.isEmpty()) text.take(n) else rrfMergeLists(listOf(text, linked), n.toUInt())
