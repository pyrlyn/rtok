// Port of src/agents/vscode/mod.rs: Copilot in VS Code (stable and Insiders profiles) reads `servers.rtok`
// (`type: stdio`) from each profile's `mcp.json`, written on every install (D33). Behind `--yes`, `plugins/claude` is
// linked at `<user dir>/plugins/rtok` and that path registered in `chat.pluginLocations` of the profile's JSONC
// `settings.json`, so a version bump never leaves a stale key (the link is relinked in place).
package rtok.agents

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.agent.sdk.NO_CHANGES
import rtok.agent.sdk.write
import rtok.config.Config
import rtok.mcp.config.Client
import rtok.mcp.config.EntryShape
import rtok.mcp.config.Jsonc
import rtok.mcp.config.JsoncEdit
import rtok.plugin.sdk.Surface
import rtok.sys.exists
import rtok.sys.readToString

class Vscode(
    private val plugins: PluginSource = ShippedPlugins,
) : Agent {
    override val id = HOST
    override val pluginSurfaces = listOf(Surface.Hook)

    override fun files(
        cfg: Config,
        kind: Kind,
    ) = PROFILES.map { mcpPath(cfg, it) } + PROFILES.map { settingsPath(cfg, it) }

    override fun installed(
        cfg: Config,
        kind: Kind,
    ): List<String> =
        listOfNotNull(
            "mcp".takeIf { PROFILES.any { McpEntries.hasEntry(mcpPath(cfg, it), "servers", NAME) } },
            "plugin".takeIf { PROFILES.any { plugin(it).ours(cfg, plugins) } },
        )

    override fun apply(
        cfg: Config,
        kind: Kind,
        mode: Mode,
    ): List<String> {
        val remove = mode == Mode.Remove
        val links = PROFILES.map { offerPlugin(cfg, it, remove) }
        val mcp = PROFILES.mapNotNull { McpRun.sync(cfg, mcpSpec(cfg, it), mode) }
        return (links + mcp).filter { it != NO_CHANGES }.ifEmpty { listOf(NO_CHANGES) }
    }

    private fun offerPlugin(
        cfg: Config,
        insiders: Boolean,
        remove: Boolean,
    ): String {
        val p = plugin(insiders)
        val wasOurs = p.ours(cfg, plugins)
        val link = p.offer(cfg, remove, plugins)
        if (applyOf(cfg).dryRun) return link
        val settings =
            when {
                remove && wasOurs -> pluginLocation(cfg, insiders, false)
                !remove && p.ours(cfg, plugins) -> pluginLocation(cfg, insiders, true)
                else -> NO_CHANGES
            }
        return if (settings == NO_CHANGES) link else "$link\n$settings"
    }

    /** Add `chat.pluginLocations.<dest>: true` or drop that member, every other byte of `settings.json` kept. */
    private fun pluginLocation(
        cfg: Config,
        insiders: Boolean,
        add: Boolean,
    ): String {
        val path = settingsPath(cfg, insiders)
        val raw = if (exists(path)) readToString(path) else ""
        val key = plugin(insiders).path(cfg)
        val have = ((if (raw.isBlank()) null else Jsonc.parse(raw)) as? JsonObject)?.get(LOCATIONS)?.let { (it as? JsonObject)?.get(key) }
        val report =
            when {
                add && have == JsonPrimitive(true) -> NO_CHANGES
                add -> "${if (have == null) "+" else "~"} $LOCATIONS: $key"
                have != null -> "- $LOCATIONS: $key"
                else -> NO_CHANGES
            }
        if (report != NO_CHANGES) {
            val body = JsoncEdit.set(path, raw, listOf(LOCATIONS), key, if (add) JsonPrimitive(true) else null)
            Jsonc.parse(body)
            write(applyOf(cfg), path, body, report)
        }
        return report
    }

    companion object {
        const val HOST = "vscode"
        private const val NAME = "rtok"
        private const val LOCATIONS = "chat.pluginLocations"
        private val PROFILES = listOf(false, true)

        private val STABLE = HostPlugin("plugins/claude", "VS Code", null, { pluginDest(it, false) }, defaultInstall = false)
        private val INSIDERS = HostPlugin("plugins/claude", "VS Code - Insiders", null, { pluginDest(it, true) }, defaultInstall = false)

        fun plugin(insiders: Boolean) = if (insiders) INSIDERS else STABLE

        fun mcpPath(
            cfg: Config,
            insiders: Boolean,
        ) = join(McpPaths.vscodeUserDir(cfg, insiders), "mcp.json")

        fun settingsPath(
            cfg: Config,
            insiders: Boolean,
        ) = join(McpPaths.vscodeUserDir(cfg, insiders), "settings.json")

        fun pluginDest(
            cfg: Config,
            insiders: Boolean,
        ) = join(join(McpPaths.vscodeUserDir(cfg, insiders), "plugins"), NAME)

        fun mcpSpec(
            cfg: Config,
            insiders: Boolean,
        ) = McpSurfaces.jsonSpec(HOST, Client.Ide, mcpPath(cfg, insiders), "servers", rtokMcpCommand(), EntryShape.TypedCommand)
    }
}
