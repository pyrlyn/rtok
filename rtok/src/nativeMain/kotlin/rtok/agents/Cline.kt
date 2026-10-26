// Port of src/agents/cline/mod.rs: the `cline` CLI and the VS Code extension share `~/Documents/Cline/Hooks`, where
// each installer row of [HookEvents] links `plugins/cline/hooks/rtok-hook` as `<hooks_path>/<Event>`. MCP is per
// surface: the CLI file and the extension's globalStorage file, both `mcpServers.rtok`. The hook links ARE the plugin unit.
package rtok.agents

import rtok.config.Config
import rtok.sys.join

class Cline(
    private val plugins: PluginSource = ShippedPlugins,
) : Agent {
    override val id = "cline"

    override fun files(
        cfg: Config,
        kind: Kind,
    ) = listOf(mcpPath(cfg, kind))

    override fun installed(
        cfg: Config,
        kind: Kind,
    ): List<String> {
        val hooks = if (HOOKS.all { it.ours(cfg, plugins) }) listOf("hooks", "plugin") else emptyList()
        val mcp = if (Kind.entries.any { mentionsRtok(mcpPath(cfg, it)) }) listOf("mcp") else emptyList()
        return hooks + mcp
    }

    override fun apply(
        cfg: Config,
        kind: Kind,
        mode: Mode,
    ): List<String> {
        val remove = mode == Mode.Remove
        val links = HOOKS.map { it.offer(cfg, remove, plugins) }
        val paths = Kind.entries.map { mcpPath(cfg, it) }
        val mcp =
            when {
                remove -> paths.map { StdioMcp.unregister(cfg, it, id) }
                cfg.setup.mcp -> paths.map { StdioMcp.register(cfg, it, id) }
                else -> emptyList()
            }
        return links + mcp
    }

    companion object {
        private const val HOOK_SRC = "plugins/cline/hooks/rtok-hook"

        /** One hook link per installer row, in link order (T95: the event is the file name). */
        val HOOKS: List<HostPlugin> =
            HookEvents.installed("cline").map { (event, _) ->
                HostPlugin(HOOK_SRC, "Cline", null, { join(it.setup.cline.hooksPath, event) }, defaultInstall = false)
            }

        /** The CLI file (`[setup.cline] mcp_path`) or the extension's `cline_mcp_settings.json`. */
        fun mcpPath(
            cfg: Config,
            kind: Kind,
        ) = McpFiles.BY_HOST.getValue("cline")(cfg, kind).path
    }
}
