// Port of src/web/mod.rs `remote_bind_allowed`: a dashboard bound beyond loopback needs an explicit opt-in.
package rtok.web

/** Opt-in for a dashboard bound beyond loopback: `RTOK_WEB_ALLOW_REMOTE=1`. */
const val ALLOW_REMOTE_ENV = "RTOK_WEB_ALLOW_REMOTE"

/** Raised when `rtok web` refuses its bind address. */
class RemoteBindRefused(
    message: String,
) : Exception(message)

private const val IPV4_PARTS = 4
private const val OCTET_MAX = 255
private const val LOOPBACK_OCTET = 127

/** `localhost`, `127.0.0.0/8` or `::1` (optionally bracketed). */
fun isLoopback(host: String): Boolean {
    val h = host.removePrefix("[").removeSuffix("]")
    return host.equals("localhost", ignoreCase = true) ||
        h == "::1" ||
        h.equals("0:0:0:0:0:0:0:1", ignoreCase = true) ||
        isLoopbackV4(h)
}

private fun isLoopbackV4(h: String): Boolean {
    val octets = h.split('.').map { p -> p.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull() }
    return octets.size == IPV4_PARTS && octets.all { it != null && it in 0..OCTET_MAX } && octets[0] == LOOPBACK_OCTET
}

/** Throws [RemoteBindRefused] for a non-loopback [host] unless [optedIn]. */
fun checkRemoteBind(
    host: String,
    optedIn: Boolean,
) {
    if (isLoopback(host) || optedIn) return
    throw RemoteBindRefused(
        "rtok web: refusing to bind $host: the dashboard has no authentication and would let anyone on the network " +
            "change config and delete files. Bind 127.0.0.1, or set $ALLOW_REMOTE_ENV=1 if the network is trusted",
    )
}
