// The eight P22 sections of the PDF (src/report/pdf.rs `sections`), in the fixed order, straight from the document.
package rtok.report

import rtok.measure.stats.fixed

internal object PdfSections {
    private const val PERCENT = 100.0
    private val LANE_KEYS = listOf("input", "cache_create", "cache_read", "output", "hit")
    private const val NO_ROWS = "No rows in window."

    fun sections(doc: Document): List<Sec> =
        listOf(
            window(doc),
            savings(doc),
            calls(doc),
            cache(doc),
            expand(doc),
            Sec("Config", paras(listOf("Every effective key with its origin (config show --sources).") + doc.config.map { "${it.key} = ${it.value} (${it.source})" } + "${doc.config.size} keys.")),
            Sec("Doctor", paras(listOf("Live probes (the rtok doctor page), not store rows.") + lines(doc.doctor.toText()))),
            Sec("Recommendations", paras(doc.recommendations.map { "${it.rule}: ${it.finding} (${it.evidence})" }.ifEmpty { listOf("No recommendations.") })),
        )

    private fun window(doc: Document): Sec {
        val w = doc.ledgers.window
        val out =
            mutableListOf(
                "calls: ${w.callsInWindow} of ${w.callsTotal} in window (${w.since})",
                "measurements: ${w.measurements} (whole ledger, no row times)",
                "usage: ${w.usage} (whole ledger, no row times)",
            )
        if (w.callsTotal == 0uL && w.measurements == 0uL && w.usage == 0uL) out.add("No rows in window. The store has no rows to report.")
        return Sec("Window", paras(out))
    }

    private fun savings(doc: Document): Sec {
        val sav = doc.ledgers.savings
        val out = mutableListOf("Estimated tokens from Measurement rows only (est_before - est_after, net).")
        if (sav.rows.isEmpty()) {
            out.add(NO_ROWS)
            return Sec("Savings", paras(out))
        }
        sav.rows.forEach { out.add("${it.plugin}: ${it.rows} rows, est ${it.estBefore} -> ${it.estAfter}, saved ${it.saved}") }
        out.add("Total: ${sav.totalSaved} est tokens over ${sav.totalRows} Measurement rows.")
        return Sec("Savings", paras(out) + Block.Chart("saved tokens per plugin", sav.rows.map { it.plugin to it.saved }))
    }

    private fun calls(doc: Document): Sec {
        val calls = doc.ledgers.calls
        val out = mutableListOf("Latency per surface, nearest-rank p50/p95 over timed calls.")
        if (calls.total == 0uL) {
            out.add(NO_ROWS)
            return Sec("Calls", paras(out))
        }
        calls.rows.forEach { out.add("${it.surface}: ${it.calls} calls, ${it.timed} timed, p50 ${ReportText.ms(it.p50Ms)} ms, p95 ${ReportText.ms(it.p95Ms)} ms") }
        out.add("${calls.inWindow} of ${calls.total} calls rows in window (${doc.ledgers.window.since}).")
        return Sec("Calls", paras(out) + Block.Chart("calls per surface", calls.rows.map { it.surface to clamp(it.calls) }))
    }

    private fun cache(doc: Document): Sec {
        val cache = doc.ledgers.cache
        val out = mutableListOf("Prompt-cache busts by cause, from the proxy usage rows.")
        if (cache.sessions == 0uL) {
            out.add(NO_ROWS)
        } else {
            cache.byCause.forEach { (cause, n) -> out.add("$cause: $n") }
            out.add("Busts: ${cache.busts} over ${cache.turns} turns in ${cache.sessions} session(s).")
        }
        if (doc.ledgers.lanes.isNotEmpty()) {
            out.add("Hit rate per proxy lane and service tier:")
            doc.ledgers.laneCells().forEach { out.add(laneLine(it)) }
        }
        val chart = if (cache.sessions > 0uL) listOf(Block.Chart("busts per cause", cache.byCause.map { (c, n) -> c to clamp(n) })) else emptyList()
        return Sec("Cache", paras(out) + chart)
    }

    private fun expand(doc: Document): Sec {
        val exp = doc.ledgers.expand
        if (exp.decisions == 0L) return Sec("Expand", paras(listOf(NO_ROWS)))
        val what = if (exp.expandedIds.isEmpty()) "none" else exp.expandedIds.joinToString(", ") { ReportText.shownArchiveId(it) }
        val line = "rtok expand froze ${exp.expanded} of ${exp.decisions} live-zone pointers (${fixed(PERCENT * exp.rate, 1)}%). Expanded: $what."
        return Sec("Expand", paras(listOf(line)))
    }

    /** `str::lines`: no trailing empty line, CRLF folded. */
    private fun lines(s: String): List<String> = s.split('\n').map { it.removeSuffix("\r") }.let { if (s.endsWith('\n') || s.isEmpty()) it.dropLast(1) else it }

    private fun laneLine(r: List<String>): String {
        if (r.size != rtok.model.ReportLedgers.LANE_HEADS.size) return r.joinToString(" ")
        return r.first() + ": " + LANE_KEYS.zip(r.drop(1)).joinToString(", ") { (k, v) -> "$k $v" }
    }

    private fun clamp(n: ULong): Long = if (n > Long.MAX_VALUE.toULong()) Long.MAX_VALUE else n.toLong()

    private fun paras(lines: List<String>): List<Block> = lines.map { Block.Para(it) }
}
