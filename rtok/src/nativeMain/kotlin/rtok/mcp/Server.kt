// The `rtok mcp` request handler (mcp.rs `Server`): the listed tools, JSON-RPC dispatch,
// `tools/call` validation and recording, and the agent tools (AgentSession). Worktree and task tools land with
// those features.
package rtok.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import rtok.config.Config
import rtok.json.StrictJson
import rtok.plugin.Runtime
import rtok.plugin.sdk.ToolDef
import rtok.plugins.Registry
import kotlin.concurrent.AtomicInt

/** A listed tool and the plugin its calls are recorded under. */
class Listed(
    val plugin: String,
    val def: ToolDef,
)

class Server(
    val cx: Runtime,
    val listed: List<Listed>,
    /** The agent this process serves (T283.1) and its registered cwd (T923). */
    val agents: AgentSession = AgentSession(cx),
) : AutoCloseable {
    /** `initialize` declared `capabilities.roots`. */
    private val rootsCapable = AtomicInt(0)

    fun allows(name: String) = listed.any { it.def.name == name }

    /**
     * One request line (or batch) to its answer line; `null` when nothing is owed. [stateless] is the HTTP surface:
     * `initialize` links no agent and asks for no roots, and no notification or client response is answered.
     */
    fun handleLine(
        line: String,
        stateless: Boolean = false,
    ): String? {
        if (line.isBlank()) return null
        val req = runCatching { StrictJson.parse(line) }.getOrNull() ?: return Protocol.rpcError(JsonNull, Protocol.PARSE_ERROR, "parse error").toString()
        if (req is JsonArray) {
            if (req.isEmpty()) return Protocol.rpcError(JsonNull, Protocol.INVALID_REQUEST, "empty batch").toString()
            val out = req.mapNotNull { handleValue(it, stateless) }
            return out.takeIf { it.isNotEmpty() }?.let { JsonArray(it).toString() }
        }
        return handleValue(req, stateless)?.toString()
    }

    private fun handleValue(
        req: JsonElement,
        stateless: Boolean,
    ): JsonObject? {
        val obj = req as? JsonObject ?: return Protocol.rpcError(JsonNull, Protocol.INVALID_REQUEST, "invalid request")
        val method = Protocol.str(obj, "method").orEmpty()
        val id = obj["id"]
        if (id == null || method.startsWith("notifications/")) return if (stateless) null else notification(method)
        if (method.isEmpty()) return if (stateless) null else response(id, obj)
        val params = obj["params"] as? JsonObject ?: JsonObject(emptyMap())
        val result =
            when (method) {
                "initialize" -> if (stateless) Protocol.serverInfo(Protocol.negotiateProtocolVersion(params["protocolVersion"]), rtok.VERSION) else initialize(params)
                "ping" -> JsonObject(emptyMap())
                "tools/list" -> buildJsonObject { put("tools", JsonArray(listed.map { Protocol.toTool(it.def) })) }
                "tools/call" -> callTool(Protocol.str(params, "name").orEmpty(), params["arguments"])
                else -> return methodNotFound(id, method)
            }
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }
    }

    private fun initialize(params: JsonObject): JsonObject {
        val roots = (params["capabilities"] as? JsonObject)?.get("roots")
        if (roots != null && roots !is JsonNull) rootsCapable.value = 1
        // T283.1: a first attempt now; `whoami` retries while hooks have not registered.
        agents.link()
        return Protocol.serverInfo(Protocol.negotiateProtocolVersion(params["protocolVersion"]), rtok.VERSION)
    }

    /** After `initialized` (or a roots change) a roots-capable client is asked for its roots. */
    private fun notification(method: String): JsonObject? =
        if (rootsCapable.value != 0 && (method == "notifications/initialized" || method == "notifications/roots/list_changed")) {
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", ROOTS_ID)
                put("method", "roots/list")
            }
        } else {
            null
        }

    private fun response(
        id: JsonElement,
        obj: JsonObject,
    ): JsonObject? {
        if ((id as? JsonPrimitive)?.content != ROOTS_ID) return Protocol.rpcError(id, Protocol.INVALID_REQUEST, "invalid request")
        (obj["result"] as? JsonObject)?.let(Roots::apply)
        return null
    }

    private fun methodNotFound(
        id: JsonElement,
        method: String,
    ) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put(
            "error",
            buildJsonObject {
                put("code", Protocol.METHOD_NOT_FOUND)
                put("message", "Method not found")
                put("data", buildJsonObject { put("method", method) })
            },
        )
    }

    /** `tools/call`: allow-list, required-field gate, invoke, record. */
    fun callTool(
        name: String,
        rawArgs: JsonElement?,
    ): JsonObject {
        val (text, ok) = run(name, rawArgs)
        if (!ok) cx.log("error", "mcp", name, "tool failed: $text")
        return Protocol.callResult(text, ok)
    }

    /** The validated, invoked and recorded call as `(text, ok)`; shared by `tools/call` and `--call`. */
    fun run(
        name: String,
        rawArgs: JsonElement?,
    ): Pair<String, Boolean> {
        val found = listed.firstOrNull { it.def.name == name }
        val args = Protocol.prepareArgs(name, rawArgs)
        val missing = found?.let { Protocol.missingRequired(it.def.inputSchema, args) }
        val answer =
            when {
                found == null -> Protocol.unknownTool(name).message.orEmpty() to false
                missing != null -> "invalid params: $missing" to false
                else -> Paths.invoke(cx, agents, name, args)
            }
        runCatching { Record.call(cx, found?.plugin ?: "archive", name, args, answer.first) }
        return answer
    }

    override fun close() = cx.close()

    companion object {
        const val ROOTS_ID = "rtok-roots"

        /** The tools every server lists, ahead of the plugins' own. */
        private val BUILTIN =
            listOf(Listed("archive", Tools.EXPAND), Listed("mcp", Tools.PING), Listed("mcp", AgentTools.WHOAMI)) +
                // T285: the worktree tools act for the linked agent; unlike `whoami`, `[mcp] tools` can narrow them.
                WorktreeTools.DEFS.map { Listed("mcp", it) } +
                AgentTools.DEFS.map { Listed("mcp", it) } +
                // T441.6: the project's tasks, same calls as `rtok task …`.
                TaskTools.DEFS.map { Listed("mcp", it) }

        /** Opens one session per process: `read_cache` rows are keyed by session and never expire. */
        fun open(
            cfg: Config,
            session: String,
        ): Server {
            val cx = Runtime.open(cfg, session)
            rtok.plugins.read.Roots
                .enable()
            return Server(cx, listedFor(cfg))
        }

        /** Built-ins, then every enabled built-in plugin's tools (first name wins), then the `[mcp] tools` and worktree filters. */
        fun listedFor(cfg: Config): List<Listed> {
            val listed = BUILTIN.toMutableList()
            val builtin =
                rtok.plugins
                    .all()
                    .map { it.manifest().id }
                    .toSet()
            for (p in Registry.new(cfg).enabled()) {
                val id = p.manifest().id
                if (id !in builtin) continue
                for (def in p.mcpTools()) if (listed.none { it.def.name == def.name }) listed += Listed(id, def)
            }
            if (!cfg.worktree.enabled) listed.removeAll { it.def.name.startsWith("worktree_") }
            val only = cfg.mcp.tools
            if (only.isNotEmpty()) listed.retainAll { it.def.name in ALWAYS || it.def.name in only }
            return listed
        }

        private val ALWAYS = setOf("expand", "ping", "whoami")
    }
}
