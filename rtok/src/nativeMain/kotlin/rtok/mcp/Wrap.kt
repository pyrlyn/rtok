// `rtok mcp -- <server argv>` (mcp/wrap.rs, plan T59.4): a lossless wrapper around a foreign stdio MCP server.
// Every frame is forwarded as the peer wrote it, except the response to a `tools/call` whose text blocks run past
// the `[mcp]` cmd rule: each long block is archived raw, cut by the cmd rules and closed with the `expand` trailer
// `rtok run` prints. Anything else passes through untouched, and a store that fails to open only turns the
// wrapper into a plain pipe (fail open, D4). A server that dies under a connected client is restarted per
// `[mcp] restart_attempts` / `restart_interval_ms`.
package rtok.mcp

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import platform.posix.getpid
import rtok.config.Config
import rtok.demon.OsProcs
import rtok.json.StrictJson
import rtok.plugin.Runtime
import rtok.plugin.sdk.Class
import rtok.plugin.sdk.Measurement
import rtok.plugins.cmd.Rule
import rtok.plugins.cmd.Rules
import rtok.plugins.cmd.Settings
import rtok.plugins.jsontree.foldJson
import rtok.shortId
import rtok.sys.PipedChild
import rtok.sys.rustLines
import rtok.sys.writeFd
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

object Wrap {
    private val json = Json

    /** Spawn [argv], pipe our stdio through it and return the server's exit code. */
    fun run(
        cfg: Config,
        argv: List<String>,
    ): Int {
        val bin = argv.firstOrNull() ?: throw IllegalArgumentException("rtok mcp: missing server command after `--`")
        // Our stderr stays the server's: its diagnostics are not part of the protocol stream.
        val spawn = { PipedChild.spawn(bin, argv.drop(1), inheritStderr = true) }
        val child = spawn()
        val runtime = runCatching { Runtime.open(cfg, "mcp-wrap-${pid()}") }.getOrNull()
        val server = bin.substringAfterLast('/').substringBeforeLast('.').ifEmpty { "mcp" }
        val pipe = Pipe(child, spawn, runtime, Settings.fromConfig(cfg), server, Restarts.of(cfg.mcp))
        val code =
            try {
                pipe.pump()
            } finally {
                runtime?.close()
            }
        return if (code < 0) 1 else code
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun pid() = getpid()

    /**
     * One wrapped connection: client frames go to [child] verbatim, its answers come back maybe shortened. A server
     * that dies while the client is still connected is started again and handed the client's handshake, so the
     * client keeps its session; only the requests the dead server owed fail.
     */
    private class Pipe(
        private var child: PipedChild,
        private val spawn: () -> PipedChild,
        private val runtime: Runtime?,
        private val settings: Settings,
        private val server: String,
        private val restarts: Restarts,
    ) {
        private val pending = Pending()
        private val up = FrameReader()
        private var down = FrameReader()
        private val buf = ByteArray(READ_CHUNK)
        private var stdinOpen = true
        private var stdoutOpen = true
        private var clientFraming = Framing.Line
        private var initialize: Frame? = null
        private var initialized: Frame? = null

        /** The id of a replayed `initialize`: its answer is ours, and client frames wait in [held] until it comes. */
        private var replayId: String? = null
        private val held = ArrayDeque<Pair<Frame, JsonElement?>>()

        /** Runs until the client hangs up, our stdout goes away or the server dies past its restarts; its exit code. */
        fun pump(): Int {
            while (true) {
                var open = true
                while (open) {
                    val (fromServer, fromClient) = child.awaitReadable(if (stdinOpen) 0 else -1)
                    if (fromClient) fromClient()
                    if (fromServer) open = fromServer()
                }
                child.finishInput()
                val code = child.waitExit()
                if (!stdinOpen || !stdoutOpen) return code
                failPending("MCP server $server exited with code $code")
                if (!stdoutOpen || !restart(code)) return code
            }
        }

        /** Client to server: forward verbatim and note the request ids; our EOF closes the child's stdin. */
        @OptIn(ExperimentalForeignApi::class)
        private fun fromClient() {
            val n = buf.usePinned { platform.posix.read(0, it.addressOf(0), buf.size.convert()).toInt() }
            if (n > 0) up.feed(buf, n)
            val eof = n <= 0
            generateSequence { up.next(eof) }.forEach { f ->
                if (f.framing != Framing.Raw) clientFraming = f.framing
                val msg = parse(f.body)
                when (method(msg)) {
                    "initialize" -> initialize = f
                    "notifications/initialized" -> initialized = f
                }
                if (replayId != null) held.addLast(f to msg) else forward(f, msg)
            }
            if (eof) {
                stdinOpen = false
                if (replayId == null) child.finishInput()
            }
        }

        /** A failed send needs no handling here: the server stopped reading, and its stdout EOF follows. */
        private fun forward(
            f: Frame,
            msg: JsonElement?,
        ) {
            msg?.let(pending::note)
            child.send(FrameReader.encode(f.framing, f.body))
        }

        /** Server to client; false once the server's stdout ended or ours broke. */
        private fun fromServer(): Boolean {
            val bytes = child.read(TimeSource.Monotonic.markNow() + 1.seconds) ?: return true
            val eof = bytes.isEmpty()
            if (!eof) down.feed(bytes)
            val written =
                generateSequence { down.next(eof) }.all { f ->
                    if (replayId == null) {
                        restarts.started()
                    } else if (answers(f.body, replayId)) {
                        endReplay()
                        return@all true
                    }
                    // Always drain the pending id, even with no store (D4: no leak for the connection's life).
                    val tool = pending.take(f.body)
                    val short = if (runtime != null && tool != null) shorten(runtime, settings, server, tool, f.body) else null
                    writeOut(FrameReader.encode(f.framing, short ?: f.body))
                }
            if (!written) stdoutOpen = false
            return written && !eof
        }

        /** Starts the server again after it exited with [code]; false once [restarts] are used up. */
        private fun restart(code: Int): Boolean {
            while (restarts.crashed()) {
                warn("$server exited with code $code; restarting in ${restarts.intervalMs} ms (${restarts.failures}/${restarts.attempts})")
                OsProcs.pause(restarts.intervalMs)
                child = runCatching(spawn).getOrNull() ?: continue
                down = FrameReader()
                replayId = null
                replay()
                return true
            }
            warn("$server exited with code $code; giving up after ${restarts.attempts} restarts")
            return false
        }

        /** Hands the new server the client's `initialize` under an id of ours; nothing to do before the client sent one. */
        private fun replay() {
            val f = initialize ?: return
            val o = parse(f.body) as? JsonObject ?: return
            val id = JsonPrimitive("rtok-restart-${restarts.failures}")
            replayId = id.toString()
            child.send(FrameReader.encode(f.framing, JsonObject(o + ("id" to id)).toString().encodeToByteArray()))
        }

        /** The new server is up: finish the handshake, then send what the client wrote meanwhile. */
        private fun endReplay() {
            replayId = null
            restarts.started()
            initialized?.let { child.send(FrameReader.encode(it.framing, it.body)) }
            while (held.isNotEmpty()) held.removeFirst().let { (f, msg) -> forward(f, msg) }
            if (!stdinOpen) child.finishInput()
        }

        /** Answers every request the dead server owed with an error, so the client is not left waiting. */
        private fun failPending(message: String) {
            for (id in pending.drain()) {
                val error = JsonObject(mapOf("code" to JsonPrimitive(INTERNAL_ERROR), "message" to JsonPrimitive(message)))
                val frame = JsonObject(mapOf("jsonrpc" to JsonPrimitive("2.0"), "id" to id, "error" to error))
                if (!writeOut(FrameReader.encode(clientFraming, frame.toString().encodeToByteArray()))) stdoutOpen = false
            }
        }

        /** True when [frame] is a response (not a server request) carrying [id], given as its JSON text. */
        private fun answers(
            frame: ByteArray,
            id: String?,
        ): Boolean {
            val o = parse(frame) as? JsonObject ?: return false
            return "method" !in o && o["id"]?.toString() == id
        }

        private fun warn(line: String) {
            runCatching { writeFd(2, "rtok mcp: $line\n".encodeToByteArray()) }
        }
    }

    private fun method(msg: JsonElement?): String? = ((msg as? JsonObject)?.get("method") as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun writeOut(bytes: ByteArray): Boolean =
        runCatching {
            writeFd(1, bytes)
            true
        }.getOrDefault(false)

    private fun parse(frame: ByteArray): JsonElement? = runCatching { StrictJson.parse(frame.decodeToString()) }.getOrNull()

    /**
     * Requests the client sent and the server has not answered, keyed by the id's JSON text (`1` ≠ `"1"`): the id
     * itself and, for a `tools/call`, the tool.
     */
    class Pending {
        private val calls = LinkedHashMap<String, Pair<JsonElement, String?>>()

        fun note(v: JsonElement) {
            val o = v as? JsonObject ?: return
            val method = method(o) ?: return
            val id = o["id"] ?: return
            val tool = if (method == "tools/call") ((o["params"] as? JsonObject)?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content else null
            calls[id.toString()] = id to tool
        }

        /** The tool name when [frame] answers a noted call; the id is forgotten once a response carries it. */
        fun take(frame: ByteArray): String? {
            val o = parse(frame) as? JsonObject ?: return null
            // A server request or notification carries `method`; its id is the server's own namespace.
            if ("method" in o) return null
            val id = o["id"] ?: return null
            return calls.remove(id.toString())?.second
        }

        /** Every unanswered id, forgotten: the server that owed the answers is gone. */
        fun drain(): List<JsonElement> = calls.values.map { it.first }.also { calls.clear() }
    }

    private fun shorten(
        cx: Runtime,
        settings: Settings,
        server: String,
        tool: String,
        frame: ByteArray,
    ): ByteArray? {
        val v = parse(frame) as? JsonObject ?: return null
        val result = v["result"] as? JsonObject ?: return null
        val cut = shortenResult(cx, settings, server, tool, result, "cmd", "wrap") ?: return null
        return JsonObject(v + ("result" to cut)).toString().encodeToByteArray()
    }

    /**
     * Cut `result.content[].text` blocks past the `[mcp]` cmd rule, recording [plugin]/[kind]; the new result, or
     * null when nothing changed (under the threshold, `isError`, or the archive failed).
     */
    fun shortenResult(
        cx: Runtime,
        settings: Settings,
        server: String,
        tool: String,
        result: JsonObject,
        plugin: String,
        kind: String,
    ): JsonObject? {
        if ((result["isError"] as? JsonPrimitive)?.booleanOrNull == true) return null
        val rule = settings.pick("mcp")
        val blocks = result["content"] as? JsonArray ?: return null
        var changed = false
        val out =
            blocks.map { block ->
                val text = ((block as? JsonObject)?.get("text") as? JsonPrimitive)?.takeIf { it.isString }?.content
                val printed = text?.let { cutBlock(cx, settings, rule, server, tool, it, plugin, kind) }
                if (printed == null) {
                    block
                } else {
                    changed = true
                    JsonObject(block.jsonObject + ("text" to JsonPrimitive(printed)))
                }
            }
        return if (changed) JsonObject(result + ("content" to JsonArray(out))) else null
    }

    private fun cutBlock(
        cx: Runtime,
        settings: Settings,
        rule: Rule,
        server: String,
        tool: String,
        text: String,
        plugin: String,
        kind: String,
    ): String? {
        val lines = text.rustLines().size
        if (lines.toUInt() <= rule.maxLines) return null
        val id = runCatching { cx.putArchive(text.encodeToByteArray()) }.getOrNull() ?: return null
        val shown = shortId(id)
        jsonTreeFold(cx, rule, tool, text, id)?.let { return it }
        val cut = Rules.apply(settings, text, 0, rule, shown)
        if (cut.utf8Len() >= text.utf8Len()) return null
        val printed = "$cut\n[rtok $shown · $lines lines · expand: rtok expand $shown]"
        runCatching {
            cx.record(
                Measurement(plugin, kind, text.utf8Len(), printed.utf8Len(), cx.estimate(text, Class.Code), cx.estimate(printed, Class.Code), "$server/$tool:$id"),
            )
        }
        return printed
    }

    /**
     * Fold one JSON value when `json_tree` is on, the tool is not `read` or `search`, and the fold fits the mcp
     * rule's line cap and is shorter than the original (already archived under [archiveId]); null keeps the cut.
     */
    private fun jsonTreeFold(
        cx: Runtime,
        rule: Rule,
        tool: String,
        text: String,
        archiveId: String,
    ): String? {
        if (!cx.config.plugins.jsonTree.enabled) return null
        if (tool.equals("read", ignoreCase = true) || tool.equals("search", ignoreCase = true)) return null
        val value = runCatching { StrictJson.parse(text) }.getOrNull() ?: return null
        val folded = foldJson(value) ?: return null
        if (folded.text
                .rustLines()
                .size
                .toUInt() > rule.maxLines
        ) {
            return null
        }
        if (folded.text.utf8Len() >= text.utf8Len()) return null
        val printed = "${folded.text.trimEnd()}\n[json-tree ${shortId(archiveId)}]"
        if (printed.utf8Len() >= text.utf8Len()) return null
        runCatching {
            cx.record(
                Measurement("json_tree", "fold", text.utf8Len(), printed.utf8Len(), cx.estimate(text, Class.Code), cx.estimate(printed, Class.Code), archiveId),
            )
        }
        return printed
    }

    private fun String.utf8Len() = encodeToByteArray().size.toULong()

    private const val READ_CHUNK = 64 * 1024

    /** JSON-RPC "Internal error". */
    private const val INTERNAL_ERROR = -32603
}
