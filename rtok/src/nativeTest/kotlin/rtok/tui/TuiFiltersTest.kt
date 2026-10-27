// Port of the filter tests of src/tui/app.rs and view.rs: `l` on Sessions (T60.10), `/` on Config (T228, T637) and
// the Calls expand pane (`e`, `/`, T60.4).
package rtok.tui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import rtok.config.Config
import rtok.support.tmpDir
import tui.Buffer
import tui.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val COLS = 100
private const val ROWS = 16

class TuiFiltersTest {
    private fun snap(s: String) = Json.parseToJsonElement(s) as JsonObject

    private fun screen(a: TuiApp) = Buffer(COLS, ROWS).also { draw(it, a, { "00:00:00" }) }.lines().joinToString("\n")

    private fun type(
        a: TuiApp,
        s: String,
    ) = s.forEach { a.key(Key.Char(it.toString())) }

    @Test fun lNarrowsSessionsToLiveOnes() {
        val a = TuiApp("sessions")
        a.snapshot = snap("""{"sessions":[{"id":"aaa-live","ended_at":null},{"id":"bbb-ended","ended_at":5},{"id":"ccc-live"}]}""")
        a.key(Key.End)
        assertEquals(2, a.cursor)
        a.key(Key.Char("l"))
        assertTrue(a.liveOnly)
        assertEquals(2, a.rows())
        assertEquals(1, a.cursor, "the cursor is clamped to the narrowed list")
        val s = screen(a)
        assertTrue("aaa-live" in s && "ccc-live" in s && "bbb-ended" !in s, s)
        a.key(Key.Char("l"))
        assertTrue("bbb-ended" in screen(a))
    }

    @Test fun configSlashFiltersAndOwnsGlobalKeys() {
        val a = TuiApp("config")
        a.snapshot = snap("""{"config":"[proxy]\nport = 1\n[tui]\ntab = \"calls\""}""")
        a.key(Key.Char("/"))
        type(a, "proxy?r")
        assertEquals("proxy?r", a.configFilter.text)
        assertTrue(a.configFilter.typing)
        a.key(Key.Backspace)
        assertEquals("proxy?", a.configFilter.text)
        assertEquals(Effect.NONE, a.key(Key.Char("q")), "q is text while filtering")
        a.key(Key.Backspace)
        a.key(Key.Backspace)
        a.key(Key.Enter)
        assertFalse(a.configFilter.typing)
        val s = screen(a)
        assertTrue("/prox" in s && "1:[proxy]" in s && "tab =" !in s, s)
        assertEquals(Effect.QUIT, a.key(Key.Char("q")), "closed, q quits again")
    }

    @Test fun eOpensTheArchivePaneAndSlashFiltersIt() {
        val dir = tmpDir("tui-expand")
        val cfg = Config.loadFrom(dir).also { it.home = dir }
        val seen = mutableListOf<String>()
        val actions =
            TuiActions(cfg, expander = { _, id ->
                seen += id
                "NEEDLELINE\nother-line"
            })
        val a = TuiApp("calls", actions)
        a.snapshot = snap("""{"calls":[{"id":7,"name":"Bash","ok":true}],"ref_ids":{"7":"abc123"}}""")
        a.key(Key.Char("e"))
        assertEquals(listOf("abc123"), seen)
        val open = screen(a)
        assertTrue("expand abc123" in open && "NEEDLELINE" in open && "other-line" in open, open)
        a.key(Key.Char("/"))
        type(a, "NEEDLE")
        val filtered = screen(a)
        assertTrue("NEEDLELINE" in filtered && "other-line" !in filtered && "/NEEDLE" in filtered, filtered)
        a.key(Key.Esc)
        assertTrue(a.expand != null, "Esc stops typing first")
        a.key(Key.Esc)
        assertNull(a.expand)
        assertEquals(Effect.QUIT, a.key(Key.Esc))
    }

    @Test fun eWithoutAnArchiveStaysClosed() {
        val a = TuiApp("calls")
        a.snapshot = snap("""{"calls":[{"id":1}],"ref_ids":{}}""")
        assertEquals(Effect.NONE, a.key(Key.Char("e")))
        assertNull(a.expand)
    }
}
