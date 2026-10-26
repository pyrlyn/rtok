// Port of src/agents/codebuddy/mod.rs: CodeBuddy Code (CLI, `[setup.codebuddy] dir`) and WorkBuddy (Desktop,
// `workbuddy_dir`) share one layout: Claude-shape hooks spelled `hook <event> --host codebuddy` under `hooks` in
// `settings.json`, and the typed `mcpServers.rtok` in the first existing `.mcp.json`/`mcp.json`/legacy `~/.codebuddy.json`,
// edited as JSONC. `plugins/codebuddy` is the D21 hooks unit, installed through the `codebuddy` CLI behind `--yes`.
package rtok.agents

import rtok.agent.sdk.HostJson
import rtok.agent.sdk.KETCH_INSTALL
import rtok.agent.sdk.NO_CHANGES
import rtok.config.Config
import rtok.mcp.config.Client
import rtok.mcp.config.EntryShape
import rtok.mcp.config.Format
import rtok.sys.exists
import rtok.sys.readToString

class Codebuddy(
    private val plugins: PluginSource = ShippedPlugins,
    private val call: (Config, List<String>, Pair<String, String>?) -> String? = { c, a, e -> HostCli.runCli(c, HOST, a, e) },
    private val onPath: (Config) -> Boolean = { Locate.cli(it, HOST) != null },
) : Agent {
    override val id = HOST
    override val pluginServesMcp = true

    override fun files(
        cfg: Config,
        kind: Kind,
    ) = listOf(settingsPath(cfg, kind), mcpPath(cfg, kind))

    override fun installed(
        cfg: Config,
        kind: Kind,
    ): List<String> {
        val settings = settingsPath(cfg, kind)
        val hooks = exists(settings) && " hook PreToolUse" in runCatching { readToString(settings) }.getOrDefault("")
        return listOfNotNull(
            "hooks".takeIf { hooks },
            "mcp".takeIf { McpEntries.hasEntry(mcpPath(cfg, kind), "mcpServers", NAME) },
            "plugin".takeIf { pluginInstalled(cfg, kind) },
        )
    }

    override fun apply(
        cfg: Config,
        kind: Kind,
        mode: Mode,
    ): List<String> =
        D21Plugin(
            offer = { c, remove -> plugin(c, kind, remove) },
            pluginInstalled = { pluginInstalled(it, kind) },
            run = { c, remove -> run(c, kind, remove) },
            registerMcp = { McpRun.install(it, mcpSpec(it, kind)) },
            unregisterMcp = { McpRun.remove(it, mcpSpec(it, kind)) },
        ).apply(cfg, mode)

    /** Install or uninstall `plugins/codebuddy` as the local marketplace `rtok`, pointed at [kind]'s dir when not the default. */
    fun plugin(
        cfg: Config,
        kind: Kind,
        remove: Boolean,
    ): String {
        val a = applyOf(cfg)
        if (pluginInstalled(cfg, kind) != remove || (!remove && !a.yes)) return NO_CHANGES
        val steps = steps(remove)
        val d = dir(cfg, kind)
        val env = ("CODEBUDDY_CONFIG_DIR" to d).takeIf { kind == Kind.Desktop || d != defaultDir() }
        val prefix = env?.let { "${it.first}=${it.second} " }.orEmpty()
        val shown = steps.joinToString(" && ") { "${prefix}codebuddy ${it.joinToString(" ")}" }
        val failed = { e: String -> "offer $PLUGIN_SRC → $shown (codebuddy failed: $e)" }
        return when {
            a.dryRun && remove -> {
                "- plugin $NAME ($shown)"
            }

            a.dryRun -> {
                "offer $PLUGIN_SRC → $shown $KETCH_INSTALL"
            }

            !onPath(cfg) -> {
                failed("not on PATH; hooks and MCP go into $d instead")
            }

            else -> {
                val results = steps.map { call(cfg, it, env) }
                when {
                    results.last() != null -> failed(results.firstNotNullOf { it })
                    remove -> "- plugin $NAME"
                    else -> "+ plugin $PLUGIN_SRC → $NAME"
                }
            }
        }
    }

    private fun steps(remove: Boolean): List<List<String>> =
        if (remove) {
            listOf(listOf("plugin", "uninstall", PLUGIN_ID, "-s", "user", "-y"))
        } else {
            listOf(listOf("plugin", "marketplace", "add", plugins.of(PLUGIN_SRC), "--name", NAME), listOf("plugin", "install", PLUGIN_ID, "-s", "user"))
        }

    companion object {
        const val HOST = "codebuddy"
        private const val NAME = "rtok"
        private const val PLUGIN_SRC = "plugins/codebuddy"
        private const val PLUGIN_ID = "rtok@rtok"

        val FORM = ClaudeHooks.formFor(HOST)

        private fun defaultDir() = "${McpPaths.home()}/.codebuddy"

        fun dir(
            cfg: Config,
            kind: Kind,
        ) = if (kind == Kind.Cli) cfg.setup.codebuddy.dir else cfg.setup.codebuddy.workbuddyDir

        fun settingsPath(
            cfg: Config,
            kind: Kind,
        ) = join(dir(cfg, kind), "settings.json")

        fun mcpPath(
            cfg: Config,
            kind: Kind,
        ) = McpPaths.codebuddy(cfg, kind)

        /** `mcpServers.rtok = {type: stdio, command, args}`, edited as JSONC so comments survive. */
        fun mcpSpec(
            cfg: Config,
            kind: Kind,
        ) = McpSurfaces
            .jsonSpec(HOST, if (kind == Kind.Cli) Client.Cli else Client.Desktop, mcpPath(cfg, kind), "mcpServers", rtokMcpCommand(), EntryShape.TypedCommand)
            .copy(format = Format.Jsonc)

        fun pluginInstalled(
            cfg: Config,
            kind: Kind,
        ): Boolean {
            val path = join(dir(cfg, kind), "plugins/installed_plugins.json")
            return exists(path) && "\"$PLUGIN_ID\"" in runCatching { readToString(path) }.getOrDefault("")
        }

        /** Apply or strip rtok's hooks under `hooks` in [kind]'s `settings.json`. */
        fun run(
            cfg: Config,
            kind: Kind,
            remove: Boolean,
        ): String {
            val path = settingsPath(cfg, kind)
            val ctx = HookEdit(FORM, applyOf(cfg), path, HookSpec(HookEvents.entries(HOST), "timeout", cfg.setup.hookTimeoutS.toLong()))
            return HostJson.edit(ctx.apply, path) { root -> ClaudeHooks.edit(root, remove, ctx) }
        }
    }
}
