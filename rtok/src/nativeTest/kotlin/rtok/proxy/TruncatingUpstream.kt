// Port of `t323_truncating_upstream` in tests/proxy.rs: a raw-socket upstream that promises a long JSON body, sends a
// prefix and hangs up. An HTTP server cannot be told to break its own framing, so this one speaks HTTP/1.1 by hand.
@file:OptIn(ExperimentalForeignApi::class, ObsoleteWorkersApi::class)
@file:Suppress("MagicNumber")

package rtok.proxy

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.INADDR_LOOPBACK
import platform.posix.SHUT_RDWR
import platform.posix.SOCK_STREAM
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.read
import platform.posix.shutdown
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import platform.posix.write
import kotlin.concurrent.AtomicInt
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker

private const val BUF = 4096
private const val BACKLOG = 8

private fun be16(v: Int) = (((v and 0xff) shl 8) or ((v shr 8) and 0xff)).toUShort()

private fun be32(v: UInt) = ((v and 0xffu) shl 24) or ((v and 0xff00u) shl 8) or ((v shr 8) and 0xff00u) or (v shr 24)

/** Reads one request (head plus `content-length` body) from [fd]. */
private fun drain(fd: Int) {
    val req = ArrayList<Byte>()
    val chunk = ByteArray(BUF)
    while (true) {
        val n = read(fd, chunk.refTo(0), BUF.convert()).toInt()
        if (n <= 0) return
        for (i in 0 until n) req += chunk[i]
        val text = req.toByteArray().decodeToString()
        val at = text.indexOf("\r\n\r\n")
        if (at < 0) continue
        val want =
            text
                .substring(0, at)
                .lowercase()
                .lines()
                .firstNotNullOfOrNull {
                    it
                        .removePrefix("content-length:")
                        .takeIf { v -> v != it }
                        ?.trim()
                        ?.toIntOrNull()
                } ?: 0
        if (req.size >= at + 4 + want) return
    }
}

private fun serveLoop(
    listener: Int,
    reply: ByteArray,
    hits: AtomicInt,
) {
    while (true) {
        val fd = accept(listener, null, null)
        if (fd < 0) return
        drain(fd)
        hits.incrementAndGet()
        write(fd, reply.refTo(0), reply.size.convert())
        shutdown(fd, SHUT_RDWR)
        close(fd)
    }
}

/** `127.0.0.1:<free>`; [hits] counts the requests that reached it. */
class TruncatingUpstream(
    full: ByteArray,
    keep: Int,
) : AutoCloseable {
    val hits = AtomicInt(0)
    private val listener = socket(AF_INET, SOCK_STREAM, 0)
    private val worker = Worker.start(name = "truncating-upstream")
    val url: String

    init {
        val port =
            memScoped {
                val addr = alloc<sockaddr_in>()
                addr.sin_family = AF_INET.convert()
                addr.sin_port = be16(0)
                addr.sin_addr.s_addr = be32(INADDR_LOOPBACK.convert())
                check(bind(listener, addr.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert()) == 0) { "bind" }
                check(listen(listener, BACKLOG) == 0) { "listen" }
                val len = alloc<socklen_tVar>()
                len.value = sizeOf<sockaddr_in>().toUInt()
                getsockname(listener, addr.ptr.reinterpret(), len.ptr)
                be16(addr.sin_port.toInt()).toInt()
            }
        url = "http://127.0.0.1:$port"
        val head = "HTTP/1.1 200 OK\r\ncontent-type: application/json\r\ncontent-length: ${full.size}\r\nconnection: close\r\n\r\n"
        val reply = head.encodeToByteArray() + full.copyOf(keep)
        worker.execute(TransferMode.SAFE, { Triple(listener, reply, hits) }) { (l, r, h) -> serveLoop(l, r, h) }
    }

    override fun close() {
        shutdown(listener, SHUT_RDWR)
        close(listener)
        worker.requestTermination()
    }
}
