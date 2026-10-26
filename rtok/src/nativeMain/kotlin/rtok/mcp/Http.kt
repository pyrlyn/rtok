// `rtok mcp --http` (mcp/http.rs, T401): the stdio server's tools over MCP Streamable HTTP, for clients that only
// reach remote servers. Stateless with JSON replies (rmcp `NeverSessionManager` + `json_response`): POST only, the
// Host check against DNS rebinding, the Origin check and `MCP-Protocol-Version`
// (https://modelcontextprotocol.io/specification/2025-11-25/basic/transports). Every JSON-RPC body goes to
// [Server.handleLine] in stateless mode, so an HTTP call runs the same allow-list, gate, dispatch and `record`.
package rtok.mcp

import rtok.config.Config
import rtok.http.server.HttpServer
import rtok.http.server.RequestHandler
import rtok.http.server.ServerRequest
import rtok.http.server.ServerResponse
import rtok.http.server.ktor.KtorHttpServer
import rtok.plugin.Runtime
import rtok.plugins.read.Roots
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

object Http {
    /** The one MCP endpoint path the spec asks for. */
    const val PATH = "/mcp"

    /** A shorter token is guessable by anyone who can reach the tunnel URL. */
    const val MIN_TOKEN_CHARS = 16

    /**
     * Tools that act as "the agent this process serves". One HTTP server answers every token holder and cannot tell
     * whose session a call comes from, so these are never served here, whatever `http_tools` says.
     */
    val AGENT_BOUND =
        setOf("whoami", "worktree_add", "worktree_remove", "worktree_finish", "worktree_adopt", "agent_send", "agent_inbox", "agent_status_set")

    /** Hosts a loopback client sends; anything else must come from `[mcp] public_url`. */
    val LOOPBACK_HOSTS = listOf("localhost", "127.0.0.1", "::1")

    class HttpSetupException(
        message: String,
    ) : Exception(message)

    /** A parsed `IP:PORT` (Rust `SocketAddr`). */
    data class Addr(
        val ip: String,
        val port: Int,
        val v6: Boolean,
    ) {
        val isLoopback get() = if (v6) ip == "::1" else ip.startsWith("127.")
        val isUnspecified get() = if (v6) ip == "::" else ip == "0.0.0.0"

        companion object {
            fun parse(raw: String): Addr {
                val bad = { HttpSetupException("rtok mcp --http: `$raw` is not IP:PORT") }
                val (host, port, v6) =
                    if (raw.startsWith("[")) {
                        val close = raw.indexOf("]:").takeIf { it > 0 } ?: throw bad()
                        Triple(raw.substring(1, close), raw.substring(close + 2), true)
                    } else {
                        Triple(raw.substringBeforeLast(':', ""), raw.substringAfterLast(':'), false)
                    }
                val p = port.toIntOrNull()?.takeIf { it in 0..MAX_PORT && port.all(Char::isDigit) } ?: throw bad()
                if (if (v6) !isV6(host) else !isV4(host)) throw bad()
                return Addr(if (v6) normalizeV6(host) else host, p, v6)
            }

            private fun isV4(s: String): Boolean {
                val parts = s.split('.')
                return parts.size == V4_PARTS &&
                    parts.all { p -> p.isNotEmpty() && p.length <= V4_DIGITS && p.all(Char::isDigit) && p.toInt() <= MAX_OCTET && !(p.length > 1 && p[0] == '0') }
            }

            private fun isV6(s: String) = s.contains(':') && s.all { it.isLetterOrDigit() || it == ':' || it == '.' } && s.count { it == ':' } <= V6_COLONS

            private fun normalizeV6(s: String) =
                when (s.lowercase()) {
                    "0:0:0:0:0:0:0:1" -> "::1"
                    "0:0:0:0:0:0:0:0" -> "::"
                    else -> s.lowercase()
                }
        }
    }

    /** A validated `[mcp] public_url`: its host and its origin (`scheme://host[:port]`, default ports dropped). */
    data class PublicUrl(
        val scheme: String,
        val host: String,
        val origin: String,
    ) {
        companion object {
            fun parse(raw: String): PublicUrl? {
                if (raw.isEmpty()) return null
                val bad = { HttpSetupException("[mcp] public_url `$raw` must be an http(s) URL with a host") }
                val scheme = raw.substringBefore("://", "").lowercase()
                if (scheme != "https" && scheme != "http") throw bad()
                val authority =
                    raw
                        .substringAfter("://")
                        .substringBefore('/')
                        .substringBefore('?')
                        .substringBefore('#')
                        .substringAfterLast('@')
                val (host, port) =
                    if (authority.startsWith("[")) {
                        authority.substringBefore(']') + "]" to authority.substringAfter("]", "").removePrefix(":")
                    } else {
                        authority.substringBefore(':') to authority.substringAfter(':', "")
                    }
                if (host.isEmpty() || (port.isNotEmpty() && port.toIntOrNull() == null)) throw bad()
                val h = host.lowercase()
                val default = if (scheme == "https") HTTPS_PORT else HTTP_PORT
                val origin = if (port.isEmpty() || port.toInt() == default) "$scheme://$h" else "$scheme://$h:${port.toInt()}"
                return PublicUrl(scheme, h, origin)
            }
        }
    }

    /** The Host and Origin allow-lists (rmcp `StreamableHttpServerConfig`). */
    data class Transport(
        val allowedHosts: List<String>,
        val allowedOrigins: List<String>,
    )

    fun transport(
        addr: Addr,
        public: PublicUrl?,
    ): Transport {
        val hosts = LOOPBACK_HOSTS.toMutableList()
        if (!addr.isUnspecified) hosts += addr.ip
        public?.let { hosts += it.host }
        return Transport(hosts, listOfNotNull(public?.origin))
    }

    /**
     * Refuse a start that would expose more than one project over the network: the read guard allows everything
     * under the cwd, and a non-loopback bind sends the bearer token in clear unless an https front terminates it.
     */
    fun exposureGuard(
        cwd: String,
        home: String?,
        addr: Addr,
        public: PublicUrl?,
    ) {
        if (rtok.sys.isUnwalkableRoot(cwd, home)) {
            throw HttpSetupException(
                "rtok mcp --http: refusing to serve $cwd (the filesystem root or home directory); start it inside a project",
            )
        }
        if (!addr.isLoopback && public?.scheme != "https") {
            throw HttpSetupException(
                "rtok mcp --http: ${addr.display()} is not loopback and `[mcp] public_url` is not https; the bearer token " +
                    "would cross the network in clear. Bind 127.0.0.1 behind a TLS tunnel and set public_url to its https URL",
            )
        }
    }

    private fun Addr.display() = if (v6) "[$ip]:$port" else "$ip:$port"

    fun bearerToken(raw: String): String {
        if (raw.length < MIN_TOKEN_CHARS || !raw.all { it.code in GRAPHIC_FIRST..GRAPHIC_LAST }) {
            throw HttpSetupException(
                "rtok mcp --http needs a bearer token of at least $MIN_TOKEN_CHARS visible ASCII characters in RTOK_MCP_TOKEN or `[mcp] token`",
            )
        }
        return raw
    }

    /** RFC 6750 `Authorization: Bearer <token>`, scheme case-insensitive; the compare is constant-time. */
    fun bearerMatches(
        value: String?,
        token: String,
    ): Boolean {
        val sp = value?.indexOf(' ') ?: return false
        if (sp < 0) return false
        val got = value.substring(sp + 1).encodeToByteArray()
        val want = token.encodeToByteArray()
        var diff = got.size xor want.size
        for (i in want.indices) diff = diff or (want[i].toInt() xor (got.getOrNull(i)?.toInt() ?: 0))
        return value.substring(0, sp).equals("bearer", ignoreCase = true) && diff == 0
    }

    /** The stdio tool set minus [AGENT_BOUND], on a fresh session with no archive grants. */
    @OptIn(ExperimentalUuidApi::class)
    fun httpServer(cfg: Config): Server {
        // A reused PID must not revive read-cache pointers from a previous HTTP process.
        val cx = Runtime.open(cfg, "mcp-http-${Uuid.random()}")
        Roots.enable()
        cx.restrictArchives()
        return Server(cx, Server.listedFor(cfg).filterNot { it.def.name in AGENT_BOUND })
    }

    /** `rtok mcp --http <addr>`: bind, then serve until the process is killed. */
    fun serveBlocking(
        cfg: Config,
        rawAddr: String,
        http: HttpServer = KtorHttpServer,
        stop: () -> Boolean = { false },
        onBound: (String) -> Unit = {},
    ) {
        val addr = Addr.parse(rawAddr)
        val token = bearerToken(cfg.mcp.token)
        val public = PublicUrl.parse(cfg.mcp.publicUrl)
        exposureGuard(rtok.sys.cwd(), rtok.sys.homeDir(), addr, public)
        // Over HTTP the caller is remote: never hand it `.env`, keys or `.git/config`.
        Roots.denySecrets()
        // The HTTP surface gets its own allow-list through the same filter as stdio's `tools`.
        val httpCfg = cfg.copy(mcp = cfg.mcp.copy(tools = cfg.mcp.httpTools))
        httpServer(httpCfg).use { server ->
            http.serveBlocking(addr.ip, addr.port, Endpoint(server, token, transport(addr, public)), stop = stop) { local ->
                val url = "http://$local$PATH"
                // The token is never printed: this line lands in terminals and logs.
                rtok.sys.writeFd(2, "rtok mcp: serving $url\n".encodeToByteArray())
                runCatching { server.cx.log("info", "mcp", "http", url) }
                onBound(local)
            }
        }
    }

    /** One Streamable HTTP endpoint: bearer gate, then Host/Origin, method, headers, and the JSON-RPC body. */
    class Endpoint(
        private val server: Server,
        private val token: String,
        private val transport: Transport,
    ) : RequestHandler {
        override fun handle(req: ServerRequest): ServerResponse =
            when {
                !bearerMatches(req.header("authorization"), token) -> {
                    text(UNAUTHORIZED, "Unauthorized", "WWW-Authenticate" to "Bearer")
                }

                req.path != PATH -> {
                    text(NOT_FOUND, "Not Found")
                }

                !hostAllowed(req.header("host")) -> {
                    text(FORBIDDEN, "Forbidden: Host header is not allowed")
                }

                !originAllowed(req.header("origin")) -> {
                    text(FORBIDDEN, "Forbidden: Origin header is not allowed")
                }

                req.method != "POST" -> {
                    text(METHOD_NOT_ALLOWED, "Method Not Allowed", "Allow" to "POST")
                }

                else -> {
                    post(req)
                }
            }

        private fun post(req: ServerRequest): ServerResponse {
            val accept = req.header("accept").orEmpty()
            val type = req.header("content-type").orEmpty()
            val version = req.header("mcp-protocol-version")
            return when {
                !("application/json" in accept && "text/event-stream" in accept) -> {
                    text(NOT_ACCEPTABLE, "Not Acceptable: Client must accept both application/json and text/event-stream")
                }

                !type.startsWith("application/json") -> {
                    text(UNSUPPORTED_MEDIA, "Unsupported Media Type: Content-Type must be application/json")
                }

                version != null && version !in Protocol.SUPPORTED_PROTOCOL_VERSIONS -> {
                    text(BAD_REQUEST, "Bad Request: Unsupported MCP-Protocol-Version: $version")
                }

                else -> {
                    val answer = server.handleLine(req.body.decodeToString(), stateless = true)
                    if (answer == null) {
                        ServerResponse(ACCEPTED)
                    } else {
                        ServerResponse(OK, listOf("Content-Type" to "application/json"), answer.encodeToByteArray())
                    }
                }
            }
        }

        private fun hostAllowed(raw: String?): Boolean {
            val host = raw?.trim()?.lowercase() ?: return false
            val name =
                if (host.startsWith("[")) host.substring(1).substringBefore(']') else host.substringBefore(':')
            return name in transport.allowedHosts
        }

        private fun originAllowed(raw: String?) = raw == null || raw.trimEnd('/').lowercase() in transport.allowedOrigins

        private fun text(
            status: Int,
            body: String,
            vararg extra: Pair<String, String>,
        ) = ServerResponse(status, listOf("Content-Type" to "text/plain; charset=utf-8") + extra, body.encodeToByteArray())
    }

    private const val MAX_PORT = 65535
    private const val MAX_OCTET = 255
    private const val V4_PARTS = 4
    private const val V4_DIGITS = 3
    private const val V6_COLONS = 7
    private const val HTTP_PORT = 80
    private const val HTTPS_PORT = 443
    private const val GRAPHIC_FIRST = 0x21
    private const val GRAPHIC_LAST = 0x7e
    private const val OK = 200
    private const val ACCEPTED = 202
    private const val BAD_REQUEST = 400
    private const val UNAUTHORIZED = 401
    private const val FORBIDDEN = 403
    private const val NOT_FOUND = 404
    private const val METHOD_NOT_ALLOWED = 405
    private const val NOT_ACCEPTABLE = 406
    private const val UNSUPPORTED_MEDIA = 415
}
