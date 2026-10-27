// Port of src/agents/cursor/mod.rs: the Cursor CLI (`cursor-agent`) and the desktop app read the same `~/.cursor` files.
// Hooks are `{version, hooks.<event>[].command}` in `hooks.json` (events from [HookEvents]); MCP is the typed
// `mcpServers.rtok` in the sibling `mcp.json`, independent of the plugin (T275/D33). `plugins/cursor` is linked into
// `plugins/local/rtok` by default (T164); once ours it serves the hooks, so `hooks.json` keeps none of ours (D21).
// The Windows copy fix (PowerShell bare lines) is not ported: there is no Windows target.
package rtok.agents

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.agent.sdk.Apply
import rtok.agent.sdk.HostJson
import rtok.agent.sdk.NO_CHANGES
import rtok.config.Config
import rtok.mcp.config.Client
import rtok.mcp.config.EntryShape
import rtok.mcp.config.McpSpec
import rtok.plugin.sdk.Surface
import rtok.sys.exists
import rtok.sys.join
import rtok.sys.readToString

class Cursor(
    private val plugins: PluginSource = ShippedPlugins,
) : Agent {
    override val id = HOST
    override val pluginSurfaces = listOf(Surface.Hook)

    override fun files(
        cfg: Config,
        kind: Kind,
    ) = listOf(cfg.setup.cursor.hooksPath, mcpPath(cfg))

    override fun markers(
        cfg: Config,
        kind: Kind,
    ) = files(cfg, kind) + PLUGIN.path(cfg)

    override fun installed(
        cfg: Config,
        kind: Kind,
    ): List<String> {
        val path = cfg.setup.cursor.hooksPath
        // T75: `ours`, not any directory at the destination; the linked plugin serves the hooks too (D21).
        val plugin = PLUGIN.ours(cfg, plugins)
        val hooks = plugin || (exists(path) && "rtok hook" in runCatching { readToString(path) }.getOrDefault(""))
        return listOfNotNull(
            "hooks".takeIf { hooks },
            "mcp".takeIf { McpEntries.hasEntry(mcpPath(cfg), "mcpServers", NAME) },
            "plugin".takeIf { plugin },
        )
    }

    override fun apply(
        cfg: Config,
        kind: Kind,
        mode: Mode,
    ): List<String> {
        val remove = mode == Mode.Remove
        // Plugin first: once linked it carries the same events, so `hooks.json` keeps none of ours (D21, T244).
        val offer = PLUGIN.offer(cfg, remove, plugins)
        val hooks = run(cfg, remove || PLUGIN.ours(cfg, plugins))
        val mcp =
            when {
                remove -> McpRun.remove(cfg, mcpSpec(cfg))
                cfg.setup.mcp -> McpRun.install(cfg, mcpSpec(cfg))
                else -> null
            }
        return listOfNotNull(hooks, offer, mcp)
    }

    companion object {
        const val HOST = "cursor"
        private const val NAME = "rtok"

        /** Rows an earlier build wrote and this one no longer does (T390.1); install and remove still take them back. */
        private val RETIRED = listOf("beforeSubmitPrompt" to "UserPromptSubmit")

        val PLUGIN =
            HostPlugin(
                "plugins/cursor",
                "Cursor",
                "~/.cursor/plugins/local",
                { join(join(join(parentOf(it.setup.cursor.hooksPath), "plugins"), "local"), NAME) },
                defaultInstall = true,
            )

        fun mcpPath(cfg: Config) = withFileName(cfg.setup.cursor.hooksPath, "mcp.json")

        fun mcpSpec(cfg: Config): McpSpec = McpSurfaces.jsonSpec(HOST, Client.Ide, mcpPath(cfg), "mcpServers", rtokMcpCommand(), EntryShape.TypedCommand)

        private fun owned() = HookEvents.installed(HOST) + RETIRED

        /**
         * T250.3: a bare `rtok` resolves at hook time. Cursor runs `sh -c "<command> <<'CURSOR_HOOK_EOF' …"`, so the
         * heredoc binds to the last command; the `{ …; }` group hands it to the whole resolver. An absolute bin stays plain.
         */
        fun hookCmd(
            bin: String,
            event: String,
            note: String? = null,
        ): String {
            val args = "hook $event --host $HOST"
            return if (bin != "rtok") "$bin $args" else "{ ${HookLine.hookResolver(args, note)}; }"
        }

        /** Apply, dry-run, or remove rtok's entries in `hooks.json`. */
        fun run(
            cfg: Config,
            remove: Boolean,
        ): String {
            val path = cfg.setup.cursor.hooksPath
            val apply = applyOf(cfg)
            return HostJson.edit(apply, path) { root -> if (remove) strip(apply, path, root) else insert(root) }
        }

        /** Retired rows out, every installer row present and current in its slot (T242.5), `version` defaulted to 1. */
        fun insert(root: JsonObject): Pair<JsonObject, String> {
            val changed = mutableListOf<String>()
            val hooks = LinkedHashMap((root["hooks"] as? JsonObject).orEmpty())
            RETIRED.forEach { (event, _) -> dropRetired(hooks, event, changed) }
            val bin = HookLine.rtokHookBin()
            HookEvents.installed(HOST).forEach { (event, rtokEvent) -> hooks[event] = upsert(event, (hooks[event] as? JsonArray).orEmpty(), hookCmd(bin, rtokEvent), changed) }
            val out = LinkedHashMap(root).apply { put("hooks", JsonObject(hooks)) }
            if ("version" !in out) out["version"] = JsonPrimitive(1)
            return JsonObject(out) to if (changed.isEmpty()) NO_CHANGES else changed.joinToString("\n")
        }

        private fun dropRetired(
            hooks: MutableMap<String, JsonElement>,
            event: String,
            changed: MutableList<String>,
        ) {
            val arr = hooks[event] as? JsonArray ?: return
            val kept = arr.filterNot(::isOurs)
            if (kept.size == arr.size) return
            changed += "- $event"
            if (kept.isEmpty()) hooks.remove(event) else hooks[event] = JsonArray(kept)
        }

        /** Ours rewritten in its slot when stale (T242.5), else one `{command}` appended. */
        private fun upsert(
            event: String,
            arr: List<JsonElement>,
            cmd: String,
            changed: MutableList<String>,
        ): JsonArray {
            val updated =
                arr.map { e ->
                    if (!isOurs(e) || str(e, "command") == cmd) return@map e
                    changed += "~ $event $cmd"
                    JsonObject((e as JsonObject) + ("command" to JsonPrimitive(cmd)))
                }
            if (arr.any(::isOurs)) return JsonArray(updated)
            changed += "+ $event $cmd"
            return JsonArray(updated + JsonObject(mapOf("command" to JsonPrimitive(cmd))))
        }

        /** T246.6: an entry still exactly `{command}` for its row goes; an edited one goes only as [StripReport.takesHook] decides. */
        fun strip(
            apply: Apply,
            path: String,
            root: JsonObject,
        ): Pair<JsonObject, String> {
            val hooks = root["hooks"] as? JsonObject ?: return root to NO_CHANGES
            val out = LinkedHashMap(hooks)
            val (removed, kept) = mutableListOf<String>() to mutableListOf<String>()
            for ((event, rtokEvent) in owned()) {
                val arr = out[event] as? JsonArray ?: continue
                val suffix = " hook $rtokEvent --host $HOST"
                val left =
                    arr.filterNot { e ->
                        val c = str(e, "command")
                        val unchanged = (e as? JsonObject)?.size == 1 && c != null && (c.endsWith(suffix) || c == hookCmd("rtok", rtokEvent))
                        isOurs(e) && StripReport.takesHook(apply, unchanged, { "hooks.$event in $path" }, kept)
                    }
                if (left.size != arr.size) {
                    removed += "- $event"
                    out[event] = JsonArray(left)
                }
            }
            val report = if (removed.isEmpty()) NO_CHANGES else removed.joinToString("\n")
            return JsonObject(root + ("hooks" to JsonObject(out))) to StripReport.withKept(kept, report)
        }

        /** `<rtok bin> hook <event> --host cursor` for an owned event, or the T250.3 resolver line. */
        fun isOurs(entry: JsonElement): Boolean {
            val cmd = str(entry, "command") ?: return false
            val events = owned().map { it.second }
            return events.any { ev ->
                val bin = cmd.removeSuffix(" hook $ev --host $HOST").takeIf { it != cmd }
                (bin != null && isRtokBin(HookLine.unquoteBin(bin))) || "exec rtok hook $ev --host $HOST;" in cmd
            }
        }
    }
}
