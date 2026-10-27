// Port of src/cli/doctor.rs: `rtok doctor [--instructions] [--json] [--agent HOST]` and
// `rtok doctor --fix [--yes [--dry-run]] [--stat] [--only CLASS]...`.
package rtok.cli

import kotlinx.serialization.json.JsonObject
import rtok.StatPreview
import rtok.agents.AgentsJson
import rtok.agents.Junk
import rtok.config.Config
import rtok.config.Layers
import rtok.doctor.DoctorJson
import rtok.doctor.Fix
import rtok.doctor.Hooks
import rtok.doctor.Page
import rtok.doctor.Prompt
import rtok.sys.currentExe

/** `--only` values (clap `ValueEnum` spelling), each one of [Fix.KINDS] in order. */
private val FIX_CLASSES = listOf("broken-hooks", "duplicate-hooks", "duplicate-mcp")

/** The checklist terminal: the screen on stdout, the answer from stdin. */
private class Terminal(
    val io: CliIo,
) : Prompt {
    override fun ask(screen: String): String? {
        io.out(screen)
        return readlnOrNull()?.let { "$it\n" }
    }
}

private fun requires(
    inv: Invocation,
    flag: String,
    needs: String,
) {
    val set = if (flag == "only") inv.values(flag).isNotEmpty() else inv.flag(flag)
    if (set && !inv.flag(needs)) {
        throw CliFailure("the following required arguments were not provided:\n  --$needs\n\nUsage: rtok doctor --$flag --$needs", 2)
    }
}

val DOCTOR =
    CommandSpec(
        "doctor",
        "Check hooks, MCP servers and the proxy chain; --fix cleans up broken and duplicate entries",
        options =
            listOf(
                OptSpec.Flag("instructions", "Also run the instruction-file audit"),
                OptSpec.Flag("json", "JSON instead of the table"),
                OptSpec.Flag(
                    "fix",
                    "Clean up broken hooks and duplicate hooks and MCP entries: a terminal gets a checklist, a pipe the diff; " +
                        "--yes writes without asking",
                ),
                OptSpec.Flag("yes", "With --fix: write the changes (a copy goes to `.backup_configs/` first)"),
                OptSpec.Flag("dry-run", "With --fix --yes: print the diffs and write nothing"),
                OptSpec.Flag("stat", "With --fix: stat lines and totals instead of the diffs (a dry run)"),
                OptSpec.Value("only", "With --fix: limit it to these problems (repeatable; default: all of them)", "ONLY", repeat = true),
                OptSpec.Value("agent", "Check (and with --fix, repair) the hooks of one host only (an id of `rtok agents list`)", "HOST"),
            ),
    ) { inv ->
        if (inv.flag("json") && inv.flag("fix")) throw CliFailure("the argument '--json' cannot be used with '--fix'", 2)
        requires(inv, "yes", "fix")
        requires(inv, "dry-run", "yes")
        requires(inv, "stat", "fix")
        requires(inv, "only", "fix")
        val only =
            inv.values("only").map {
                val i = FIX_CLASSES.indexOf(it)
                if (i < 0) throw CliFailure("invalid value '$it' for '--only <ONLY>'\n  [possible values: ${FIX_CLASSES.joinToString(", ")}]", 2)
                Fix.KINDS[i]
            }
        val agent = inv.value("agent")?.let { Hooks.hostId(it).getOrElse { e -> throw CliFailure(e.message.orEmpty()) } }
        val cfg = Config.loadWith(inv.configFile, Layers.doctorFlags(inv.flag("instructions")))
        if (inv.flag("fix")) {
            StatPreview.set(inv.flag("stat"))
            val yes = inv.flag("yes")
            // A terminal and no `--yes`: the user picks what goes. Pipes and CI keep the dry run.
            val tty = platform.posix.isatty(0) == 1 && platform.posix.isatty(1) == 1
            val ask = if (!yes && tty) Terminal(inv.io) else null
            val (text, code) = Fix.run(cfg, yes && !inv.flag("dry-run"), agent, only.ifEmpty { Fix.KINDS }, ask)
            inv.io.out(text)
            if (code != 0) throw CliExit(code)
        } else {
            val page = Page.page(cfg, currentExe())
            val report = page.copy(problems = page.problems.filter { agent == null || it.agent == agent })
            if (inv.flag("json")) {
                inv.io.out(AgentsJson.PRETTY.encodeToString(JsonObject.serializer(), DoctorJson.of(report)) + "\n")
            } else {
                inv.io.out(report.copy(junkBytes = Junk.report(cfg).freedDefaultBytes).toConsole())
            }
        }
    }
