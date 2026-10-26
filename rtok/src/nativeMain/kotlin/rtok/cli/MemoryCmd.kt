// Port of src/cli/memory.rs: `rtok memory …`. `status` (needs model::memory_status) and TTY styling
// (ui::style) are not ported yet; lines print plain, as Rust does off a terminal.
package rtok.cli

import rtok.plugin.Runtime
import rtok.plugins.memory.*

private fun Invocation.idArg(name: String = "id"): Int = requireArg(name).toIntOrNull() ?: throw CliFailure("invalid value for <$name>", ExitCode.USAGE)

private fun Invocation.intOpt(name: String): Int? = value(name)?.let { it.toIntOrNull() ?: throw CliFailure("invalid value for --$name", ExitCode.USAGE) }

private fun <T> Invocation.withRt(body: (Runtime) -> T): T = Runtime.open(loadConfig(), "memory").use(body)

private val ID = ArgSpec("id")
private val PROJECT = OptSpec.Value("project", "Only notes of this project", "PROJECT")

val MEMORY =
    CommandSpec(
        "memory",
        "Notes (`mem_save` / import / export)",
        subcommands =
            listOf(
                CommandSpec(
                    "import",
                    "Import `{kind,title,body}` JSONL; dedupe by body sha256",
                    options = listOf(OptSpec.Flag("dry-run", "Count what would be imported and write nothing")),
                    args = listOf(ArgSpec("file")),
                ) { inv ->
                    inv.io.println(Import.run(inv.loadConfig(), inv.requireArg("file"), inv.flag("dry-run")).toString())
                },
                CommandSpec("export", "Print every note but session checkpoints as the JSONL `import` reads", options = listOf(PROJECT)) { inv ->
                    Export.run(inv.loadConfig(), inv.value("project")) { inv.io.out(it) }
                },
                CommandSpec(
                    "extract",
                    "Save notes an external program extracts from a transcript (off by default)",
                    args = listOf(ArgSpec("transcript", "Claude Code transcript (`.jsonl`)")),
                ) { inv ->
                    inv.io.println(inv.withRt { runConfiguredExtract(it, inv.requireArg("transcript")) })
                },
                CommandSpec(
                    "retire",
                    "Retire a note: a tombstone — never recalled or searched, body kept",
                    options = listOf(OptSpec.Value("superseded-by", "The replacement note id this one is superseded by", "SUPERSEDED_BY")),
                    args = listOf(ID),
                ) { inv ->
                    inv.io.println(inv.withRt { memUpdate(it, inv.idArg(), true, inv.intOpt("superseded-by"), null) })
                },
                CommandSpec(
                    "forget",
                    "Retire notes nobody read for a long time (a dry run unless `--apply`)",
                    options =
                        listOf(
                            OptSpec.Value("project", "Only this project", "PROJECT"),
                            OptSpec.Value("days", "Idle days; default `[plugins.memory] forget_after_days`", "DAYS"),
                            OptSpec.Flag("apply", "Retire the notes; without it nothing is written"),
                        ),
                ) { inv -> inv.io.println(inv.withRt { memForget(it, inv.value("project"), inv.intOpt("days")?.toUInt(), inv.flag("apply")) }) },
                CommandSpec("pin", "Pin a note so it leads SessionStart recall", args = listOf(ID)) { inv -> inv.io.println(inv.withRt { memUpdate(it, inv.idArg(), false, null, true) }) },
                CommandSpec("unpin", "Drop a note back to newest-first recall order", args = listOf(ID)) { inv -> inv.io.println(inv.withRt { memUpdate(it, inv.idArg(), false, null, false) }) },
                CommandSpec("history", "Print the earlier title and body kept when an upsert changed this note", args = listOf(ID)) { inv -> inv.io.println(inv.withRt { memHistory(it, inv.idArg()) }) },
                CommandSpec(
                    "revise",
                    "Save a replacement (title, body) for a note and retire the old row",
                    options =
                        listOf(
                            OptSpec.Value("title", "The replacement title", "TITLE"),
                            OptSpec.Value("body", "The replacement body", "BODY"),
                        ),
                    args = listOf(ID),
                ) { inv ->
                    val title = inv.value("title") ?: throw CliFailure("the following required arguments were not provided: --title <TITLE>", ExitCode.USAGE)
                    val body = inv.value("body") ?: throw CliFailure("the following required arguments were not provided: --body <BODY>", ExitCode.USAGE)
                    val (new, retired) = inv.withRt { memRevise(it, inv.idArg(), title, body) }
                    inv.io.println(retired?.let { "revised note $it → $new" } ?: "updated note $new in place")
                },
                CommandSpec(
                    "sync",
                    "Write pinned-then-newest titles into a managed CLAUDE.md / AGENTS.md block",
                    options =
                        listOf(
                            OptSpec.Value("file", "CLAUDE.md or AGENTS.md", "FILE"),
                            OptSpec.Value("budget", "Token budget; default `[plugins.memory] sync_tokens`", "BUDGET"),
                            OptSpec.Flag("dry-run", "Print the unified diff and write nothing"),
                            OptSpec.Flag("remove", "Delete the managed block and nothing else"),
                            OptSpec.Flag("force", "Overwrite a hand-edited block"),
                        ),
                ) { inv ->
                    Sync.run(inv.loadConfig(), inv.value("file") ?: "CLAUDE.md", inv.intOpt("budget")?.toUInt(), inv.flag("dry-run"), inv.flag("remove"), inv.flag("force")) { inv.io.out(it) }
                },
            ),
    )
