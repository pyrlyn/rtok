// Port of src/agents/devin/mod.rs: Devin CLI + Desktop share `config.json` (`[setup.devin] config_path`), whose `hooks`
// object holds Claude-layout groups in Devin's event names (`hook <event> --host devin`, `timeout` in seconds), and the
// sibling `mcp_config.json` with the stdio `mcpServers.rtok`. Devin's plugin store is undocumented, so the plugin is
// only offered behind `--yes` (`devin plugins install --local`) and `installed` never reports it.
package rtok.agents

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.agent.sdk.HostJson
import rtok.agent.sdk.NO_CHANGES
import rtok.config.Config
import rtok.sys.environ
import rtok.sys.exists
import rtok.sys.join
import rtok.sys.parent
import rtok.sys.readToString
import kotlin.experimental.ExperimentalNativeApi

class Devin(
    private val plugins: PluginSource = ShippedPlugins,
) : Agent {
    override val id = HOST

    override fun files(
        cfg: Config,
        kind: Kind,
    ) = listOf(configPath(cfg), mcpPath(cfg))

    override fun installed(
        cfg: Config,
        kind: Kind,
    ): List<String> {
        val hooks = exists(configPath(cfg)) && "rtok hook" in runCatching { readToString(configPath(cfg)) }.getOrDefault("")
        return listOfNotNull("hooks".takeIf { hooks }, "mcp".takeIf { mentionsRtok(mcpPath(cfg)) })
    }

    override fun apply(
        cfg: Config,
        kind: Kind,
        mode: Mode,
    ): List<String> {
        if (mode == Mode.Remove) return listOf(offerPlugin(cfg, true), run(cfg, true), StdioMcp.unregister(cfg, mcpPath(cfg), HOST))
        val lines =
            listOf(run(cfg, false)) +
                listOfNotNull(
                    cfg.setup.mcp
                        .takeIf { it }
                        ?.let { StdioMcp.register(cfg, mcpPath(cfg), HOST) },
                )
        // A repeat install is all NO_CHANGES, the offer included: every line counts toward `already installed`.
        val offer = if (lines.any { it != NO_CHANGES } && cfg.setup.yes) offerPlugin(cfg, false) else NO_CHANGES
        return listOf(offer) + lines
    }

    /** `devin plugins install --local <plugins/devin>` behind `--yes`; rtok never writes the store. */
    fun offerPlugin(
        cfg: Config,
        remove: Boolean,
    ): String =
        PluginOffers.printOffer(
            cfg,
            remove,
            false,
            PLUGIN_REL,
            "devin plugins install --local ${plugins.of(PLUGIN_REL)}",
            "keep the Devin plugin (its store is undocumented; remove with `devin plugins remove rtok --local`)",
        )

    /** Apply, dry-run, or remove rtok's hooks under `hooks` in `config.json`; foreign hooks stay. */
    fun run(
        cfg: Config,
        remove: Boolean,
    ): String {
        val path = configPath(cfg)
        val ctx = HookEdit(FORM, applyOf(cfg), path, HookSpec(entries(), "timeout", cfg.setup.hookTimeoutS.toLong()))
        return HostJson.edit(ctx.apply, path) { root -> ClaudeHooks.edit(root, remove, ctx) }
    }

    companion object {
        const val HOST = "devin"
        private const val PLUGIN_REL = "plugins/devin"

        /** The timeout `plugins/devin/hooks.json` ships with: the default `[setup] hook_timeout_s`. */
        const val PLUGIN_HOOK_TIMEOUT_S = 5L

        val FORM = ClaudeHooks.formFor(HOST)

        /** `(event, matcher)` in Devin's names, from the `devin` rows of [HookEvents]; empty matcher omits the field. */
        fun entries() = HookEvents.entries(HOST)

        /** `config.json`; on Windows the shipped XDG default goes to `%APPDATA%\devin\config.json`, where Devin reads. */
        @OptIn(ExperimentalNativeApi::class)
        fun configPath(cfg: Config): String {
            val path = cfg.setup.devin.configPath
            val appdata = environ().firstOrNull { it.first == "APPDATA" }?.second
            val windows = Platform.osFamily == OsFamily.WINDOWS
            return if (windows && isXdgDefault(path) && appdata != null) join(join(appdata, HOST), "config.json") else path
        }

        fun isXdgDefault(path: String) = path.replace('\\', '/').endsWith(".config/devin/config.json")

        /** `mcp_config.json` beside [configPath]. */
        fun mcpPath(cfg: Config) = join(parent(configPath(cfg)) ?: ".", "mcp_config.json")

        /** The plugin's `hooks.json`: top-level event names (no `hooks` wrapper), the POSIX resolver on every OS. */
        fun hooksDoc() = hooksDocWith({ HookLine.hookResolver("hook $it --host $HOST") }, PLUGIN_HOOK_TIMEOUT_S)

        /** [hooksDoc]'s shape with `command(event)` and `timeout` of the caller's choosing. */
        fun hooksDocWith(
            command: (String) -> String,
            timeout: Long,
        ): JsonObject {
            val out = LinkedHashMap<String, List<JsonElement>>()
            for ((event, matcher) in entries()) {
                val hook = JsonObject(mapOf("type" to JsonPrimitive("command"), "command" to JsonPrimitive(command(event)), "timeout" to JsonPrimitive(timeout)))
                val head = if (matcher.isEmpty()) emptyMap() else mapOf("matcher" to JsonPrimitive(matcher))
                out[event] = out[event].orEmpty() + JsonObject(mapOf("hooks" to JsonArray(listOf(hook))) + head)
            }
            return JsonObject(out.mapValues { JsonArray(it.value) })
        }
    }
}
