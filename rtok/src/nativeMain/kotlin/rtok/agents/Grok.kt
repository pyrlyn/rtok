// Port of src/agents/grok/mod.rs: Grok Build reads `[mcp_servers.rtok]` from `~/.grok/config.toml` and fires one hook
// set — `plugins/grok` (offered behind `--yes`, Grok owns its copy) or rtok's Claude hooks through its `[compat.claude]`
// import — so setup never writes a second set. MCP is independent of the plugin (T275/D33), skipped only while the
// Claude import already covers it; an entry an older rtok wrote without `--host` is upgraded (T283.2).
package rtok.agents

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import rtok.agent.sdk.KETCH_INSTALL
import rtok.agent.sdk.NO_CHANGES
import rtok.config.Config
import rtok.config.Toml
import rtok.mcp.config.Client
import rtok.mcp.config.EntryShape
import rtok.mcp.config.Format
import rtok.plugin.sdk.Surface
import rtok.sys.exists
import rtok.sys.isDir
import rtok.sys.readToString

class Grok(
    private val plugins: PluginSource = ShippedPlugins,
) : Agent {
    override val id = HOST
    override val pluginSurfaces = listOf(Surface.Hook)

    override fun files(
        cfg: Config,
        kind: Kind,
    ) = listOf(cfg.setup.grok.configPath)

    override fun markers(
        cfg: Config,
        kind: Kind,
    ) = listOf(cfg.setup.grok.configPath, pluginMarker(cfg))

    override fun installed(
        cfg: Config,
        kind: Kind,
    ): List<String> =
        listOfNotNull(
            "plugin".takeIf { pluginDetected(cfg) },
            "hooks".takeIf { covered(cfg, "hooks") },
            "mcp".takeIf { covered(cfg, "mcp") || McpEntries.hasTomlEntry(cfg.setup.grok.configPath, KEY, NAME) },
        )

    override fun apply(
        cfg: Config,
        kind: Kind,
        mode: Mode,
    ): List<String> {
        if (mode == Mode.Remove) return listOf(offerPlugin(cfg, true), unregisterMcp(cfg))
        val lines = listOfNotNull(hooksNote(cfg), if (cfg.setup.mcp) registerMcp(cfg) else null)
        val head = if (lines.any { it != NO_CHANGES } && cfg.setup.yes) offerPlugin(cfg, false) else NO_CHANGES
        return listOf(head) + lines
    }

    /** Offer `grok plugin install <plugins/grok> --trust` behind `--yes`; remove leaves Grok's own copy. */
    fun offerPlugin(
        cfg: Config,
        remove: Boolean,
    ): String =
        when {
            remove && pluginDetected(cfg) -> "keep ~/.grok/plugins/rtok (owned by Grok; remove with `grok plugin uninstall rtok`)"
            remove || !applyOf(cfg).yes -> NO_CHANGES
            else -> "offer $PLUGIN_SRC → grok plugin install ${plugins.of(PLUGIN_SRC)} --trust $KETCH_INSTALL"
        }

    companion object {
        const val HOST = "grok"
        private const val NAME = "rtok"
        private const val KEY = "mcp_servers"
        private const val PLUGIN_SRC = "plugins/grok"

        private fun home(cfg: Config) =
            cfg.setup.grok.configPath
                .substringBeforeLast('/', ".")

        fun pluginMarker(cfg: Config) = "${home(cfg)}/plugins/rtok"

        fun pluginDetected(cfg: Config) = isDir(pluginMarker(cfg))

        private fun doc(cfg: Config): JsonObject? {
            val path = cfg.setup.grok.configPath
            return if (exists(path)) Toml.parse(readToString(path)) as? JsonObject else JsonObject(emptyMap())
        }

        /** `[compat.claude] <key>` (default on) and rtok's Claude files serving [module]. */
        fun covered(
            cfg: Config,
            module: String,
        ): Boolean {
            val key = mapOf("hooks" to "hooks", "mcp" to "mcps")[module] ?: return false
            val compat = runCatching { doc(cfg) }.getOrNull()?.let { at(it, "compat", "claude", key) as? JsonPrimitive }?.booleanOrNull ?: true
            return compat && module in Claude.filesServeRtok(cfg)
        }

        private fun at(
            root: JsonElement,
            vararg keys: String,
        ): JsonElement? = keys.fold<String, JsonElement?>(root) { n, k -> (n as? JsonObject)?.get(k) }

        private fun hooksNote(cfg: Config) = if (covered(cfg, "hooks") && applyOf(cfg).yes) "hooks: covered by rtok's Claude hooks ([compat.claude] hooks); no second set" else NO_CHANGES

        fun mcpSpec(cfg: Config) =
            McpSurfaces
                .jsonSpec(HOST, Client.Cli, cfg.setup.grok.configPath, KEY, rtokMcpCommand(), EntryShape.Command)
                .copy(format = Format.Toml)

        /** An entry already there stays, except the exact `{command: <rtok>, args: ["mcp"]}` an older rtok wrote. */
        fun registerMcp(cfg: Config): String {
            if (covered(cfg, "mcp")) return if (applyOf(cfg).yes) "mcp: covered by rtok's Claude MCP ([compat.claude] mcps); no second server" else NO_CHANGES
            val have = doc(cfg)?.let { at(it, KEY, NAME) }
            return if (have != null && !isLegacy(have)) NO_CHANGES else McpRun.install(cfg, mcpSpec(cfg))
        }

        private fun isLegacy(have: JsonElement) = str(have, "command")?.let(::isRtokBin) == true && (have as? JsonObject)?.get("args") == JsonArray(listOf(JsonPrimitive("mcp")))

        fun unregisterMcp(cfg: Config): String = McpRun.remove(cfg, mcpSpec(cfg))
    }
}
