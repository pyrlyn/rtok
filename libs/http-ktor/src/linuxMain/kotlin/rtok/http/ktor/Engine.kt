package rtok.http.ktor

internal actual fun platformEngine(): io.ktor.client.engine.HttpClientEngine = io.ktor.client.engine.curl.Curl.create()

/** The Curl engine ignores `socketTimeoutMillis`, so [KtorHttp] watches idle reads itself. */
internal actual val engineEnforcesSocketTimeout: Boolean = false
