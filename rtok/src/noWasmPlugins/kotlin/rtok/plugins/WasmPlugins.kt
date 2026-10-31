// `wasmPlugins` for a build without -Prtok.wasm=true: the host (:rtok-wasm) and the engine are not compiled in.
package rtok.plugins

import rtok.appendLog
import rtok.config.Config
import rtok.plugin.sdk.Plugin
import rtok.sys.eprint

/** Returns [plugins] as they are; a config that asks for WASM plugins gets a warning on stderr and in the log. */
internal fun wasmPlugins(
    plugins: List<Pair<Plugin, Boolean>>,
    config: Config,
): List<Pair<Plugin, Boolean>> {
    if (config.plugins.wasm.enabled) {
        val msg = "not in this build (rebuild with -Prtok.wasm=true), skipped"
        eprint("wasm plugins $msg\n")
        appendLog(config, "warn", "wasm", "load", msg)
    }
    return plugins
}
