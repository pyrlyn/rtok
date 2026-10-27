// Port of src/report/html.rs (T22.2): the same eight sections as one self-contained file: inline CSS, inline SVG
// charts, no network fetch, openable from `file://`. It formats; it computes nothing (D24).
package rtok.report

import rtok.model.ReportLedgers
import rtok.report.ReportText.dec
import rtok.report.ReportText.ms

object Html {
    private const val CSS =
        "body{font-family:sans-serif;max-width:60rem;margin:2rem auto;padding:0 1rem;color:#222}\n" +
            "table{border-collapse:collapse;margin:1rem 0}\nth,td{border:1px solid #ccc;padding:.25rem .5rem;text-align:left}\n" +
            "pre{background:#f6f6f6;padding:1rem;overflow-x:auto}\nsvg.chart{background:#fafafa;margin:1rem 0}\n"
    private const val PERCENT = 100.0
    private const val ROW_H = 24
    private const val PAD = 8
    private const val TOP = 4
    private const val BASELINE = 12
    private const val BAR_W = 440.0
    private const val BAR_X = 146
    private const val NO_ROWS = "<p>No rows in window.</p>\n"
    private const val WHOLE = "whole ledger (no row times)"

    fun render(doc: Document): String =
        buildString {
            append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n<title>rtok report</title>\n<style>\n")
            append(CSS)
            append("</style>\n</head>\n<body>\n<h1>rtok report</h1>\n")
            window(doc)
            savings(doc)
            calls(doc)
            cache(doc)
            expand(doc)
            config(doc)
            append("<h2 id=\"doctor\">Doctor</h2>\n<p>Live probes (the <code>rtok doctor</code> page), not store rows.</p>\n<pre><code>")
            append(esc(doc.doctor.toText()))
            append("</code></pre>\n<h2 id=\"recommendations\">Recommendations</h2>\n")
            if (doc.recommendations.isEmpty()) {
                append("<p>No recommendations.</p>\n")
            } else {
                append("<ul>\n")
                for (r in doc.recommendations) append("<li><strong>${esc(r.rule)}</strong>: ${esc(r.finding)} (${esc(r.evidence)})</li>\n")
                append("</ul>\n")
            }
            append("</body>\n</html>\n")
        }

    private fun StringBuilder.window(doc: Document) {
        val w = doc.ledgers.window
        append("<p>Window ${esc(w.fromDate)} → ${esc(w.toDate)} (${esc(w.since)}) · store ${esc(w.dbPath)}</p>\n<h2 id=\"window\">Window</h2>\n")
        table(
            listOf("ledger", "rows", "covers"),
            listOf(
                listOf("calls", "${w.callsInWindow} of ${w.callsTotal}", "window (${esc(w.since)})"),
                listOf("measurements", w.measurements.toString(), WHOLE),
                listOf("usage", w.usage.toString(), WHOLE),
            ),
        )
        if (w.callsTotal == 0uL && w.measurements == 0uL && w.usage == 0uL) append("<p><strong>No rows in window.</strong> The store has no rows to report.</p>\n")
    }

    private fun StringBuilder.savings(doc: Document) {
        val sav = doc.ledgers.savings
        append(
            "<h2 id=\"savings\">Savings</h2>\n<p>Estimated tokens from <code>Measurement</code> rows only (est_before − est_after, " +
                "net; floors, not context-token-turns).</p>\n",
        )
        if (sav.rows.isEmpty()) {
            append(NO_ROWS)
            return
        }
        append(bars("saved tokens per plugin", sav.rows.map { it.plugin to it.saved }))
        table(listOf("plugin", "rows", "est before", "est after", "saved"), sav.rows.map { listOf(esc(it.plugin), "${it.rows}", "${it.estBefore}", "${it.estAfter}", "${it.saved}") })
        append("<p>Total: ${sav.totalSaved} est tokens over ${sav.totalRows} <code>Measurement</code> rows (whole ledger: no row times).</p>\n")
    }

    private fun StringBuilder.calls(doc: Document) {
        val calls = doc.ledgers.calls
        append("<h2 id=\"calls\">Calls</h2>\n<p>Latency per surface, nearest-rank p50/p95 over the calls that recorded one.</p>\n")
        if (calls.total == 0uL) {
            append(NO_ROWS)
            return
        }
        append(bars("calls per surface", calls.rows.map { it.surface to clamp(it.calls) }))
        table(listOf("surface", "calls", "timed", "p50 ms", "p95 ms"), calls.rows.map { listOf(esc(it.surface), "${it.calls}", "${it.timed}", ms(it.p50Ms), ms(it.p95Ms)) })
        append(
            "<p>${calls.inWindow} of ${calls.total} <code>calls</code> rows in window (${esc(doc.ledgers.window.since)}); surfaces " +
                "beyond hook/mcp/proxy are not in the section set.</p>\n",
        )
    }

    private fun StringBuilder.cache(doc: Document) {
        val cache = doc.ledgers.cache
        append("<h2 id=\"cache\">Cache</h2>\n<p>Prompt-cache busts by cause, from the proxy's <code>usage</code> rows (whole ledger).</p>\n")
        if (cache.sessions == 0uL) {
            append(NO_ROWS)
        } else {
            append(bars("busts per cause", cache.byCause.map { it.first to clamp(it.second) }))
            table(listOf("cause", "busts"), cache.byCause.map { listOf(esc(it.first), "${it.second}") })
            append("<p>Busts: ${cache.busts} over ${cache.turns} turns in ${cache.sessions} session(s).</p>\n")
        }
        if (doc.ledgers.lanes.isNotEmpty()) {
            append("<p>Prompt-cache hit rate per proxy lane and service tier:</p>\n")
            table(ReportLedgers.LANE_HEADS, doc.ledgers.laneCells().map { row -> row.map { esc(it) } })
        }
    }

    private fun StringBuilder.expand(doc: Document) {
        val exp = doc.ledgers.expand
        append("<h2 id=\"expand\">Expand</h2>\n<p>How often a live-zone pointer had to be expanded (<code>archive_decisions</code> rows).</p>\n")
        if (exp.decisions == 0L) {
            append(NO_ROWS)
            return
        }
        val what = if (exp.expandedIds.isEmpty()) "none" else exp.expandedIds.joinToString(", ") { "<code>${esc(ReportText.shownArchiveId(it))}</code>" }
        append("<p><code>rtok expand</code> froze ${exp.expanded} of ${exp.decisions} live-zone pointers (${dec(PERCENT * exp.rate)}%). Expanded: $what.</p>\n")
    }

    private fun StringBuilder.config(doc: Document) {
        append("<h2 id=\"config\">Config</h2>\n<p>Every effective key with its origin — the <code>config show --sources</code> page.</p>\n")
        // Values ride in `<code>` so a URL is a code span, never an external reference.
        table(listOf("key", "value", "source"), doc.config.map { listOf(esc(it.key), "<code>${esc(it.value)}</code>", esc(it.source)) })
        append("<p>${doc.config.size} keys.</p>\n")
    }

    private fun clamp(n: ULong): Long = if (n > Long.MAX_VALUE.toULong()) Long.MAX_VALUE else n.toLong()

    /** One `<table>`; cells arrive pre-escaped (or plain numbers). */
    private fun StringBuilder.table(
        heads: List<String>,
        rows: List<List<String>>,
    ) {
        append("<table>\n<tr>")
        heads.forEach { append("<th>$it</th>") }
        append("</tr>\n")
        for (r in rows) {
            append("<tr>")
            r.forEach { append("<td>$it</td>") }
            append("</tr>\n")
        }
        append("</table>\n")
    }

    /** One inline-SVG bar chart: no script, no external reference. */
    private fun bars(
        title: String,
        pairs: List<Pair<String, Long>>,
    ): String {
        if (pairs.isEmpty()) return "<p>No data for ${esc(title)}.</p>\n"
        val shares = ReportText.barShares(pairs)
        return buildString {
            append("<svg class=\"chart\" width=\"600\" height=\"${pairs.size * ROW_H + PAD}\" role=\"img\" aria-label=\"${esc(title)}\">\n")
            pairs.forEachIndexed { i, (label, v) ->
                val y = i * ROW_H + TOP
                val wd = (shares[i] * BAR_W).toLong()
                append(
                    "<text x=\"0\" y=\"${y + BASELINE}\" font-size=\"12\">${esc(label)}</text><rect x=\"140\" y=\"$y\" width=\"$wd\" height=\"14\"/>" +
                        "<text x=\"${BAR_X + wd}\" y=\"${y + BASELINE}\" font-size=\"12\">$v</text>\n",
                )
            }
            append("</svg>\n")
        }
    }

    /** Escape text for element content and attribute values. */
    fun esc(s: String) =
        s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
