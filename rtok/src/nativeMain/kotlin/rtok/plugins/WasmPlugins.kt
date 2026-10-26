// Port of the `wasm::append` call in src/plugins/mod.rs: wires the WASM plugin host (:rtok-wasm)
// to rtok's config, estimator and log, with Chasm as the engine (the only place that picks it).
package rtok.plugins

import rtok.appendLog
import rtok.config.Config
import rtok.estimate
import rtok.fileLog
import rtok.plugin.sdk.Plugin
import rtok.plugins.wasm.WasmHostEnv
import rtok.plugins.wasm.appendWasm
import rtok.sys.eprint
import rtok.wasm.chasm.ChasmRuntime

/** Appends `[plugins.wasm] dir`'s `.wasm` plugins when `[plugins.wasm] enabled`; load errors go to stderr and the log. */
internal fun wasmPlugins(
    plugins: List<Pair<Plugin, Boolean>>,
    config: Config,
): List<Pair<Plugin, Boolean>> {
    if (!config.plugins.wasm.enabled) return plugins
    val env = WasmHostEnv({ text, cls -> estimate(text, cls, config.estimator) }, config.fileLog(), ::eprint)
    return appendWasm(plugins, config.plugins.wasm.dir, ChasmRuntime, env, config::pluginEnabled) { msg ->
        eprint("wasm plugin $msg\n")
        appendLog(config, "error", "wasm", "load", msg)
    }
}
