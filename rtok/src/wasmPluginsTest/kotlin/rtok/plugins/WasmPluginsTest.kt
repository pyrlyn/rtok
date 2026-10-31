package rtok.plugins

import rtok.config.Config
import rtok.sys.tempDir
import kotlin.test.*

class WasmPluginsTest {
    // Registry half of Rust `from_plugins_loads_demo_and_records_measurement`.
    @Test fun fromPluginsLoadsWasmDemo() {
        val dir = rtok.config.envVar("RTOK_WASM_FIXTURES")!!
        val base = Config().finish("${tempDir()}/rtok-wasm-plugins-test")
        val cfg = base.copy(plugins = base.plugins.copy(wasm = rtok.config.Wasm(enabled = true, dir = dir)))
        assertEquals(listOf("wasm-demo"), Registry.fromPlugins(emptyList(), cfg).enabled().map { it.manifest().id })
        assertTrue(Registry.fromPlugins(emptyList(), base).enabled().isEmpty())
    }
}
