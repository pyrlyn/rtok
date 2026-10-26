// Port of src/agents/commandcode/mod.rs: Command Code (`command-code`, alias `cmd`) keeps user state in
// `[setup.commandcode] dir` (`~/.commandcode`). Hooks merge into `settings.json` `hooks.<Event>[]` (Claude-shaped, one
// definition per matcher, lifecycle events with no matcher, fixed 5 s `timeout`), MCP is the stdio `mcpServers.rtok`
// in the user-scope `mcp.json`, and the plugin tree is linked to `<dir>/plugins/rtok` behind `--yes`. Its own walk
// (no prune, no refresh, strip by command) differs from [ClaudeHooks], so it lives here.
package rtok.agents

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.agent.sdk.HostJson
import rtok.agent.sdk.NO_CHANGES
import rtok.config.Config
import rtok.sys.exists
import rtok.sys.join
import rtok.sys.readToString

class CommandCode(
    private val plugins: PluginSource = ShippedPlugins,
) : Agent {
    override val id = HOST

    override fun files(
        cfg: Config,
        kind: Kind,
    ) = listOf(settingsPath(cfg), mcpPath(cfg))

    override fun installed(
        cfg: Config,
        kind: Kind,
    ): List<String> {
        val path = settingsPath(cfg)
        val hooks = exists(path) && "rtok hook" in runCatching { readToString(path) }.getOrDefault("")
        return listOfNotNull(
            "hooks".takeIf { hooks },
            // Only the config's own entry counts (T275/D33): the plugin never stands in for it.
            "mcp".takeIf { McpEntries.hasEntry(mcpPath(cfg), "mcpServers", "rtok") },
            "plugin".takeIf { PLUGIN.ours(cfg, plugins) },
        )
    }

    override fun apply(
        cfg: Config,
        kind: Kind,
        mode: Mode,
    ): List<String> {
        val remove = mode == Mode.Remove
        // MCP is independent of the plugin (T275/D33): written on every install and update, taken back only by remove.
        val mcp =
            when {
                remove -> StdioMcp.unregister(cfg, mcpPath(cfg), HOST)
                cfg.setup.mcp -> StdioMcp.register(cfg, mcpPath(cfg), HOST)
                else -> null
            }
        return listOfNotNull(run(cfg, remove), PLUGIN.offer(cfg, remove, plugins), mcp, Skills.sync(HOST, cfg, remove, plugins))
    }

    companion object {
        const val HOST = "commandcode"
        private const val TIMEOUT_S = 5L

        /** The linked plugin tree (D21); dry-run and the open offer name `plugins/commandcode` and `ketch install`. */
        val PLUGIN = HostPlugin("plugins/commandcode", "Command Code", null, ::pluginDest, defaultInstall = false)

        fun settingsPath(cfg: Config) = join(cfg.setup.commandcode.dir, "settings.json")

        fun mcpPath(cfg: Config) = join(cfg.setup.commandcode.dir, "mcp.json")

        /** No documented local-plugin directory: linked next to the user files when accepted. */
        fun pluginDest(cfg: Config) = join(join(cfg.setup.commandcode.dir, "plugins"), "rtok")

        fun hookCommand(
            event: String,
            bin: String = HookLine.rtokHookBin(),
        ) = "$bin hook $event --host $HOST"

        /** Merge, dry-run, or strip rtok's hook entries in `settings.json`. */
        fun run(
            cfg: Config,
            remove: Boolean,
        ): String = HostJson.edit(applyOf(cfg), settingsPath(cfg)) { root -> if (remove) strip(root) else insert(root) }

        fun insert(root: JsonObject): Pair<JsonObject, String> {
            val hooks = LinkedHashMap(root["hooks"] as? JsonObject ?: JsonObject(emptyMap()))
            val added = mutableListOf<String>()
            for (row in HookEvents.installerRows(HOST)) {
                val cmd = hookCommand(row.rtokEvent)
                val matcher = row.matcher.takeIf { it.isNotEmpty() }
                val defs = (hooks[row.hostEvent] as? JsonArray).orEmpty().toMutableList()
                var at = defs.indexOfFirst { str(it, "matcher") == matcher && (matcher != null || (it as? JsonObject)?.containsKey("matcher") != true) }
                if (at < 0) {
                    val head = matcher?.let { mapOf("matcher" to JsonPrimitive(it)) }.orEmpty()
                    defs += JsonObject(head + ("hooks" to JsonArray(emptyList())))
                    at = defs.lastIndex
                }
                val entries = innerHooks(defs[at]).orEmpty()
                if (entries.none { str(it, "command") == cmd }) {
                    val hook = JsonObject(mapOf("type" to JsonPrimitive("command"), "command" to JsonPrimitive(cmd), "timeout" to JsonPrimitive(TIMEOUT_S)))
                    defs[at] = withInner(defs[at], entries + hook)
                    added += "+ ${row.hostEvent} $cmd"
                }
                hooks[row.hostEvent] = JsonArray(defs)
            }
            val report = if (added.isEmpty()) NO_CHANGES else added.joinToString("\n")
            return JsonObject(root + ("hooks" to JsonObject(hooks))) to report
        }

        fun strip(root: JsonObject): Pair<JsonObject, String> {
            val hooks = root["hooks"] as? JsonObject ?: return root to NO_CHANGES
            val out = LinkedHashMap(hooks)
            val removed = mutableListOf<String>()
            for ((event, _) in HookEvents.installed(HOST)) {
                val defs = out[event] as? JsonArray ?: continue
                var done = false
                val kept =
                    defs.map { def ->
                        val entries = innerHooks(def)
                        if (done || entries == null) return@map def
                        val left = entries.filterNot(::isOurs)
                        if (left.size == entries.size) return@map def
                        done = true
                        removed += "- $event"
                        withInner(def, left)
                    }
                // Definitions left with no hooks go; foreign ones stay.
                out[event] = JsonArray(kept.filter(::keepsHooks))
            }
            val next = if (out.isEmpty()) JsonObject(root - "hooks") else JsonObject(root + ("hooks" to JsonObject(out)))
            return if (removed.isEmpty()) root to NO_CHANGES else next to removed.joinToString("\n")
        }

        /** `<rtok-bin> hook <Claude event> --host commandcode`, for any event the installer writes. */
        fun isOurs(entry: JsonElement): Boolean {
            val cmd = str(entry, "command") ?: return false
            return HookEvents.installed(HOST).any { (_, claude) ->
                val suffix = " hook $claude --host $HOST"
                cmd.endsWith(suffix) && isRtokBin(HookLine.unquoteBin(cmd.removeSuffix(suffix)))
            }
        }
    }
}
