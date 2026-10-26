// Port of src/agents/claude/mod.rs (install side, chunk 3d-1): Claude Code keeps its hooks and `env.ANTHROPIC_BASE_URL`
// in `settings.json` and `mcpServers.rtok` in `~/.claude.json`; Claude Desktop has only `mcpServers.rtok` in
// `claude_desktop_config.json`, with the absolute binary. `plugins/claude` comes from the GitHub marketplace
// `pyrlyn/rtok` through `claude plugin …`, by default once `claude` is on PATH; while installed it is the only hook
// path (D21), MCP stays independent (T275); `agents update` takes the version decision ([ClaudeUpdate]). `--replace` runs
// [ClaudeMigrate]. Not yet ported: skill sync.
package rtok.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import rtok.agent.sdk.HostJson
import rtok.agent.sdk.KETCH_INSTALL
import rtok.agent.sdk.NO_CHANGES
import rtok.config.Config
import rtok.mcp.config.Client
import rtok.mcp.config.EntryShape
import rtok.sys.exists
import rtok.sys.readToString

/** What Claude Code's `known_marketplaces.json` says about the `rtok` marketplace. */
enum class MarketplaceState { Absent, Current, Stale }

class Claude(
    private val call: (Config, List<String>, Pair<String, String>?) -> String? = { c, a, e -> HostCli.runCli(c, HOST, a, e) },
    val onPath: (Config) -> Boolean = { Locate.cli(it, HOST) != null },
    val plugins: PluginSource = ShippedPlugins,
) : Agent {
    override val id = HOST

    override fun files(
        cfg: Config,
        kind: Kind,
    ) = if (kind == Kind.Desktop) listOf(McpPaths.claudeDesktop()) else listOf(cfg.setup.claude.settingsPath, cfg.doctor.claudeJson)

    override fun installed(
        cfg: Config,
        kind: Kind,
    ): List<String> {
        if (kind == Kind.Desktop) return listOfNotNull("mcp".takeIf { McpEntries.hasEntry(McpPaths.claudeDesktop(), KEY, NAME) })
        val plugin = pluginInstalled(cfg)
        val files = filesServeRtok(cfg)
        val root = runCatching { Json.parseToJsonElement(read(cfg.setup.claude.settingsPath)) as JsonObject }.getOrNull()
        val proxy = root != null && ProxyEnv.value(root, PROXY_KEY) == ProxyEnv.anthropic(cfg)
        return listOfNotNull(
            "hooks".takeIf { "hooks" in files || plugin },
            "mcp".takeIf { "mcp" in files },
            "proxy".takeIf { proxy },
            "plugin".takeIf { plugin },
        )
    }

    override fun apply(
        cfg: Config,
        kind: Kind,
        mode: Mode,
    ): List<String> =
        when {
            kind == Kind.Desktop -> {
                listOf(McpRun.sync(cfg, mcpSpec(cfg, kind), mode) ?: NO_CHANGES)
            }

            mode == Mode.Remove -> {
                listOf(plugin(cfg, true), run(cfg, true), McpRun.remove(cfg, mcpSpec(cfg, kind)), proxy(cfg, true))
            }

            mode == Mode.Replace -> {
                listOf(ClaudeMigrate.run(cfg))
            }

            else -> {
                val head = if (mode == Mode.Update) ClaudeUpdate(this).run(cfg) else plugin(cfg, false)
                listOfNotNull(
                    head,
                    run(cfg, pluginInstalled(cfg)),
                    if (cfg.setup.mcp) McpRun.install(cfg, mcpSpec(cfg, kind)) else null,
                    if (cfg.setup.proxy) proxy(cfg, false) else null,
                )
            }
        }

    /** Offer, install or uninstall `plugins/claude` from the GitHub marketplace (T115, T139). */
    fun plugin(
        cfg: Config,
        remove: Boolean,
    ): String = pluginAgainst(cfg, remove, MARKETPLACE_REPO, force = false)

    /** [plugin] over any marketplace [target] (a GitHub shorthand or a local checkout); [force] reinstalls. */
    fun pluginAgainst(
        cfg: Config,
        remove: Boolean,
        target: String,
        force: Boolean,
    ): String {
        val steps = pluginSteps(cfg, remove, target, force) ?: return NO_CHANGES
        val shown = shownSteps(steps)
        return when {
            applyOf(cfg).dryRun && remove -> "- plugin $PLUGIN_ID ($shown)"
            applyOf(cfg).dryRun -> "offer $PLUGIN_SRC → $shown $KETCH_INSTALL"
            !remove && !onPath(cfg) -> claudeMissing(shown)
            else -> runSteps(cfg, remove, steps, shown)
        }
    }

    /** One `claude …` call; `CLAUDE_CONFIG_DIR` only when `settings_path` is not the default. Null on success. */
    fun cli(
        cfg: Config,
        args: List<String>,
    ): String? = call(cfg, args, configDir(cfg).takeIf { it != "${McpPaths.home()}/.claude" }?.let { "CLAUDE_CONFIG_DIR" to it })

    private fun runSteps(
        cfg: Config,
        remove: Boolean,
        steps: List<List<String>>,
        shown: String,
    ): String {
        var removed = false
        var failure: String? = null
        for (step in steps) {
            val err = cli(cfg, step)
            if (err != null) {
                failure = if (!remove && removed) "plugin $PLUGIN_ID $REINSTALL_FAILED $err" else "offer $PLUGIN_SRC → $shown (claude failed: $err)"
                break
            }
            if (!remove && step.take(2) == listOf("plugin", "uninstall")) removed = true
        }
        return failure ?: if (remove) "- plugin $PLUGIN_ID" else "+ plugin $PLUGIN_SRC → $PLUGIN_ID"
    }

    companion object {
        const val HOST = "claude"
        private const val NAME = "rtok"
        private const val KEY = "mcpServers"
        private const val PROXY_KEY = "ANTHROPIC_BASE_URL"
        const val PLUGIN_SRC = "plugins/claude"
        const val PLUGIN_ID = "rtok@rtok"
        const val MARKETPLACE_REPO = "pyrlyn/rtok"

        /** The one plugin failure `agents update` cannot shrug off: the old copy is gone, the new one did not land. */
        const val REINSTALL_FAILED = "removed, reinstall failed:"

        private fun read(path: String) = if (exists(path)) runCatching { readToString(path) }.getOrDefault("") else ""

        /** `~/.claude`: the dir `settings_path` lives in. */
        fun configDir(cfg: Config): String =
            cfg.setup.claude.settingsPath
                .substringBeforeLast('/', cfg.setup.claude.settingsPath)

        /** `mcpServers.rtok` for [kind]: `rtok mcp --host claude` in `~/.claude.json`, or the absolute binary on the desktop. */
        fun mcpSpec(
            cfg: Config,
            kind: Kind,
        ) = if (kind == Kind.Cli) {
            McpSurfaces.jsonSpec(HOST, Client.Cli, cfg.doctor.claudeJson, KEY, rtokMcpCommand(), EntryShape.TypedCommand)
        } else {
            McpSurfaces.jsonSpec(HOST, Client.Desktop, McpPaths.claudeDesktop(), KEY, HookLine.desktopCommand(), EntryShape.TypedCommand)
        }

        /** The modules the two files a foreign importer reads carry (Grok's `[compat.claude]`, T100). */
        fun filesServeRtok(cfg: Config): List<String> =
            listOfNotNull(
                "hooks".takeIf { "rtok hook" in read(cfg.setup.claude.settingsPath) },
                "mcp".takeIf { McpEntries.hasEntry(cfg.doctor.claudeJson, KEY, NAME) },
            )

        /** True when Claude Code's own record lists `rtok@rtok` (T75). */
        fun pluginInstalled(cfg: Config) = "\"$PLUGIN_ID\"" in read("${configDir(cfg)}/plugins/installed_plugins.json")

        /** The `rtok` marketplace against [target]: GitHub `repo` or `directory` `path`; anything else is stale. */
        fun marketplaceState(
            cfg: Config,
            target: String,
        ): MarketplaceState {
            val root = runCatching { Json.parseToJsonElement(read("${configDir(cfg)}/plugins/known_marketplaces.json")) as JsonObject }.getOrNull()
            val src = (root?.get(NAME) as? JsonObject ?: return MarketplaceState.Absent)["source"]
            val at =
                when (str(src, "source")) {
                    "github" -> str(src, "repo")
                    "directory" -> str(src, "path")
                    else -> null
                }
            return if (at == target) MarketplaceState.Current else MarketplaceState.Stale
        }

        /** The `claude plugin …` calls, or null when there is nothing to do. */
        fun pluginSteps(
            cfg: Config,
            remove: Boolean,
            target: String,
            force: Boolean,
        ): List<List<String>>? {
            val installed = pluginInstalled(cfg)
            val state = marketplaceState(cfg, target)
            val repoint = state == MarketplaceState.Stale
            return when {
                remove && installed -> {
                    listOf(listOf("plugin", "uninstall", PLUGIN_ID), listOf("plugin", "marketplace", "remove", NAME))
                }

                remove || (installed && !repoint && !force) -> {
                    null
                }

                else -> {
                    listOfNotNull(
                        listOf("plugin", "uninstall", PLUGIN_ID).takeIf { installed && (repoint || force) },
                        listOf("plugin", "marketplace", "remove", NAME).takeIf { repoint },
                        listOf("plugin", "marketplace", "add", target).takeIf { state != MarketplaceState.Current },
                        listOf("plugin", "install", PLUGIN_ID),
                    )
                }
            }
        }

        fun shownSteps(steps: List<List<String>>) = steps.joinToString(" && ") { "claude ${it.joinToString(" ")}" }

        fun claudeMissing(shown: String) = "offer $PLUGIN_SRC → $shown (claude failed: claude not found on PATH) $KETCH_INSTALL"

        /** Apply or strip rtok's hooks in `settings.json` (Claude's rows, Worktree rows are plugin-only). */
        fun run(
            cfg: Config,
            remove: Boolean,
        ): String {
            val path = cfg.setup.claude.settingsPath
            val rows = HookEvents.entries(HOST).filterNot { it.first.startsWith("Worktree") }
            val ctx = HookEdit(ClaudeHooks.CLAUDE, applyOf(cfg), path, HookSpec(rows, "timeout", cfg.setup.hookTimeoutS.toLong()))
            return HostJson.edit(ctx.apply, path) { root -> ClaudeHooks.edit(root, remove, ctx) }
        }

        /** `env.ANTHROPIC_BASE_URL` → the proxy; remove only while it still names this proxy. */
        fun proxy(
            cfg: Config,
            remove: Boolean,
        ) = ProxyEnv.run(cfg, cfg.setup.claude.settingsPath, PROXY_KEY, ProxyEnv.anthropic(cfg), remove, onlyOurs = true)
    }
}
