// Port of the writing parts of src/tui/app.rs (`toggle_selected`, T15.4, `graph_key`, T476) and src/tui/junk_clear.rs (T479): what a
// key on the Plugins and Hosts tabs changes. No rules of their own: a toggle goes through `config set`'s writer
// (Validate.set) and the junk panel through JunkWeb, the pair the web page calls.
package rtok.tui

import rtok.Expand
import rtok.agents.Cleared
import rtok.agents.Junk
import rtok.agents.JunkClear
import rtok.agents.JunkReport
import rtok.agents.JunkView
import rtok.agents.JunkWeb
import rtok.config.Config
import rtok.config.Validate
import rtok.model.JunkPage
import rtok.plugin.Runtime
import rtok.plugins.graph.DiffReport
import rtok.plugins.graph.Drill
import rtok.plugins.graph.DrillRequest
import rtok.plugins.graph.GraphDiffs
import rtok.plugins.graph.Saved
import rtok.web.DrawnNode
import rtok.web.GraphAnswer
import rtok.web.ProjectRequest

/** The writes `rtok tui` may make; tests hand in a config on a temp home and a fixture junk report. */
class TuiActions(
    initial: Config,
    private val junkReport: (Config) -> JunkReport = Junk::report,
    private val expander: (Config, String) -> String? = ::expandPayload,
) {
    /** app.rs `graph_key`: a Graph-tab registry write through the web page's own function; throws when refused. */
    fun projectWrite(r: ProjectRequest): String = GraphAnswer.projectWrite(cfg, r)

    /**
     * compare.rs `answer`: one typed line is a saved export when it names a file, otherwise the old sides as
     * `rtok graph diff --from` takes them. A file named like a ref wins, as with git's own ambiguity.
     */
    fun compare(
        project: String,
        input: String,
    ): DiffReport =
        Runtime.open(cfg, "tui-compare").use { rt ->
            val file = input.trim()
            if (file.isNotEmpty() && rtok.sys.isFile(file)) {
                GraphDiffs.pageOf(rt, project, emptyList(), null, Saved.read(file))
            } else {
                GraphDiffs.pageOf(rt, project, input.split(' ', '\t').filter { it.isNotEmpty() }, null, null)
            }
        }

    /** drill.rs `load`: the page's `graph` answer at its own cap; a state other than `ok` is the refusal. */
    fun drill(id: Int): Drawn =
        Runtime.open(cfg, "tui-drill").use { rt ->
            val g = Drill.run(rt, DrillRequest(project = id.toString()))
            check(g.state == "ok") { g.state }
            Drawn(g.nodes.map { DrawnNode(it.label, it.path) }, g.more)
        }

    /** The config the loop builds snapshots from; a toggle swaps in the reloaded one, so the next tick agrees. */
    var cfg: Config = initial
        private set

    /**
     * app.rs `toggle_selected`: writes `plugins.<id>.enabled` to `<home>/config.toml` and reports the effective value
     * after the reload (an env override still wins); a refusal is the status line, never a crash.
     */
    fun toggle(
        id: String,
        written: Boolean,
    ): String {
        val key = "plugins.$id.enabled"
        return runCatching { Validate.set(cfg.home, key, written.toString(), false) }.fold(
            {
                val home = cfg.home
                runCatching { Config.loadFrom(home) }.getOrNull()?.let { cfg = it }
                "$id ${if (cfg.pluginEnabled(id, written)) "on" else "off"}"
            },
            { "config set $key: ${it.message ?: it}" },
        )
    }

    /** model.rs `expand_payload`: archive [id]'s body, `[expand] max_lines`-capped; null when it is gone. */
    fun expand(id: String): String? = runCatching { expander(cfg, id) }.getOrNull()

    /** junk_clear.rs `c`: the dry run of `agents junk clear` for every agent. */
    fun junkPlan(): Cleared = JunkWeb.plan(cfg, junkReport(cfg))

    /** junk_clear.rs `y`: a fresh scan cut to [paths], the ones the user was shown; the cached card is dropped. */
    fun junkApply(paths: List<String>): Cleared = JunkWeb.apply(cfg, junkReport(cfg), paths).also { JunkPage.forget() }

    /** `agents junk clear`'s own text of [c]. */
    fun junkText(c: Cleared): String = JunkClear.toText(c, JunkView.of(cfg))
}

/** model.rs `expand_payload` without a grep: what the web `/ws` `expand` frame carries. */
fun expandPayload(
    cfg: Config,
    id: String,
): String? =
    Runtime.open(cfg, "expand").use { cx ->
        Expand.fetch(cx, id)?.let { Expand.renderLines(it.decodeToString(), id, null, null, 0, cfg.expand.maxLines) }
    }
