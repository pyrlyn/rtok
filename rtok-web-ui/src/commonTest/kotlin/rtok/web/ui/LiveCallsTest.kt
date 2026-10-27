package rtok.web.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class LiveCallsTest {
    private val feed =
        (
            Json.parseToJsonElement(
                """[{"caller":"claude","tool":"refs","project":"rtok"},{"caller":"codex","tool":"defs","project":null},
                   {"caller":"claude","tool":"defs","project":"ketch"}]""",
            ) as JsonArray
        ).map { it as JsonObject }

    @Test fun filtersByEverySelect() {
        assertEquals(3, filterFeed(feed, FeedFilter()).size)
        assertEquals(2, filterFeed(feed, FeedFilter(caller = "claude")).size)
        assertEquals(1, filterFeed(feed, FeedFilter(caller = "claude", tool = "defs")).size)
        assertEquals(0, filterFeed(feed, FeedFilter(project = "none")).size)
    }

    @Test fun distinctSortedNonEmpty() {
        assertEquals(listOf("ketch", "rtok"), distinct(feed, "project"))
        assertEquals(listOf("claude", "codex"), distinct(feed, "caller"))
    }

    @Test fun totalsAndSubscription() {
        val v = Json.parseToJsonElement("""{"windows":[{"calls":2},{"calls":9}]}""") as JsonObject
        assertEquals(9.0, totalCalls(v))
        assertEquals("""{"calls":{"subscribe":true}}""", callsSubscribe(true))
    }
}
