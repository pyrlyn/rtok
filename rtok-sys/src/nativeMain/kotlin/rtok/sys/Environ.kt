package rtok.sys

/** Every `KEY=VALUE` of the process environment, like `std::env::vars_os`. */
expect fun environ(): List<Pair<String, String>>

internal fun splitEnv(raw: List<String>) =
    raw.mapNotNull { e ->
        val i = e.indexOf('=')
        if (i <= 0) null else e.substring(0, i) to e.substring(i + 1)
    }
