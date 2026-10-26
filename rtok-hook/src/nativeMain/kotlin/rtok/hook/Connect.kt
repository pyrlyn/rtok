// Unix-socket half of crates/rtok-hook (`connect`, `exchange`).
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package rtok.hook

import kotlinx.cinterop.*
import platform.posix.*

/** Connected socket fd, or null when the resident is not listening. */
@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
fun connect(endpoint: String): Int? = memScoped {
    val bytes = endpoint.encodeToByteArray(); if (bytes.size >= 104) return null
    val fd = socket(AF_UNIX, SOCK_STREAM, 0); if (fd < 0) return null
    // struct sockaddr_un laid out by hand (not exposed by the posix klib):
    // Darwin: u8 sun_len, u8 sun_family, char[104]; Linux: u16 sun_family, char[108].
    val size = 110
    val addr = allocArray<ByteVar>(size); for (i in 0 until size) addr[i] = 0
    if (Platform.osFamily == OsFamily.MACOSX) { addr[0] = size.toByte(); addr[1] = AF_UNIX.toByte() }
    else { addr[0] = AF_UNIX.toByte(); addr[1] = 0 }
    bytes.forEachIndexed { i, b -> addr[2 + i] = b }
    if (connect(fd, addr.reinterpret(), (2 + bytes.size + 1).convert()) != 0) { close(fd); return null }
    fd
}

fun writeAll(fd: Int, data: ByteArray): Boolean {
    var off = 0
    data.usePinned { p -> while (off < data.size) { val n = write(fd, p.addressOf(off), (data.size - off).convert()); if (n <= 0) return false; off += n.toInt() } }
    return true
}

fun readExact(fd: Int, n: Int): ByteArray {
    val out = ByteArray(n); var off = 0
    if (n == 0) return out
    out.usePinned { p -> while (off < n) { val r = read(fd, p.addressOf(off), (n - off).convert()); if (r <= 0) throw FrameException("eof"); off += r.toInt() } }
    return out
}

/** Sends a request frame and returns the decoded response (null on any failure). */
fun exchange(fd: Int, frame: ByteArray): Result<ByteArray?>? {
    if (!writeAll(fd, frame)) return null
    return runCatching { decodeResponse(readFrame { readExact(fd, it) }) }.getOrNull()
}
