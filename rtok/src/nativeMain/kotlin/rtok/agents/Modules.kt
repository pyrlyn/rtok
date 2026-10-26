// Port of src/agents/mod.rs module and plugin rows: `ModuleState`, `ModuleRow`, `installed_modules`, `module_rows`,
// `module_lines`, `module_summary`, `expected`, `missing`, `PluginRow`, `modules_for`, `reaches`, `plugin_rows`,
// `plugin_lines`, and mcp.rs `lines`. Output is the colourless form Rust prints to a pipe (marks kept, no ANSI).
package rtok.agents

import rtok.config.Config
import rtok.plugin.sdk.Surface
import rtok.plugins.Registry

/** One module's state in a host variant. */
enum class ModuleState(
    private val mark: String,
    /** `module_summary`'s word. */
    val short: String,
    /** `module_lines`' word. */
    val word: String,
) {
    Installed("✓ ", "yes", "installed"),
    NotInstalled("✗ ", "no", "not installed"),
    NotSupported("− ", "n/a", "not supported"),
    ;

    /** `{indent}{mark}{text}`; [console] = false drops the mark (the doctor text also lands in the PDF report). */
    fun line(
        indent: String,
        text: String,
        console: Boolean,
    ) = if (console) "$indent$mark$text\n" else "$indent$text\n"
}

/** One [Hosts.MODULES] row of a host variant: its state and the note after it (the flag, or the reason it cannot be). */
data class ModuleRow(
    val name: String,
    val state: ModuleState,
    val note: String,
)

/** One of rtok's own plugins as a host variant reaches it; [on] is `[plugins.<id>] enabled`. */
data class PluginRow(
    val id: String,
    val on: Boolean,
    val state: ModuleState,
)

object Modules {
    private const val NAME_WIDTH = 7

    /** [agent]'s support for [module] in [kind]; a host missing from the registry supports nothing. */
    fun support(
        agent: Agent,
        kind: Kind,
        module: String,
    ): Support = Hosts.host(agent.id)?.support(kind, module) ?: Support.No("unknown host")

    /** [Agent.installed] with `mcp` read per surface wherever the host has an MCP entry (T278). */
    fun installed(
        agent: Agent,
        kind: Kind,
        cfg: Config,
    ): List<String> {
        val found = agent.installed(cfg, kind)
        val rows = McpDoctor.rows(agent, cfg, kind)
        if (rows.isEmpty()) return found
        return found.filter { it != "mcp" } + listOfNotNull("mcp".takeIf { rows.any(McpRow::found) })
    }

    /** A module found in the host's files counts as installed even where setup cannot write it. */
    fun rows(
        agent: Agent,
        kind: Kind,
        cfg: Config,
    ): List<ModuleRow> {
        val found = installed(agent, kind, cfg)
        return Hosts.MODULES.map { name ->
            if (name in found) {
                ModuleRow(name, ModuleState.Installed, "")
            } else {
                when (val s = support(agent, kind, name)) {
                    Support.Yes -> ModuleRow(name, ModuleState.NotInstalled, "")
                    is Support.Flag -> ModuleRow(name, ModuleState.NotInstalled, " (${s.flag})")
                    is Support.Offer -> ModuleRow(name, ModuleState.NotInstalled, " (${s.flag})")
                    is Support.No -> ModuleRow(name, ModuleState.NotSupported, ": ${s.reason}")
                }
            }
        }
    }

    /** `✓ hooks   installed`, one line per row. */
    fun lines(
        rows: List<ModuleRow>,
        indent: String,
        console: Boolean,
    ): String = rows.joinToString("") { it.state.line(indent, "${it.name.padEnd(NAME_WIDTH)} ${it.state.word}${it.note}", console) }

    /** `hooks=yes mcp=no proxy=n/a plugin=no`. */
    fun summary(rows: List<ModuleRow>): String = rows.joinToString(" ") { "${it.name}=${it.state.short}" }

    /** mcp.rs `lines`: one `mcp` line per surface. */
    fun mcpLines(
        rows: List<McpRow>,
        indent: String,
    ): String =
        rows.joinToString("") {
            val state = if (it.serves()) ModuleState.Installed else ModuleState.NotInstalled
            state.line(indent, "mcp     ${it.surface}  ${it.text()}", true)
        }

    /** The modules an install should leave: every `Yes` module (`mcp` only with `[setup] mcp`), a flag module only with its flag. */
    fun expected(
        agent: Agent,
        kind: Kind,
        cfg: Config,
    ): List<String> =
        Hosts.MODULES.filter { m ->
            when (val s = support(agent, kind, m)) {
                Support.Yes -> m != "mcp" || cfg.setup.mcp
                is Support.Flag -> (s.flag == "--proxy" && cfg.setup.proxy) || (s.flag == "--yes" && cfg.setup.yes)
                is Support.Offer, is Support.No -> false
            }
        }

    /** Expected modules that do not read back from the host's files: the installer checking its own write. */
    fun missing(
        agent: Agent,
        kind: Kind,
        cfg: Config,
    ): List<String> {
        val have = installed(agent, kind, cfg)
        return expected(agent, kind, cfg).filter { it !in have }
    }
}

/** Rust `plugin_rows`/`plugin_lines`: rtok's own plugins grouped by the modules that carry their surfaces. */
object PluginRows {
    private const val WORD_WIDTH = 14

    /** The modules that carry [surface] into a host: hooks carry `hook` and `cli`, the linked plugin what it serves. */
    private fun modulesFor(
        agent: Agent,
        surface: Surface,
    ): List<String> {
        val base =
            when (surface) {
                Surface.Hook, Surface.Cli -> "hooks"
                Surface.Mcp -> "mcp"
                Surface.Proxy -> "proxy"
            }
        return listOfNotNull(base, "plugin".takeIf { surface in agent.pluginSurfaces })
    }

    /** True when at least one of [surfaces] has a module setup can write here. */
    fun reaches(
        agent: Agent,
        kind: Kind,
        surfaces: List<Surface>,
    ): Boolean = surfaces.any { s -> modulesFor(agent, s).any { Modules.support(agent, kind, it) !is Support.No } }

    /** Every catalogue plugin: installed when a surface rides an installed module, not installed when one could. */
    fun rows(
        agent: Agent,
        kind: Kind,
        cfg: Config,
        modules: List<ModuleRow> = Modules.rows(agent, kind, cfg),
    ): List<PluginRow> =
        Registry.new(cfg).manifests().map { (m, on) ->
            val states = m.surfaces.flatMap { modulesFor(agent, it) }.mapNotNull { n -> modules.firstOrNull { it.name == n }?.state }
            val state =
                when {
                    ModuleState.Installed in states -> ModuleState.Installed
                    ModuleState.NotInstalled in states -> ModuleState.NotInstalled
                    else -> ModuleState.NotSupported
                }
            PluginRow(m.id, on, state)
        }

    /** `plugins` and one line per state; an empty not-installed group is omitted (a lone `-` reads as a failure). */
    fun lines(
        rows: List<PluginRow>,
        indent: String,
        console: Boolean,
    ): String {
        val inner = "$indent  "
        return "${indent}plugins\n" +
            ModuleState.entries.joinToString("") { state ->
                val ids = rows.filter { it.state == state }.map { if (it.on) it.id else "${it.id} (off)" }
                if (ids.isEmpty() && state == ModuleState.NotInstalled) {
                    ""
                } else {
                    state.line(inner, "${state.word.padEnd(WORD_WIDTH)} ${ids.ifEmpty { listOf("-") }.joinToString(", ")}", console)
                }
            }
    }
}
