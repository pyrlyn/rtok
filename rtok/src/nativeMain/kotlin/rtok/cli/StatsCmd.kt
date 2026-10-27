// Port of src/cli/stats.rs: `rtok stats` — transcript sizes, CTT and API counters; `--cache` health, `--plugin` rows,
// baselines and `--price`. Every page is a rendering of the operator model (T15.11).
package rtok.cli

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import rtok.calibrateOrSkip
import rtok.config.CATALOGUE
import rtok.config.Config
import rtok.config.parseSince
import rtok.measure.stats.Baseline
import rtok.measure.stats.CacheHealth
import rtok.measure.stats.toTable
import rtok.model.StatsPage

/** The flags that merge into `[stats]`; none set → no layer. */
internal fun statsFlags(
    since: String?,
    json: Boolean,
    plugin: String?,
    compare: String?,
    price: Boolean,
): JsonObject? {
    val stats =
        buildJsonObject {
            since?.let { put("since", it) }
            if (json) put("format", "json")
            plugin?.let { put("plugin", it) }
            compare?.let { put("baseline", it) }
            if (price) put("price", true)
        }
    return if (stats.isEmpty()) null else buildJsonObject { put("stats", stats) }
}

val STATS =
    CommandSpec(
        "stats",
        "Transcript sizes, CTT and API counters",
        options =
            listOf(
                OptSpec.Value("since", "How far back to read transcripts (`60d`, `24h`)", "SINCE"),
                OptSpec.Flag("json", "JSON instead of the table"),
                OptSpec.Value("plugin", "Restrict to one tool or plugin id", "PLUGIN"),
                OptSpec.Value("save-baseline", "Write this report JSON to `<home>/measurements/<name>.json`", "NAME"),
                OptSpec.Value("compare", "Print deltas against a saved baseline", "NAME"),
                OptSpec.Flag("calibrate", "Fit chars-per-token via count_tokens (skipped without an API key)"),
                OptSpec.Flag("cache", "Cache health per session from proxy usage rows: busts and their cause"),
                OptSpec.Flag("price", "Show per-model USD costs from `[stats.prices]` (`--price`)"),
            ),
    ) { inv ->
        val since = inv.value("since")
        // Parsed before it merges into `stats.since`, so a later error can only come from the config.
        since?.let(::parseSince)
        val compare = inv.value("compare")
        val cfg = Config.loadWith(inv.configFile, statsFlags(since, inv.flag("json"), inv.value("plugin"), compare, inv.flag("price")))
        val json = cfg.stats.format == "json"
        when {
            inv.flag("calibrate") -> {
                inv.io.out(calibrateOrSkip(cfg.estimator) + "\n")
            }

            inv.flag("cache") -> {
                val report = StatsPage.cacheHealth(cfg)
                inv.io.out(if (json) CacheHealth.toJsonText(report) else CacheHealth.table(report))
            }

            CATALOGUE.any { it.first == cfg.stats.plugin } -> {
                inv.io.out(StatsPage.pluginJson(cfg, cfg.stats.plugin))
            }

            else -> {
                val report = StatsPage.report(cfg)
                val saveAs = inv.value("save-baseline")
                val base =
                    compare ?: cfg.stats.baseline
                        .trim()
                        .ifEmpty { null }
                when {
                    saveAs != null -> inv.io.out(Baseline.save(cfg.home, saveAs, report) + "\n")
                    base != null -> inv.io.out(Baseline.compare(cfg.home, base, report))
                    json -> inv.io.out(report.toJsonText())
                    else -> inv.io.out(report.toTable())
                }
            }
        }
    }
