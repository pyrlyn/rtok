package rtok.plugins

import rtok.config.CATALOGUE
import rtok.config.Config
import rtok.plugin.sdk.*
import rtok.sys.tempDir
import kotlin.test.*

/** A finished default config: `~` expanded under a temp home, so nothing is written to a literal `./~`. */
private fun cfg(): Config = Config().finish("${tempDir()}/rtok-registry-test")

class RegistryTest {
    /** Rust checks equality with the catalogue; until Phase 5 ends the registry is an ordered subset. */
    @Test fun registryMatchesCatalogue() {
        val got = Registry.new(cfg()).manifests().map { (m, _) -> m.id to m.defaultOn }
        val order = CATALOGUE.filter { it in got }
        assertEquals(order, got, "plugins/Registry.kt and config CATALOGUE drifted")
    }

    @Test fun configOverridesDefaultOn() {
        val cfg = Config().withPluginEnabled("measure", false).withPluginEnabled("proxy", true)
        val reg = Registry.new(cfg)
        val on = reg.enabled().map { it.manifest().id }
        assertFalse("measure" in on)
        assertTrue("proxy" in on)
        assertTrue(reg.table().contains("measure   off"))
    }

    @Test fun fromPluginsTakesExternalPlugins() {
        val ext =
            object : Plugin {
                override fun manifest() = Manifest("ext", listOf(Surface.Mcp), true)

                override fun dashboardPage() = DashboardPage("Ext", "An out-of-tree plugin.", false)
            }
        assertEquals(listOf("ext"), Registry.fromPlugins(listOf(ext), cfg()).enabled().map { it.manifest().id })
    }

    @Test fun skipWordHandlesQuotes() {
        assertEquals("rest", rtok.skipWord("~/'My Documents'  rest"))
        assertEquals("", rtok.skipWord("word"))
        assertNull(rtok.skipWord("'open"))
    }

    // Registry half of Rust `from_plugins_loads_demo_and_records_measurement`.
    @Test fun fromPluginsLoadsWasmDemo() {
        val dir = rtok.config.envVar("RTOK_WASM_FIXTURES")!!
        val base = cfg()
        val cfg = base.copy(plugins = base.plugins.copy(wasm = rtok.config.Wasm(enabled = true, dir = dir)))
        assertEquals(listOf("wasm-demo"), Registry.fromPlugins(emptyList(), cfg).enabled().map { it.manifest().id })
        assertTrue(Registry.fromPlugins(emptyList(), base).enabled().isEmpty())
    }
}
