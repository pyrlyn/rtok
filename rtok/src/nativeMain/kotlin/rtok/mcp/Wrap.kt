// `rtok mcp -- <server argv>` (mcp/wrap.rs, plan T59.4): a lossless wrapper around a foreign stdio MCP server.
// Every frame is forwarded as the peer wrote it, except the response to a `tools/call` whose text blocks run past
// the `[mcp]` cmd rule: each long block is archived raw, cut by the cmd rules and closed with the `expand` trailer
// `rtok run` prints. Anything else passes through untouched, and a store that fails to open only turns the
// wrapper into a plain pipe (fail open, D4). A server that dies under a connected client is restarted per
// `[mcp] restart_attempts` / `restart_interval_ms`.
package rtok.mcp

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import platform.posix.POLLIN
import platform.posix.getpid
import platform.posix.poll
import platform.posix.pollfd
import rtok.config.Config
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
import kotlin.time.Duration.Companion.milliseconds
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
     * client keeps its session; only the requests the dead server owed fail, and the ones it asked are cancelled.
     */
    private class Pipe(
        private var child: PipedChild,
        private val spawn: () -> PipedChild,
        private val runtime: Runtime?,
        private val settings: Settings,
        private val server: String,
        private val restarts: Restarts,
    ) {
        /** What the client asked the server, and what the server asked the client. */
        private val pending = Pending()
        private val asked = Pending()

        /** Ids of requests a dead server asked and the client has not answered: nobody waits for those answers. */
        private val orphans = HashSet<String>()
        private val up = FrameReader()
        private var down = FrameReader()
        private val buf = ByteArray(READ_CHUNK)
        private var stdinOpen = true
        private var stdoutOpen = true
        private var clientFraming = Framing.Line

        /** The handshake a server was sent, for the next one. */
        private var initialize: Frame? = null
        private var initialized: Frame? = null

        /** True from the server's death until its successor is ready: client frames wait in [held] meanwhile. */
        private var holding = false
        private val held = ArrayDeque<Pair<Frame, JsonElement?>>()

        /** The id of a replayed `initialize`: its answer is ours, not the client's. */
        private var replayId: String? = null

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
                // Frames the dead server never read keep its stdin open: writing them now fails, which closes it.
                child.finishInput()
                if (!stdinOpen || !stdoutOpen) return code
                settle("MCP server $server exited with code $code")
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
                if (f.framing == Framing.Line || f.framing == Framing.Header) clientFraming = f.framing
                val msg = parse(f.body)
                // An answer to a request only a dead server asked: its successor never asked, and may reuse the id.
                val stale = asked.take(msg) == null && idText(msg, request = false)?.let(orphans::remove) == true
                when {
                    stale -> Unit
                    holding -> held.addLast(f to msg)
                    else -> forward(f, msg)
                }
            }
            if (eof) {
                stdinOpen = false
                if (!holding) child.finishInput()
            }
        }

        /** A failed send needs no handling here: the server stopped reading, and its stdout EOF follows. */
        private fun forward(
            f: Frame,
            msg: JsonElement?,
        ) {
            // Noted here, not on arrival: a held frame is still on its way, and replaying it too would send it twice.
            when (method(msg)) {
                "initialize" -> initialize = f
                "notifications/initialized" -> initialized = f
            }
            pending.note(msg)
            child.send(FrameReader.encode(f.framing, f.body))
        }

        /** Server to client; false once the server's stdout ended or ours broke. */
        private fun fromServer(): Boolean {
            val bytes = child.read(TimeSource.Monotonic.markNow() + 1.seconds) ?: return true
            val eof = bytes.isEmpty()
            if (!eof) down.feed(bytes)
            val written =
                generateSequence { down.next(eof) }.all { f ->
                    // A frame the server died inside is no use to a client that stays on, and that client would
                    // read our next frames as the rest of its body.
                    if (f.framing == Framing.Torn && stdinOpen) return@all true
                    val msg = parse(f.body)
                    if (replayId != null && idText(msg, request = false) == replayId) {
                        endReplay()
                        return@all true
                    }
                    // This server's own request: the id is no longer a dead server's.
                    idText(msg, request = true)?.let(orphans::remove)
                    asked.note(msg)
                    // Always drain the pending id, even with no store (D4: no leak for the connection's life).
                    val call = pending.take(msg)
                    // Only an answer the client asked for shows the server works: one that dies right after its
                    // handshake, every time, must still run out of attempts.
                    if (call != null) restarts.answered()
                    val tool = call?.tool
                    val short = if (runtime != null && tool != null) shorten(runtime, settings, server, tool, f.body) else null
                    writeOut(FrameReader.encode(f.framing, short ?: f.body))
                }
            if (!written) stdoutOpen = false
            return written && !eof
        }

        /**
         * Starts the server again after it exited with [code]; false once [restarts] are used up or the client
         * hung up during the wait.
         */
        private fun restart(code: Int): Boolean {
            holding = true
            replayId = null
            var why = "exited with code $code"
            var next: PipedChild? = null
            while (next == null && stdinOpen && restarts.crashed()) {
                warn("$server $why; restarting in ${restarts.intervalMs} ms (${restarts.failures}/${restarts.attempts})")
                if (!awaitRestart()) break
                next =
                    runCatching(spawn).getOrElse {
                        why = "did not start (${it.message})"
                        null
                    }
            }
            if (next == null) {
                // 0 attempts is the plain wrapper: the server's exit ends the connection, with nothing to report.
                if (stdinOpen && restarts.attempts > 0) warn("$server $why; giving up after ${restarts.attempts} restarts")
                return false
            }
            child = next
            down = FrameReader()
            replay()
            return true
        }

        /**
         * Waits out the restart interval reading the client, so its frames are held rather than left in a pipe
         * that fills up, and its hang-up ends the wait at once; false once the client hung up.
         */
        private fun awaitRestart(): Boolean {
            val until = TimeSource.Monotonic.markNow() + restarts.intervalMs.milliseconds
            while (stdinOpen && until.hasNotPassedNow()) {
                val left = (-until.elapsedNow()).inWholeMilliseconds.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
                if (readable(0, left)) fromClient()
            }
            return stdinOpen
        }

        /**
         * Hands the new server the `initialize` its predecessor got, under an id of ours; before the client sent
         * one there is no session to restore, and its frames go straight on.
         */
        private fun replay() {
            val f = initialize
            val o = f?.let { parse(it.body) } as? JsonObject
            if (f == null || o == null) return release()
            val id = JsonPrimitive("rtok-restart-${restarts.failures}")
            replayId = id.toString()
            child.send(FrameReader.encode(f.framing, JsonObject(o + ("id" to id)).toString().encodeToByteArray()))
        }

        /** The new server answered `initialize`: finish the handshake, then [release]. */
        private fun endReplay() {
            replayId = null
            initialized?.let { child.send(FrameReader.encode(it.framing, it.body)) }
            release()
        }

        /** Sends on what the client wrote while no server could take it. */
        private fun release() {
            holding = false
            while (held.isNotEmpty()) held.removeFirst().let { (f, msg) -> forward(f, msg) }
            if (!stdinOpen) child.finishInput()
        }

        /**
         * Closes what the dead server left open with the client: an error for every request it owed an answer, so
         * the client is not left waiting, and a cancellation for every request it asked, so the client stops
         * working on an answer nobody reads.
         */
        private fun settle(message: String) {
            val reason = JsonPrimitive(message)
            val error = JsonObject(mapOf("code" to JsonPrimitive(INTERNAL_ERROR), "message" to reason))
            val asks = asked.drain()
            orphans += asks.map { it.toString() }
            val frames =
                pending.drain().map { rpc("id" to it, "error" to error) } +
                    asks.map { rpc("method" to JsonPrimitive(CANCELLED), "params" to JsonObject(mapOf("requestId" to it, "reason" to reason))) }
            for (frame in frames) {
                if (!writeOut(FrameReader.encode(clientFraming, frame.toString().encodeToByteArray()))) stdoutOpen = false
            }
        }
    }

    private fun method(msg: JsonElement?): String? = ((msg as? JsonObject)?.get("method") as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun writeOut(bytes: ByteArray): Boolean =
        runCatching {
            writeFd(1, bytes)
            true
        }.getOrDefault(false)

    /**
     * Requests one peer sent and the other has not answered, keyed by the id's JSON text (`1` ≠ `"1"`).
     */
    class Pending {
        /** One unanswered request: its [id] and, for a `tools/call`, the [tool]. */
        class Call(
            val id: JsonElement,
            val tool: String?,
        )

        private val calls = LinkedHashMap<String, Call>()

        fun note(v: JsonElement?) {
            val o = v as? JsonObject ?: return
            val method = method(o) ?: return
            val params = o["params"] as? JsonObject
            if (method == CANCELLED) {
                // The sender gave up on that request and the peer need not answer it: nothing is owed any more.
                params?.get("requestId")?.let { calls.remove(it.toString()) }
                return
            }
            val id = o["id"] ?: return
            val tool = if (method == "tools/call") (params?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content else null
            calls[id.toString()] = Call(id, tool)
        }

        /** The noted request [msg] answers; the id is forgotten once a response carries it. */
        fun take(msg: JsonElement?): Call? = idText(msg, request = false)?.let(calls::remove)

        /** Every unanswered id, forgotten: the peer that owed the answers is gone. */
        fun drain(): List<JsonElement> = calls.values.map { it.id }.also { calls.clear() }
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
    private const val CANCELLED = "notifications/cancelled"
}

private fun parse(frame: ByteArray): JsonElement? = runCatching { StrictJson.parse(frame.decodeToString()) }.getOrNull()

/**
 * The JSON text of [msg]'s id when it is a request ([request]) or a response (not [request]). Both carry an id,
 * each in its sender's own namespace; only a request carries `method`.
 */
private fun idText(
    msg: JsonElement?,
    request: Boolean,
): String? = (msg as? JsonObject)?.takeIf { ("method" in it) == request }?.get("id")?.toString()

private fun rpc(vararg fields: Pair<String, JsonElement>) = JsonObject(mapOf("jsonrpc" to JsonPrimitive("2.0"), *fields))

/** True once [fd] has bytes or hung up, false after [timeoutMs] with neither. */
@OptIn(ExperimentalForeignApi::class)
private fun readable(
    fd: Int,
    timeoutMs: Int,
): Boolean =
    memScoped {
        val p = alloc<pollfd>()
        p.fd = fd
        p.events = POLLIN.toShort()
        poll(p.ptr, 1u, timeoutMs) > 0
    }

private fun warn(line: String) {
    runCatching { writeFd(2, "rtok mcp: $line\n".encodeToByteArray()) }
}
