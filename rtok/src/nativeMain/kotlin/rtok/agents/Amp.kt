// Port of src/agents/amp/mod.rs: Amp keeps user MCP servers under the one dotted top-level key `amp.mcpServers` of
// `~/.config/amp/settings.json` (or a lone `settings.jsonc`). The file is edited as JSONC so comments survive; no plugin
// is linked (Amp's plugin API is a different surface).
package rtok.agents

import rtok.agent.sdk.NO_CHANGES
import rtok.config.Config
import rtok.mcp.config.Client
import rtok.mcp.config.DuplicateName
import rtok.mcp.config.EntryShape
import rtok.mcp.config.Format
import rtok.mcp.config.Launch
import rtok.mcp.config.McpSpec
import rtok.mcp.config.RTOK

object Amp : Agent {
    const val HOST = "amp"

    override val id = HOST

    /** `[setup.amp] settings_path`, or its `.jsonc` sibling when that is the only one Amp has. */
    fun settingsPath(cfg: Config) = McpFiles.BY_HOST.getValue(HOST)(cfg, Kind.Cli).path

    /** `amp.mcpServers.rtok = {command, args}`; the key is one path segment, not a nested `amp` object. */
    fun mcpSpec(cfg: Config) =
        McpSpec(
            host = HOST,
            client = Client.Cli,
            configPath = settingsPath(cfg),
            format = Format.Jsonc,
            keyPath = listOf(AMP_SERVERS),
            server = RTOK,
            launch = Launch(command = rtokMcpCommand(), hostArg = true),
            entryShape = EntryShape.Command,
            duplicateName = DuplicateName.ShowsBoth,
            pluginServes = null,
        )

    override fun files(
        cfg: Config,
        kind: Kind,
    ) = listOf(settingsPath(cfg))

    override fun installed(
        cfg: Config,
        kind: Kind,
    ): List<String> = if (McpEntries.hasEntryAt(settingsPath(cfg), listOf(AMP_SERVERS), RTOK.name)) listOf("mcp") else emptyList()

    override fun apply(
        cfg: Config,
        kind: Kind,
        mode: Mode,
    ): List<String> = listOf(McpRun.sync(cfg, mcpSpec(cfg), mode) ?: NO_CHANGES)
}
