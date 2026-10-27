// Port of src/agents/junk_web.rs `filter`, `plan` and `apply` (T330.7): the web Hosts page's "clear safe junk" button.
// It is `agents junk clear` with every agent named and nothing else: `plan` is its dry run and writes nothing, `apply`
// is `--yes` through the same JunkClear plan, re-checks and refusals. No state is kept between the two: `apply` plans
// again and removes only the paths the page showed and the user confirmed. The card itself is model/JunkPage.kt.
package rtok.agents

import rtok.config.Config
import rtok.config.HOSTS

object JunkWeb {
    /**
     * What the button clears: the `safe` kinds of every agent. A bare `clear` is T182's rtok-only run, so the agents are
     * named, as `--agent` does; no `--kind`, `--include review` or `--trash`.
     */
    fun filter(): JunkFilter = JunkFilter(agents = listOf("rtok") + HOSTS)

    /** The dry run over [report]; nothing is removed. */
    fun plan(
        cfg: Config,
        report: JunkReport,
    ): Cleared = JunkClear.runIn(cfg, report, filter(), false, null)

    /** Removes what [paths] names out of a fresh plan; sent only for the page's explicit confirmation. */
    fun apply(
        cfg: Config,
        report: JunkReport,
        paths: List<String>,
    ): Cleared = JunkClear.runIn(cfg, report, filter(), true, paths)
}
