// Port of src/cli/hook.rs — `rtok hook <event> [--host <h>]` and the hidden `--serve`.
package rtok.cli

import rtok.config.Config
import rtok.config.Layers
import rtok.hooks.Gate
import rtok.hooks.armStdout

val HOOK =
    CommandSpec(
        "hook",
        "Run one agent hook event: host JSON on stdin, hook JSON on stdout (fail open)",
        options =
            listOf(
                OptSpec.Value("host", "Overlay `[hook] host` (`claude` | `cursor` | `copilot` | `devin` | `cline`)", "HOST"),
                OptSpec.Flag("serve", "Run the resident hook process `rtok-hook` talks to"),
            ),
        args = listOf(ArgSpec("event", "Hook event name, e.g. PreToolUse", required = false)),
    ) { inv ->
        if (inv.flag("serve")) {
            if (inv.arg("event") != null || inv.value("host") != null) throw CliFailure("--serve conflicts with <event> and --host", ExitCode.USAGE)
            rtok.hooks.Resident.runner = { rtok.hooks.residentRun(it) }
            rtok.hooks.Resident.serve()
            return@CommandSpec
        }
        val event = inv.arg("event") ?: throw CliFailure("missing argument <event>", ExitCode.USAGE)
        // Armed before config and stdin, the two places a hook can hang before the dispatcher (T921).
        val gate = Gate()
        armStdout(event, rtok.hook.deadline { rtok.config.envVar(it) }, gate).use {
            val cfg = Config.loadLenient(inv.configFile, Layers.hookHostFlag(inv.value("host")))
            cfg.hookClientPid = platform.posix.getpid().toUInt()
            val out = rtok.hooks.run(event, { limit -> rtok.sys.readFdBounded(0, limit) }, cfg)
            if (gate.claim()) inv.io.writeBytes(out)
        }
    }
