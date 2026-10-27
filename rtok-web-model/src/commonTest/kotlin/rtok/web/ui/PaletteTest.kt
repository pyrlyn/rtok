package rtok.web.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class PaletteTest {
    @Test fun goKeysAreUniqueAndComplete() {
        assertEquals(PAGES.map { it.id }.toSet(), GO.keys)
        assertEquals(GO.size, GO.values.toSet().size)
        assertEquals("tasks", pageForKey("b"))
        assertEquals(null, pageForKey("z"))
    }

    @Test fun groupsAndFilter() {
        val snap =
            Json.parseToJsonElement(
                """{"plugins":[{"id":"git","title":"Git"}],
                   "sessions":[{"id":"abcdef123456","host":"claude","project":"rtok","last_activity":2},
                               {"id":"zz","host":"codex","last_activity":5}]}""",
            ) as JsonObject
        val g = paletteGroups(snap)
        assertEquals(listOf("Pages", "Plugins", "Sessions", "Hosts"), g.map { it.first })
        assertEquals("/plugins?sel=git", g[1].second.single().href)
        assertEquals(listOf("zz", "abcdef12"), g[2].second.map { it.hint })
        assertEquals(listOf("claude", "codex"), g[3].second.map { it.label })
        val f = filterGroups(g, "CLAUDE")
        assertEquals(listOf("Sessions", "Hosts"), f.map { it.first })
        assertEquals(emptyList(), filterGroups(g, "nothing-here"))
    }
}
