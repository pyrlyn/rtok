// Unix-socket half of crates/rtok-hook (`connect`, `exchange`).
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package rtok.hook

import kotlinx.cinterop.*
import platform.posix.*

/** Connected socket fd, or null when the resident is not listening. */
fun connect(endpoint: String): Int? =
    withSockaddr(endpoint) { addr, len ->
        val fd = socket(AF_UNIX, SOCK_STREAM, 0)
        if (fd < 0) return@withSockaddr null
        if (connect(fd, addr, len) != 0) {
            close(fd)
            null
        } else {
            fd
        }
    }

/** A listening Unix socket bound at `path` (backlog 64), or null. */
fun bindUnix(path: String): Int? =
    withSockaddr(path) { addr, len ->
        val fd = socket(AF_UNIX, SOCK_STREAM, 0)
        if (fd < 0) return@withSockaddr null
        if (bind(fd, addr, len) != 0 || listen(fd, 64) != 0) {
            close(fd)
            null
        } else {
            fd
        }
    }

/** struct sockaddr_un laid out by hand (not exposed by the posix klib):
 *  Darwin: u8 sun_len, u8 sun_family, char[104]; Linux: u16 sun_family, char[108]. */
@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
private fun <T> withSockaddr(
    path: String,
    use: (CPointer<sockaddr>, socklen_t) -> T?,
): T? =
    memScoped {
        val bytes = path.encodeToByteArray()
        if (bytes.size >= 104) return null
        val size = 110
        val addr = allocArray<ByteVar>(size)
        for (i in 0 until size) addr[i] = 0
        if (Platform.osFamily == OsFamily.MACOSX) {
            addr[0] = size.toByte()
            addr[1] = AF_UNIX.toByte()
        } else {
            addr[0] = AF_UNIX.toByte()
            addr[1] = 0
        }
        bytes.forEachIndexed { i, b -> addr[2 + i] = b }
        use(addr.reinterpret(), (2 + bytes.size + 1).convert())
    }

fun writeAll(
    fd: Int,
    data: ByteArray,
): Boolean {
    var off = 0
    data.usePinned { p ->
        while (off < data.size) {
            val n = write(fd, p.addressOf(off), (data.size - off).convert())
            if (n <= 0) return false
            off += n.toInt()
        }
    }
    return true
}

fun readExact(
    fd: Int,
    n: Int,
): ByteArray {
    val out = ByteArray(n)
    var off = 0
    if (n == 0) return out
    out.usePinned { p ->
        while (off < n) {
            val r = read(fd, p.addressOf(off), (n - off).convert())
            if (r <= 0) throw FrameException("eof")
            off += r.toInt()
        }
    }
    return out
}

/** Sends a request frame and returns the decoded response (null on any failure). */
fun exchange(
    fd: Int,
    frame: ByteArray,
): Result<ByteArray?>? {
    if (!writeAll(fd, frame)) return null
    return runCatching { decodeResponse(readFrame { readExact(fd, it) }) }.getOrNull()
}
