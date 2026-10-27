// Tests of the live.rs reader and poller port: the cursor, the replay since start and the subscription's lifetime.
package rtok.web

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rtok.config.Config
import rtok.config.Graph
import rtok.store.EventPhase
import rtok.store.GraphEvent
import rtok.store.Store
import rtok.store.insertGraphEvent
import rtok.support.tmpDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val FAST_POLL_MS = 20L
private const val WAIT_MS = 5_000L
private const val SINCE_START = 3
private const val ENDS = 30
private const val RATE = 40u
private const val PER_POLL = 10

class LiveTest {
    private fun config(tag: String): Config {
        val dir = tmpDir("live-$tag")
        val c = Config.loadFrom(dir)
        return c.copy(core = c.core.copy(dbPath = "$dir/rtok.db", archiveDir = "$dir/archive")).also { it.home = dir }
    }

    private fun write(
        cfg: Config,
        vararg events: GraphEvent,
    ) = Store.open(cfg.core.dbPath).use { s -> events.forEach { s.insertGraphEvent(it) } }

    private fun ev(
        call: String,
        phase: EventPhase,
    ) = GraphEvent.new(call, phase, "mcp-1234567890", "callers")

    @Test
    fun aReaderStartsAtTheNewestEventAndReadsOnlyWhatCameAfter() {
        val cfg = config("reader")
        write(cfg, ev("old", EventPhase.Start))
        val r = assertNotNull(Reader.open(cfg.core.dbPath, Graph()))
        r.use {
            assertNull(it.poll(), "nothing new")
            write(cfg, ev("a", EventPhase.Start), ev("a", EventPhase.End), ev("b", EventPhase.Start))
            val b = assertNotNull(it.poll())
            assertEquals(listOf("a", "b"), b.events.map { e -> e.call })
            assertEquals(setOf("a"), b.ended)
            assertEquals("mcp-1234", b.caller("mcp-1234567890"), "the store names no such session")
            assertNull(it.poll(), "the cursor moved")
        }
    }

    @Test
    fun aReplayFoldsTheEventsSinceAGivenIdInChunksOfOnePoll() {
        val cfg = config("replay")
        write(cfg, ev("before", EventPhase.End))
        val since = assertNotNull(headOf(cfg.core.dbPath))
        val ends = (0 until ENDS).map { ev("c$it", EventPhase.End) }
        write(cfg, *ends.toTypedArray())
        Reader.open(cfg.core.dbPath, Graph(liveMaxEventsPerS = RATE))!!.use { r ->
            val chunks = r.replay(since)
            assertEquals(List(ENDS / PER_POLL) { PER_POLL }, chunks.map { it.first.events.size })
            assertEquals(ENDS.toLong(), chunks.sumOf { it.first.summary.ends })
        }
    }

    @Test
    fun theFirstSubscriberGetsTheCallsSinceStartAndThePollerStopsWhenNobodyListens() {
        val cfg = config("subscribe")
        write(cfg, ev("before", EventPhase.End))
        val live = LiveCalls({ cfg }, pollMs = FAST_POLL_MS)
        write(cfg, ev("a", EventPhase.End), ev("b", EventPhase.End))
        runBlocking {
            val (first, flow) = live.subscribe()
            val v = Json.parseToJsonElement(first).jsonObject
            assertEquals("calls", v["type"]!!.jsonPrimitive.content)
            val since = v["calls"]!!.jsonObject["windows"]!!.jsonArray[SINCE_START].jsonObject
            assertEquals("2", since["calls"]!!.jsonPrimitive.content, "only the events written after the server started")
            assertTrue(live.polling)
            val listener = launch { flow.collect {} }
            write(cfg, ev("c", EventPhase.End))
            withTimeout(WAIT_MS) { flow.first { it.contains("\"call\":\"c\"") } }
            listener.cancel()
            withTimeout(WAIT_MS) { while (live.polling) delay(FAST_POLL_MS) }
            assertFalse(live.polling)
        }
    }
}
