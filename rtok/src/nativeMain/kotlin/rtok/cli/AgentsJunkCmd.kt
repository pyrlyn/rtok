// Port of src/cli/agents.rs `agents junk list|clear` (`JunkCmd`, `Breakdown`, `session_days_flag`).
package rtok.cli

import humantime.parseHumanDuration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.Bytes
import rtok.agents.AgentsJson
import rtok.agents.Junk
import rtok.agents.JunkClear
import rtok.agents.JunkFilter
import rtok.agents.JunkItems
import rtok.agents.JunkList
import rtok.agents.JunkOwn
import rtok.agents.JunkSort
import rtok.agents.JunkView
import rtok.config.Config

private const val MAX_SESSION_DAYS = 3650

/** A clap value-parser refusal: `invalid value 'x' for '--name <META>'`, exit 2. */
private fun invalid(
    name: String,
    meta: String,
    value: String,
    why: String,
): Nothing = throw CliFailure("invalid value '$value' for '--$name <$meta>'$why", ExitCode.USAGE)

private fun possible(values: List<String>) = "\n  [possible values: ${values.joinToString(", ")}]"

private val BREAKDOWN =
    listOf(
        OptSpec.Value("items", "Items listed per kind: a count, `all`, or `0` for totals only", "N|all"),
        OptSpec.Value("sort", "Order of the items in a kind: `size` (largest first), `last-used` (longest unused first) or `path`", "ORDER"),
        OptSpec.Value("min-size", "Only items of at least this size (`10MB`); on `clear` this also limits what `--yes` removes", "SIZE"),
    )

private val SESSION_DAYS = OptSpec.Value("session-days", "Sessions untouched for more than this many days are old, for this run only", "N")

/** `--min-size`, 0 when absent. */
private fun Invocation.minSize(): Long = value("min-size")?.let { v -> runCatching { Bytes.parse(v) }.getOrElse { invalid("min-size", "SIZE", v, ": ${it.message}") } } ?: 0

/** `Breakdown::view`: the `--items`/`--sort`/`--min-size` flags over the config's view. */
private fun Invocation.view(
    cfg: Config,
    exact: Boolean,
    links: Boolean,
): JunkView {
    val shown = value("items")?.let { v -> runCatching { JunkItems.shownArg(v) }.getOrElse { invalid("items", "N|all", v, ": ${it.message}") } } ?: JunkItems.DEFAULT_SHOWN
    val sort = value("sort")?.also { if (it !in JunkItems.SORTS) invalid("sort", "ORDER", it, possible(JunkItems.SORTS)) } ?: "size"
    return JunkView.of(cfg).copy(shown = shown, sort = JunkSort.parse(sort), minSize = minSize(), exact = exact, links = links)
}

/** `--session-days N` as the flag layer of `agents.junk.stale_session_days`. */
private fun Invocation.sessionDaysFlag(): JsonObject? {
    val raw = value("session-days") ?: return null
    val days = raw.toIntOrNull()?.takeIf { it in 0..MAX_SESSION_DAYS } ?: invalid("session-days", "N", raw, ": $raw is not in 0..=$MAX_SESSION_DAYS")
    val junk = JsonObject(mapOf("stale_session_days" to JsonPrimitive(days)))
    return JsonObject(mapOf("agents" to JsonObject(mapOf("junk" to junk))))
}

private fun pretty(json: kotlinx.serialization.json.JsonElement) =
    AgentsJson.PRETTY.encodeToString(
        kotlinx.serialization.json.JsonElement
            .serializer(),
        json,
    ) + "\n"

private val LIST =
    CommandSpec(
        "list",
        "Folders, junk kinds and sizes per agent, and the space `agents junk clear` would free",
        options =
            listOf(
                OptSpec.Flag("json", "JSON instead of the text"),
                OptSpec.Flag("bytes", "Exact byte counts instead of KB/MB/GB"),
                OptSpec.Flag("all", "Also the hosts that are not installed"),
                SESSION_DAYS,
            ) + BREAKDOWN,
    ) { inv ->
        val cfg = Config.loadWith(inv.configFile, inv.sessionDaysFlag())
        val json = inv.flag("json")
        val view = inv.view(cfg, inv.flag("bytes"), !json && inv.io.tty)
        val report = Junk.reportInRepo(cfg, inv.flag("all"))
        inv.io.out(if (json) pretty(JunkItems.json(report, view)) else JunkList.toList(report, view))
    }

/** The `clear` flags that choose what goes, each refused like clap would (exit 2). */
private fun Invocation.filter(): JunkFilter {
    val agents = values("agent").map { a -> runCatching { JunkClear.agentArg(a) }.getOrElse { invalid("agent", "AGENT", a, ": ${it.message}") } }
    val kinds = values("kind").onEach { if (it !in JunkClear.KINDS) invalid("kind", "KIND", it, possible(JunkClear.KINDS)) }
    val include = value("include")?.also { if (it != "review") invalid("include", "CLASS", it, possible(listOf("review"))) }
    val olderThan = value("older-than")?.let { v -> runCatching { parseHumanDuration(v) }.getOrElse { invalid("older-than", "AGE", v, ": ${it.message}") } }
    return JunkFilter(agents, kinds, include != null, olderThan, flag("trash"), minSize())
}

private val CLEAR =
    CommandSpec(
        "clear",
        "List what `agents junk clear` would remove; `--yes` applies it",
        options =
            listOf(
                OptSpec.Flag("yes", "Apply; without it this is a dry run that changes nothing"),
                OptSpec.Flag("dry-run", "Say the dry run out loud: print what would go and change nothing (the default)"),
                OptSpec.Flag("stat", "The same preview as the dry run: a removal's line already is its stat"),
                OptSpec.Flag("json", "JSON instead of the table"),
                OptSpec.Value("agent", "Only this agent: `rtok` or a host id (repeatable)", "AGENT", repeat = true),
                OptSpec.Value("kind", "Only this junk kind (repeatable)", "KIND", repeat = true),
                OptSpec.Value("include", "Also the review kinds", "CLASS"),
                OptSpec.Value("older-than", "Only items not modified for this long (`7d`, `12h`)", "AGE"),
                SESSION_DAYS,
                OptSpec.Flag("trash", "Move to the OS trash instead of deleting"),
            ) + BREAKDOWN,
    ) { inv ->
        inv.conflict("dry-run", "yes")
        val filter = inv.filter()
        val cfg = Config.loadWith(inv.configFile, inv.sessionDaysFlag())
        val yes = inv.flag("yes")
        val json = inv.flag("json")
        val failed =
            if (!filter.isT182 || filter.trash) {
                val cleared = JunkClear.run(cfg, filter, yes)
                inv.io.out(if (json) pretty(cleared.json()) else JunkClear.toText(cleared, inv.view(cfg, false, inv.io.tty)))
                cleared.failed
            } else {
                val outcomes = JunkOwn.run(cfg, yes)
                inv.io.out(if (json) pretty(JunkOwn.json(outcomes)) else JunkOwn.toTable(outcomes, yes))
                outcomes.any { it.failed }
            }
        if (failed) throw CliFailure("some junk could not be removed")
    }

val JUNK =
    CommandSpec(
        "junk",
        "Junk and agent folders: `list` shows rtok's and every installed host's folders with sizes, `clear` removes rtok's own junk or, filtered, any agent's",
        subcommands = listOf(LIST, CLEAR),
    )
