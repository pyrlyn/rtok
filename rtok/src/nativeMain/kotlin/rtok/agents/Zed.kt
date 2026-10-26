// Port of src/agents/zed/mod.rs: Zed (CLI and app share `[setup.zed] config_path`) reads MCP servers from
// `context_servers` in its JSONC settings; rtok's `{command, args}` entry is edited in place so comments, trailing
// commas and foreign servers survive. Zed has no hooks, proxy or local plugin directory.
package rtok.agents

import rtok.agent.sdk.NO_CHANGES
import rtok.config.Config
import rtok.mcp.config.RTOK

object Zed : Agent {
    const val HOST = "zed"
    private const val KEY = "context_servers"

    override val id = HOST

    fun mcpSpec(cfg: Config) = McpSurfaces.jsoncSpec(HOST, cfg.setup.zed.configPath, listOf(KEY))

    override fun files(
        cfg: Config,
        kind: Kind,
    ) = listOf(cfg.setup.zed.configPath)

    override fun installed(
        cfg: Config,
        kind: Kind,
    ): List<String> = if (McpEntries.hasEntry(cfg.setup.zed.configPath, KEY, RTOK.name)) listOf("mcp") else emptyList()

    override fun apply(
        cfg: Config,
        kind: Kind,
        mode: Mode,
    ): List<String> = listOf(McpRun.sync(cfg, mcpSpec(cfg), mode) ?: NO_CHANGES)
}
