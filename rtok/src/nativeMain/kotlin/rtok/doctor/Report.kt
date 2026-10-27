@file:Suppress("MagicNumber")

// Port of the data half of src/doctor.rs: `Report` (what `rtok doctor` found), its parts, and the three renderers
// (`to_text`, `to_console`, `to_compact_text`). The probes that fill it live in [Page].

package rtok.doctor

import rtok.agents.ModuleRow
import rtok.agents.Modules
import rtok.agents.Skills
import rtok.hooks.fmt1
import rtok.plugins.graph.Alert
import rtok.plugins.graph.Alerts
import rtok.plugins.graph.Score
import rtok.plugins.graph.Weak
import rtok.sys.ChangePreview

/** What `rtok doctor` found, as data. */
data class Report(
    val hooksTotal: Int = 0,
    val hooksByEvent: Map<String, Int> = emptyMap(),
    /** One probed MCP server: name, command, tool count, description tokens. */
    val mcp: List<ServerInfo> = emptyList(),
    /** The proxy chain behind `ANTHROPIC_BASE_URL`, hops joined with `→`. */
    val proxy: String = "",
    /** The proxy chain behind the OpenAI seed (`OPENAI_BASE_URL` or a host config). */
    val proxyOpenai: String = "",
    /** MCP tool search is not confirmed on. */
    val mcpToolSearchDisabled: Boolean = false,
    val mcpToolSearch: ToolSearch = ToolSearch(),
    val bashMaxOutputLength: String? = null,
    val autoCompactWindow: String? = null,
    /** Read-class token share from the transcripts (null = no data, fail open). */
    val readShare: ReadShare? = null,
    /** The instruction audit (T7.2): set only when `[doctor] instructions` ran. */
    val instructions: Instructions? = null,
    /** The skills audit (T61.3): set when the probe ran; an empty tree prints no section. */
    val skills: SkillsAudit? = null,
    /** Host-native features that duplicate a running rtok surface (T59.7). Advice only. */
    val overlaps: List<String> = emptyList(),
    /** Explicit user-file keys whose value is not the current default (T381). Text only. */
    val configNotes: List<String> = emptyList(),
    /** What `rtok agents junk clear` would free by default (T330.6). Text only, set by `rtok doctor` alone. */
    val junkBytes: Long? = null,
    /** Advice for enabling `[proxy.tools_rewrite]` (T59.5). */
    val toolsRewriteAdvice: String? = null,
    /** Every host variant and the state of each rtok module in it. */
    val agents: List<AgentModules> = emptyList(),
    /** Hooks that lead nowhere or cannot be checked (T331.1) and the later detectors' findings. */
    val problems: List<Problem> = emptyList(),
    /** Graph health check alerts for linked projects (T329.17). */
    val graphAlerts: List<Alert> = emptyList(),
    /** Projects with a graph health score under 80, weakest first (T329.19). */
    val graphHealth: List<Weak> = emptyList(),
) {
    /** The doctor text the reports and the TUI embed: plain, no marks or colour. */
    fun toText() = render(AgentsStyle.Plain)

    /** `rtok doctor` on a console: [toText] with the module marks. */
    fun toConsole() = render(AgentsStyle.Console)

    /** The doctor text `rtok report --ai` embeds: one line per host variant (T664). */
    fun toCompactText() = render(AgentsStyle.Compact)

    private enum class AgentsStyle { Plain, Console, Compact }

    private fun render(style: AgentsStyle): String =
        buildString {
            append("hooks $hooksTotal\n")
            for ((ev, n) in hooksByEvent) append("  $ev $n\n")
            append(Hooks.render(problems))
            append(Dupes.renderMcp(problems))
            append("mcp\n")
            for (s in mcp) append("  ${s.name} (${s.tools} tools, ~${s.descTokens} desc tokens) ${s.cmd}\n")
            append("proxy $proxy\n")
            append("proxy openai $proxyOpenai\n")
            append(mcpToolSearch.render())
            append("BASH_MAX_OUTPUT_LENGTH ${bashMaxOutputLength ?: "(unset)"}\n")
            append("autoCompactWindow ${autoCompactWindow ?: "(unset)"}\n")
            val s = readShare
            if (s != null) {
                append(
                    "read-share grep+glob ${fmt1(s.share * 100.0)}% of read-class tokens " +
                        "(read ${s.readTokens}, grep ${s.grepTokens}, glob ${s.globTokens})\n",
                )
            } else {
                append("read-share no data\n")
            }
            section("graph alerts", Alerts.texts(graphAlerts))
            section("graph health", Score.texts(graphHealth))
            renderAgents(style)
            instructions?.let { renderInstructions(it) }
            section("overlaps", overlaps)
            skills?.takeIf { it.rows.isNotEmpty() }?.let { renderSkills(it) }
            junkBytes?.let { bytes ->
                append("junk\n  reclaimable: ${ChangePreview.humanBytes(bytes)}")
                if (bytes > JUNK_HINT_BYTES) append(" (run `rtok agents junk list`)")
                append('\n')
            }
            section("config", configNotes)
        }

    private fun StringBuilder.section(
        title: String,
        lines: List<String>,
    ) {
        if (lines.isEmpty()) return
        append("$title\n")
        for (l in lines) append("  $l\n")
    }

    private fun StringBuilder.renderAgents(style: AgentsStyle) {
        if (style == AgentsStyle.Compact) {
            append("agents (yes installed, no not installed, n/a not supported; reasons: rtok doctor)\n")
            for (a in agents) append("  ${a.host} (${a.kind}) ${Modules.summary(a.modules)}\n")
        } else {
            append("agents\n")
            for (a in agents) {
                append("  ${a.host} (${a.kind})\n")
                append(Modules.lines(a.modules, "    ", style == AgentsStyle.Console))
            }
        }
    }

    private fun StringBuilder.renderInstructions(audit: Instructions) {
        append("instructions\n")
        for (r in audit.rows) append("  ${r.name} ${r.tokens} tokens ${r.path}${if (r.warn) " WARN" else ""}\n")
        for ((sent, names) in audit.duplicates) append("  duplicate `$sent` in ${names.joinToString(", ")}\n")
    }

    private fun StringBuilder.renderSkills(skills: SkillsAudit) {
        val max = Skills.SKILL_DESC_MAX
        append("skills (${skills.rows.size} listed, ${skills.descBytes} desc bytes ≈ ${skills.descBytes / 4} tokens per request)\n")
        val (long, tokens) = skills.capSaving()
        if (long > 0) append("  descriptions over $max chars: $long skills, ≈ $tokens tokens/request recoverable\n")
        for (r in skills.rows) {
            val flags =
                buildString {
                    if (r.warnDesc) append(" WARN desc>$max")
                    if (r.warnBody) append(" WARN body>8K (references/)")
                    if (r.warnNever) append(" WARN never invoked")
                }
            append("  ${r.name} ${r.source} desc ${r.descChars}c body ${r.bodyBytes}B calls ${r.invocations?.toString() ?: "-"}$flags\n")
        }
        for (d in skills.duplicates) {
            append(
                "  WARN duplicate skill `${d.name}`: ${d.copies.size} listings, bodies " +
                    "${if (d.identical) "identical" else "differ"}, extra listings ≈ ${d.extraTokens} tokens per request\n",
            )
            for ((source, path) in d.copies) append("    $source $path\n")
        }
        for (r in skills.repeats) append("  WARN repeat load `${r.name}`: ${r.loads} times in one context window ≈ ${r.tokens} tokens\n")
    }

    companion object {
        /** Above this much reclaimable junk the doctor line points at `rtok agents junk list`. */
        const val JUNK_HINT_BYTES = 1L shl 30
    }
}

data class AgentModules(
    val host: String,
    val kind: String,
    val modules: List<ModuleRow>,
)

data class ServerInfo(
    val name: String,
    val cmd: String,
    val tools: Int,
    val descTokens: Int,
)

data class Instructions(
    val rows: List<InstructionRow> = emptyList(),
    val duplicates: List<Pair<String, List<String>>> = emptyList(),
)

data class InstructionRow(
    val name: String,
    val tokens: Int,
    val path: String,
    val warn: Boolean,
)

/** Share of Read-class transcript tokens spent in native Grep/Glob (T50.4). */
data class ReadShare(
    val readTokens: Long,
    val grepTokens: Long,
    val globTokens: Long,
    val share: Double,
)

/** Whether Claude Code defers MCP tools (T388). */
enum class ToolSearchState(
    val word: String,
) {
    Enabled("enabled"),
    Disabled("disabled"),

    /** No override, but a custom `ANTHROPIC_BASE_URL`: rtok cannot see whether the proxy forwards `tool_reference`. */
    Unknown("unknown"),
}

/** The tool-search state and where it came from; [source] null is the default, which prints no line. */
data class ToolSearch(
    val state: ToolSearchState = ToolSearchState.Enabled,
    val source: String? = null,
) {
    fun render(): String = source?.let { "mcp_tool_search ${state.word} ($it)\n" }.orEmpty()

    /** Tool search is not confirmed on: the old "likely disabled" flag. */
    fun likelyOff() = state != ToolSearchState.Enabled
}
