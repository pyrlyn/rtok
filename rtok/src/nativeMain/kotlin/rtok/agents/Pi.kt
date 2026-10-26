// Port of src/agents/pi/mod.rs: pi has no MCP and no hook events, so its only unit is `plugins/pi` linked as
// `<[setup.pi] extensions_path>/rtok`, installed by default once pi is detected (T164). oh my pi links the same tree.
package rtok.agents

import rtok.config.Config

class Pi(
    private val plugins: PluginSource = ShippedPlugins,
) : Agent {
    override val id = "pi"

    override fun files(
        cfg: Config,
        kind: Kind,
    ) = emptyList<String>()

    /** T75: only what remove takes back counts; a foreign directory at the dest is not ours. */
    override fun installed(
        cfg: Config,
        kind: Kind,
    ): List<String> = if (PLUGIN.ours(cfg, plugins)) listOf("plugin") else emptyList()

    override fun apply(
        cfg: Config,
        kind: Kind,
        mode: Mode,
    ): List<String> = listOf(PLUGIN.offer(cfg, mode == Mode.Remove, plugins), Skills.sync(id, cfg, mode == Mode.Remove, plugins))

    companion object {
        /** The extension tree pi and oh my pi both load. */
        const val PLUGIN_REL = "plugins/pi"
        const val DIR_NAME = "rtok"

        val PLUGIN = HostPlugin(PLUGIN_REL, "pi", null, { join(it.setup.pi.extensionsPath, DIR_NAME) }, defaultInstall = true)
    }
}
