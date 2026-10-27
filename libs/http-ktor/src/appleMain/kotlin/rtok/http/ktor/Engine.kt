package rtok.http.ktor

internal actual fun platformEngine(): io.ktor.client.engine.HttpClientEngine =
    io.ktor.client.engine.darwin.Darwin
        .create()

/** Darwin applies `socketTimeoutMillis` itself. */
internal actual val engineEnforcesSocketTimeout: Boolean = true
