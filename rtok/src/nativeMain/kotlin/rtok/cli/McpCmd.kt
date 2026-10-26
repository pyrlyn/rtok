// Port of src/cli/mcp.rs (stdio server and `--call`; `--http`, `ping` and the wrapper come later).
package rtok.cli

import kotlinx.serialization.json.Json
import rtok.mcp.Mcp

val MCP =
    CommandSpec(
        "mcp",
        "Serve rtok's MCP tools on stdio, or run one tool with `--call`",
        options =
            listOf(
                OptSpec.Value("call", "Run one tool and print its result instead of serving", "TOOL"),
                OptSpec.Value("args", "JSON arguments for --call", "ARGS"),
            ),
    ) { inv ->
        val cfg = inv.loadConfig()
        val name = inv.value("call")
        if (name == null) {
            Mcp.run(cfg)
        } else {
            val raw = inv.value("args") ?: "{}"
            inv.io.println(Mcp.call(cfg, name, Json.parseToJsonElement(raw)))
        }
    }
