// Port of src/cli/docs.rs: `rtok docs fetch <name>`.
package rtok.cli

import rtok.plugins.docs.runFetch

val DOCS =
    CommandSpec(
        "docs",
        "Cached rustdoc for Cargo.lock dependencies (`rtok docs fetch`)",
        subcommands =
            listOf(
                CommandSpec(
                    "fetch",
                    "Download rustdoc JSON for a Cargo.lock dependency (the only network path)",
                    args = listOf(ArgSpec("name")),
                ) { inv -> inv.io.println(runFetch(inv.loadConfig(), inv.requireArg("name"))) },
            ),
    )
