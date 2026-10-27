// Port of src/cli/graph.rs: `rtok graph` index, dead, status, impact, projects, review, diff, export and
// affected. Each subcommand opens the runtime, resolves its scope and prints what the plugin returns.
package rtok.cli

import rtok.plugin.Runtime
import rtok.plugin.sdk.Ctx
import rtok.plugins.graph.DiffQuery
import rtok.plugins.graph.ExportFormat
import rtok.plugins.graph.ExportImage
import rtok.plugins.graph.ExportLevel
import rtok.plugins.graph.ExportQuery
import rtok.plugins.graph.ExportView
import rtok.plugins.graph.Filter
import rtok.plugins.graph.Follow
import rtok.plugins.graph.GraphDiffs
import rtok.plugins.graph.Index
import rtok.plugins.graph.ProjectAction
import rtok.plugins.graph.Projects
import rtok.plugins.graph.Scope
import rtok.plugins.graph.Status

private const val PROJECT_HELP = "Project id or directory instead of the cwd (see `rtok graph projects`)"
private const val MAX_SCALE = 4

private fun projectOpt(help: String = PROJECT_HELP) = OptSpec.Value("project", help, "PROJECT")

private val PATH_ARG = ArgSpec("path", "Directory (default: the cwd)", required = false)

private fun withGraph(
    inv: Invocation,
    body: (Runtime) -> Unit,
) = Runtime.open(inv.loadConfig(), "graph").use(body)

/** `--project` (id or directory) else `[path]` else the cwd, as `cli_root_for`. */
private fun rootFor(
    rt: Runtime,
    inv: Invocation,
): String {
    inv.conflict("project", "path")
    return inv.value("project")?.let { Status.cliRoot(Projects.resolve(rt.store, it).root) } ?: Status.cliRoot(inv.arg("path"))
}

/** The scope a multi-project subcommand answers for: `--project`, else the project of [root]. */
private fun scopeOf(
    rt: Runtime,
    inv: Invocation,
    root: String = Status.cliRoot(null),
) = Scope.resolve(rt.store, inv.value("project"), root)

private fun Invocation.depth(default: Int): Int = value("depth")?.let { it.toUIntOrNull()?.toInt() ?: throw CliFailure("invalid value '$it' for '--depth <DEPTH>': invalid digit found in string", ExitCode.USAGE) } ?: default

private val INDEX =
    CommandSpec(
        "index",
        "Walk a tree and insert definitions + references",
        options = listOf(OptSpec.Flag("dry-run", "Report what would be indexed and write no rows"), projectOpt("Project id or directory (see `rtok graph projects`)")),
        args = listOf(PATH_ARG),
    ) { inv ->
        withGraph(inv) { rt ->
            val root = rootFor(rt, inv)
            val dry = inv.flag("dry-run")
            val r = Index.run(Ctx(rt), root, dry)
            inv.io.println(
                "indexed ${r.indexed} files · ${r.inserted} rows · ${r.skipped} skipped · ${r.read} read · exclude ${r.excludeSkipped} · " +
                    "include ${r.includeAdded} · mapped ${r.extensionMapped}",
            )
            if (!dry) Follow.report(rt, root).forEach { inv.io.eprintln(it) }
        }
    }

private val DEAD =
    CommandSpec(
        "dead",
        "List unreferenced private definitions (skips pub, trait impls, tests, macros)",
        options = listOf(OptSpec.Flag("json", "JSON rows instead of `path:line kind name` lines (uncapped)"), projectOpt("Project id or directory (see `rtok graph projects`)")),
        args = listOf(PATH_ARG),
    ) { inv ->
        withGraph(inv) { rt ->
            inv.conflict("project", "path")
            val scope = scopeOf(rt, inv, Status.cliRoot(inv.arg("path")))
            if (inv.flag("json")) inv.io.println(Scope.deadJson(Ctx(rt), scope)) else inv.io.out(Scope.dead(Ctx(rt), scope))
        }
    }

private val STATUS =
    CommandSpec(
        "status",
        "Index health for the current or given root",
        options = listOf(OptSpec.Flag("json", "JSON instead of the table"), projectOpt("Project id or directory (see `rtok graph projects`)")),
        args = listOf(PATH_ARG),
    ) { inv ->
        withGraph(inv) { rt ->
            val s = Status.collect(Ctx(rt), rootFor(rt, inv))
            if (inv.flag("json")) {
                inv.io.println(
                    Projects.pretty.encodeToString(
                        rtok.plugins.graph.GraphStatus
                            .serializer(),
                        s,
                    ),
                )
            } else {
                inv.io.out(Status.formatTable(s))
            }
        }
    }

private val IMPACT =
    CommandSpec(
        "impact",
        "Symbol impact or call paths to a target",
        options =
            listOf(
                OptSpec.Value("depth", "Caller depth", "DEPTH"),
                OptSpec.Value("to", "Call paths from NAME to this symbol", "TO"),
                OptSpec.Flag("all", "Every reached row, not the file-grouped answer cut at `plugins.graph.impact_tokens`"),
                projectOpt("Project id or directory (see `rtok graph projects`)"),
            ),
        args = listOf(ArgSpec("name", "Symbol name"), PATH_ARG),
    ) { inv ->
        withGraph(inv) { rt ->
            inv.conflict("project", "path")
            val scope = scopeOf(rt, inv, Status.cliRoot(inv.arg("path")))
            inv.io.out(Scope.impact(Ctx(rt), scope, inv.requireArg("name"), inv.depth(2), Filter(all = inv.flag("all")), inv.value("to")))
        }
    }

private val JSON_GLOBAL = OptSpec.Flag("json", "JSON instead of a table", global = true)

private fun projectsAction(
    name: String,
    help: String,
    options: List<OptSpec> = emptyList(),
    args: List<ArgSpec> = listOf(ArgSpec("project", "Project id or directory")),
    action: (Invocation) -> ProjectAction,
) = CommandSpec(name, help, options, args) { inv ->
    withGraph(inv) { rt ->
        val a = action(inv)
        inv.io.out(if (inv.flag("dry-run")) Projects.preview(rt, a, inv.flag("json")) else Projects.run(rt, a, inv.flag("json")))
    }
}

private val PROJECTS =
    CommandSpec(
        "projects",
        "The project registry: list, add, remove, select",
        options = listOf(JSON_GLOBAL),
        runsBare = true,
        subcommands =
            listOf(
                CommandSpec("list", "List the registered projects") { inv -> withGraph(inv) { inv.io.out(Projects.run(it, ProjectAction.List, inv.flag("json"))) } },
                projectsAction("add", "Register a directory as a project (a known one only refreshes its last-used time)", args = listOf(ArgSpec("path"))) {
                    ProjectAction.Add(it.requireArg("path"))
                },
                projectsAction("select", "Make a project the selected one; the page and later the CLI answer for it") { ProjectAction.Select(it.requireArg("project")) },
                projectsAction(
                    "link",
                    "Link a project into the selected one's graph scope (indexes it when it never was)",
                    listOf(
                        OptSpec.Value("from", "Link from this project instead of the selected one", "FROM"),
                        OptSpec.Flag("both", "Also link the other way"),
                        OptSpec.Value("reason", "Why (shown next to the link)", "REASON"),
                    ),
                ) { ProjectAction.Link(it.requireArg("project"), it.value("from"), it.flag("both"), it.value("reason")) },
                projectsAction(
                    "unlink",
                    "Remove a link; an auto link stays removed on re-index",
                    listOf(
                        OptSpec.Value("from", "Unlink from this project instead of the selected one", "FROM"),
                        OptSpec.Flag("both", "Also remove the link the other way"),
                        OptSpec.Flag("dry-run", "Show the links that would go; remove nothing"),
                    ),
                ) { ProjectAction.Unlink(it.requireArg("project"), it.value("from"), it.flag("both")) },
                projectsAction("reindex", "Re-read a project's files into its index (the page's \"Index now\")") { ProjectAction.Reindex(it.requireArg("project")) },
                projectsAction(
                    "remove",
                    "Drop a project and its index rows; its files are never touched",
                    listOf(OptSpec.Flag("dry-run", "Show the index rows and links that would go; remove nothing")),
                ) { ProjectAction.Remove(it.requireArg("project")) },
            ),
    ) { inv -> withGraph(inv) { inv.io.out(Projects.run(it, ProjectAction.List, inv.flag("json"))) } }

private val SINCE_STAGED =
    listOf(
        OptSpec.Value("since", "Diff against this ref", "SINCE"),
        OptSpec.Flag("staged", "Staged files only (`git diff --cached --name-only`)"),
    )

private val REVIEW =
    CommandSpec(
        "review",
        "Risk-ranked reading list for a git diff (files, untested defs, line ranges)",
        options = SINCE_STAGED + listOf(OptSpec.Flag("json", "JSON instead of the text list"), projectOpt()),
    ) { inv ->
        inv.conflict("since", "staged")
        withGraph(inv) { rt -> inv.io.out(Scope.reviewGit(Ctx(rt), scopeOf(rt, inv), inv.value("since"), inv.flag("staged"), inv.flag("json"))) }
    }

private val DIFF =
    CommandSpec(
        "diff",
        "What a change did to the graph: symbols changed, added, removed, renamed or moved, with callers",
        options =
            listOf(
                OptSpec.Value("from", "Compare from this git ref (default HEAD); `PROJECT:REF` sets it for one project, repeat for more", "FROM", repeat = true),
                OptSpec.Value("from-export", "Compare from a saved `rtok graph export --level symbols` file instead of a revision", "FROM_EXPORT"),
                OptSpec.Value("to", "Compare to this git ref; `working` is the working tree", "TO"),
                OptSpec.Flag("json", "JSON instead of the text answer (whole, not capped)"),
                projectOpt(),
            ),
    ) { inv ->
        inv.conflict("from-export", "from")
        inv.conflict("from-export", "to")
        withGraph(inv) { rt ->
            val to = inv.value("to")?.takeIf { it != "working" }
            inv.io.out(GraphDiffs.run(rt, scopeOf(rt, inv), DiffQuery(inv.values("from"), to, inv.flag("json"), inv.value("from-export"))))
        }
    }

private fun <T> choice(
    inv: Invocation,
    name: String,
    default: T,
    all: Map<String, T>,
): T =
    inv.value(name)?.let {
        all[it] ?: throw CliFailure("invalid value '$it' for '--$name <${name.uppercase()}>'\n  [possible values: ${all.keys.joinToString(", ")}]", ExitCode.USAGE)
    } ?: default

private val EXPORT =
    CommandSpec(
        "export",
        "The graph as `rtok.graph.v1` JSON, with home, user name and absolute paths redacted",
        options =
            listOf(
                OptSpec.Value("level", "`overview` is projects and links; `symbols` adds every symbol and call", "LEVEL"),
                OptSpec.Value("focus", "Only the symbols within `--depth` calls of this one, callers and callees", "FOCUS"),
                OptSpec.Value("depth", "Calls to follow from `--focus`", "DEPTH"),
                OptSpec.Flag("no-redact", "Keep absolute paths, the home directory and the user name"),
                OptSpec.Value("from", "Show a saved export instead of the live graph (read-only)", "FROM"),
                OptSpec.Value("format", "`json` is the data, `svg` a picture of it (the 200 best connected nodes), `png` that picture rasterised (needs `--output`)", "FORMAT"),
                OptSpec.Value("scale", "PNG size: 1 to 4 times the SVG size", "SCALE"),
                OptSpec.Flag("transparent", "SVG and PNG without a background"),
                OptSpec.Value("output", "Write to this file instead of stdout", "OUTPUT", short = 'o'),
                projectOpt(),
            ),
    ) { inv ->
        inv.conflict("from", "project")
        inv.conflict("from", "focus")
        if (inv.value("depth") != null && inv.value("focus") == null) throw CliFailure("the following required arguments were not provided:\n  --focus <FOCUS>", ExitCode.USAGE)
        val level = choice(inv, "level", ExportLevel.Overview, mapOf("overview" to ExportLevel.Overview, "symbols" to ExportLevel.Symbols))
        val format = choice(inv, "format", ExportFormat.Json, mapOf("json" to ExportFormat.Json, "svg" to ExportFormat.Svg, "png" to ExportFormat.Png))
        val scale =
            inv.value("scale")?.let {
                it.toIntOrNull()?.takeIf { n -> n in 1..MAX_SCALE } ?: throw CliFailure("invalid value '$it' for '--scale <SCALE>': $it is not in 1..=4", ExitCode.USAGE)
            }
        if (format == ExportFormat.Png && inv.value("output") == null) throw CliFailure("the following required arguments were not provided:\n  --output <OUTPUT>", ExitCode.USAGE)
        if (scale != null && format != ExportFormat.Png) throw CliFailure("--scale is for --format png")
        withGraph(inv) { rt ->
            val from = inv.value("from")
            val scope = if (from != null) emptyList() else scopeOf(rt, inv)
            val q = ExportQuery(level, inv.value("focus"), inv.depth(2), redact = !inv.flag("no-redact"), pretty = true, from = from)
            val img = ExportImage(format, inv.flag("transparent"), scale ?: 1)
            val file = inv.value("output")
            if (file != null) {
                inv.io.println("wrote ${ExportView.write(rt, scope, q, img, file)} bytes to $file")
            } else {
                inv.io.out(ExportView.render(rt, scope, q, img).decodeToString())
            }
        }
    }

private val AFFECTED =
    CommandSpec(
        "affected",
        "Tests that reach files changed in git (`git diff --name-only`)",
        options = SINCE_STAGED + listOf(OptSpec.Flag("json", "JSON instead of `file ← via symbol` lines"), projectOpt()),
    ) { inv ->
        inv.conflict("since", "staged")
        withGraph(inv) { rt -> inv.io.out(Scope.affectedGit(Ctx(rt), scopeOf(rt, inv), inv.value("since"), inv.flag("staged"), inv.flag("json"))) }
    }

val GRAPH =
    CommandSpec(
        "graph",
        "Code graph: index, impact, dead code, review, diff, export",
        subcommands = listOf(INDEX, DEAD, STATUS, IMPACT, PROJECTS, REVIEW, DIFF, EXPORT, AFFECTED),
    )
