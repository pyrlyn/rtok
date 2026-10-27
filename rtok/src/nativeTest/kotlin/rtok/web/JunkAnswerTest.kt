// Port of src/agents/junk_web.rs tests and the `/ws` junk messages of src/web/mod.rs (T330.7): the dry run writes
// nothing, `apply` removes only the confirmed paths, the filter reaches the `safe` kinds of every agent.
package rtok.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import rtok.agents.AgentJunk
import rtok.agents.JunkCache
import rtok.agents.JunkItem
import rtok.agents.JunkReport
import rtok.agents.JunkWeb
import rtok.agents.ageFiles
import rtok.agents.putFile
import rtok.config.Config
import rtok.config.HOSTS
import rtok.support.diskConfig
import rtok.support.installDb
import rtok.sys.exists
import rtok.sys.removeDirAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val DAY = 86_400L
private const val SIZE = 5

class JunkAnswerTest {
    private class Fixture(
        val cfg: Config,
        val dir: String,
        val stale: String,
        val report: JunkReport,
    )

    /** One stale rtok temp item, aged past the settle so `apply` may take it. */
    private fun fixture(tag: String): Fixture {
        installDb()
        val (cfg, dir) = diskConfig(tag)
        val stale = "$dir/junk/stale"
        putFile("$stale/f", ByteArray(SIZE) { 'x'.code.toByte() })
        ageFiles("$dir/junk", 2 * DAY)
        val item = JunkItem("temp", "safe", stale, SIZE.toLong(), JunkCache.SECTION_22)
        val row = AgentJunk("rtok", false, true, emptyList(), 0, emptyList(), listOf(item), emptyList(), 0, 0)
        return Fixture(cfg, dir, stale, JunkReport(listOf(row), emptyList(), 0, 0, 0))
    }

    private fun answer(f: Fixture) = Answer(LiveConfig(f.cfg)) { f.report }

    @Test fun aPlanListsTheItemAndWritesNothing() {
        val f = fixture("junk-web-plan")
        try {
            val plan = JunkWeb.plan(f.cfg, f.report)
            assertTrue(!plan.yes && plan.freedBytes == 0L)
            assertEquals(listOf(f.stale), plan.items.map { it.path })
            assertTrue(plan.items[0].planned && plan.plannedBytes == SIZE.toLong())
            assertTrue(exists(f.stale))
        } finally {
            removeDirAll(f.dir)
        }
    }

    @Test fun applyRemovesOnlyTheConfirmedPaths() {
        val f = fixture("junk-web-apply")
        try {
            val none = JunkWeb.apply(f.cfg, f.report, listOf("/somewhere/else"))
            assertTrue(none.items.isEmpty() && none.freedBytes == 0L)
            assertTrue(exists(f.stale), "an unconfirmed item was removed")
            val done = JunkWeb.apply(f.cfg, f.report, listOf(f.stale))
            assertTrue(done.yes && !done.failed, done.json().toString())
            assertEquals(SIZE.toLong() to "removed", done.freedBytes to done.items[0].note)
            assertFalse(exists(f.stale))
        } finally {
            removeDirAll(f.dir)
        }
    }

    @Test fun theFilterNamesEveryAgentAndNoKindOrReview() {
        val f = JunkWeb.filter()
        assertTrue(!f.isT182 && f.kinds.isEmpty() && !f.includeReview && !f.trash)
        assertTrue(HOSTS.all { it in f.agents } && "rtok" in f.agents)
    }

    @Test fun theSocketPlansThenAppliesTheConfirmedPaths() {
        val f = fixture("junk-web-ws")
        try {
            val a = answer(f)
            val plan = Json.parseToJsonElement(a.answer("""{"req":2,"junk":{"action":"plan"}}""")!!).jsonObject
            assertEquals("2", plan["req"]!!.jsonPrimitive.content)
            assertEquals("junkplan", plan["type"]!!.jsonPrimitive.content)
            val items = plan["plan"]!!.jsonObject["items"]!!.jsonArray
            assertEquals(
                f.stale,
                items
                    .single()
                    .jsonObject["path"]!!
                    .jsonPrimitive.content,
            )
            assertTrue(exists(f.stale))
            val done = Json.parseToJsonElement(a.answer("""{"junk":{"action":"apply","paths":["${f.stale}"]}}""")!!).jsonObject
            assertEquals("junkcleared", done["type"]!!.jsonPrimitive.content)
            assertEquals(SIZE.toLong(), done["cleared"]!!.jsonObject["freed_bytes"]!!.jsonPrimitive.long)
            assertFalse(exists(f.stale))
        } finally {
            removeDirAll(f.dir)
        }
    }

    @Test fun aMalformedJunkRequestIsANotice() {
        val f = fixture("junk-web-bad")
        try {
            val a = answer(f)
            val notice = """{"type":"message","text":"junk needs an action"}"""
            for (bad in listOf("""{"junk":{"action":"all"}}""", """{"junk":{}}""", """{"junk":{"action":"apply","paths":[1]}}""", """{"junk":1}""")) {
                assertEquals(notice, a.answer(bad), bad)
            }
            assertTrue(exists(f.stale))
        } finally {
            removeDirAll(f.dir)
        }
    }
}
