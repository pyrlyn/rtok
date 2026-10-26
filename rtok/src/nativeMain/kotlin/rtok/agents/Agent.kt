// Port of src/agents/mod.rs `Agent` contract (files/installed/apply), `Mode`, `apply(cfg)`, the stdio MCP helpers
// (`register_stdio_mcp[_in]`, `unregister_stdio_mcp[_in]`, `mcp_args`, `mcp_summary`) and the MCP-only host shape
// (`mcp_only_file!`, `installed_mcp_only`, `apply_mcp_only`). Host descriptors (variants, support) stay in [Hosts].
package rtok.agents

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.agent.sdk.Apply
import rtok.agent.sdk.HostJson
import rtok.agent.sdk.NO_CHANGES
import rtok.config.Config
import rtok.sys.exists
import rtok.sys.readToString

/** What an `agents install|update|remove` run does. */
enum class Mode(
    val verb: String,
) {
    Install("install"),
    Update("update"),
    Replace("replace"),
    Remove("remove"),
}

/** The install side of one host: the files it writes, what is installed, and how to apply a [Mode]. */
interface Agent {
    val id: String

    /** Every file the host's installer may write for [kind]. */
    fun files(
        cfg: Config,
        kind: Kind,
    ): List<String>

    /** The modules found installed for [kind] (`hooks`, `mcp`, `proxy`, `plugin`). */
    fun installed(
        cfg: Config,
        kind: Kind,
    ): List<String>

    /** Install, update or remove rtok for [kind]; one report line per change. */
    fun apply(
        cfg: Config,
        kind: Kind,
        mode: Mode,
    ): List<String>
}

/** The `[setup]` gates as the agent SDK reads them. */
fun applyOf(cfg: Config) = Apply(dryRun = cfg.setup.dryRun, backup = cfg.setup.backup, backupFiles = cfg.setup.backupFiles.toInt(), yes = cfg.setup.yes)

object StdioMcp {
    fun args(host: String) = listOf("mcp", "--host", host)

    /** The report text after `<key>.rtok: `. */
    fun summary(
        cmd: String,
        host: String,
    ) = "$cmd ${args(host).joinToString(" ")}"

    /** `{command, args}` with no `type`: the stdio shape Cline, Roo Code, Windsurf, Droid and others read. */
    fun entry(
        cmd: String,
        host: String,
    ) = JsonObject(mapOf("command" to JsonPrimitive(cmd), "args" to JsonArray(args(host).map(::JsonPrimitive))))

    fun register(
        cfg: Config,
        path: String,
        host: String,
        key: String = "mcpServers",
        cmd: String = rtokMcpCommand(),
    ): String = HostJson.registerServer(applyOf(cfg), path, key, "rtok", entry(cmd, host), summary(cmd, host))

    /** Only the entry as rtok wrote it (T246.2). */
    fun unregister(
        cfg: Config,
        path: String,
        host: String,
        key: String = "mcpServers",
    ): String = HostJson.unregisterOwned(applyOf(cfg), path, key, "rtok", entry("rtok", host), ::isRtokBin)
}

/** The host file mentions `"rtok"` (T186); a missing or unreadable file does not. */
fun mentionsRtok(path: String): Boolean = exists(path) && "\"rtok\"" in runCatching { readToString(path) }.getOrDefault("")

/** A host whose only module is `mcp` (any variant: Windsurf is desktop-only): one JSON file, rtok's stdio entry under [key]. */
class McpOnlyAgent(
    override val id: String,
    private val path: (Config) -> String,
    private val key: String = "mcpServers",
    private val cmd: () -> String = ::rtokMcpCommand,
) : Agent {
    fun mcpPath(cfg: Config) = path(cfg)

    override fun files(
        cfg: Config,
        kind: Kind,
    ) = listOf(path(cfg))

    /** Present iff the file mentions `"rtok"` (T186). */
    override fun installed(
        cfg: Config,
        kind: Kind,
    ): List<String> = if (mentionsRtok(path(cfg))) listOf("mcp") else emptyList()

    override fun apply(
        cfg: Config,
        kind: Kind,
        mode: Mode,
    ): List<String> =
        listOf(
            when {
                mode == Mode.Remove -> StdioMcp.unregister(cfg, path(cfg), id, key)
                cfg.setup.mcp -> StdioMcp.register(cfg, path(cfg), id, key, cmd())
                else -> NO_CHANGES
            },
        )
}

/** The install side of every ported host, by id. */
object Agents {
    val MCP_ONLY: List<McpOnlyAgent> =
        listOf(
            McpOnlyAgent("augment", { it.setup.augment.settingsPath }),
            McpOnlyAgent("crush", { it.setup.crush.configPath }, CRUSH_MCP_KEY),
            McpOnlyAgent("droid", { it.setup.droid.mcpPath }),
            McpOnlyAgent("junie", { McpFiles.BY_HOST.getValue("junie")(it, Kind.Cli).path }),
            McpOnlyAgent("kiro", { it.setup.kiro.mcpPath }),
            McpOnlyAgent("openhands", { it.setup.openhands.mcpPath }),
            McpOnlyAgent("roo", { McpFiles.BY_HOST.getValue("roo")(it, Kind.Cli).path }),
            McpOnlyAgent("warp", { it.setup.warp.mcpPath }),
            McpOnlyAgent("windsurf", { it.setup.windsurf.configPath }),
        )

    private val BY_ID: Map<String, Agent> = (MCP_ONLY + Cline() + Qwen() + Gemini() + Devin() + CommandCode()).associateBy { it.id }

    /** The ported install side of [id], or null while that host is not ported yet. */
    fun agent(id: String): Agent? = BY_ID[id]
}
