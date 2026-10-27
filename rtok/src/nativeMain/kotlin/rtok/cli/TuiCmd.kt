// Port of src/cli/web.rs `tui`: `rtok tui --tab --tick-secs`.
package rtok.cli

import rtok.config.Config
import rtok.config.Layers

val TUI =
    CommandSpec(
        "tui",
        "Terminal UI over the same data as `rtok web` (one model, two renderings)",
        options =
            listOf(
                OptSpec.Value("tab", "Start on this tab (a page name both surfaces carry)", "TAB"),
                OptSpec.Value("tick-secs", "Model re-read cadence in seconds", "TICK_SECS"),
            ),
    ) { inv ->
        val tick = inv.value("tick-secs")?.let { it.toULongOrNull() ?: throw CliFailure("invalid value '$it' for '--tick-secs <TICK_SECS>'") }
        val cfg = Config.loadWith(inv.configFile, Layers.tuiFlags(inv.value("tab"), tick))
        rtok.tui.checkTty(platform.posix.isatty(0) == 1, platform.posix.isatty(1) == 1)?.let { throw CliFailure(it) }
        rtok.tui.run(cfg)
    }
