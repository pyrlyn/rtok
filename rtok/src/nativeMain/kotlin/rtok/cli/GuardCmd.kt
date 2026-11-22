// Port of src/cli/guard.rs — `rtok guard check`, the allow/deny `plugins::guard` returns on PreToolUse.
package rtok.cli

import rtok.config.Config
import rtok.config.Layers
import rtok.plugin.Runtime

private const val ALLOW = """{"allow":true}"""

val GUARD =
    CommandSpec(
        "guard",
        "Duplicate-call verdict (`rtok guard check` — pi / OpenCode plugin path)",
        subcommands =
            listOf(
                CommandSpec(
                    "check",
                    "Print `{\"allow\":true}` or `{\"allow\":false,\"reason\":…}` (fail open: bad input allows)",
                    options =
                        listOf(
                            OptSpec.Value("tool", "Host tool name (`bash`, `Read`, …)", "TOOL"),
                            OptSpec.Value("json", "Tool arguments as JSON", "INPUT"),
                            OptSpec.Value("session", "Host session id (the cache is per session)", "SESSION"),
                            OptSpec.Value("host", "Overlay `[hook] host`", "HOST"),
                        ),
                ) { inv ->
                    val tool = inv.value("tool") ?: throw CliFailure("the following required arguments were not provided: --tool <TOOL>", ExitCode.USAGE)
                    val json = inv.value("json") ?: throw CliFailure("the following required arguments were not provided: --json <INPUT>", ExitCode.USAGE)
                    val cfg = Config.loadLenient(inv.configFile, Layers.hookHostFlag(inv.value("host")))
                    // The caller blocks the tool on anything but an allow, so a store that cannot open must allow too.
                    val verdict =
                        runCatching {
                            Runtime.open(cfg, inv.value("session") ?: "guard-check").use { cx -> rtok.plugins.guard.check(tool, json, cx) }
                        }.getOrElse { ALLOW }
                    inv.io.println(verdict)
                },
            ),
    )
