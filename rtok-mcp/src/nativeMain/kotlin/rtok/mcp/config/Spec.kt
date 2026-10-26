// Port of crates/rtok-mcp/src/registry.rs + spec.rs: rtok's own MCP servers and `McpSpec`, the per-host-client
// data (config path, format, dotted key path, entry shape, duplicate-name behaviour).
package rtok.mcp.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A stdio MCP server rtok can serve. */
data class Server(
    val name: String,
    val command: String,
    val args: List<String>,
    /** Extra environment variables the entry sets (most hosts' entries carry none). */
    val env: List<Pair<String, String>> = emptyList(),
)

/** rtok's own MCP server: `rtok mcp` on `PATH`. */
val RTOK = Server(name = "rtok", command = "rtok", args = listOf("mcp"))

/** The config file's syntax: strict JSON is rewritten pretty; JSONC and TOML keep comments and formatting. */
enum class Format { Json, Jsonc, Toml }

/** Which client of a host an entry is for. */
enum class Client { Cli, Desktop, Ide }

/** What a host does when a plugin serves a server under the same name as a config entry (research.md §25). */
enum class DuplicateName { ShowsBoth, OverridesByScope, Merges, Errors }

/** `"type": "stdio"` + argv, the bare argv, or Copilot CLI's `"type": "local"` + `"tools": ["*"]`. */
enum class EntryShape { TypedCommand, Command, LocalAllTools }

/** How a host starts the server when that is not the registry's bare `rtok mcp`. */
data class Launch(
    /** The binary to run instead of `server.command`. */
    val command: String? = null,
    /** Append `--host <spec.host>` to the registry's args. */
    val hostArg: Boolean = false,
)

/** One host client's MCP config: where it lives, how it is shaped, and the entry rtok writes into it. */
data class McpSpec(
    val host: String,
    val client: Client,
    val configPath: String,
    val format: Format,
    /** Path to the servers table (`mcpServers`, `mcp_servers`, `mcp.servers`). */
    val keyPath: List<String>,
    val server: Server = RTOK,
    val launch: Launch = Launch(),
    val entryShape: EntryShape = EntryShape.TypedCommand,
    val duplicateName: DuplicateName = DuplicateName.ShowsBoth,
    /** The name the rtok plugin itself serves on this host, if any (D33: usually none). */
    val pluginServes: String? = null,
) {
    private val command get() = launch.command ?: server.command
    private val args get() = server.args + if (launch.hostArg) listOf("--host", host) else emptyList()

    /** `rtok mcp --host claude`: what a host's report prints after `<key>.<name>: `. */
    fun summary(): String = "$command ${args.joinToString(" ")}"

    /** `<key_path>.<name>`. */
    fun at(): String = (keyPath + server.name).joinToString(".")

    /** The JSON entry written under `keyPath.<server.name>` (TOML specs get the same shape converted on write). */
    fun entry(): JsonObject {
        val m = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        when (entryShape) {
            EntryShape.TypedCommand -> m["type"] = JsonPrimitive("stdio")
            EntryShape.LocalAllTools -> m["type"] = JsonPrimitive("local")
            EntryShape.Command -> Unit
        }
        m["command"] = JsonPrimitive(command)
        m["args"] = JsonArray(args.map(::JsonPrimitive))
        if (entryShape == EntryShape.LocalAllTools) m["tools"] = JsonArray(listOf(JsonPrimitive("*")))
        if (server.env.isNotEmpty()) m["env"] = JsonObject(server.env.associate { (k, v) -> k to JsonPrimitive(v) })
        return JsonObject(m)
    }

    companion object {
        /** `"a.b.c"` → `["a", "b", "c"]`. */
        fun keyPath(dotted: String): List<String> = dotted.split('.')
    }
}
