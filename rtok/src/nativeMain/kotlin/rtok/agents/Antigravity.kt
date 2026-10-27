// Port of src/agents/antigravity/mod.rs: the plugin is the only unit (T90): `plugins/antigravity` carries the MCP config
// and no hooks. Desktop links it into `<[setup.antigravity] plugins_path>/rtok` behind `--yes`; the CLI's store is
// `agy`'s own, so rtok prints the `agy plugin install` line instead (the Kimi rule, T86).
package rtok.agents

import rtok.config.Config
import rtok.plugin.sdk.Surface

class Antigravity(
    private val plugins: PluginSource = ShippedPlugins,
) : Agent {
    override val id = "antigravity"
    override val pluginSurfaces = listOf(Surface.Mcp)

    override fun files(
        cfg: Config,
        kind: Kind,
    ) = emptyList<String>()

    override fun markers(
        cfg: Config,
        kind: Kind,
    ) = when (kind) {
        Kind.Cli -> listOf(cliPluginDest(cfg), cfg.setup.antigravity.cliPluginsPath)
        Kind.Desktop -> listOf(PLUGIN.path(cfg), cfg.setup.antigravity.pluginsPath)
    }

    override fun installed(
        cfg: Config,
        kind: Kind,
    ): List<String> {
        val found = if (kind == Kind.Cli) cliPluginDetected(cfg) else PLUGIN.ours(cfg, plugins)
        return if (found) listOf("plugin") else emptyList()
    }

    override fun apply(
        cfg: Config,
        kind: Kind,
        mode: Mode,
    ): List<String> {
        val remove = mode == Mode.Remove
        // T91.2: the hub skills go to each variant's own global skill root.
        return if (kind == Kind.Cli) {
            listOf(offerCli(cfg, remove), Skills.sync("antigravity-cli", cfg, remove, plugins))
        } else {
            listOf(PLUGIN.offer(cfg, remove, plugins), Skills.sync("antigravity", cfg, remove, plugins))
        }
    }

    private fun offerCli(
        cfg: Config,
        remove: Boolean,
    ) = PluginOffers.printOffer(
        cfg,
        remove,
        cliPluginDetected(cfg),
        PLUGIN_REL,
        "agy plugin install ${plugins.of(PLUGIN_REL)}",
        "keep antigravity-cli/plugins/rtok (owned by agy; remove with `agy plugin uninstall rtok`)",
    )

    companion object {
        private const val PLUGIN_REL = "plugins/antigravity"
        private const val DIR_NAME = "rtok"

        val PLUGIN = HostPlugin(PLUGIN_REL, "Antigravity", null, { join(it.setup.antigravity.pluginsPath, DIR_NAME) }, defaultInstall = false)

        fun cliPluginDest(cfg: Config) = join(cfg.setup.antigravity.cliPluginsPath, DIR_NAME)

        fun cliPluginDetected(cfg: Config) = PluginOffers.manifestNames(cliPluginDest(cfg), "plugin.json", DIR_NAME)
    }
}
