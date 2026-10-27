// Port of the T15.4 toggle tests of src/tui/app.rs and view.rs and of src/tui/junk_clear.rs: Space writes
// `plugins.<id>.enabled` through `config set`'s writer on a temp home; `c` plans, `y` removes only what was shown.
package rtok.tui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import rtok.agents.AgentJunk
import rtok.agents.JunkCache
import rtok.agents.JunkItem
import rtok.agents.JunkReport
import rtok.agents.ageFiles
import rtok.agents.putFile
import rtok.config.Config
import rtok.support.installDb
import rtok.support.tmpDir
import rtok.sys.exists
import rtok.sys.readBytes
import rtok.sys.removeDirAll
import tui.Buffer
import tui.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val DAY = 86_400L
private const val SIZE = 5
private const val COLS = 120
private const val ROWS = 12

class TuiActionsTest {
    private fun config(tag: String): Config {
        installDb()
        val dir = tmpDir("tui-$tag")
        val c = Config.loadFrom(dir)
        // `home` is transient: a data-class copy drops it, and a write would then land in the cwd.
        return c.copy(core = c.core.copy(dbPath = "$dir/rtok.db", archiveDir = "$dir/archive")).also { it.home = dir }
    }

    private fun screen(a: TuiApp): String = Buffer(COLS, ROWS).also { draw(it, a, { "00:00:00" }) }.lines().joinToString("\n")

    @Test fun spaceTogglesTheSelectedPluginThroughConfigSet() {
        val cfg = config("toggle")
        try {
            val a = TuiApp("plugins", TuiActions(cfg))
            a.snapshot = Json.parseToJsonElement("""{"plugins":[{"id":"guard","title":"Guard","enabled":true}]}""") as JsonObject
            assertEquals(Effect.REFRESH, a.key(Key.Char(" ")))
            assertEquals("guard off", a.pluginStatus)
            val file = readBytes("${cfg.home}/config.toml").decodeToString()
            assertTrue(Regex("""\[plugins\.guard][^\[]*enabled = false""").containsMatchIn(file), file)
            assertFalse(
                a.actions!!
                    .cfg.plugins.guard.enabled,
            )
            assertEquals(cfg.home, a.actions!!.cfg.home)
            assertTrue("guard off" in screen(a))
            a.key(Key.Down)
            assertEquals("", a.pluginStatus)
        } finally {
            removeDirAll(cfg.home)
        }
    }

    @Test fun aToggleWithoutARowOrARefusalIsTheStatusLine() {
        val cfg = config("toggle-refuse")
        try {
            val a = TuiApp("plugins", TuiActions(cfg))
            assertEquals(Effect.NONE, a.key(Key.Enter))
            assertEquals("no plugin to toggle", a.pluginStatus)
            a.snapshot = Json.parseToJsonElement("""{"plugins":[{"id":"nope","enabled":true}]}""") as JsonObject
            assertEquals(Effect.NONE, a.key(Key.Enter))
            assertTrue(a.pluginStatus.startsWith("config set plugins.nope.enabled: "), a.pluginStatus)
        } finally {
            removeDirAll(cfg.home)
        }
    }

    private fun junkFixture(cfg: Config): Pair<String, JunkReport> {
        val stale = "${cfg.home}/junk/stale"
        putFile("$stale/f", ByteArray(SIZE) { 'x'.code.toByte() })
        ageFiles("${cfg.home}/junk", 2 * DAY)
        val item = JunkItem("temp", "safe", stale, SIZE.toLong(), JunkCache.SECTION_22)
        val row = AgentJunk("rtok", false, true, emptyList(), 0, emptyList(), listOf(item), emptyList(), 0, 0)
        return stale to JunkReport(listOf(row), emptyList(), 0, 0, 0)
    }

    @Test fun cPlansAndYRemovesOnlyThePlannedItems() {
        val cfg = config("junk")
        try {
            val (stale, report) = junkFixture(cfg)
            val a = TuiApp("hosts", TuiActions(cfg) { report })
            assertEquals(Effect.NONE, a.key(Key.Char("c")))
            assertTrue(a.junk.stage is TuiJunk.Stage.Plan)
            assertTrue("dry run, nothing removed" in screen(a))
            assertTrue(exists(stale))
            assertEquals(Effect.REFRESH, a.key(Key.Char("y")))
            assertFalse(exists(stale))
            assertTrue("clear safe junk: done" in screen(a), screen(a))
            assertEquals(Effect.NONE, a.key(Key.Esc), "any key closes the result, it does not quit")
            assertTrue(a.junk.stage is TuiJunk.Stage.Closed)
            assertEquals(Effect.QUIT, a.key(Key.Esc))
        } finally {
            removeDirAll(cfg.home)
        }
    }

    @Test fun anyOtherKeyDeclinesThePlan() {
        val cfg = config("junk-decline")
        try {
            val (stale, report) = junkFixture(cfg)
            val a = TuiApp("hosts", TuiActions(cfg) { report })
            a.key(Key.Char("c"))
            assertEquals(Effect.NONE, a.key(Key.Char("n")))
            assertTrue(a.junk.stage is TuiJunk.Stage.Closed)
            assertTrue(exists(stale))
            assertEquals(Effect.NONE, a.key(Key.Char("y")), "y with no plan open removes nothing")
            assertTrue(exists(stale))
        } finally {
            removeDirAll(cfg.home)
        }
    }
}
