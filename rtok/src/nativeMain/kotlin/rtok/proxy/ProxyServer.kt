// Port of `serve`/`serve_blocking` in src/proxy/mod.rs: bind `[proxy] bind:port`, start retention and the OTel
// ticker on their own workers, then serve until stopped (the CLI: until the process is killed).
package rtok.proxy

import rtok.config.Config
import rtok.http.server.StreamingHttpServer
import rtok.http.server.ktor.KtorHttpServer

object ProxyServer {
    fun serveBlocking(
        cfg: Config,
        http: StreamingHttpServer = KtorHttpServer,
        stop: () -> Boolean = { false },
        onBound: (String) -> Unit = {},
    ) {
        ProxyState.open(cfg).use { state ->
            // Background, own connection: housekeeping must neither delay the listener nor die on a busy store.
            rtok.Retention.spawn(cfg, "proxy")
            rtok.otel.Ticker.spawn(cfg)
            http.serveStreaming(cfg.proxy.bind, cfg.proxy.port.toInt(), ProxyHandler(state), ProxyHttp.MAX_BODY_BYTES, stop, onBound)
        }
    }
}
