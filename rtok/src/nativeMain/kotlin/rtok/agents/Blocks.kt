// Port of src/agents/mod.rs `Outcome` and `block`: the block one host variant prints for `agents list|info|install|
// update|remove`. Colourless (Rust's pipe output): no operation icon, diff lines unpainted.
package rtok.agents

import rtok.StatPreview
import rtok.agent.sdk.NO_CHANGES
import rtok.config.Config

/** What a block reports about its variant. */
sealed interface Outcome {
    /** The app is not on this machine: header, app and config lines only. */
    data object NotFound : Outcome

    /** `agents list`: state, no installer ran. */
    data object Listed : Outcome

    /** The installer ran with these step reports. */
    data class Applied(
        val mode: Mode,
        val reports: List<String>,
    ) : Outcome

    /** A shared-config host already applied under sibling variant [first]. */
    data class Shared(
        val first: String,
    ) : Outcome

    /** `agents update` found no rtok module in this variant and wrote nothing (T242.2). */
    data object NotInstalled : Outcome
}

object Blocks {
    /**
     * ```text
     * CLI: Codex — already installed
     *   app     /opt/homebrew/bin/codex (codex-cli 0.40.0)
     *   config  /Users/me/.codex/config.toml
     *   ✓ mcp     installed
     * ```
     */
    fun block(
        agent: Agent,
        v: Variant,
        cfg: Config,
        outcome: Outcome,
        appVersion: (Variant) -> String = { AppVersion.of(cfg, agent.id, it) },
    ): String {
        val head = "${v.kind.label}: ${v.name}${note(cfg, outcome)}\n"
        if (outcome is Outcome.NotInstalled) return "$head  skip    nothing of rtok here; run `rtok agents install ${agent.id}`\n"
        val out = StringBuilder(head)
        val at = Locate.locate(cfg, agent.id, v)
        out.append(if (at != null) "  app     $at (${appVersion(v)})\n" else "  app     -\n")
        val files = agent.files(cfg, v.kind)
        if (files.isNotEmpty()) out.append("  config  ${files.joinToString(", ")}\n")
        if (outcome !is Outcome.NotFound) {
            if (outcome is Outcome.Applied) out.append(changes(cfg, v, files, outcome.reports))
            out.append(state(agent, v, cfg))
        }
        return out.toString()
    }

    /** The module rows (`mcp` one line per surface, T278) and the plugin groups. */
    private fun state(
        agent: Agent,
        v: Variant,
        cfg: Config,
    ): String {
        val surfaces = McpDoctor.rows(agent, cfg, v.kind)
        val modules = Modules.rows(agent, v.kind, cfg)
        val rows = modules.map { withStatus(it, agent, v, cfg) }.joinToString("") { row -> if (row.name == "mcp" && surfaces.isNotEmpty()) Modules.mcpLines(surfaces, "  ") else Modules.lines(listOf(row), "  ", true) }
        return rows + PluginRows.lines(PluginRows.rows(agent, v.kind, cfg, modules), "  ", true)
    }

    /** The installed `plugin` row names its version and source (outdated.rs `plugin_status`, T382). */
    private fun withStatus(
        row: ModuleRow,
        agent: Agent,
        v: Variant,
        cfg: Config,
    ): ModuleRow =
        if (row.name == "plugin" && row.state == ModuleState.Installed) {
            Outdated.pluginStatus(agent, v.kind, cfg)?.let { row.copy(note = " ${it.note}") } ?: row
        } else {
            row
        }

    private fun note(
        cfg: Config,
        outcome: Outcome,
    ) = when (outcome) {
        Outcome.NotFound -> {
            " — not found"
        }

        Outcome.Listed -> {
            ""
        }

        is Outcome.Applied -> {
            when {
                cfg.setup.dryRun -> {
                    " — dry run, nothing written"
                }

                outcome.reports.all { it == NO_CHANGES } -> {
                    when (outcome.mode) {
                        Mode.Remove -> " — no changes"
                        Mode.Update -> " — already current"
                        else -> " — already installed"
                    }
                }

                else -> {
                    ""
                }
            }
        }

        is Outcome.Shared -> {
            " — same files as above"
        }

        Outcome.NotInstalled -> {
            " — not installed"
        }
    }

    /** The steps that changed something; a dry run goes through `--stat` against the one config file (or the host). */
    private fun changes(
        cfg: Config,
        v: Variant,
        files: List<String>,
        reports: List<String>,
    ): String {
        val changed = reports.filter { it != NO_CHANGES }
        if (changed.isEmpty()) return ""
        val text = changed.joinToString("\n")
        val shown =
            if (cfg.setup.dryRun) {
                val at = files.singleOrNull() ?: "${v.kind.label}: ${v.name}"
                StatPreview.show(listOf(at to text), StatPreview.shown(text))
            } else {
                text
            }
        return if (shown.isEmpty()) "" else "$shown\n"
    }
}
