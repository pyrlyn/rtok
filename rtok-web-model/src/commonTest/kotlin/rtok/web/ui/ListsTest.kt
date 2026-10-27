package rtok.web.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ListsTest {
    private fun o(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test fun okIsTruthyLikeJs() {
        assertTrue(o("""{"ok":1}""").flag("ok"))
        assertFalse(o("""{"ok":0}""").flag("ok"))
        assertTrue(o("""{"ok":true}""").flag("ok"))
        assertFalse(o("""{"ok":"1"}""").flag("ok"))
        assertFalse(o("{}").flag("ok"))
    }

    @Test fun callsFilterBySurfaceResultAndText() {
        val c = o("""{"surface":"mcp","ok":0,"name":"Read","session":"abc"}""")
        assertTrue(matchesCall(c, "all", "failed", "read"))
        assertTrue(matchesCall(c, "mcp", "any", "ABC"))
        assertFalse(matchesCall(c, "hook", "any", ""))
        assertFalse(matchesCall(c, "all", "ok", ""))
        assertEquals(10.0, tokensOf(o("""{"input":4,"cache_read":1,"output":5}""")))
        assertNull(tokensOf(o("""{"input":null}""")))
    }

    @Test fun sessionsFilterLiveAndText() {
        val s = o("""{"id":"s1","host":"claude","ended_at":null}""")
        assertTrue(matchesSession(s, true, "clau"))
        assertFalse(matchesSession(o("""{"id":"s2","ended_at":5}"""), true, ""))
    }

    @Test fun logsParseTheShapeOrKeepTheLine() {
        val l = parseLog("2026-10-27 04:00:00 WARN rtok/web: slow tick", 3)
        assertEquals(LogLine(3, l.raw, "2026-10-27 04:00:00", "warn", "rtok", "web", "slow tick"), l)
        assertEquals("info", parseLog("free text", 0).level)
        assertTrue(matchesLog(l, "warn", "SLOW"))
        assertFalse(matchesLog(l, "error", ""))
    }
}
