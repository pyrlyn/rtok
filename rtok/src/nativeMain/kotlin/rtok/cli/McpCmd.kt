// Port of src/cli/mcp.rs (stdio server, `--call`, `--http` and the `-- <server>` wrapper; `ping` comes later).
package rtok.cli

import kotlinx.serialization.json.Json
import rtok.mcp.Http
import rtok.mcp.Mcp
import rtok.mcp.Wrap

val MCP =
    CommandSpec(
        "mcp",
        "Serve rtok's MCP tools on stdio, or run one tool with `--call`",
        options =
            listOf(
                OptSpec.Value("call", "Run one tool and print its result instead of serving", "TOOL"),
                OptSpec.Value("args", "JSON arguments for --call", "ARGS"),
                OptSpec.Value(
                    "http",
                    "Serve over Streamable HTTP at `IP:PORT` (default `[mcp] http`) instead of stdio; the bearer token comes from `[mcp] token` or RTOK_MCP_TOKEN",
                    "ADDR",
                    optional = true,
                ),
            ),
        args = listOf(ArgSpec("server", "Foreign stdio MCP server to wrap losslessly (`rtok mcp -- npx some-server`)", required = false, variadic = true)),
    ) { inv ->
        val cfg = inv.loadConfig()
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
            inv.io.println(Mcp.call(cfg, name, Json.parseToJsonElement(raw)))
        }
    }
