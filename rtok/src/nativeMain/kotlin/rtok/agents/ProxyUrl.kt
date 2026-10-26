// Port of src/agents/mod.rs `openai_proxy_url` and `loopback_port`: the proxy URL hosts are pointed at, and the port of
// any loopback http(s) URL, so a value rtok wrote under another bind spelling is still recognised as ours.
package rtok.agents

import rtok.config.Config

object ProxyUrl {
    private val DEFAULT_PORTS = mapOf("http" to 80, "https" to 443)
    private const val MAX_PORT = 65535

    /** `http://<bind>:<port>/v1`. */
    fun openai(cfg: Config) = "http://${cfg.proxy.bind}:${cfg.proxy.port}/v1"

    /** The port of an `http`/`https` URL on `localhost`, `127.0.0.0/8` or `[::1]`; null for anything else. */
    fun loopbackPort(value: String): Int? {
        val v = value.trim()
        val default = DEFAULT_PORTS[v.substringBefore("://", "").lowercase()]
        val authority =
            v
                .substringAfter("://")
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
                .substringAfterLast('@')
        val (host, port) =
            if (authority.startsWith("[")) {
                authority.substringBefore(']').drop(1) to authority.substringAfter(']').removePrefix(":")
            } else {
                authority.substringBefore(':') to authority.substringAfter(':', "")
            }
        return when {
            default == null || !isLoopback(host.lowercase()) -> null
            port.isEmpty() -> default
            else -> port.toIntOrNull()?.takeIf { it in 0..MAX_PORT }
        }
    }

    private fun isLoopback(host: String): Boolean {
        if (host == "localhost" || host == "::1" || host == "0:0:0:0:0:0:0:1") return true
        val parts = host.split('.')
        return parts.size == IPV4_PARTS && parts[0] == "127" && parts.all { p -> p.toIntOrNull()?.let { it in 0..IPV4_MAX } == true }
    }

    private const val IPV4_PARTS = 4
    private const val IPV4_MAX = 255
}
