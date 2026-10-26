// Port of src/agents/omp/mod.rs: oh my pi is a pi fork that loads `plugins/pi` as is (linked behind `--yes`) and,
// unlike pi, has native MCP: `mcpServers.rtok = {command, args}` in `[setup.omp] mcp_path`, no `type`.
package rtok.agents

import rtok.config.Config
import rtok.plugin.sdk.Surface

class Omp(
    private val plugins: PluginSource = ShippedPlugins,
) : Agent {
    override val id = HOST
    override val pluginSurfaces = listOf(Surface.Cli)

    override fun files(
        cfg: Config,
        kind: Kind,
    ) = listOf(cfg.setup.omp.mcpPath)

    override fun installed(
        cfg: Config,
        kind: Kind,
    ): List<String> =
        listOfNotNull(
            "plugin".takeIf { PLUGIN.ours(cfg, plugins) },
            "mcp".takeIf { mentionsRtok(cfg.setup.omp.mcpPath) },
        )

    override fun apply(
        cfg: Config,
        kind: Kind,
        mode: Mode,
    ): List<String> {
        val remove = mode == Mode.Remove
        val mcp =
            when {
                remove -> StdioMcp.unregister(cfg, cfg.setup.omp.mcpPath, HOST)
                cfg.setup.mcp -> StdioMcp.register(cfg, cfg.setup.omp.mcpPath, HOST)
                else -> null
            }
        return listOfNotNull(PLUGIN.offer(cfg, remove, plugins), mcp)
    }

    companion object {
        const val HOST = "omp"

        /** Left asking with `--yes`, unlike pi (T164). */
        val PLUGIN = HostPlugin(Pi.PLUGIN_REL, "oh my pi", null, { join(it.setup.omp.extensionsPath, Pi.DIR_NAME) }, defaultInstall = false)
    }
}
