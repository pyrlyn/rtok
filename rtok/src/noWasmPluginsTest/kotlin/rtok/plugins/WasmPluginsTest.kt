package rtok.plugins

import rtok.config.Config
import rtok.sys.tempDir
import kotlin.test.*

class WasmPluginsTest {
    @Test fun fromPluginsSkipsWasmWithoutTheHost() {
        val dir = rtok.config.envVar("RTOK_WASM_FIXTURES")!!
        val base = Config().finish("${tempDir()}/rtok-wasm-plugins-test")
        val cfg = base.copy(plugins = base.plugins.copy(wasm = rtok.config.Wasm(enabled = true, dir = dir)))
        assertTrue(Registry.fromPlugins(emptyList(), cfg).enabled().isEmpty())
    }
}
