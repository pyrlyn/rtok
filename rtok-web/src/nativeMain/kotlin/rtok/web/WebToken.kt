// Port of src/web/token.rs (S2): the `/ws` access token. `rtok web` issues a fresh random token on every
// start and writes it to `<RTOK_HOME>/web-token`, readable by the owning user only.
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package rtok.web

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.errno
import platform.posix.open
import platform.posix.rename
import platform.posix.write
import rtok.sys.IoException
import rtok.sys.createDirAll
import rtok.sys.join
import rtok.sys.pid
import rtok.sys.readPrefix
import rtok.sys.readToString
import rtok.sys.removeFile

object WebToken {
    /** File name of the token inside the rtok home. */
    const val TOKEN_FILE = "web-token"

    private const val TOKEN_BYTES = 32L
    private const val PRIVATE_MODE = 0x180 // 0o600
    private const val BYTE_MASK = 0xff
    private const val HEX = 16

    /** Where the server of [home] keeps its token. */
    fun path(home: String): String = join(home, TOKEN_FILE)

    /** 256 bits from the OS CSPRNG, as 64 lower-case hex chars. */
    fun generate(): String {
        val raw = readPrefix("/dev/urandom", TOKEN_BYTES)
        if (raw == null || raw.size.toLong() != TOKEN_BYTES) throw IoException(IoException.Kind.Other, "/dev/urandom: short read")
        return raw.joinToString("") { (it.toInt() and BYTE_MASK).toString(HEX).padStart(2, '0') }
    }

    /**
     * Generate a new token and store it for [home], replacing the previous one. The file is created 0600
     * (never world-readable) and swapped in with a rename, so a reader sees the old token or the new one.
     */
    fun issue(home: String): String {
        createDirAll(home)
        val token = generate()
        val target = path(home)
        val tmp = join(home, "$TOKEN_FILE.${pid()}.tmp")
        runCatching { removeFile(tmp) }
        try {
            writePrivate(tmp, token.encodeToByteArray())
            if (rename(tmp, target) != 0) throw IoException(IoException.Kind.Other, "rename $tmp: errno $errno")
        } catch (e: IoException) {
            runCatching { removeFile(tmp) }
            throw IoException(e.kind, "write $target: ${e.message}").also { it.addSuppressed(e) }
        }
        return token
    }

    /** The token a running server of [home] issued; what a local client sends. */
    fun read(home: String): String {
        val p = path(home)
        return try {
            readToString(p).trim()
        } catch (e: IoException) {
            throw IoException(e.kind, "read $p (is `rtok web` running?): ${e.message}").also { it.addSuppressed(e) }
        }
    }

    /** Constant-time equality, so the comparison does not leak how much of a guess matched. */
    fun matches(
        expected: String,
        presented: String,
    ): Boolean {
        val a = expected.encodeToByteArray()
        val b = presented.encodeToByteArray()
        var diff = a.size xor b.size
        for (i in a.indices) diff = diff or (a[i].toInt() xor (if (i < b.size) b[i].toInt() else 0))
        return diff == 0
    }

    /**
     * The token a client presented: `Authorization: Bearer <token>`, or `?token=<token>` for a browser,
     * whose WebSocket API cannot set headers. A non-bearer Authorization header presents nothing.
     */
    fun presented(
        authorization: String?,
        query: String?,
    ): String? {
        if (authorization == null) {
            return query?.split('&')?.firstNotNullOfOrNull { kv -> kv.removePrefix("token=").takeIf { it.length != kv.length } }
        }
        val scheme = authorization.substringBefore(' ', missingDelimiterValue = "")
        return if (scheme.equals("bearer", ignoreCase = true)) authorization.substringAfter(' ').trim() else null
    }

    private fun writePrivate(
        path: String,
        bytes: ByteArray,
    ) {
        val fd = open(path, O_WRONLY or O_CREAT or O_EXCL, PRIVATE_MODE)
        if (fd < 0) throw IoException(IoException.Kind.Other, "$path: errno $errno")
        try {
            val n = bytes.usePinned { write(fd, it.addressOf(0), bytes.size.convert()) }
            if (n.toLong() != bytes.size.toLong()) throw IoException(IoException.Kind.Other, "$path: short write")
        } finally {
            close(fd)
        }
    }
}
