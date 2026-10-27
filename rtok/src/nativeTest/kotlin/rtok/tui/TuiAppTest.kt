package rtok.tui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import tui.Buffer
import tui.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TuiAppTest {
    private val compactCases = listOf(999.0 to "999", 1234.0 to "1.2k", 3_456_789.0 to "3.5M")

    private fun snap(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test fun startTabFromConfigOrFirst() {
        assertEquals("plugins", TuiApp("plugins").page)
        assertEquals("overview", TuiApp("no-such-page").page)
        assertEquals("overview", TuiApp("").page)
    }

    @Test fun keysWrapJumpAndQuit() {
        val a = TuiApp("")
        a.key(Key.Left)
        assertEquals("tasks", a.page)
        a.key(Key.Right)
        assertEquals("overview", a.page)
        a.key(Key.Char("3"))
        assertEquals("calls", a.page)
        a.key(Key.Char("0"))
        assertEquals("calls", a.page)
        assertEquals(Effect.REFRESH, a.key(Key.Char("r")))
        assertEquals(Effect.QUIT, a.key(Key.Char("q")))
        assertEquals(Effect.QUIT, a.key(Key.Ctrl('c')))
        a.key(Key.Char("?"))
        assertTrue(a.help)
        assertEquals(Effect.NONE, a.key(Key.Char("q")))
        assertEquals(false, a.help)
    }

    @Test fun cursorStaysInRange() {
        val a = TuiApp("plugins")
        a.snapshot = snap("""{"plugins":[{"id":"a"},{"id":"b"}]}""")
        a.key(Key.Down)
        a.key(Key.Down)
        a.key(Key.Down)
        assertEquals(1, a.cursor)
        a.key(Key.Home)
        assertEquals(0, a.cursor)
    }

    @Test fun drawsTablesAndNotReady() {
        val a = TuiApp("plugins")
        a.snapshot =
            snap(
                """{"plugins":[{"id":"git","title":"Git","enabled":true,"saves_tokens":true,"surfaces":["hook"],
                   "stats":{"rows":3,"est_before":2000,"est_after":500},"summary":"Compact git"}],"doctor":null}""",
            )
        val b = Buffer(80, 8)
        draw(b, a, { "00:00:00" })
        val text = b.lines().joinToString("\n")
        assertTrue("Git" in text && "1.5k" in text && "●" in text, text)
        a.select(TABS.indexOfFirst { it.first == "doctor" })
        val b2 = Buffer(80, 8)
        draw(b2, a, { "00:00:00" })
        assertTrue("not ready" in b2.lines().joinToString("\n"))
    }

    @Test fun ttyGuardAndCompact() {
        assertNull(checkTty(true, true))
        assertNotNull(checkTty(false, true))
        assertNotNull(checkTty(true, false))
        compactCases.forEach { (n, want) -> assertEquals(want, compact(n)) }
        assertEquals("-", compact(null))
    }
}
