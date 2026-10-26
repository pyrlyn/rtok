// Port of src/agents/kimi/mod.rs: Kimi Code CLI + Desktop read `[[hooks]]` tables (`event`, `matcher`, `command`,
// `timeout` s) in `[setup.kimi] config_path` and MCP from the sibling `mcp.json` (`{command, args}`, no `type`). The TOML
// goes through [TomlDoc], so comments and foreign hooks survive. Kimi owns its plugin store, so the plugin is only a
// printed `/plugins install <plugins/kimi>` offer; while its managed copy exists (D21) rtok's own tables are stripped.
package rtok.agents

import rtok.agent.sdk.Apply
import rtok.agent.sdk.NO_CHANGES
import rtok.agent.sdk.write
import rtok.config.Config
import rtok.mcp.config.Client
import rtok.mcp.config.EntryShape
import rtok.mcp.config.McpSpec
import rtok.sys.exists
import rtok.sys.isFile
import rtok.sys.join
import rtok.sys.parent
import rtok.sys.readToString
import tomlaot.TomlDoc
import tomlaot.TomlTable
import tomlaot.TomlValue

class Kimi(
    private val plugins: PluginSource = ShippedPlugins,
) : Agent {
    override val id = HOST

    override fun files(
        cfg: Config,
        kind: Kind,
    ) = listOf(cfg.setup.kimi.configPath, mcpPath(cfg))

    override fun installed(
        cfg: Config,
        kind: Kind,
    ): List<String> {
        val path = cfg.setup.kimi.configPath
        val hooks = exists(path) && "rtok hook" in runCatching { readToString(path) }.getOrDefault("")
        return listOfNotNull(
            "hooks".takeIf { hooks },
            "mcp".takeIf { McpEntries.hasEntry(mcpPath(cfg), "mcpServers", NAME) },
            "plugin".takeIf { pluginDetected(cfg) },
        )
    }

    override fun apply(
        cfg: Config,
        kind: Kind,
        mode: Mode,
    ): List<String> {
        if (mode == Mode.Remove) return listOfNotNull(offerPlugin(cfg, true), run(cfg, true), McpRun.sync(cfg, mcpSpec(cfg), mode))
        val plugin = pluginDetected(cfg)
        // D21: while the plugin is installed it is the only hook path, so rtok's tables go; MCP stays (T275/D33).
        val lines = listOfNotNull(run(cfg, plugin), McpRun.sync(cfg, mcpSpec(cfg), mode))
        // A repeat install is all NO_CHANGES, the offer included, so it reads back as `already installed`.
        val offer = if (plugin || (lines.any { it != NO_CHANGES } && cfg.setup.yes)) offerPlugin(cfg, false) else NO_CHANGES
        return listOf(offer) + lines
    }

    /** The `/plugins install <plugins/kimi>` line; rtok never writes `plugins/managed/` (Kimi's, undocumented). */
    fun offerPlugin(
        cfg: Config,
        remove: Boolean,
    ): String =
        PluginOffers.printOffer(
            cfg,
            remove,
            pluginDetected(cfg),
            PLUGIN_REL,
            "/plugins install ${plugins.of(PLUGIN_REL)}",
            "keep plugins/managed/rtok (owned by Kimi; remove with `/plugins remove rtok`)",
        )

    companion object {
        const val HOST = "kimi"
        private const val NAME = "rtok"
        private const val PLUGIN_REL = "plugins/kimi"
        private const val AOT = "hooks"

        private fun home(cfg: Config) = parent(cfg.setup.kimi.configPath) ?: "."

        /** `mcp.json` beside `config.toml`; one key configures both. */
        fun mcpPath(cfg: Config) = join(home(cfg), "mcp.json")

        /** The managed copy Kimi writes on `/plugins install <dir>` (T86). */
        fun pluginMarker(cfg: Config) = join(join(join(join(home(cfg), "plugins"), "managed"), NAME), "kimi.plugin.json")

        fun pluginDetected(cfg: Config) = isFile(pluginMarker(cfg))

        /** `mcpServers.rtok = {command, args}` in `mcp.json`, written and removed by the shared MCP core (T277.2). */
        fun mcpSpec(cfg: Config): McpSpec = McpSurfaces.jsonSpec(HOST, Client.Cli, mcpPath(cfg), "mcpServers", rtokMcpCommand(), EntryShape.Command)

        /** Kimi's `(event, matcher)` pairs: Claude's minus SubagentStart (the `kimi` rows of [HookEvents]). */
        fun entries() = HookEvents.entries(HOST)

        /** Apply, dry-run, or remove the `[[hooks]]` tables. */
        fun run(
            cfg: Config,
            remove: Boolean,
        ): String {
            val path = cfg.setup.kimi.configPath
            val doc = HostToml.load(path)
            val apply = applyOf(cfg)
            val timeout = cfg.setup.hookTimeoutS.toLong()
            val report = if (remove) strip(apply, path, doc, timeout) else insert(doc, timeout)
            write(apply, path, doc.toString(), report)
            return report
        }

        /**
         * Add a table per [entries] pair and bring rtok's up to date (T242.5): another binary or timeout is rewritten in
         * place, one on a pair no longer listed goes. Foreign tables are never touched.
         */
        fun insert(
            doc: TomlDoc,
            timeout: Long,
        ): String {
            val entries = entries()
            val lines = mutableListOf<String>()
            doc.retain(AOT) { t ->
                val keep = !ours(t) || (event(t) to matcher(t)) in entries
                if (!keep) lines += "- [[hooks]] ${event(t)}${ClaudeHooks.show(matcher(t))}"
                keep
            }
            val removed = lines.size
            val bin = HookLine.rtokHookBin()
            var (added, updated) = 0 to 0
            for ((event, matcher) in entries) {
                val cmd = "$bin hook $event"
                val mine = doc.arrayOfTables(AOT).filter { ours(it) && event(it) == event && matcher(it) == matcher }
                for (t in mine.filter { it.str("command") != cmd || it.int("timeout") != timeout }) {
                    t["command"] = TomlValue.Str(cmd)
                    t["timeout"] = TomlValue.Int(timeout)
                    lines += "~ [[hooks]] $event${ClaudeHooks.show(matcher)} $cmd"
                    updated++
                }
                if (mine.isNotEmpty()) continue
                val fields = listOf("event" to TomlValue.Str(event)) + listOfNotNull(matcher.takeIf { it.isNotEmpty() }?.let { "matcher" to TomlValue.Str(it) })
                doc.push(AOT, fields + listOf("command" to TomlValue.Str(cmd), "timeout" to TomlValue.Int(timeout)))
                lines += "+ [[hooks]] $event${ClaudeHooks.show(matcher)} $cmd"
                added++
            }
            if (lines.isEmpty()) return NO_CHANGES
            val counts = listOf(added to "additions", updated to "updates", removed to "removals").filter { it.first > 0 }
            return lines.joinToString("\n") + "\n" + counts.joinToString(", ") { "${it.first} ${it.second}" }
        }

        /** Remove rtok's tables (T246.6): one as [insert] writes it goes; one the user changed as `keep_edited` decides. */
        fun strip(
            apply: Apply,
            path: String,
            doc: TomlDoc,
            timeout: Long,
        ): String {
            var removed = 0
            val kept = mutableListOf<String>()
            doc.retain(AOT) { t ->
                if (!ours(t)) return@retain true
                val keys = if (t.containsKey("matcher")) OWN_KEYS_WITH_MATCHER else OWN_KEYS
                val unchanged = (event(t) to matcher(t)) in entries() && t.size == keys && t.int("timeout") == timeout
                val at = { "[[hooks]] ${event(t)}${ClaudeHooks.show(matcher(t))} in $path" }
                val take = StripReport.takesHook(apply, unchanged, at, kept)
                if (take) removed++
                !take
            }
            return StripReport.withKept(kept, StripReport.removedReport(removed))
        }

        private const val OWN_KEYS = 3
        private const val OWN_KEYS_WITH_MATCHER = 4
    }
}

private fun event(t: TomlTable) = t.str("event").orEmpty()

private fun matcher(t: TomlTable) = t.str("matcher").orEmpty()

/** rtok's own table: Claude's command spelling for the table's event. */
private fun ours(t: TomlTable) = t.str("command")?.let { ClaudeHooks.isOurs(it, event(t)) } == true
