// Port of the detail-pane tests of src/tui/view.rs and app.rs: Calls Enter/z (T15.5), Sessions Enter (T60.3).
package rtok.tui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import tui.Buffer
import tui.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val COLS = 120
private const val ROWS = 30

class TuiDetailTest {
    private fun snap(s: String) = Json.parseToJsonElement(s) as JsonObject

    private fun screen(a: TuiApp) = Buffer(COLS, ROWS).also { draw(it, a, { "00:00:00" }) }.lines().joinToString("\n")

    private val calls =
        """{"calls":[
        {"id":1,"ts":60,"session":"s1","surface":"hook","kind":"hook","name":"Bash","ok":1},
        {"id":2,"ts":61,"session":"s1","surface":"mcp","kind":"tool","name":"read","ok":0,"error":"boom","ms":12.5,
         "api":"anthropic","input":10,"cache_create":1,"cache_read":2,"output":3}],
        "ref_ids":{"2":"r2"}}"""

    @Test fun enterAndZToggleTheCallDetailThatFollowsTheCursor() {
        val a = TuiApp("calls")
        a.key(Key.Enter)
        assertFalse(a.callsDetail, "Enter does nothing with no rows")
        a.snapshot = snap(calls)
        assertFalse("usage (" in screen(a), "no detail until Enter/z")
        a.key(Key.Enter)
        assertTrue(a.callsDetail)
        val first = screen(a)
        assertTrue("call 1" in first && "usage no row linked" in first && "ref_id -" in first, first)
        a.key(Key.Down)
        val second = screen(a)
        assertTrue("call 2" in second && "error boom" in second && "ref_id r2" in second, second)
        assertTrue("usage (anthropic) input 10 cache create 1 cache read 2 output 3 = 16 tok" in second, second)
        assertTrue("ms 12.5" in second && "ts 1970-01-01 00:01:01 UTC" in second, second)
        a.key(Key.Char("z"))
        assertFalse(a.callsDetail, "z closes what Enter opened")
    }

    @Test fun enterOpensTheSessionDetailPane() {
        val a = TuiApp("sessions")
        a.key(Key.Enter)
        assertFalse(a.sessionsDetail)
        a.snapshot =
            snap(
                """{"sessions":[{"id":"s1","project":"rtok","api":"anthropic","input":30,"cache_create":1,"cache_read":7,
                "output":7,"started_at":1,"last_activity":2,"ended_at":null}],
                "calls":[{"id":1,"ts":5,"session":"s1","surface":"hook","kind":"hook","name":"Bash","ok":1},
                {"id":2,"ts":5,"session":"other","surface":"mcp","kind":"tool","name":"x","ok":1}]}""",
            )
        assertFalse("project rtok" in screen(a), "no detail until Enter")
        a.key(Key.Enter)
        val s = screen(a)
        assertTrue("project rtok · api anthropic" in s && "ended live" in s && "calls 1" in s, s)
        assertTrue("usage (anthropic) input 30 cache create 1 cache read 7 output 7" in s, s)
        assertTrue("00:00:00 hook hook Bash" in s, s)
        a.key(Key.Enter)
        assertEquals(false, a.sessionsDetail)
    }
}
