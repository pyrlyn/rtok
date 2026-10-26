// Port of src/plugins/graph/index.rs: the tree-sitter-tags symbol index (T8.1). The walk and the stat gate run on
// the calling thread; files that pass the gate are read, hashed and parsed on one worker per core (T35.2), and
// every store write stays on the calling thread in walk order (D18).
package rtok.plugins.graph

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import rtok.config.Graph
import rtok.plugin.sdk.Ctx
import rtok.plugin.sdk.SymbolFile
import rtok.plugin.sdk.SymbolRow
import rtok.plugins.read.Languages
import rtok.plugins.read.TagHit
import rtok.plugins.read.supported
import rtok.plugins.read.tagsWithExtensions
import rtok.plugins.read.walkRootOk
import rtok.store.canonRoot
import rtok.store.hexSha256
import rtok.sys.*

data class IndexReport(
    var indexed: Int = 0,
    var inserted: Int = 0,
    var skipped: Int = 0,
    /** Files whose bytes were read (T8.4). A warm run over an untouched tree reads none. */
    var read: Int = 0,
    var excludeSkipped: Int = 0,
    var includeAdded: Int = 0,
    var extensionMapped: Int = 0,
)

/** A progress bar over the files an index run reads; only `rtok graph index` draws one. */
interface IndexProgress {
    fun step(label: String) {}

    fun finish() {}

    companion object {
        val Hidden = object : IndexProgress {}
    }
}

/** The stored file-rank document (rank.rs); the index refreshes it after a run that changed something. */
interface FileRanks {
    fun missing(
        cx: Ctx,
        root: String,
    ): Boolean

    fun refresh(
        cx: Ctx,
        root: String,
    )

    /** Stores and refreshes nothing (tests, dry tools). */
    object None : FileRanks {
        override fun missing(
            cx: Ctx,
            root: String,
        ) = false

        override fun refresh(
            cx: Ctx,
            root: String,
        ) {}
    }
}

object Index {
    /** Doc comments stored on a definition, in bytes. */
    internal const val DOC_CAP = 512

    /** `file_sha` for a file that cannot be decoded or parsed (T36.16); the stat gate skips it next run. */
    internal const val EMPTY_SHA = ""

    /** Files per symbol-and-edge write transaction on a cold run (T451). */
    internal const val SYMBOL_BATCH_FILES = 200

    /** Bump when [scoped] changes (T35.5). */
    internal const val INDEX_VERSION = 5

    /** The index key of a root (shared with the project registry). */
    fun canon(root: String): String = canonRoot(root)

    var ranks: FileRanks = Rank

    /** Incremental index of [root]. [dryRun] walks and parses as a real run does but writes nothing. */
    fun run(
        cx: Ctx,
        root: String,
        dryRun: Boolean,
        cfg: Graph = graphCfg(cx),
    ): IndexReport = runWith(cx, root, dryRun, cfg) { IndexProgress.Hidden }

    /** [run] stepping [bar] over the files that pass the stat gate; [bar] gets their count once the walk is done. */
    fun runWith(
        cx: Ctx,
        rootIn: String,
        dryRun: Boolean,
        cfg: Graph = graphCfg(cx),
        bar: (Int) -> IndexProgress,
    ): IndexReport {
        walkRootOk(rootIn) // T356: the one choke point for the CLI, MCP tools, watcher and `ensure`.
        val root = canonicalize(rootIn) ?: rootIn
        val rk = canon(root)
        val currentFp = extractorFingerprint(cfg.extensions)
        val fpStale = cx.extractorFingerprint(rk) != currentFp
        if (fpStale && !dryRun) runCatching { cx.deleteSymbolsMissing(rk, emptySet()) }
        // A stale root is indexed cold, dry run included: its stats and shas belong to the old extractor.
        val stats = if (fpStale) emptyMap() else cx.symbolStats(rk)
        val report = IndexReport()
        var replaced = false
        val keep = HashSet<String>()
        val jobs = ArrayList<Job>()
        val matcher = Matcher(root, cfg)
        for (entry in matcher.walk()) {
            if (!entry.isFile) continue
            val path = entry.path
            val builtin = supported(path)
            val indexable = matcher.indexable(path)
            if (builtin && !indexable) {
                report.excludeSkipped++
                continue
            }
            if (!indexable) continue
            if (matcher.usesExtensionMap(path)) {
                report.extensionMapped++
            } else if (!builtin) {
                report.includeAdded++
            }
            val rel = relOf(path, root) ?: path
            keep += rel
            val stat = fileStat(path) ?: (0L to 0L)
            val known = stats[rel]
            // Same mtime and size: git's rule for "unchanged". Nothing is opened.
            if (known != null && (known.second to known.third) == stat && stat != (0L to 0L)) {
                report.skipped++
                continue
            }
            jobs += Job(path, rel, stat, known?.first, matcher.extensions)
        }
        val pending = ArrayList<SymbolFile>()
        val touches = ArrayList<Pair<String, Pair<Long, Long>>>()
        val progress = bar(jobs.size)
        eachParsed(jobs) { job, parsed ->
            progress.step(job.rel)
            stage(job, parsed, report, pending, touches)
            if (!dryRun && pending.size >= SYMBOL_BATCH_FILES) {
                report.inserted += cx.replaceSymbolFiles(rk, pending)
                replaced = true
                pending.clear()
            }
        }
        if (dryRun) {
            report.inserted = pending.sumOf { it.rows.size } // T12.6: dry run still owes the would-be row count.
        } else {
            if (pending.isNotEmpty()) {
                report.inserted += cx.replaceSymbolFiles(rk, pending)
                replaced = true
            }
            for ((path, stat) in touches) cx.touchSymbols(rk, path, stat.first, stat.second)
            val removed = runCatching { cx.deleteSymbolsMissing(rk, keep) }.getOrDefault(0)
            if (fpStale) cx.setExtractorFingerprint(rk, currentFp)
            cx.touchSymbolIndexedAt(rk, epochSeconds())
            rememberIndexedHead(cx, root)
            cx.rebuildSymbolIdf(rk) // T368: one pass over names so callers rank without rescanning.
            // A failed rebuild leaves the previous graph; indexing still succeeds.
            if (replaced || fpStale || removed > 0 || ranks.missing(cx, rk)) runCatching { ranks.refresh(cx, rk) }
        }
        progress.finish()
        return report
    }

    /** Index only [changed] event paths (T35.4). Vanished or unsupported paths drop their rows. */
    fun runChanged(
        cx: Ctx,
        rootIn: String,
        changed: Set<String>,
        cfg: Graph = graphCfg(cx),
        dryRun: Boolean = false,
    ): IndexReport {
        walkRootOk(rootIn)
        val root = canonicalize(rootIn) ?: rootIn
        val rk = canon(root)
        val report = IndexReport()
        val matcher = Matcher(root, cfg)
        // Rows of a stale extractor are not evidence a file is unchanged; the stored fingerprint stays,
        // so the next full run still goes cold.
        val fpStale = cx.extractorFingerprint(rk) != extractorFingerprint(cfg.extensions)
        var dropped = false
        val jobs = ArrayList<Job>()
        for (event in changed) {
            val abs = changedAbs(root, event)
            val rel = relOf(abs, root) ?: continue
            if (!exists(abs) || !matcher.indexable(abs)) {
                if (!dryRun) {
                    dropped = dropped or (cx.symbolStat(rk, rel) != null)
                    runCatching { cx.markSymbolsStale(canon(abs)) }
                }
                continue
            }
            val stat = fileStat(abs) ?: (0L to 0L)
            val known = if (fpStale) null else cx.symbolStat(rk, rel)
            if (known != null && (known.second to known.third) == stat && stat != (0L to 0L)) {
                report.skipped++
                continue
            }
            jobs += Job(abs, rel, stat, known?.first, matcher.extensions)
        }
        eachParsed(jobs) { job, parsed ->
            when (parsed) {
                Parsed.Unreadable, Parsed.NotText -> {}

                Parsed.Unparsed -> {
                    report.read++
                }

                Parsed.Same -> {
                    report.read++
                    report.skipped++
                    if (!dryRun) cx.touchSymbols(rk, job.rel, job.stat.first, job.stat.second)
                }

                is Parsed.Rows -> {
                    report.read++
                    report.indexed++
                    dropped = dropped or !dryRun
                    report.inserted += if (dryRun) parsed.rows.size else cx.replaceSymbols(rk, job.rel, parsed.sha, job.stat, parsed.rows)
                }
            }
        }
        if (!dryRun) {
            cx.rebuildSymbolIdf(rk)
            if (report.inserted > 0 || dropped) runCatching { ranks.refresh(cx, rk) }
        }
        return report
    }

    /** Index [root] only when it has no rows yet (first tool call in that repo). */
    fun ensure(
        cx: Ctx,
        root: String,
        cfg: Graph = graphCfg(cx),
    ): IndexReport {
        walkRootOk(root)
        if (cx.symbolCount(canon(root)) > 0) return IndexReport()
        return run(cx, root, false, cfg)
    }

    /** The rows an index run stores for [src]; null when the grammar fails. The graph diff reads git blobs with it too. */
    fun rowsOf(
        path: String,
        src: String,
        extensions: Map<String, String>,
    ): List<SymbolRow>? = runCatching { tagsWithExtensions(path, src, extensions) }.getOrNull()?.let { scoped(src, it) }

    /** A file the stat gate could not skip, with the sha the store holds for it. */
    internal class Job(
        val path: String,
        val rel: String,
        val stat: Pair<Long, Long>,
        val known: String?,
        val extensions: Map<String, String>,
    )

    /** What a worker made of a [Job]; the calling thread turns it into store writes. */
    internal sealed interface Parsed {
        /** The read failed (permissions, I/O); the bytes may be fine next time. */
        data object Unreadable : Parsed

        /** Read, but not UTF-8; stays so until the bytes change. */
        data object NotText : Parsed

        /** Read, but the grammar returned an error. */
        data object Unparsed : Parsed

        /** Same bytes as the stored sha. */
        data object Same : Parsed

        class Rows(
            val sha: String,
            val rows: List<SymbolRow>,
        ) : Parsed
    }

    private fun stage(
        job: Job,
        parsed: Parsed,
        report: IndexReport,
        pending: MutableList<SymbolFile>,
        touches: MutableList<Pair<String, Pair<Long, Long>>>,
    ) {
        when (parsed) {
            // T654: the unknown stat (0, 0) never matches the gate, so the file is read once it is readable again.
            Parsed.Unreadable -> {
                pending += SymbolFile(job.rel, EMPTY_SHA, 0L to 0L, emptyList())
            }

            Parsed.NotText -> {
                pending += SymbolFile(job.rel, EMPTY_SHA, job.stat, emptyList())
            }

            Parsed.Unparsed -> {
                report.read++
                pending += SymbolFile(job.rel, EMPTY_SHA, job.stat, emptyList())
            }

            Parsed.Same -> {
                report.read++
                report.skipped++
                touches += job.rel to job.stat
            }

            is Parsed.Rows -> {
                report.read++
                report.indexed++
                pending += SymbolFile(job.rel, parsed.sha, job.stat, parsed.rows)
            }
        }
    }

    internal fun parse(job: Job): Parsed {
        val bytes = runCatching { readBytes(job.path) }.getOrElse { return Parsed.Unreadable }
        val src = runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrElse { return Parsed.NotText }
        val sha = hexSha256(bytes)
        if (job.known == sha) return Parsed.Same
        return rowsOf(job.path, src, job.extensions)?.let { Parsed.Rows(sha, it) } ?: Parsed.Unparsed
    }

    /** Parses [jobs] on the worker pool and hands each result to [write] here, in [jobs] order. */
    private fun eachParsed(
        jobs: List<Job>,
        write: (Job, Parsed) -> Unit,
    ) {
        // Compile every tags query on this thread first: the per-process cache is then read-only for the workers.
        for (j in jobs) grammarOf(j.path, j.extensions)?.let(Languages::tags)
        eachOrdered(jobs, ::parse, write)
    }

    private fun grammarOf(
        path: String,
        extensions: Map<String, String>,
    ): String? = extension(path)?.let { Languages.grammarName(extensions[it] ?: it) }

    /**
     * Hex sha256 of [INDEX_VERSION], every tags and locals query rtok compiles, and the configured suffix → grammar
     * map (T542), sorted and absent when empty, so map order and an unconfigured root keep their hash.
     */
    fun extractorFingerprint(extensions: Map<String, String>): String {
        val out = ArrayList<Byte>()

        fun add(b: ByteArray) {
            for (x in b) out += x
        }
        add(byteArrayOf(INDEX_VERSION.toByte(), (INDEX_VERSION shr 8).toByte(), (INDEX_VERSION shr 16).toByte(), (INDEX_VERSION shr 24).toByte()))
        for (g in FINGERPRINT_GRAMMARS) {
            Languages.queries(g)?.let { (tags, locals) ->
                add(tags.encodeToByteArray())
                add(locals.encodeToByteArray())
            }
        }
        for ((ext, grammar) in extensions.entries.sortedWith(compareBy({ it.key }, { it.value }))) add("\u0000ext:$ext=$grammar".encodeToByteArray())
        return hexSha256(out.toByteArray())
    }

    /** Tagged grammars in a fixed order; tsx shares typescript's queries but is hashed once per name like the rest. */
    private val FINGERPRINT_GRAMMARS = listOf("c", "dart", "go", "javascript", "python", "rust", "typescript", "tsx", "java", "kotlin", "swift", "c_sharp", "ruby", "php")

    /** Contiguous `///` or `//!` lines directly above [line], capped at [DOC_CAP] UTF-8 bytes. */
    internal fun docAbove(
        src: String,
        line: Int,
    ): String {
        if (line <= 1) return ""
        val lines = src.lines().let { if (src.endsWith("\n")) it.dropLast(1) else it }
        var i = (line - 1).coerceAtMost(lines.size)
        val rev = ArrayList<String>()
        while (i > 0) {
            i--
            val t = lines[i].trim()
            if (t.startsWith("///") || t.startsWith("//!")) rev += lines[i] else break
        }
        rev.reverse()
        val out = StringBuilder()
        var len = 0
        for ((n, l) in rev.withIndex()) {
            if (n > 0 && len + 1 >= DOC_CAP) break
            if (n > 0) {
                out.append('\n')
                len++
            }
            val bytes = l.encodeToByteArray()
            val room = (DOC_CAP - len).coerceAtLeast(0)
            if (bytes.size <= room) {
                out.append(l)
                len += bytes.size
            } else {
                var end = room
                while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
                out.append(bytes.decodeToString(0, end))
                break
            }
        }
        return out.toString()
    }

    private fun contentHash(
        src: ByteArray,
        start: Int,
        end: Int,
    ): String {
        val s = start.coerceIn(0, src.size)
        val e = end.coerceAtMost(src.size).coerceAtLeast(s)
        return hexSha256(src.copyOfRange(s, e))
    }

    /**
     * Rows for one file, each reference tagged with the innermost definition enclosing it (T8.5). Ties break to
     * the smaller span; a reference outside every definition gets `""` (file level). Imports keep the full
     * specifier in `scope` (T368).
     */
    internal fun scoped(
        src: String,
        hits: List<TagHit>,
    ): List<SymbolRow> {
        val bytes = src.encodeToByteArray()
        val defs = hits.filter { it.isDef }.map { Triple(it.line, maxOf(it.endLine, it.line), it.name) }
        return hits.map { h ->
            val scope =
                when {
                    h.kind == "import" -> {
                        h.importPath
                    }

                    h.isDef -> {
                        ""
                    }

                    else -> {
                        defs
                            .filter { (s, e, _) -> s <= h.line && h.line <= e }
                            .minByOrNull { (s, e, _) -> e - s }
                            ?.third
                            .orEmpty()
                    }
                }
            SymbolRow(
                name = h.name,
                kind = h.kind,
                line = h.line,
                isDef = h.isDef,
                endLine = h.endLine,
                scope = scope,
                startByte = h.startByte.toLong(),
                endByte = h.endByte.toLong(),
                contentHash = contentHash(bytes, h.startByte, h.endByte),
                signature = if (h.isDef) h.lineText.trimEnd('\r', '\n') else "",
                doc = if (h.isDef) docAbove(src, h.line) else "",
            )
        }
    }

    /** [path] relative to [root] with `/` separators; null when it is not under [root]. */
    private fun relOf(
        path: String,
        root: String,
    ): String? = stripPrefix(path, root)?.takeIf { it.isNotEmpty() }?.replace('\\', '/')

    /** An event path made absolute and canonical; a vanished file keeps its canonical parent. */
    private fun changedAbs(
        root: String,
        event: String,
    ): String {
        val raw = if (event.startsWith("/")) event else join(root, event)
        return canonicalize(raw) ?: parent(event)?.let(::canonicalize)?.let { p -> fileName(event)?.let { join(p, it) } } ?: raw
    }

    @Serializable internal class IndexedHead(
        val head: String,
    )

    /** Remember `git rev-parse HEAD` for [root]; git missing or a non-repo is a no-op. */
    fun rememberIndexedHead(
        cx: Ctx,
        root: String,
    ) {
        val head =
            Git
                .stdout(root, listOf("rev-parse", "HEAD"))
                ?.decodeToString()
                ?.trim()
                ?.ifEmpty { null } ?: return
        runCatching { cx.pluginStateSet("graph", headKey(root), Json.encodeToString(IndexedHead.serializer(), IndexedHead(head))) }
    }

    fun headKey(root: String) = "head:${canon(root)}"

    private fun epochSeconds(): Long =
        kotlin.time.Clock.System
            .now()
            .epochSeconds
}
