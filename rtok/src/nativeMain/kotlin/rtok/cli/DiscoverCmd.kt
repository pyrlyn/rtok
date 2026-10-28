// Port of src/cli/discover.rs — `rtok discover` (T688): uncovered shell command families, most output first.
package rtok.cli

import kotlinx.serialization.json.Json
import rtok.config.Config
import rtok.measure.Discovery
import rtok.measure.stats.SerdeJson
import rtok.model.StatsPage

private const val DEFAULT_SINCE = "30d"
private const val DEFAULT_LIMIT = 20

val DISCOVER =
    CommandSpec(
        "discover",
        "Shell commands your agents ran that have no rule or formatter, most output first",
        options =
            listOf(
                sinceOpt("How far back to read recorded shell calls (`30d`, `24h`)"),
                OptSpec.Value("limit", "Rows to print, most output first", "LIMIT"),
                OptSpec.Flag("json", "JSON instead of the table"),
            ),
    ) { inv ->
        val since = inv.since() ?: rtok.config.parseSince(DEFAULT_SINCE)
        val limit = inv.number("limit")?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: DEFAULT_LIMIT
        val found = StatsPage.discover(Config.loadWith(inv.configFile, null), since, limit)
        if (inv.flag("json")) {
            inv.io.println(SerdeJson.pretty(Json.encodeToJsonElement(Discovery.serializer(), found)))
        } else {
            inv.io.out(found.toTable())
        }
    }
