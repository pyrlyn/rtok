// Port of `collect` / `fold_session` and the size-row plumbing of src/measure/stats.rs: one walk over the
// transcripts under a dir, sub-agent transcripts attributed to their parents.
@file:Suppress("MagicNumber", "LoopWithTooManyJumpStatements")

package rtok.measure.stats

import rtok.measure.Dictionary
import rtok.measure.Parsed
import rtok.measure.SubagentParent
import rtok.measure.SubagentScan
import rtok.measure.estTokens
import rtok.measure.field
import rtok.measure.fileStem
import rtok.measure.isSubagent
import rtok.measure.jsonlPaths
import rtok.measure.parsePath
import rtok.measure.str
import rtok.measure.utf8Len

/** Bounded per-row sizes for the p95 column (T219): 2048 longs per row. */
internal const val P95_CAP = 2048

/** One sample sidecar per `SizeRow` map, consumed by [finish]. */
internal class RowSamples {
    private val m = HashMap<String, ArrayList<Long>>()

    fun push(
        name: String,
        bytes: Long,
    ) = pushCapped(m.getOrPut(name) { ArrayList() }, bytes)

    fun add(
        map: SortedMap<SizeRow>,
        name: String,
        bytes: Long,
        tokens: Long,
        ctt: Long,
    ) {
        val row = map.getOrPut(name) { SizeRow() }
        row.count++
        row.totalBytes += bytes
        row.max = maxOf(row.max, bytes)
        row.estTokens += tokens
        row.ctt += ctt
        push(name, bytes)
    }

    /** Mean from totals; nearest-rank p95 over the sidecar, max when a row has none. */
    fun finish(map: SortedMap<SizeRow>) {
        for ((name, row) in map) {
            row.mean = if (row.count == 0L) 0 else row.totalBytes / row.count
            row.p95 = m.remove(name)?.takeIf { it.isNotEmpty() }?.let(::nearestP95) ?: row.max
        }
    }

    fun finishSkills(map: SortedMap<SkillRow>) {
        for ((name, row) in map) {
            row.mean = if (row.count == 0L) 0 else row.bytes / row.count
            row.p95 = m.remove(name)?.takeIf { it.isNotEmpty() }?.let(::nearestP95) ?: row.max
        }
    }
}

/** Deterministic thinning past the cap: keep the even-indexed half, then take the new sample. */
internal fun pushCapped(
    v: MutableList<Long>,
    bytes: Long,
) {
    if (v.size >= P95_CAP) {
        val kept = v.filterIndexed { i, _ -> i % 2 == 0 }
        v.clear()
        v += kept
    }
    v += bytes
}

/** Nearest-rank p95: the `ceil(0.95·n)`-th smallest sample, integer math. */
internal fun nearestP95(samples: MutableList<Long>): Long {
    if (samples.isEmpty()) return 0
    samples.sort()
    val rank = (95 * samples.size + 99) / 100
    return samples[(rank - 1).coerceAtLeast(0).coerceAtMost(samples.size - 1)]
}

private class Samples {
    val tools = RowSamples()
    val bash = RowSamples()
    val mcp = RowSamples()
    val skills = RowSamples()
}

/** `rtok stats` over [dir]: transcripts modified at or after [cutoffSeconds], in path order (the p95 reservoir is order-bound). */
fun collect(
    dir: String,
    cutoffSeconds: Long,
    plugin: String,
    replay: Replay,
): Report {
    val report = Report()
    val finals = ArrayList<Long>()
    val parents = ArrayList<SubagentParent>()
    val samples = Samples()
    for (p in jsonlPaths(dir, cutoffSeconds).sorted()) {
        if (isSubagent(p)) continue
        val parsed = parsePath(p)
        if (parsed == null) {
            report.malformed++
            continue
        }
        report.compact += parsed.compactions.size
        fileStem(p)?.let { report.sessionStems += it }
        foldSession(parsed, plugin, replay, report, samples, finals)
        parents += SubagentParent(p, parsed)
    }
    report.noCheckpoint = report.sessions
    samples.tools.finish(report.tools)
    samples.bash.finish(report.bashFamilies)
    samples.mcp.finish(report.mcpGroups)
    report.skills?.let { samples.skills.finishSkills(it) }
    val denom = report.usageCacheRead + report.usageCacheCreate + report.usageInput
    report.cacheHitRate = if (denom == 0L) 0.0 else report.usageCacheRead.toDouble() / denom.toDouble()
    finals.sort()
    report.medianFinalContext = if (finals.isEmpty()) 0 else finals[finals.size / 2]
    report.subagents = SubagentScan.collect(parents, cutoffSeconds)
    report.subagents?.let {
        report.repeatReads.subagent.calls += it.rereadCalls
        report.repeatReads.subagent.bytes += it.rereadBytes
    }
    return report
}

@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun foldSession(
    parsed: Parsed,
    plugin: String,
    replay: Replay,
    report: Report,
    samples: Samples,
    finals: MutableList<Long>,
) {
    report.sessions++
    report.lines += parsed.lines
    report.malformed += parsed.malformed
    val n = parsed.turns.toLong()
    val idName = HashMap<String, String>()
    val idFamily = HashMap<String, String>()
    val idSkill = HashMap<String, String>()
    for (u in parsed.toolUses) {
        idName[u.id] = u.name
        if (u.name == "Bash") {
            u.input
                .field("command")
                .str()
                ?.let { idFamily[u.id] = bashFamily(it) }
        }
        if (u.name == "Skill") {
            u.input
                .field("skill")
                .str()
                ?.let { idSkill[u.id] = it }
        }
        foldEdits(report.edits, u)
    }
    foldReadDelta(report.readDelta, parsed)
    foldReadWhole(report.readWhole, parsed, replay)
    foldReadDeny(report.readDeny, parsed)
    foldGrepSymbol(report.grepSymbol, parsed)
    foldRepeat(report.repeat, parsed)
    foldRepeatReads(report.repeatReads, parsed)
    foldExpandAfter(report.expandAfter, parsed)
    for (r in parsed.toolResults) {
        val name = idName[r.toolUseId] ?: "unknown"
        if (plugin.isNotEmpty() && plugin != name && plugin !in name) continue
        val bytes = utf8Len(r.content)
        val tokens = estTokens(bytes)
        val remain = (n - r.turn).coerceAtLeast(0)
        val ctt = satMul(tokens, remain)
        val after = replayCtt(r.content, tokens, remain, replay)
        report.cttTotal += ctt
        report.cttArchive += after
        if (after != ctt) report.archiveCandidates++
        samples.tools.add(report.tools, name, bytes, tokens, ctt)
        if (name == "Bash") samples.bash.add(report.bashFamilies, idFamily[r.toolUseId] ?: "other", bytes, tokens, ctt)
        mcpGroup(name)?.let { samples.mcp.add(report.mcpGroups, it, bytes, tokens, ctt) }
    }
    report.thinking.blocks += parsed.thinking.size
    report.thinking.bytes += parsed.thinking.sumOf { it.bytes }
    foldImages(parsed, idName, report.images)
    Dictionary.fold(parsed, report.dictionary)
    foldSkills(parsed, idSkill, report, samples.skills)
    for (u in parsed.usages) {
        report.usageInput += u.inputTokens
        report.usageCacheCreate += u.cacheCreationInputTokens
        report.usageCacheRead += u.cacheReadInputTokens
        report.usageOutput += u.outputTokens
    }
    parsed.usages.lastOrNull()?.let { finals += it.inputTokens + it.cacheCreationInputTokens + it.cacheReadInputTokens }
}

/** T137: the session's image blocks; `resident` = tokens × the requests at or after the block's turn. */
private fun foldImages(
    parsed: Parsed,
    idName: Map<String, String>,
    row: ImageRow,
) {
    for (img in parsed.images) {
        val later = parsed.usages.count { it.turn >= img.turn }.toLong()
        row.blocks++
        row.bytes += img.bytes
        row.tokens += img.tokens
        row.resident += satMul(img.tokens, later)
        if (!img.sized) row.noSize++
        val src = if (img.toolUseId.isEmpty()) "user" else idName[img.toolUseId] ?: "unknown"
        val r = row.bySource.getOrPut(src) { ImageSource() }
        r.blocks++
        r.bytes += img.bytes
        r.tokens += img.tokens
    }
}

/** T61.1: injected skill bodies, one row per `Skill` tool_use's `input.skill`. */
private fun foldSkills(
    parsed: Parsed,
    idSkill: Map<String, String>,
    report: Report,
    samples: RowSamples,
) {
    if (parsed.injected.isEmpty()) return
    val map = report.skills ?: SortedMap<SkillRow>().also { report.skills = it }
    for (inj in parsed.injected) {
        val name = idSkill[inj.toolUseId] ?: continue
        val later = parsed.usages.count { it.turn >= inj.turn }.toLong()
        val row = map.getOrPut(name) { SkillRow() }
        row.count++
        row.bytes += inj.bytes
        row.max = maxOf(row.max, inj.bytes)
        row.estTokens += estTokens(inj.bytes)
        row.resident += satMul(inj.bytes, later)
        samples.push(name, inj.bytes)
    }
}

/** What `ctt` becomes when `archive` swaps this result for its pointer after `keep_turns`. */
internal fun replayCtt(
    content: String,
    tokens: Long,
    remain: Long,
    rp: Replay,
): Long {
    if (tokens < rp.minTokens || remain <= rp.keepTurns) return satMul(tokens, remain)
    val lines = rtokLines(content)
    val n = lines.size
    val (head, tail) = if (n <= rp.headLines + rp.tailLines) n to 0 else rp.headLines to rp.tailLines
    val kept = (lines.subList(0, head) + lines.subList(n - tail, n)).sumOf { utf8Len(rtok.plugins.archive.clip(it)) + 1 }
    val pointer = estTokens(kept + 64)
    return satMul(tokens, rp.keepTurns) + satMul(pointer, remain - rp.keepTurns)
}

/** Rust `str::lines`: `\n`-split, one trailing `\r` dropped, no empty last line. */
internal fun rtokLines(s: String): List<String> = if (s.isEmpty()) emptyList() else s.removeSuffix("\n").split('\n').map { it.removeSuffix("\r") }
