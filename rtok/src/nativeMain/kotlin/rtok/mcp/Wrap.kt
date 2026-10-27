// `rtok mcp -- <server argv>` (mcp/wrap.rs, plan T59.4): a lossless wrapper around a foreign stdio MCP server.
// Every frame is forwarded as the peer wrote it, except the response to a `tools/call` whose text blocks run past
// the `[mcp]` cmd rule: each long block is archived raw, cut by the cmd rules and closed with the `expand` trailer
// `rtok run` prints. Anything else passes through untouched, and a store that fails to open only turns the
// wrapper into a plain pipe (fail open, D4).
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
        val child = PipedChild.spawn(bin, argv.drop(1), inheritStderr = true)
        val runtime = runCatching { Runtime.open(cfg, "mcp-wrap-${pid()}") }.getOrNull()
        val pipe = Pipe(child, runtime, Settings.fromConfig(cfg), bin.substringAfterLast('/').substringBeforeLast('.').ifEmpty { "mcp" })
        try {
            pipe.pump()
        } finally {
            child.finishInput()
            runtime?.close()
        }
        val code = child.waitExit()
        return if (code < 0) 1 else code
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun pid() = getpid()

    /** One wrapped connection: client frames go to [child] verbatim, its answers come back maybe shortened. */
    private class Pipe(
        private val child: PipedChild,
        private val runtime: Runtime?,
        private val settings: Settings,
        private val server: String,
    ) {
        private val pending = Pending()
        private val up = FrameReader()
        private val down = FrameReader()
        private val buf = ByteArray(READ_CHUNK)
        private var stdinOpen = true

        /** Runs until the server closes its stdout or our stdout goes away. */
        fun pump() {
            var open = true
            while (open) {
                val (fromServer, fromClient) = child.awaitReadable(if (stdinOpen) 0 else -1)
                if (fromClient) fromClient()
                if (fromServer) open = fromServer()
            }
        }

        /** Client to server: forward verbatim and note which ids are `tools/call`; our EOF closes the child's stdin. */
        @OptIn(ExperimentalForeignApi::class)
        private fun fromClient() {
            val n = buf.usePinned { platform.posix.read(0, it.addressOf(0), buf.size.convert()).toInt() }
            if (n > 0) up.feed(buf, n)
            val eof = n <= 0
            generateSequence { up.next(eof) }.forEach { f ->
                parse(f.body)?.let(pending::note)
                if (!child.send(FrameReader.encode(f.framing, f.body))) stdinOpen = false
            }
            if (eof) {
                stdinOpen = false
                child.finishInput()
            }
        }

        /** Server to client; false once the server's stdout ended or ours broke. */
        private fun fromServer(): Boolean {
            val bytes = child.read(TimeSource.Monotonic.markNow() + 1.seconds) ?: return true
            val eof = bytes.isEmpty()
            if (!eof) down.feed(bytes)
            val written =
                generateSequence { down.next(eof) }.all { f ->
                    // Always drain the pending id, even with no store (D4: no leak for the connection's life).
                    val tool = pending.take(f.body)
                    val short = if (runtime != null && tool != null) shorten(runtime, settings, server, tool, f.body) else null
                    writeOut(FrameReader.encode(f.framing, short ?: f.body))
                }
            return written && !eof
        }
    }

    private fun writeOut(bytes: ByteArray): Boolean =
        runCatching {
            writeFd(1, bytes)
            true
        }.getOrDefault(false)

    private fun parse(frame: ByteArray): JsonElement? = runCatching { StrictJson.parse(frame.decodeToString()) }.getOrNull()

    /** `tools/call` ids the client sent and the server has not answered, keyed by the id's JSON text (`1` ≠ `"1"`). */
    class Pending {
        private val calls = HashMap<String, String>()

        fun note(v: JsonElement) {
            val o = v as? JsonObject ?: return
            if ((o["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content != "tools/call") return
            val id = o["id"] ?: return
            val tool = ((o["params"] as? JsonObject)?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
            calls[id.toString()] = tool
        }

        /** The tool name when [frame] answers a noted call; the id is forgotten once a response carries it. */
        fun take(frame: ByteArray): String? {
            val o = parse(frame) as? JsonObject ?: return null
            // A server request or notification carries `method`; its id is the server's own namespace.
            if ("method" in o) return null
            val id = o["id"] ?: return null
            return calls.remove(id.toString())
        }
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
}
