package rtok.plugins

import kotlin.test.*
import rtok.config.CATALOGUE
import rtok.config.Config
import rtok.plugin.sdk.*

class RegistryTest {
    /** Rust checks equality with the catalogue; until Phase 5 ends the registry is an ordered subset. */
    @Test fun registryMatchesCatalogue() {
        val got = Registry.new(Config()).manifests().map { (m, _) -> m.id to m.defaultOn }
        val order = CATALOGUE.filter { it in got }
        assertEquals(order, got, "plugins/Registry.kt and config CATALOGUE drifted")
    }

    @Test fun configOverridesDefaultOn() {
        val cfg = Config().withPluginEnabled("measure", false).withPluginEnabled("proxy", true)
        val reg = Registry.new(cfg)
        val on = reg.enabled().map { it.manifest().id }
        assertFalse("measure" in on); assertTrue("proxy" in on)
        assertTrue(reg.table().contains("measure   off"))
    }

    @Test fun fromPluginsTakesExternalPlugins() {
        val ext = object : Plugin {
            override fun manifest() = Manifest("ext", listOf(Surface.Mcp), true)
            override fun dashboardPage() = DashboardPage("Ext", "An out-of-tree plugin.", false)
        }
        assertEquals(listOf("ext"), Registry.fromPlugins(listOf(ext), Config()).enabled().map { it.manifest().id })
    }

    @Test fun skipWordHandlesQuotes() {
        assertEquals("rest", rtok.skipWord("~/'My Documents'  rest"))
        assertEquals("", rtok.skipWord("word"))
        assertNull(rtok.skipWord("'open"))
    }
}
