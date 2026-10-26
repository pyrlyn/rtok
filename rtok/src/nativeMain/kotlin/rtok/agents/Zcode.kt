// Port of src/agents/zcode/mod.rs: the ZCode desktop app reads Claude-layout hooks from `[setup.zcode] config_path`
// under `hooks.events` (with `hooks.enabled` and `timeoutMs`) and MCP from `mcp.servers.rtok = {command, args}`. The
// app starts without a shell PATH, so both carry the absolute binary. `plugins/zcode` is linked into
// `plugins/local/rtok` beside the config and listed in `plugins.dirs`; once linked it is the only hook path (D21).
package rtok.agents

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.agent.sdk.HostJson
import rtok.agent.sdk.NO_CHANGES
import rtok.config.Config
import rtok.sys.exists
import rtok.sys.readToString

class Zcode(
    private val plugins: PluginSource = ShippedPlugins,
) : Agent {
    override val id = HOST

    override fun files(
        cfg: Config,
        kind: Kind,
    ) = listOf(cfg.setup.zcode.configPath)

    override fun installed(
        cfg: Config,
        kind: Kind,
    ): List<String> {
        val path = cfg.setup.zcode.configPath
        val plugin = PLUGIN.ours(cfg, plugins)
        // The command is an absolute path, so the tail is matched; the linked plugin serves the hooks itself (D21).
        val hooks = plugin || (exists(path) && " hook PreToolUse" in runCatching { readToString(path) }.getOrDefault(""))
        return listOfNotNull(
            "hooks".takeIf { hooks },
            "mcp".takeIf { McpEntries.hasEntry(path, MCP_KEY, NAME) },
            "plugin".takeIf { plugin },
        )
    }

    override fun apply(
        cfg: Config,
        kind: Kind,
        mode: Mode,
    ): List<String> {
        val remove = mode == Mode.Remove
        // Offer first: a linked plugin is the only call path, so the config hooks go, also on the run that links.
        val offer = offerPlugin(cfg, remove)
        val hooks = run(cfg, remove || PLUGIN.ours(cfg, plugins))
        val path = cfg.setup.zcode.configPath
        val mcp =
            when {
                remove -> StdioMcp.unregister(cfg, path, HOST, MCP_KEY)
                cfg.setup.mcp -> StdioMcp.register(cfg, path, HOST, MCP_KEY, HookLine.desktopCommand())
                else -> null
            }
        return listOfNotNull(offer, hooks, mcp)
    }

    /** Link or unlink the plugin, then keep `plugins.dirs` in step with whether it is ours. */
    fun offerPlugin(
        cfg: Config,
        remove: Boolean,
    ): String {
        val lines = listOf(PLUGIN.offer(cfg, remove, plugins), pluginDirs(cfg, remove)).filter { it != NO_CHANGES }
        return if (lines.isEmpty()) NO_CHANGES else lines.joinToString("\n")
    }

    private fun pluginDirs(
        cfg: Config,
        remove: Boolean,
    ): String {
        val dest = PLUGIN.path(cfg)
        val want = !remove && PLUGIN.ours(cfg, plugins)
        return HostJson.edit(applyOf(cfg), cfg.setup.zcode.configPath) { root ->
            val pluginsObj = root["plugins"] as? JsonObject ?: JsonObject(emptyMap())
            val dirs = (pluginsObj["dirs"] as? JsonArray).orEmpty()
            val has = dirs.any { (it as? JsonPrimitive)?.content == dest }
            when {
                want == has -> root to NO_CHANGES
                want -> withDirs(root, pluginsObj, dirs + JsonPrimitive(dest)) to "+ plugins.dirs += $dest"
                else -> withDirs(root, pluginsObj, dirs.filter { (it as? JsonPrimitive)?.content != dest }) to "- plugins.dirs -= $dest"
            }
        }
    }

    private fun withDirs(
        root: JsonObject,
        pluginsObj: JsonObject,
        dirs: List<kotlinx.serialization.json.JsonElement>,
    ) = JsonObject(root + ("plugins" to JsonObject(pluginsObj + ("dirs" to JsonArray(dirs)))))

    companion object {
        const val HOST = "zcode"
        private const val NAME = "rtok"
        private const val MCP_KEY = "mcp.servers"
        private const val TIMEOUT_KEY = "timeoutMs"
        private const val MS_PER_S = 1000L

        val PLUGIN =
            HostPlugin(
                "plugins/zcode",
                "ZCode",
                "~/.zcode/cli/plugins/local",
                { join(join(join(parentOf(it.setup.zcode.configPath), "plugins"), "local"), NAME) },
                defaultInstall = true,
            )

        fun entries() = HookEvents.entries(HOST)

        /** Apply or strip rtok's hooks under `hooks.events`; install also sets `hooks.enabled = true`. */
        fun run(
            cfg: Config,
            remove: Boolean,
        ): String {
            val path = cfg.setup.zcode.configPath
            val spec = HookSpec(entries(), TIMEOUT_KEY, cfg.setup.hookTimeoutS.toLong() * MS_PER_S)
            val ctx = HookEdit(ClaudeHooks.CLAUDE, applyOf(cfg), path, spec, HookLine.desktopCommand())
            return HostJson.edit(ctx.apply, path) { root ->
                val hooks = root["hooks"] as? JsonObject ?: JsonObject(emptyMap())
                if (remove) {
                    val (events, report) = HookStrip.strip(ctx.form, ctx.apply, path, hooks["events"], spec)
                    (if (events == null) root else JsonObject(root + ("hooks" to JsonObject(hooks + ("events" to events))))) to report
                } else {
                    val (events, report) = HookInsert.insert(ctx.form, hooks["events"], spec, ctx.bin)
                    val enable = hooks["enabled"] != JsonPrimitive(true)
                    val out = JsonObject(root + ("hooks" to JsonObject(hooks + ("enabled" to JsonPrimitive(true)) + ("events" to events))))
                    out to
                        when {
                            !enable -> report
                            report == NO_CHANGES -> "+ hooks.enabled = true"
                            else -> "+ hooks.enabled = true\n$report"
                        }
                }
            }
        }
    }
}
