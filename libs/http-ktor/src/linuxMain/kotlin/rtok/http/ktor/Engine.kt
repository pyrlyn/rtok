package rtok.http.ktor

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.curl.Curl

internal actual fun platformEngine(): HttpClientEngine = Curl.create()

/** The Curl engine ignores `socketTimeoutMillis`, so [KtorHttp] watches idle reads itself. */
internal actual val engineEnforcesSocketTimeout: Boolean = false
