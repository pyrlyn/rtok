package rtok.web.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Fixture and cases from web/src/pages/textFixtures.ts and text.test.ts.
class ServicesTest {
    private val text =
        listOf(
            "proxy  running  pid=48211  uptime=18342s  log=~/.rtok/demon/proxy.log",
            "mcp  stopped  pid=-  uptime=-  log=~/.rtok/demon/mcp.log",
            "web  running  pid=48230  uptime=18339s  log=~/.rtok/demon/web.log",
            "otel endpoint=http://127.0.0.1:4318 calls_mark=5102 calls_pending=18 logs_mark=2210 logs_pending=0 sessions_mark=311",
            "last flush: [info] otel exported 120 calls, 40 logs",
            "",
        ).joinToString("\n")

    @Test fun services() {
        val v = parseServices(text)
        assertEquals(
            listOf(
                ServiceRow("proxy", true, "48211", 18_342, "~/.rtok/demon/proxy.log"),
                ServiceRow("mcp", false, null, null, "~/.rtok/demon/mcp.log"),
                ServiceRow("web", true, "48230", 18_339, "~/.rtok/demon/web.log"),
            ),
            v.services,
        )
        assertEquals(OtelView("http://127.0.0.1:4318", 5102, 18, 2210, 0, 311), v.otel)
        assertTrue(v.lastFlush!!.contains("otel exported 120 calls"))
        assertNull(parseServices("proxy  stopped  pid=-  uptime=-  log=/l\n").otel)
        assertEquals("rtok demon start mcp", serviceCommand(v.services[1]))
    }

    @Test fun helpers() {
        assertEquals(listOf("0m 59s", "2m 5s", "5h 5m", "-"), listOf(59L, 125L, 18_342L, null).map(::humanSecs))
        assertEquals(mapOf("input" to "1", "hit" to "85.0%", "median_context" to "9"), kvPairs("usage input=1 hit=85.0%  median_context=9"))
    }
}
