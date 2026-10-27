// Port of src/cli/web.rs: `rtok web --host --port` and the deprecated `rtok dashboard` alias.
package rtok.cli

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import rtok.appendLog
import rtok.config.Config
import rtok.config.Layers
import rtok.http.server.ktor.KtorHttpServer
import rtok.web.ALLOW_REMOTE_ENV
import rtok.web.BoundAddr
import rtok.web.DIST_ENV
import rtok.web.NoAssets
import rtok.web.RemoteBindRefused
import rtok.web.WebApp
import rtok.web.WebToken
import rtok.web.checkRemoteBind
import rtok.web.resolveAssets

private val WEB_OPTIONS =
    listOf(
        OptSpec.Value("host", "Override `[web] host`", "HOST"),
        OptSpec.Value("port", "Override `[web] port`", "PORT"),
    )

@OptIn(ExperimentalForeignApi::class)
private fun env(name: String) = platform.posix.getenv(name)?.toKString()

private fun webConfig(inv: Invocation): Config {
    val port = inv.value("port")?.let { it.toUShortOrNull() ?: throw CliFailure("invalid value '$it' for '--port <PORT>'") }
    return Config.loadWith(inv.configFile, Layers.webFlags(inv.value("host"), port))
}

/** `rtok web`: bind `[web] host:port` and serve until killed. */
internal fun serveWeb(
    cfg: Config,
    io: CliIo,
) {
    runCatching { checkRemoteBind(cfg.web.host, env(ALLOW_REMOTE_ENV) == "1") }
        .onFailure { if (it is RemoteBindRefused) throw CliFailure(it.message ?: "refusing to bind") else throw it }
    // S2: a fresh token per start; written before the startup line so a client waiting on it finds the file.
    val token = WebToken.issue(cfg.home)
    val (assets, notice) = resolveAssets(env(DIST_ENV), NoAssets)
    val bound = BoundAddr(cfg.web.host, cfg.web.port.toInt())
    val app = WebApp(bound, assets, token)
    KtorHttpServer.serveBlocking(cfg.web.host, cfg.web.port.toInt(), app) { addr ->
        bound.port = addr.substringAfterLast(':').toIntOrNull() ?: bound.port
        io.eprintln("rtok web http://$addr  (ws://$addr/ws)")
        io.eprintln("rtok web: /ws needs the token in ${WebToken.path(cfg.home)}")
        appendLog(cfg, "info", "web", "serve", "http://$addr websocket /ws")
        notice?.let {
            io.eprintln(it)
            appendLog(cfg, "warn", "web", "spa", it)
        }
    }
}

val WEB =
    CommandSpec(
        "web",
        "Local web UI over the same data as `rtok tui` (WebSocket API + SPA)",
        options = WEB_OPTIONS,
    ) { inv -> serveWeb(webConfig(inv), inv.io) }

val DASHBOARD =
    CommandSpec(
        "dashboard",
        "Deprecated: use `rtok web`",
        options = WEB_OPTIONS,
    ) { inv ->
        val msg = "`rtok dashboard` is deprecated; use `rtok web`"
        inv.io.eprintln("\u001b[33mwarning: $msg\u001b[0m".let { if (inv.io.tty) it else "warning: $msg" })
        val cfg = webConfig(inv)
        appendLog(cfg, "warn", "cli", "dashboard", msg)
        serveWeb(cfg, inv.io)
    }
