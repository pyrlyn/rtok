package rtok.web.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginsTest {
    private fun o(s: String) = Json.parseToJsonElement(s) as JsonObject

    private val p = o("""{"id":"git","title":"Git","summary":"Compact git output","enabled":true,"saves_tokens":false,"surfaces":["hook"]}""")

    @Test fun showGroups() {
        assertTrue(matchesPlugin(p, "all", ""))
        assertTrue(matchesPlugin(p, "on", ""))
        assertFalse(matchesPlugin(p, "off", ""))
        assertFalse(matchesPlugin(p, "saves", ""))
    }

    @Test fun queryMatchesIdTitleSummaryCaseInsensitive() {
        assertTrue(matchesPlugin(p, "all", " COMPACT "))
        assertFalse(matchesPlugin(p, "all", "docker"))
        assertEquals(listOf("hook"), p.strings("surfaces"))
    }

    @Test fun toggleRequestShape() {
        assertEquals("""{"set":{"key":"plugins.git.enabled","value":false}}""", pluginToggle("git", false))
    }
}
