package rtok.web.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class FramesTest {
    private val enc: (String) -> String = { it.replace("+", "%2B") }

    @Test fun wsUrlFollowsTheScheme() {
        assertEquals("ws://h:1/ws", wsUrl("http:", "h:1", null, enc))
        assertEquals("wss://h/ws?token=a%2Bb", wsUrl("https:", "h", "a+b", enc))
        assertEquals("ws://h/ws", wsUrl("http:", "h", "", enc))
    }

    @Test fun retryDoublesUpToTenSeconds() {
        assertEquals(listOf(500L, 1000L, 2000L, 4000L, 8000L, 10_000L, 10_000L), (0..6).map(::retryDelayMs))
        assertEquals(10_000L, retryDelayMs(100))
    }

    @Test fun snapshotNeedsPluginsElseItIsTheErrorFallback() {
        assertIs<Frame.Snapshot>(parseFrame("""{"type":"snapshot","plugins":[]}"""))
        assertEquals(Frame.SnapshotError("boom"), parseFrame("""{"type":"snapshot","error":"boom"}"""))
        assertNull(parseFrame("""{"type":"snapshot"}"""))
    }

    @Test fun repliesKeepTypeAndReq() {
        val f = assertIs<Frame.Reply>(parseFrame("""{"type":"message","text":"hi","req":7}"""))
        assertEquals("message", f.type)
        assertEquals(7, f.req)
        assertEquals("hi", f.body.str("text"))
    }

    @Test fun junkIsNull() {
        assertNull(parseFrame("not json"))
        assertNull(parseFrame("[1]"))
        assertNull(parseFrame("""{"type":1}"""))
    }

    @Test fun pagesRouteByFirstSegment() {
        assertEquals("overview", pageFor("/")?.id)
        assertEquals("agent_usage", pageFor("/usage")?.field)
        assertEquals("graph", pageFor("/graph/x")?.id)
        assertNull(pageFor("/nope"))
        assertEquals(15, PAGES.size)
    }
}
