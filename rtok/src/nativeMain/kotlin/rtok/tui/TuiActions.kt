// Port of the writing parts of src/tui/app.rs (`toggle_selected`, T15.4) and src/tui/junk_clear.rs (T479): what a
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

/** The writes `rtok tui` may make; tests hand in a config on a temp home and a fixture junk report. */
class TuiActions(
    initial: Config,
    private val junkReport: (Config) -> JunkReport = Junk::report,
    private val expander: (Config, String) -> String? = ::expandPayload,
) {
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
