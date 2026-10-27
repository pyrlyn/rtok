// Port of src/cli/mcp.rs (stdio server, `--call`, `--http`, the `-- <server>` wrapper and `rtok mcp ping`).
package rtok.cli

import kotlinx.serialization.json.Json
import rtok.config.Config
import rtok.config.Layers
import rtok.json.StrictJson
import rtok.mcp.Http
import rtok.mcp.Mcp
import rtok.mcp.PingOpts
import rtok.mcp.PingRun
import rtok.mcp.Wrap
import kotlin.time.Duration.Companion.seconds

private const val PING_TIMEOUT_S = 60L

val MCP_PING =
    CommandSpec(
        "ping",
        "Prove the agent's rtok MCP server is alive and answering",
        options =
            listOf(
                OptSpec.Flag("cli", "Only the CLI app"),
                OptSpec.Flag("desktop", "Only the desktop app"),
                OptSpec.Value("timeout", "Seconds to wait for the agent or the server", "SECS"),
                OptSpec.Flag("json", "One JSON object per host"),
            ),
        args = listOf(ArgSpec("agent", "Host (`claude`, `cursor`, …); omitted checks every host with MCP installed", required = false)),
    ) { inv ->
        val secs = inv.value("timeout")?.let { it.toLongOrNull() ?: throw CliFailure("--timeout: not a number: $it") } ?: PING_TIMEOUT_S
        val opts = PingOpts(inv.arg("agent"), inv.flag("cli"), inv.flag("desktop"), secs.seconds, inv.flag("json"))
        val cfg = inv.loadConfig()
        val (code, out, err) = runCatching { PingRun().run(cfg, opts) }.getOrElse { throw CliFailure(it.message ?: it.toString()) }
        inv.io.out(out)
        inv.io.err(err)
        if (code != 0) throw CliExit(code)
    }

val MCP =
    CommandSpec(
        "mcp",
        "Serve rtok's MCP tools on stdio, or run one tool with `--call`",
        options =
            listOf(
                OptSpec.Value("call", "Run one tool and print its result instead of serving", "TOOL"),
                OptSpec.Value("args", "JSON arguments for --call", "ARGS"),
                // T283.1
                OptSpec.Value(
                    "host",
                    "The host this MCP entry belongs to (`claude`, `cursor`, `grok`, …): overlays `[hook] host` so the process can find its rtok agent",
                    "HOST",
                ),
                OptSpec.Value(
                    "http",
                    "Serve over Streamable HTTP at `IP:PORT` (default `[mcp] http`) instead of stdio; the bearer token comes from `[mcp] token` or RTOK_MCP_TOKEN",
                    "ADDR",
                    optional = true,
                ),
            ),
        subcommands = listOf(MCP_PING),
        runsBare = true,
        args = listOf(ArgSpec("server", "Foreign stdio MCP server to wrap losslessly (`rtok mcp -- npx some-server`)", required = false, variadic = true)),
    ) { inv ->
        val cfg = Config.loadWith(inv.configFile, Layers.hookHostFlag(inv.value("host")))
        val name = inv.value("call")
        val wrap = inv.args("server")
        val http = inv.value("http")
        if (http != null) {
            if (wrap.isNotEmpty()) throw CliFailure("rtok mcp --http serves rtok's own tools; it takes no subcommand or `--`")
            if (name != null || inv.value("args") != null) throw CliFailure("rtok mcp --http cannot be used with --call or --args")
            Http.serveBlocking(cfg, http.ifEmpty { cfg.mcp.http })
            return@CommandSpec
        }
        if (name != null && wrap.isNotEmpty()) throw CliFailure("rtok mcp --call does not wrap a foreign server")
        if (wrap.isNotEmpty()) {
            val code = Wrap.run(cfg, wrap)
            if (code != 0) throw CliExit(code)
        } else if (name == null) {
            Mcp.run(cfg)
        } else {
            val raw = inv.value("args") ?: "{}"
            inv.io.println(Mcp.call(cfg, name, StrictJson.parse(raw)))
        }
    }
