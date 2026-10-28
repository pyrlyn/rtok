// Port of src/cli/info.rs — `rtok info [--json]`: version, effective paths, disk usage, error count and proxy status.
package rtok.cli

import rtok.Info

val INFO =
    CommandSpec(
        "info",
        "Version, effective paths, disk usage, error count and proxy status",
        options = listOf(OptSpec.Flag("json", "JSON instead of the text lines")),
    ) { inv ->
        val info = Info.collect(inv.loadConfig(), inv.configFile)
        if (inv.flag("json")) inv.io.println(info.toJson()) else inv.io.out(info.toText())
    }
