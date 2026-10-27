// Port of `Report::to_table` and its section formatters from src/measure/stats.rs: the plain-text `rtok stats`.
@file:Suppress("MagicNumber", "TooManyFunctions")

package rtok.measure.stats

import rtok.Col
import rtok.measure.CHARS_PER_TOKEN
import rtok.measure.Subagents
import rtok.measure.estTokens
import rtok.measure.pct
import rtok.table

private fun p1(v: Double) = fixed(v, 1)

fun Report.toTable(): String =
    buildString {
        append("sessions $sessions  compact $compact  checkpoint $checkpoint  no_checkpoint $noCheckpoint  lines $lines  malformed $malformed\n")
        append(
            "usage input=$usageInput cache_create=$usageCacheCreate cache_read=$usageCacheRead output=$usageOutput  " +
                "hit=${p1(cacheHitRate * 100.0)}%  median_context=$medianFinalContext\n",
        )
        subagents?.let { append(subagentLines(it)) }
        append(apiTable("api", api))
        append(apiTable("lane", lanes))
        append(apiTable("lane/tier", laneTiers))
        if (cttTotal > 0) {
            val p = 100.0 * (cttTotal.toDouble() - cttArchive.toDouble()) / cttTotal.toDouble()
            append("archive replay (estimate) ctt $cttTotal → $cttArchive  -${p1(p)}%  over $archiveCandidates results\n")
        }
        cost?.let { append(it.toTable()) }
        append(readLines())
        append(volumeLines())
        append(formatSection("tool", tools))
        append(formatBashSection(bashFamilies, bashFilter))
        if (bashDefaultRule.isNotEmpty()) append(formatSection("bash_default", bashDefaultRule))
        if (!bashUnmarked.isEmpty()) {
            val d = bashUnmarked
            val total = d.gateBytes + d.unmatchedBytes
            append(
                "bash unmarked  below_gate calls ${d.gateCalls} bytes ${d.gateBytes} ${p1(pct(d.gateBytes, total))}%  " +
                    "no_rule calls ${d.unmatchedCalls} bytes ${d.unmatchedBytes} ${p1(pct(d.unmatchedBytes, total))}%\n",
            )
        }
        append(formatSection("mcp", mcpGroups))
        skills?.let { append(skillsSection(it)) }
    }

private fun subagentLines(sa: Subagents) =
    buildString {
        append(
            "sub-agents ${sa.sessions} sessions / ${sa.count} agents  results ${sa.resultBytes} B vs parents ${sa.parentResultBytes} B " +
                "(${p1(pct(sa.resultBytes, sa.resultBytes + sa.parentResultBytes))}% of the tree)\n",
        )
        append("  reads ${sa.readBytes} B (${p1(pct(sa.readBytes, sa.resultBytes))}% of results)  re-reads: parent ${sa.readParent} B, sibling ${sa.readSibling} B\n")
        append("  re-read ${sa.rereadBytes} B = ${p1(sa.shareRead)}% of reads, ${p1(sa.shareResults)}% of results, ${p1(sa.shareTree)}% of the tree\n")
        append("  usage input=${sa.usageInput} cache_read=${sa.usageCacheRead} cache_write=${sa.usageCacheWrite} output=${sa.usageOutput}\n")
        if (sa.withBrief.count > 0 || sa.withoutBrief.count > 0) {
            for ((label, b) in listOf("brief" to sa.withBrief, "no brief" to sa.withoutBrief)) {
                append("  $label: ${b.count} agents  re-read ${b.rereadBytes} B (${p1(b.shareRead)}% of reads)  input=${b.usageInput}\n")
            }
        }
        for (r in sa.byType) {
            append("  ${r.agentType} | ${r.model}  ${r.count} agents  results ${r.resultBytes} B  reads ${r.readBytes} B  re-read ${r.rereadBytes} B\n")
        }
    }

private fun Report.readLines() =
    buildString {
        if (edits.calls > 0) {
            val e = edits
            append(
                "edit calls ${e.calls}  old_string ${e.oldBytes} B  new_string ${e.newBytes} B  old/tool_input ${p1(pct(e.oldBytes, e.toolInputBytes))}%  " +
                    "old/output_tokens (est) ${p1(pct(estTokens(e.oldBytes), usageOutput))}%\n",
            )
        }
        if (readDelta.calls > 0) {
            val d = readDelta
            append("read delta calls ${d.calls}  bytes ${d.bytes}  of Read bytes ${d.readBytes}  ${p1(pct(d.bytes, d.readBytes))}%\n")
        }
        if (readWhole.calls > 0) {
            val d = readWhole
            val plain = d.bytes - d.editedBytes
            append(
                "read whole calls ${d.calls}  bytes ${d.bytes}  ${p1(pct(d.bytes, d.readBytes))}% of Read  ${p1(pct(d.bytes, d.resultBytes))}% of results  " +
                    "not edited ${d.calls - d.edited} bytes ${p1(pct(plain, d.resultBytes))}% of results  edited after ${d.edited}\n",
            )
        }
        if (readDeny.calls > 0) {
            val d = readDeny
            append(
                "read deny calls ${d.calls}  tokens ${d.tokens}  then rtok read ${d.thenMcp}  then native Read ${d.thenNative}  then ToolSearch ${d.thenToolsearch}\n",
            )
        }
        if (grepSymbol.calls > 0) {
            val g = grepSymbol
            append("grep symbol answers ${g.calls}  tokens ${g.tokens}  then same-name Grep/Read ${g.followed} (${p1(pct(g.followed, g.calls))}%)\n")
        }
        if (!repeatReads.isEmpty()) {
            val classes = repeatReads.classes()
            append("repeat reads  total ${classes.sumOf { it.second.calls }}\n")
            for ((name, c) in classes) append("  $name calls ${c.calls} bytes ${c.bytes}\n")
        }
    }

private fun Report.volumeLines() =
    buildString {
        val input = usageInput + usageCacheCreate + usageCacheRead
        if (thinking.blocks > 0) {
            val est = estTokens(thinking.bytes)
            val share = if (input > 0) 100.0 * est.toDouble() / input.toDouble() else 0.0
            append("thinking blocks ${thinking.blocks} bytes ${thinking.bytes} est. tokens $est ${fixed(share, 4)}% of session input\n")
        }
        if (images.blocks > 0) {
            val d = images
            append(
                "images blocks ${d.blocks}  bytes ${d.bytes}  est. tokens ${d.tokens}  resident ${d.resident}  " +
                    "${fixed(pct(d.resident, input), 2)}% of session input  no_size ${d.noSize}\n",
            )
            for ((src, r) in d.bySource.sortedEntries()) append("  images $src  blocks ${r.blocks}  bytes ${r.bytes}  est. tokens ${r.tokens}\n")
        }
        if (!dictionary.isEmpty()) {
            val d = dictionary
            val billed = satMul(d.requestTokens, CHARS_PER_TOKEN.toLong())
            append("dictionary requests ${d.requests}  content bytes ${d.inputBytes}  billed input est. bytes $billed\n")
            for ((name, k) in listOf("paths" to d.paths, "idents" to d.idents)) {
                append(
                    "  $name repeated ${k.repeated} (${fixed(pct(k.repeated, billed), 2)}% of billed)  cache-safe saving ${k.saved} " +
                        "(${fixed(pct(k.saved, billed), 2)}% of billed, ${fixed(pct(k.saved, d.inputBytes), 2)}% of content)\n",
                )
            }
        }
        if (repeat.calls > 0) {
            val d = repeat
            append("repeat calls ${d.calls}  bytes ${d.bytes}  of result bytes ${d.resultBytes}  ${p1(pct(d.bytes, d.resultBytes))}%\n")
        }
        if (expandAfter.calls > 0) {
            val d = expandAfter
            append("expand right after  calls ${d.calls}  bytes ${d.bytes}  shown bytes ${d.shownBytes}\n")
        }
    }

/** u64 `saturating_mul` over non-negative counters. */
internal fun satMul(
    a: Long,
    b: Long,
): Long = if (b != 0L && a > Long.MAX_VALUE / b) Long.MAX_VALUE else a * b

private fun numericCells(r: SizeRow) = listOf(r.count, r.totalBytes, r.mean, r.p95, r.max, r.estTokens, r.ctt).map(Long::toString)

private val SIZE_COLS = listOf(Col.right(7), Col.right(12), Col.right(8), Col.right(8), Col.right(8), Col.right(12), Col.right(12))
private val SIZE_HEAD = listOf("count", "bytes", "mean", "p95", "max", "est_tokens", "ctt")

private fun formatBashSection(
    rows: SortedMap<SizeRow>,
    kinds: SortedMap<String>,
): String {
    val out = mutableListOf(listOf("bash", "filter") + SIZE_HEAD)
    for ((name, r) in rows.sortedEntries()) out += listOf(name, kinds[name] ?: "default") + numericCells(r)
    return table(listOf(Col.left(24), Col.left(8)) + SIZE_COLS, out)
}

internal fun formatSection(
    title: String,
    rows: SortedMap<SizeRow>,
): String {
    val out = mutableListOf(listOf(title) + SIZE_HEAD)
    for ((name, r) in rows.sortedEntries()) out += listOf(name) + numericCells(r)
    return table(listOf(Col.left(24)) + SIZE_COLS, out)
}

/** T61.1: the injected skill bodies, `resident` = the bytes the later API requests of the same session carried. */
private fun skillsSection(skills: SortedMap<SkillRow>): String {
    val cols = listOf(Col.left(24), Col.right(7), Col.right(12), Col.right(8), Col.right(8), Col.right(8), Col.right(12), Col.right(14))
    val out = mutableListOf(listOf("skill", "count", "bytes", "mean", "p95", "max", "est_tokens", "resident"))
    for ((name, r) in skills.sortedEntries()) out += listOf(name) + listOf(r.count, r.bytes, r.mean, r.p95, r.max, r.estTokens, r.resident).map(Long::toString)
    return table(cols, out)
}

/** The `api` / `lane` counters table; empty input prints nothing. */
internal fun apiTable(
    label: String,
    rows: SortedMap<ApiRow>,
): String {
    if (rows.isEmpty()) return ""
    val cols = listOf(Col.left(24), Col.right(8), Col.right(12), Col.right(10), Col.right(6), Col.right(6))
    val out = mutableListOf(listOf(label, "input", "cache_create", "cache_read", "output", "hit"))
    for ((name, r) in rows.sortedEntries()) {
        out += listOf(name, r.input.toString(), r.cacheCreate.toString(), r.cacheRead.toString(), r.output.toString(), "${p1(r.hit * 100.0)}%")
    }
    return table(cols, out)
}

fun CostReport.toTable(): String =
    buildString {
        append("cost (USD at [stats.prices] \$/MTok; `-` = no price row)\n")
        if (models.isEmpty() && unknown.isEmpty()) {
            append("  no usage rows\n")
            return@buildString
        }
        val cols = listOf(Col.left(24), Col.right(12), Col.right(12), Col.right(12), Col.right(12), Col.right(10), Col.right(10))
        val rows = mutableListOf(listOf("model", "input", "cache_create", "cache_read", "output", "cost", "saved"))

        fun money(v: Double?) = v?.let { fixed(it, 2) } ?: "-"
        for ((model, r) in models.sortedEntries()) {
            rows += listOf(model, r.input.toString(), r.cacheCreate.toString(), r.cacheRead.toString(), r.output.toString(), money(r.cost), money(r.saved))
        }
        append(table(cols, rows))
        append("cost total \$${fixed(totalCost, 2)} (cache reads saved \$${fixed(totalSaved, 2)}; ${unknown.size} model(s) without a price)\n")
        if (lanes.isNotEmpty()) {
            append("cost by lane (USD; `*` = excludes models without a price)\n")
            for ((lane, c) in lanes.sortedEntries()) {
                append("  ${lane.padEnd(12)} ${fixed(c.cost, 2).padStart(10)}${if (c.partial) "*" else ""}\n")
            }
        }
    }
