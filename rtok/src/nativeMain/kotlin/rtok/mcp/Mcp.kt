// `rtok mcp` entry points (mcp.rs `run`, `call`): serve stdio until EOF, or answer one call.
package rtok.mcp

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.serialization.json.JsonElement
import platform.posix.getpid
import rtok.config.Config
import rtok.plugins.graph.LspSession
import rtok.plugins.graph.Watch
import kotlin.concurrent.AtomicInt
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker

object Mcp {
    /** Standard input as a [ByteSource]. */
    @OptIn(ExperimentalForeignApi::class)
    val STDIN =
        ByteSource { buf ->
            buf.usePinned { platform.posix.read(0, it.addressOf(0), buf.size.convert()).toInt() }
        }

    @OptIn(ExperimentalForeignApi::class)
    private fun session() = "mcp-${getpid()}"

    /**
     * Serve MCP on [input] until EOF, writing each answer line to [out]. Any `[plugins.graph] watch`
     * value but `off` watches the cwd's project scope; the language server is stopped on exit.
     */
    @OptIn(ObsoleteWorkersApi::class)
    fun run(
        cfg: Config,
        input: ByteSource = STDIN,
        out: (String) -> Unit = { println(it) },
    ) {
        Server.open(cfg, session()).use { server ->
            val stop = AtomicInt(0)
            val watcher =
                if (server.cx.config.plugins.graph.watch != "off") {
                    Worker.start(name = "mcp-watch").also { w ->
                        w.execute(TransferMode.SAFE, { Triple(server.cx, rtok.sys.cwd(), stop) }) { (cx, root, s) -> Watch.runScope(cx, root, s) }
                    }
                } else {
                    null
                }
            try {
                val lines = LineReader(input)
                while (true) {
                    val line = lines.next() ?: break
                    server.handleLine(line)?.let(out)
                }
            } finally {
                stop.value = 1
                watcher?.requestTermination()?.result
                LspSession.shutdown()
            }
        }
    }

    /** One-shot `tools/call` for hosts that cannot speak MCP (`rtok mcp --call`); stops the language server on every exit. */
    fun call(
        cfg: Config,
        name: String,
        args: JsonElement?,
    ): String =
        try {
            Server.open(cfg, session()).use { server ->
                if (!server.allows(name)) throw Protocol.unknownTool(name)
                val (text, ok) = server.run(name, args)
                if (!ok) throw ToolException(text)
                text
            }
        } finally {
            LspSession.shutdown()
        }
}
