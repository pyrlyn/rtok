@file:Suppress("MagicNumber")

package rtok.model

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import rtok.agents.AgentJunk
import rtok.agents.JunkReport
import rtok.agents.KindRow
import kotlin.test.Test
import kotlin.test.assertEquals

class JunkPageTest {
    private fun agent(
        name: String,
        host: Boolean,
        kinds: List<KindRow>,
    ) = AgentJunk(name, host, true, emptyList(), 10, kinds, emptyList(), emptyList(), 4, 6)

    @Test fun theCardCarriesRtokAndHostsWithKindsOnly() {
        val log = KindRow("log", "safe", 1, 5)
        val report =
            JunkReport(
                listOf(agent("rtok", false, emptyList()), agent("claude", true, listOf(log)), agent("gemini", true, emptyList())),
                emptyList(),
                30,
                8,
                12,
            )
        val card = JunkPage.card(report)
        val agents = card["agents"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("rtok", "claude"), agents.map { it["name"]!!.jsonPrimitive.content })
        val kind = agents[1]["kinds"]!!.jsonArray[0].jsonObject
        assertEquals("safe", kind["class"]!!.jsonPrimitive.content)
        assertEquals(1, kind["items"]!!.jsonPrimitive.int)
        assertEquals(5, kind["size_bytes"]!!.jsonPrimitive.long)
        assertEquals(8, card["freed_default_bytes"]!!.jsonPrimitive.long)
        assertEquals(12, card["freed_review_bytes"]!!.jsonPrimitive.long)
        assertEquals(30, card["total_bytes"]!!.jsonPrimitive.long)
    }

    @Test fun hostsPageShowsTheJunkText() {
        HostsPage.forget()
        val c = rtok.config.Config()
        val start =
            kotlin.time.TimeSource.Monotonic
                .markNow()
        var page = HostsPage.PROBING
        while (page == HostsPage.PROBING && start.elapsedNow().inWholeSeconds < 10) {
            page = HostsPage.text(c, probe = { "claude  cli\n" }, junk = { "rtok\n" })
            platform.posix.usleep(20_000u)
        }
        assertEquals("claude  cli\n\njunk\nrtok\n", page)
    }

    @Test fun worktreesPageReadsOffTheTick() {
        val bg = Background<String>("wt-test")
        assertEquals(WorktreesPage.READING, WorktreesPage.text({ "table\n" }, bg))
        val start =
            kotlin.time.TimeSource.Monotonic
                .markNow()
        var page = WorktreesPage.READING
        while (page == WorktreesPage.READING && start.elapsedNow().inWholeSeconds < 10) {
            page = WorktreesPage.text({ "table\n" }, bg)
            platform.posix.usleep(20_000u)
        }
        assertEquals("table\n", page)
    }
}
