// `rtok mcp ping` config side (mcp/ping.rs): where each host writes rtok's MCP server object, the headless
// command lines, and reading a `Launch` back from a host config. The process probe and CLI are the next chunk.
package rtok.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.agents.Jsonc
import rtok.config.Toml

/** Command line taken from a host config. Never a path this binary invented. */
data class Launch(
    val command: String,
    val args: List<String>,
    val env: List<Pair<String, String>>,
)

/** Where a host writes rtok's server object; [argv] means `command` is the whole argv array. */
internal data class Shape(
    val host: String,
    val keys: List<String>,
    val toml: Boolean = false,
    val argv: Boolean = false,
)

/** `{prompt}` in [argv] is replaced with [Ping.promptFor]. */
internal data class Headless(
    val host: String,
    val bin: String,
    val argv: List<String>,
)

object Ping {
    private val servers = listOf("mcpServers", "rtok")
    private val codexStyle = listOf("mcp_servers", "rtok")
    private val mcpArgv = listOf("mcp", "rtok")

    internal val SHAPES: List<Shape> =
        listOf(
            Shape("claude", servers),
            Shape("cursor", servers),
            Shape("codex", codexStyle, toml = true),
            Shape("opencode", mcpArgv, argv = true),
            Shape("kilo", mcpArgv, argv = true),
            Shape("omp", servers),
            Shape("zcode", listOf("mcp", "servers", "rtok")),
            Shape("kimi", servers),
            Shape("grok", codexStyle, toml = true),
            Shape("vscode", listOf("servers", "rtok")),
            Shape("copilot", servers),
            Shape("windsurf", servers),
            Shape("zed", listOf("context_servers", "rtok")),
            Shape("cline", servers),
            Shape("gemini", servers),
            Shape("codewhale", servers),
            Shape("mimo", mcpArgv, argv = true),
            Shape("devin", servers),
        )

    internal val HEADLESS: List<Headless> =
        listOf(
            Headless("claude", "claude", listOf("-p", "{prompt}")),
            Headless("cursor", "cursor-agent", listOf("-p", "{prompt}")),
            Headless("codex", "codex", listOf("exec", "{prompt}")),
            Headless("copilot", "copilot", listOf("-p", "{prompt}", "--allow-all-tools")),
            Headless("gemini", "gemini", listOf("-p", "{prompt}")),
        )

    /** The ping tool's reply; the display name is what `agents list` prints. */
    fun pingText(agent: String) = "MCP $agent жив"

    fun promptFor(display: String) = "Call the rtok MCP tool \"ping\" with agent=\"$display\" and reply with its result only, nothing else."

    internal fun shapeFor(host: String) = SHAPES.firstOrNull { it.host == host }

    /** First rtok entry among [paths]; [read] is the only source of `command` / `args`. */
    fun launchFromRead(
        read: (String) -> String?,
        paths: List<String>,
        host: String,
    ): Launch? {
        val shape = shapeFor(host) ?: return null
        return paths.firstNotNullOfOrNull { p -> read(p)?.let { launchFromBody(it, shape) } }
    }

    /** Parse one config body; null when this host's rtok object is absent. */
    fun launchFromBody(
        body: String,
        host: String,
    ): Launch? = shapeFor(host)?.let { launchFromBody(body, it) }

    internal fun launchFromBody(
        body: String,
        shape: Shape,
    ): Launch? {
        val root = if (shape.toml) runCatching { Toml.parse(body) }.getOrNull() else Jsonc.parseOrNull(body)
        val obj = shape.keys.fold<String, JsonElement?>(root) { cur, k -> (cur as? JsonObject)?.get(k) } as? JsonObject
        return obj?.let { if (shape.argv) argvLaunch(it) else splitLaunch(it) }
    }

    private fun argvLaunch(obj: JsonObject): Launch? = strings(obj["command"] as? JsonArray).takeIf { it.isNotEmpty() }?.let { Launch(it.first(), it.drop(1), envOf(obj["env"])) }

    private fun splitLaunch(obj: JsonObject): Launch? =
        (obj["command"] as? JsonPrimitive)
            ?.takeIf { it.isString && it.content.isNotEmpty() }
            ?.let { Launch(it.content, strings(obj["args"] as? JsonArray), envOf(obj["env"])) }

    private fun strings(a: JsonArray?) = a.orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

    private fun envOf(e: JsonElement?) = (e as? JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.let { k to it.content } }
}
