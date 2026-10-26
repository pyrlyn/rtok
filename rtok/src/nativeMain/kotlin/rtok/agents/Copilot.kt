// Port of src/agents/copilot/mod.rs: Copilot CLI and the GitHub Copilot app share `[setup.copilot] dir` (`~/.copilot`).
// rtok owns `hooks/rtok.json` outright (apply makes it the hook document, remove deletes it), its commands run
// `rtok hook <Event> --host copilot`; MCP is `mcpServers.rtok = {type: "local", command, args, tools: ["*"]}` in
// `mcp-config.json` through the shared MCP core; the plugin goes through `copilot plugin install <dir>` behind `--yes`
// and is the D21 hooks unit (its hooks serve, so `hooks/rtok.json` goes while it is installed).
package rtok.agents

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.agent.sdk.Apply
import rtok.agent.sdk.HostJson
import rtok.agent.sdk.NO_CHANGES
import rtok.agent.sdk.backup
import rtok.config.Config
import rtok.mcp.config.Client
import rtok.mcp.config.EntryShape
import rtok.mcp.config.McpSpec
import rtok.plugin.sdk.Surface
import rtok.sys.exists
import rtok.sys.isDir
import rtok.sys.join
import rtok.sys.listDir
import rtok.sys.readToString
import rtok.sys.removeFile

class Copilot(
    private val plugins: PluginSource = ShippedPlugins,
    private val cli: ((Config, List<String>) -> String?)? = null,
) : Agent {
    override val id = HOST
    override val pluginSurfaces = listOf(Surface.Hook)

    private val offer = PluginOffer(HOST, NAME, PLUGIN_SRC, listOf("plugin", "install"), listOf("plugin", "uninstall"))

    override fun files(
        cfg: Config,
        kind: Kind,
    ) = listOf(hooksPath(cfg), mcpPath(cfg))

    override fun installed(
        cfg: Config,
        kind: Kind,
    ): List<String> {
        val hooks = exists(hooksPath(cfg)) && " hook PreToolUse" in runCatching { readToString(hooksPath(cfg)) }.getOrDefault("")
        return listOfNotNull(
            "hooks".takeIf { hooks },
            "mcp".takeIf { McpEntries.hasEntry(mcpPath(cfg), "mcpServers", NAME) },
            "plugin".takeIf { pluginInstalled(cfg) },
        )
    }

    override fun apply(
        cfg: Config,
        kind: Kind,
        mode: Mode,
    ): List<String> =
        D21Plugin(
            offer = ::plugin,
            pluginInstalled = ::pluginInstalled,
            run = ::run,
            registerMcp = { McpRun.install(it, mcpSpec(it)) },
            unregisterMcp = { McpRun.remove(it, mcpSpec(it)) },
            // T234: skill sync runs on every apply, install or remove alike.
            extra = { c, remove -> Skills.sync(HOST, c, remove, plugins) },
        ).apply(cfg, mode)

    /** Offer, install, or uninstall `plugins/copilot` through `copilot plugin` behind `--yes`; a failing CLI keeps the offer. */
    fun plugin(
        cfg: Config,
        remove: Boolean,
    ): String = PluginOffers.offerPlugin(cfg, remove, pluginInstalled(cfg), offer, plugins) { (cli ?: ::copilotCli)(cfg, it) }

    /** One `copilot …` call; `COPILOT_HOME` only when `dir` is not the default. */
    private fun copilotCli(
        cfg: Config,
        args: List<String>,
    ): String? {
        val dir = cfg.setup.copilot.dir
        val env = ("COPILOT_HOME" to dir).takeIf { dir != join(McpPaths.home(), ".copilot") }
        return HostCli.runCli(cfg, HOST, args, env)
    }

    companion object {
        const val HOST = "copilot"
        const val NAME = "rtok"
        private const val PLUGIN_SRC = "plugins/copilot"

        /** Copilot's flat `additionalContext` note on `sessionStart` when `rtok` resolves nowhere. */
        private const val MISSING_RTOK_NOTE =
            "{\"additionalContext\":\"rtok is not installed; run ketch install pyrlyn/rtok to enable it.\"}"

        fun mcpPath(cfg: Config) = join(cfg.setup.copilot.dir, "mcp-config.json")

        /** rtok's own file; Copilot loads every JSON file under `hooks`. */
        fun hooksPath(cfg: Config) = join(join(cfg.setup.copilot.dir, "hooks"), "rtok.json")

        fun mcpSpec(cfg: Config): McpSpec = McpSurfaces.jsonSpec(HOST, Client.Cli, mcpPath(cfg), "mcpServers", rtokMcpCommand(), EntryShape.LocalAllTools)

        /** Write, dry-run, or delete `hooks/rtok.json`; an identical file is no change. */
        fun run(
            cfg: Config,
            remove: Boolean,
        ): String {
            val path = hooksPath(cfg)
            val apply = applyOf(cfg)
            if (remove) return removeOwned(apply, path)
            val want = hooksDoc(HookLine.rtokHookBin(), cfg.setup.hookTimeoutS.toLong())
            return HostJson.edit(apply, path) { root ->
                if (root == want) root to NO_CHANGES else want to "+ $path (${HookEvents.installed(HOST).size} events)"
            }
        }

        /**
         * `{version: 1, hooks: {<event>: [{type, bash, powershell, timeoutSec}]}}`. With `bin = "rtok"` both shells resolve
         * at run time (`sessionStart` adds the missing-rtok note); another bin stays plain.
         */
        fun hooksDoc(
            bin: String,
            timeout: Long,
        ): JsonObject {
            val hooks =
                HookEvents.installed(HOST).associate { (copilot, claude) ->
                    val args = "hook $claude --host $HOST"
                    val note = MISSING_RTOK_NOTE.takeIf { copilot == "sessionStart" }
                    val (bash, ps) = if (bin == "rtok") HookLine.hookResolver(args, note) to hookResolverPs(args, note) else "$bin $args".let { it to it }
                    val hook =
                        JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("command"),
                                "bash" to JsonPrimitive(bash),
                                "powershell" to JsonPrimitive(ps),
                                "timeoutSec" to JsonPrimitive(timeout),
                            ),
                        )
                    copilot to JsonArray(listOf(hook))
                }
            return JsonObject(mapOf("version" to JsonPrimitive(1), "hooks" to JsonObject(hooks)))
        }

        /** PowerShell twin of [HookLine.hookResolver]; `& $r` with no pipeline keeps stdin; [note] must not contain `'`. */
        fun hookResolverPs(
            args: String,
            note: String?,
        ): String {
            require(note == null || '\'' !in note) { note.orEmpty() }
            val tail = note?.let { "'$it'; exit 0" } ?: "exit 0"
            return "\$r = (Get-Command rtok -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1).Source; " +
                "if (-not \$r) { \$k = Join-Path \$env:USERPROFILE '.ketch\\bin\\rtok.exe'; if (Test-Path -LiteralPath \$k) { \$r = \$k } }; " +
                "if (\$r) { & \$r $args; exit \$LASTEXITCODE }; $tail"
        }

        /** Delete a file rtok owns: backed up like any edit, reported as `- <path>`; absent is no change. */
        private fun removeOwned(
            apply: Apply,
            path: String,
        ): String {
            if (!exists(path)) return NO_CHANGES
            val report = "- $path"
            if (apply.writes(report)) {
                if (apply.backup) backup(path, apply.backupFiles)
                removeFile(path)
            }
            return report
        }

        /** True while `copilot plugin` has rtok under `installed-plugins/<source>/<id>/plugin.json` (T116, T75). */
        fun pluginInstalled(cfg: Config): Boolean {
            val root = join(cfg.setup.copilot.dir, "installed-plugins")
            return children(root).any { outer -> children(outer).any { PluginOffers.manifestNames(it, "plugin.json", NAME) } }
        }

        private fun children(dir: String) = if (isDir(dir)) runCatching { listDir(dir) }.getOrDefault(emptyList()).map { join(dir, it) } else emptyList()
    }
}
