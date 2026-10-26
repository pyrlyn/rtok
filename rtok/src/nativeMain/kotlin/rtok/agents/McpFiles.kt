// The MCP file and servers key of every host variant whose entry `rtok_mcp` parses (agents/mcp.rs `surfaces` match).
// One row per host: adding a host is one entry.
package rtok.agents

import rtok.config.Config

/** The server key most hosts keep their MCP servers under. */
internal const val SERVERS = "mcpServers"

/** Amp's servers table: one flat top-level key containing a dot, kept as a single path segment. */
const val AMP_SERVERS = "amp.mcpServers"

/** Crush's servers table. */
const val CRUSH_MCP_KEY = "mcp"

/** A host variant's MCP config file and the dotted key of its servers table. */
data class McpFile(
    val path: String,
    val key: String,
)

internal object McpFiles {
    private fun f(
        path: String,
        key: String = SERVERS,
    ) = McpFile(path, key)

    /** Host id → its file for a variant; hosts absent here have no `rtok_mcp`-parsed entry. */
    val BY_HOST: Map<String, (Config, Kind) -> McpFile> =
        mapOf(
            "amp" to { c, _ -> f(siblingIfNamed(c.setup.amp.settingsPath, "settings.json"), AMP_SERVERS) },
            "claude" to { c, k -> f(if (k == Kind.Desktop) McpPaths.claudeDesktop() else c.doctor.claudeJson) },
            "cline" to { c, k ->
                val ext = join(McpPaths.codeUserDir(c), "globalStorage/saoudrizwan.claude-dev/settings/cline_mcp_settings.json")
                f(if (k == Kind.Desktop) ext else c.setup.cline.mcpPath)
            },
            "codebuddy" to { c, k -> f(McpPaths.codebuddy(c, k)) },
            "codewhale" to { c, _ -> f(join(c.setup.codewhale.dir, "mcp.json")) },
            "commandcode" to { c, _ -> f(join(c.setup.commandcode.dir, "mcp.json")) },
            "codex" to { c, _ -> f(c.setup.codex.configPath, "mcp_servers") },
            "copilot" to { c, _ -> f(join(c.setup.copilot.dir, "mcp-config.json")) },
            "cursor" to { c, _ -> f(withFileName(c.setup.cursor.hooksPath, "mcp.json")) },
            "devin" to { c, _ -> f(withFileName(c.setup.devin.configPath, "mcp_config.json")) },
            "gemini" to { c, _ -> f(join(c.setup.gemini.dir, "settings.json")) },
            "grok" to { c, _ -> f(c.setup.grok.configPath, "mcp_servers") },
            "junie" to { c, _ ->
                f(
                    c.setup.junie.mcpPath
                        .ifEmpty { "${McpPaths.home()}/.junie/mcp/mcp.json" },
                )
            },
            "kilo" to { c, _ -> f(c.setup.kilo.configPath, "mcp") },
            "kimi" to { c, _ -> f(withFileName(c.setup.kimi.configPath, "mcp.json")) },
            "mimo" to { c, _ -> f(siblingIfNamed(c.setup.mimo.configPath, "mimocode.json"), "mcp") },
            "omp" to { c, _ -> f(c.setup.omp.mcpPath) },
            "opencode" to { c, k -> f(if (k == Kind.Desktop) McpPaths.opencodeDesktop() else c.setup.opencode.configPath, "mcp") },
            "qwen" to { c, _ -> f(join(c.setup.qwen.dir, "settings.json")) },
            "roo" to { c, _ ->
                f(
                    c.setup.roo.mcpPath
                        .ifEmpty { join(McpPaths.codeUserDir(c), "globalStorage/rooveterinaryinc.roo-cline/settings/mcp_settings.json") },
                )
            },
            "droid" to { c, _ -> f(c.setup.droid.mcpPath) },
            "kiro" to { c, _ -> f(c.setup.kiro.mcpPath) },
            "warp" to { c, _ -> f(c.setup.warp.mcpPath) },
            "crush" to { c, _ -> f(c.setup.crush.configPath, CRUSH_MCP_KEY) },
            "openhands" to { c, _ -> f(c.setup.openhands.mcpPath) },
            "augment" to { c, _ -> f(c.setup.augment.settingsPath) },
            "windsurf" to { c, _ -> f(c.setup.windsurf.configPath) },
            "zcode" to { c, _ -> f(c.setup.zcode.configPath, "mcp.servers") },
            "zed" to { c, _ -> f(c.setup.zed.configPath, "context_servers") },
        )

    /** VS Code's two profiles, each with its own `mcp.json` under `servers`. */
    fun vscode(cfg: Config): List<Pair<String, McpFile>> = listOf("stable" to false, "insiders" to true).map { (label, i) -> label to McpFile(join(McpPaths.vscodeUserDir(cfg, i), "mcp.json"), "servers") }
}
