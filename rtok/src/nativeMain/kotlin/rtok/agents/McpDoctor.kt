// Port of src/agents/mcp.rs `rows`, `own_row`, `doctor_lines`, `host_doctor_lines`: `rtok doctor`'s MCP check (T275
// Fix 5, D33). Install and update always write rtok's entry, so an installed host whose config lacks it, or holds a
// stale one, is a line naming the install that writes it.
package rtok.agents

import rtok.config.Config
import rtok.mcp.config.RTOK

object McpDoctor {
    /** The MCP surfaces of [agent]'s [kind] as rows on the real disk; a host with its own entry shape has [ownRow]. */
    fun rows(
        agent: Agent,
        cfg: Config,
        kind: Kind,
    ): List<McpRow> {
        val plugin = RTOK.name.takeIf { agent.pluginServesMcp && "plugin" in agent.installed(cfg, kind) }
        val grokImport = agent.id == Grok.HOST && Grok.covered(cfg, "mcp")
        val surfaces = McpSurfaces.surfaces(agent.id, cfg, kind, plugin, grokImport)
        if (surfaces.isEmpty()) return listOfNotNull(ownRow(agent, cfg, kind))
        return surfaces.map { McpSurfaces.spawnable(McpSurfaces.status(DiskFs, it), it) }
    }

    /**
     * The one row of a host whose MCP entry `rtok_mcp` does not parse (Continue, Goose, Reasonix): its own `installed`
     * decides present or missing for the file it writes, with no stale state. Null for a host without MCP.
     */
    private fun ownRow(
        agent: Agent,
        cfg: Config,
        kind: Kind,
    ): McpRow? {
        val file = agent.files(cfg, kind).firstOrNull()
        if (file == null || Hosts.host(agent.id)?.support(kind, "mcp") is Support.No) return null
        val entry = if ("mcp" in agent.installed(cfg, kind)) EntryState.Present else EntryState.Missing
        return McpRow(kind.word, McpSurfaces.tilde(file, McpPaths.home()), entry, null, null)
    }

    /** Every host's lines; none under `[setup] mcp = false`, where a missing entry is intended. */
    fun doctorLines(cfg: Config): List<String> =
        if (!cfg.setup.mcp) {
            emptyList()
        } else {
            Hosts.ALL.flatMap { host ->
                Agents.agent(host.id)?.let { a -> hostDoctorLines(a, cfg) { v -> Locate.present(cfg, host.id, v) } }.orEmpty()
            }
        }

    /**
     * One host: installed once any variant carries a module besides `mcp`. Then every variant that carries one too or is
     * [present] (Claude Desktop beside the Claude Code plugin) is checked, one line per surface file.
     */
    fun hostDoctorLines(
        agent: Agent,
        cfg: Config,
        present: (Variant) -> Boolean,
    ): List<String> {
        val own = { kind: Kind -> agent.installed(cfg, kind).any { it != "mcp" } }
        val host = Hosts.host(agent.id)?.takeIf { h -> h.variants.any { own(it.kind) } }
        val flagged = host != null && host.variants.size > 1 && !host.shared
        return host
            ?.variants
            ?.filter { own(it.kind) || present(it) }
            ?.flatMap { v -> rows(agent, cfg, v.kind).mapNotNull { McpSurfaces.doctorLine(agent.id, v.kind.takeIf { flagged }, it) } }
            ?.distinct()
            .orEmpty()
    }
}
