// Port of src/web/mod.rs `origin_allowed`: the cross-site / DNS-rebinding guard on `/ws` and `/auth`.
package rtok.web

private const val HTTP_DEFAULT_PORT = "80"
private const val HTTPS_DEFAULT_PORT = "443"

/**
 * Browsers send `Origin` on cross-site WebSocket upgrades; non-browser clients send none. A present
 * `Origin` must name the same host and port as `Host`, and that host must be an IP literal or `localhost`
 * (a rebinding page's DNS name resolves here but cannot be either).
 */
fun originAllowed(
    origin: String?,
    host: String?,
): Boolean =
    when {
        origin == null -> true
        host == null -> false
        else -> sameSafeAuthority(origin, host)
    }

private fun sameSafeAuthority(
    origin: String,
    host: String,
): Boolean {
    val o = originAuthority(origin)
    val h = hostAuthority(host)
    if (o == null || h == null) return false
    val default = if (origin.substringBefore("://").lowercase() in setOf("https", "wss")) HTTPS_DEFAULT_PORT else HTTP_DEFAULT_PORT
    val samePort = (portOf(o) ?: default) == (portOf(h) ?: default)
    val oh = stripPort(o)
    val hh = stripPort(h)
    return oh.equals(hh, ignoreCase = true) && samePort && rebindSafe(hh)
}

private fun rebindSafe(host: String): Boolean = isIpLiteral(host) || host.equals("localhost", ignoreCase = true) || host.lowercase().endsWith(".localhost")

private const val V4_PARTS = 4
private const val OCTET_DIGITS = 3
private const val OCTET_MAX = 255

private fun isOctet(p: String) = p.length in 1..OCTET_DIGITS && p.all(Char::isDigit) && p.toInt() <= OCTET_MAX

private fun isV4(h: String) = h.split('.').let { parts -> parts.size == V4_PARTS && parts.all(::isOctet) }

private fun isV6(h: String) = h.count { it == ':' } >= 2 && h.all { it.isLetterOrDigit() && it.lowercaseChar() in "0123456789abcdef" || it in ":." }

private fun isIpLiteral(h: String): Boolean = isV4(h) || isV6(h)

private fun originAuthority(origin: String): String? {
    if (!origin.contains("://")) return null
    val rest = origin.substringAfter("://")
    val authority = rest.split('/', '?', '#').first()
    return authority.substringAfterLast('@')
}

private fun hostAuthority(host: String): String? {
    val h = host.trim()
    // Several `Host` values joined: picking one could pass the equality with a crafted `Origin`.
    if (h.isEmpty() || h.contains(',')) return null
    return h.substringAfterLast('@')
}

private fun portOf(authority: String): String? =
    when {
        authority.startsWith('[') -> {
            authority.indexOf(']').takeIf { it >= 0 }?.let { end ->
                authority.substring(end + 1).takeIf { it.startsWith(':') }?.substring(1)
            }
        }

        authority.count { it == ':' } == 1 -> {
            authority.substringAfter(':')
        }

        else -> {
            null
        }
    }

private fun stripPort(authority: String): String {
    if (authority.startsWith('[')) {
        val end = authority.indexOf(']')
        if (end >= 0) return authority.substring(1, end)
    }
    return if (authority.count { it == ':' } == 1) authority.substringBefore(':') else authority
}
