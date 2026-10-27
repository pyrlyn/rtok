// Port of src/cli/proxy.rs: `rtok proxy` serves until killed; `--dry-run` prints the effective `[proxy]` settings.
package rtok.cli

import rtok.config.Config
import rtok.config.Layers
import rtok.proxy.ProxyServer

val PROXY =
    CommandSpec(
        "proxy",
        "Local API proxy for ANTHROPIC_BASE_URL",
        options =
            listOf(
                OptSpec.Value("port", "Override `[proxy] port`", "PORT"),
                OptSpec.Value("upstream", "Override `[proxy] upstream`", "URL"),
                OptSpec.Value("mode", "Override `[proxy] mode` (`passthrough` | `compress`)", "MODE"),
                OptSpec.Flag("dry-run", "Print effective `[proxy]` settings and exit"),
            ),
    ) { inv ->
        val port = inv.value("port")?.let { it.toUShortOrNull() ?: throw CliFailure("--port: not a port: $it") }
        val cfg = Config.loadWith(inv.configFile, Layers.proxyFlags(port, inv.flag("dry-run"), inv.value("upstream"), inv.value("mode")))
        val p = cfg.proxy
        if (p.dryRun) {
            inv.io.out(
                "bind = ${p.bind}\nport = ${p.port}\nmode = ${p.mode}\nupstream = ${p.upstream}\nopenai_upstream = ${p.openaiUpstream}\n" +
                    "timeout_s = ${p.timeoutS}\ninclude_usage = ${p.includeUsage}\ndry_run = ${p.dryRun}\n",
            )
            return@CommandSpec
        }
        ProxyServer.serveBlocking(cfg)
    }
