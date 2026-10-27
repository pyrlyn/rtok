// Port of src/report/mod.rs (P22, D24): `rtok report`'s format-neutral document. Every renderer prints these same
// sections in this order, from the model and nothing else.
package rtok.report

import rtok.doctor.Report
import rtok.model.ReportLedgers

/** One recommendation: a rule over the ledgers, what triggered it and the rows it read (T22.5). */
data class Recommendation(
    val rule: String,
    val finding: String,
    val evidence: String,
)

/** One effective config key with its origin: a `config show --sources` row. */
data class ConfigEntry(
    val key: String,
    val value: String,
    val source: String,
)

/** The whole report: the fixed section set of P22. */
data class Document(
    val ledgers: ReportLedgers,
    val config: List<ConfigEntry>,
    /** The `rtok doctor` page: the one section of live probes rather than store rows. */
    val doctor: Report,
    /** T22.5 rules over the ledgers, most recoverable tokens first; empty when nothing fires. */
    val recommendations: List<Recommendation>,
)

object ReportText {
    private const val FULL_ID_LEN = 64

    /** Each value's share of the largest one in `0.0..1.0`; negatives count as 0, an all-zero series is all 0.0. */
    fun barShares(pairs: List<Pair<String, Long>>): List<Double> {
        val max = maxOf(pairs.maxOfOrNull { maxOf(it.second, 0) } ?: 0, 1)
        return pairs.map { maxOf(it.second, 0).toDouble() / max }
    }

    /** A full archive sha prints as its short prefix; a shorter id or non-hex text stays whole. */
    fun shownArchiveId(id: String): String = if (id.length >= FULL_ID_LEN && id.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) rtok.shortId(id) else id

    /** One decimal for a latency or a share; `—` for none or non-finite (T105). */
    fun dec(v: Double?): String = if (v != null && v.isFinite()) rtok.measure.stats.fixed(v, 1) else "—"

    /** A latency: an untimed surface is not a zero-ms one. */
    fun ms(v: Double?) = dec(v)

    /** Newlines folded to spaces, so a value stays on its line (the `--ai` rendering's `flat`). */
    fun flat(s: String) = s.replace('\n', ' ').replace('\r', ' ')
}

object ReportDocument {
    /**
     * `document`: the ledgers, the `config show --sources` page for [home] / [configFile] (so the two cannot disagree
     * about a layer), the doctor page (live probes) and the advice rules.
     */
    fun build(
        cfg: rtok.config.Config,
        home: String,
        configFile: String?,
        ownExe: String? = null,
    ): Document {
        val ledgers = ReportLedgers.read(cfg)
        rtok.config.Config.ensureUserFile(home, configFile)
        val config =
            rtok.config.Layers
                .entries(rtok.config.Layers.figment(home, configFile, null))
                .map { (k, v, s) -> ConfigEntry(k, v, s) }
        return Document(ledgers, config, rtok.doctor.Page.page(cfg, ownExe), Advice.recommendations(ledgers, cfg))
    }
}
