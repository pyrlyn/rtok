// Port of crates/rtok-hook/src/lib.rs — the thin hook client ↔ resident `rtok` wire protocol
// (length-prefixed little-endian frames), env fingerprint and home/tilde expansion.
// Byte-compatible with Rust rtok: a Kotlin client can talk to a Rust resident and vice versa.
package rtok.hook

const val MAX_FRAME: Int = 64 shl 20
const val DEADLINE_MS: Long = 3000
const val CHILD_GRACE_MS: Long = 500
const val HOOK_MAX_INPUT_BYTES: Int = 8 shl 20
val HOST_VARS = listOf("GROK_HOOK_EVENT", "DEVIN_PROJECT_DIR")
private val CONFIG_VARS = listOf("HOME", "USERPROFILE", "OTEL_EXPORTER_OTLP_ENDPOINT", "OTEL_EXPORTER_OTLP_HEADERS")

/** `RTOK_HOOK_DEADLINE_MS` or 3000 ms. */
fun deadline(v: (String) -> String?): Long = v("RTOK_HOOK_DEADLINE_MS")?.trim()?.toULongOrNull()?.toLong() ?: DEADLINE_MS

class Request(
    val version: String,
    val fingerprint: ULong,
    val event: String,
    val host: String,
    val cwd: String,
    val stdin: ByteArray,
    val pid: UInt? = null,
) {
    fun copy(pid: UInt?) = Request(version, fingerprint, event, host, cwd, stdin, pid)

    override fun equals(other: Any?) =
        other is Request && version == other.version && fingerprint == other.fingerprint &&
            event == other.event && host == other.host && cwd == other.cwd && stdin.contentEquals(other.stdin) && pid == other.pid

    override fun hashCode() = version.hashCode() * 31 + event.hashCode()

    override fun toString() = "Request($version,$fingerprint,$event,$host,$cwd,${stdin.size}B,$pid)"

    fun encode(): ByteArray {
        val fields =
            mutableListOf(
                version.encodeToByteArray(),
                le64(fingerprint),
                event.encodeToByteArray(),
                host.encodeToByteArray(),
                cwd.encodeToByteArray(),
                stdin,
            )
        pid?.let { fields += le32(it) }
        val body = ArrayList<Byte>()
        for (f in fields) {
            le32(f.size.toUInt()).forEach { body += it }
            f.forEach { body += it }
        }
        return frame(body.toByteArray())
    }

    companion object {
        fun decode(body0: ByteArray): Request? {
            val r = Reader(body0)

            fun text(b: ByteArray?) = b?.let { runCatching { it.decodeToString(throwOnInvalidSequence = true) }.getOrNull() }
            val version = text(r.take()) ?: return null
            val fp = r.take()?.takeIf { it.size == 8 } ?: return null
            val event = text(r.take()) ?: return null
            val host = text(r.take()) ?: return null
            val cwd = text(r.take()) ?: return null
            val stdin = r.take() ?: return null
            val pid = if (r.empty()) null else (r.take()?.takeIf { it.size == 4 } ?: return null).let { readLe32(it, 0) }
            return if (r.empty()) Request(version, fp.foldIndexed(0uL) { i, a, b -> a or ((b.toULong() and 0xffu) shl (8 * i)) }, event, host, cwd, stdin, pid) else null
        }
    }
}

private class Reader(
    val b: ByteArray,
) {
    var pos = 0

    fun empty() = pos == b.size

    fun take(): ByteArray? {
        if (b.size - pos < 4) return null
        val len = readLe32(b, pos).toLong()
        pos += 4
        if (b.size - pos < len) return null
        val out = b.copyOfRange(pos, pos + len.toInt())
        pos += len.toInt()
        return out
    }
}

fun encodeResponse(stdout: ByteArray?): ByteArray = frame(byteArrayOf(if (stdout == null) 1 else 0) + (stdout ?: ByteArray(0)))

/** Some(stdout) / Some(null)=pass through / null=malformed. */
fun decodeResponse(body: ByteArray): Result<ByteArray?>? =
    when {
        body.isEmpty() -> null
        body[0] == 0.toByte() -> Result.success(body.copyOfRange(1, body.size))
        body[0] == 1.toByte() && body.size == 1 -> Result.success(null)
        else -> null
    }

class FrameException(
    msg: String,
) : Exception(msg)

fun frameLen(header: ByteArray): Int {
    val len = readLe32(header, 0).toLong()
    if (len > MAX_FRAME) throw FrameException("frame too large")
    return len.toInt()
}

/** Reads one frame body with [readExact] (throws on EOF). */
fun readFrame(readExact: (Int) -> ByteArray): ByteArray = readExact(frameLen(readExact(4)))

/** FNV-1a over sorted `RTOK_*`, config and host vars. */
fun fingerprint(vars: Iterable<Pair<String, String>>): ULong {
    val kept =
        vars
            .filter { (k, _) -> k.startsWith("RTOK_") || k in CONFIG_VARS || k in HOST_VARS }
            .map { (k, v) -> k.encodeToByteArray() to v.encodeToByteArray() }
            .sortedWith { a, b -> cmpBytes(a.first, b.first).let { if (it != 0) it else cmpBytes(a.second, b.second) } }
    val bytes = ArrayList<Byte>()
    for ((k, v) in kept) {
        k.forEach { bytes += it }
        bytes += 0
        v.forEach { bytes += it }
        bytes += 0
    }
    return fnv(bytes.toByteArray())
}

private fun cmpBytes(
    a: ByteArray,
    b: ByteArray,
): Int {
    for (i in 0 until minOf(a.size, b.size)) {
        val c = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
        if (c != 0) return c
    }
    return a.size - b.size
}

fun isAbsolute(p: String) = p.startsWith("/")

fun home(v: (String) -> String?): String? {
    val user = userHomeFrom(v("HOME"), v("USERPROFILE"))
    return homeDirFrom(v("RTOK_HOME"), user).takeIf { isAbsolute(it) }
}

fun userHomeFrom(
    home: String?,
    userprofile: String?,
): String? = home?.takeIf { it.isNotEmpty() } ?: userprofile?.takeIf { it.isNotEmpty() }

private fun pjoin(
    a: String,
    b: String,
) = when {
    b.startsWith("/") -> b
    a.isEmpty() -> b
    a.endsWith("/") -> a + b
    else -> "$a/$b"
}

fun homeDirFrom(
    rtokHome: String?,
    userHome: String?,
): String {
    val default = pjoin(userHome ?: "", ".rtok")
    val h = rtokHome?.takeIf { it.isNotEmpty() } ?: return default
    return expandWith(h, default, userHome)
}

/** `~/.rtok/…` → rtok home, `~` / `~/…` → user home; anything else unchanged. */
fun expandWith(
    path: String,
    rtokHome: String,
    userHome: String?,
): String {
    stripRtokHomePrefix(path)?.let { rest -> return if (rest.isEmpty()) rtokHome else joinTildeRest(rtokHome, rest) }
    if (path == "~") return userHome ?: path
    stripHomePrefix(path)?.let { rest -> return if (userHome != null) joinTildeRest(userHome, rest) else path }
    return path
}

private fun stripRtokHomePrefix(raw: String): String? {
    for (p in listOf("~/.rtok/", "~/.rtok\\", "~\\.rtok\\", "~\\.rtok/")) if (raw.startsWith(p)) return raw.substring(p.length)
    return if (raw in setOf("~/.rtok", "~/.rtok/", "~/.rtok\\", "~\\.rtok", "~\\.rtok\\", "~\\.rtok/")) "" else null
}

private fun stripHomePrefix(raw: String): String? =
    when {
        raw.startsWith("~/") || raw.startsWith("~\\") -> raw.substring(2)
        else -> null
    }

private fun joinTildeRest(
    base: String,
    rest: String,
): String = rest.split('/', '\\').filter { it.isNotEmpty() }.fold(base) { acc, part -> pjoin(acc, part) }

/** Resident socket: `<home>/hook.sock` when the path fits sun_path (< 100 bytes). */
fun endpoint(home: String): String? = pjoin(home, "hook.sock").takeIf { it.encodeToByteArray().size < 100 }

private fun frame(body: ByteArray) = le32(body.size.toUInt()) + body

internal fun le32(v: UInt) = ByteArray(4) { (v shr (8 * it)).toByte() }

internal fun le64(v: ULong) = ByteArray(8) { (v shr (8 * it)).toByte() }

internal fun readLe32(
    b: ByteArray,
    at: Int,
): UInt = (0 until 4).fold(0u) { a, i -> a or ((b[at + i].toUInt() and 0xffu) shl (8 * i)) }

fun fnv(bytes: ByteArray): ULong = bytes.fold(0xcbf29ce484222325uL) { h, b -> (h xor (b.toULong() and 0xffu)) * 0x100000001b3uL }
