// Port of src/doctor.rs `mcp_roundtrip`: spawn a configured stdio MCP server, send `initialize`,
// `notifications/initialized`, `tools/list` and an optional call, and collect the JSON replies until the call's
// answer (id 3), the tool list, end of output or the deadline.
package rtok.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.sys.PipedChild
import kotlin.time.Duration
import kotlin.time.TimeSource

object Roundtrip {
    private const val HANDSHAKE =
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"rtok","version":"0.1.0"}}}""" +
            "\n" + """{"jsonrpc":"2.0","method":"notifications/initialized"}""" +
            "\n" + """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""" + "\n"

    /** The id the probe's own call carries. */
    const val CALL_ID = 3L

    /** The replies in order, or why the server could not answer in time. */
    fun run(
        command: String,
        args: List<String>,
        env: Map<String, String>,
        call: String?,
        timeout: Duration,
    ): Result<List<JsonElement>> =
        runCatching {
            if (command.isEmpty()) throw RoundtripError("server failed to start: empty command")
            val child =
                runCatching { PipedChild.spawn(command, args, extraEnv = env) }
                    .getOrElse { throw RoundtripError("server failed to start: ${it.message}") }
            child.send((HANDSHAKE + call?.let { "$it\n" }.orEmpty()).encodeToByteArray())
            try {
                collect(child, TimeSource.Monotonic.markNow() + timeout, call != null)
                    ?: throw RoundtripError("timed out after ${timeout.inWholeSeconds}s")
            } finally {
                child.finishInput()
                child.kill()
            }
        }

    /** JSON lines until the last one wanted or end of output; null at the deadline. */
    private fun collect(
        child: PipedChild,
        deadline: TimeSource.Monotonic.ValueTimeMark,
        withCall: Boolean,
    ): List<JsonElement>? {
        val out = mutableListOf<JsonElement>()
        val pending = StringBuilder()
        var finished = false
        while (!finished) {
            val bytes = child.read(deadline) ?: return null
            pending.append(bytes.decodeToString())
            finished = bytes.isEmpty() || takeLines(pending).any { v -> out.add(v).let { done(v, withCall) } }
        }
        return out
    }

    /** Complete lines off the front of [pending] that parse as JSON, lazily so a finished reply stops the scan. */
    private fun takeLines(pending: StringBuilder): Sequence<JsonElement> =
        generateSequence {
            val nl = pending.indexOf('\n')
            if (nl < 0) {
                null
            } else {
                val line = pending.substring(0, nl)
                pending.deleteRange(0, nl + 1)
                line
            }
        }.mapNotNull { runCatching { Json.parseToJsonElement(it) }.getOrNull() }

    private fun done(
        v: JsonElement,
        withCall: Boolean,
    ): Boolean = if (withCall) isCallReply(v) else ((v as? JsonObject)?.get("result") as? JsonObject)?.containsKey("tools") == true

    /** A reply whose id is [CALL_ID], as a number or a string. */
    fun isCallReply(v: JsonElement): Boolean {
        val id = (v as? JsonObject)?.get("id") as? JsonPrimitive ?: return false
        return id.content == CALL_ID.toString()
    }
}

class RoundtripError(
    message: String,
) : Exception(message)
