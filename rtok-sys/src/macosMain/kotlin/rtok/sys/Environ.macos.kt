@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package rtok.sys

import kotlinx.cinterop.*

actual fun environ(): List<Pair<String, String>> {
    val env =
        platform.osx
            ._NSGetEnviron()
            ?.pointed
            ?.value ?: return emptyList()
    val out = ArrayList<String>()
    var i = 0
    while (true) {
        val p = env[i++] ?: break
        out += p.toKString()
    }
    return splitEnv(out)
}
