// Port of src/web/mod.rs socket tests: T206 build coalescing, the reply envelope and the socket loop.
package rtok.web

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CONCURRENT = 8
private const val SLOW_US = 200_000u
private const val FAST_TICK_MS = 50L
private const val REQ = 7L
private const val SLOW_TICK_MS = 60_000L
private const val STEP_MS = 100L
private const val SUBSCRIBE = """{"calls":{"subscribe":true}}"""
private const val UNSUBSCRIBE = """{"calls":{"subscribe":false}}"""

private class FakeSocket(
    val incoming: Channel<String?> = Channel(Channel.UNLIMITED),
) : rtok.http.server.WsSocket {
    val sent = mutableListOf<String>()

    override suspend fun send(text: String): Boolean {
        sent += text
        return true
    }

    override suspend fun receive(): String? = incoming.receive()
}

class SocketTest {
    @Test
    fun ticksLandingDuringABuildShareIt() {
        val builds = AtomicInt(0)
        val snaps =
            Snapshots {
                builds.incrementAndGet()
                platform.posix.usleep(SLOW_US)
                "snap"
            }
        val got = runBlocking { (1..CONCURRENT).map { async { snaps.shared() } }.awaitAll() }
        assertTrue(got.all { it == "snap" })
        assertEquals(1, builds.value)
    }

    @Test
    fun aFailedBuildIsAnErrorSnapshotAndTheNextBuilds() {
        var n = 0
        val snaps = Snapshots { if (n++ == 0) error("boom") else "ok" }
        runBlocking {
            assertTrue(snaps.shared().contains("\"error\""))
            assertEquals("ok", snaps.shared())
        }
    }

    @Test
    fun tagReplyPutsReqFirst() {
        assertEquals("""{"req":7,"type":"message","text":"x"}""", tagReply("""{"type":"message","text":"x"}""", REQ))
        assertEquals("""{"type":"x"}""", tagReply("""{"type":"x"}""", null))
        assertEquals("[]", tagReply("[]", REQ))
    }

    @Test
    fun socketSendsASnapshotFirstThenRepliesAndStopsOnClose() {
        val app = WebApp(BoundAddr("127.0.0.1", 1), NoAssets, null)
        val ep = WsEndpoint(app, Snapshots { "S" }, { t -> if (t == "ping") "pong" else null }, tickMs = FAST_TICK_MS)
        val sock = FakeSocket()
        runBlocking {
            sock.incoming.send("ping")
            sock.incoming.send("ignored")
            sock.incoming.send(null)
            ep.run(sock)
        }
        assertEquals("S", sock.sent.first())
        assertTrue("pong" in sock.sent)
        // Every inbound frame makes the next snapshot due.
        assertTrue(sock.sent.count { it == "S" } >= 2)
    }

    @Test
    fun aCallsSubscriptionIsTheSocketsAndNotAnInboundRequest() {
        assertEquals(true, callsSubscription("""{"calls":{"subscribe":true}}"""))
        assertEquals(false, callsSubscription("""{"calls":{"subscribe":false},"req":1}"""))
        assertNull(callsSubscription("""{"calls":{"subscribe":"yes"}}"""))
        assertNull(callsSubscription("""{"calls":{}}"""))
        assertNull(callsSubscription("""{"expand":"ab","calls":{"subscribe":true}}"""), "an earlier variant wins")
        assertNull(callsSubscription("not json"))
    }

    @Test
    fun aSubscribedSocketGetsTheTotalsSoFarThenEachChangeUntilItUnsubscribes() {
        val app = WebApp(BoundAddr("127.0.0.1", 1), NoAssets, null)
        val flow = MutableStateFlow("C0")
        val subscribes = AtomicInt(0)
        val feed =
            CallsFeed {
                subscribes.incrementAndGet()
                flow.value to flow
            }
        val ep = WsEndpoint(app, Snapshots { "S" }, { "reply:$it" }, tickMs = SLOW_TICK_MS, calls = feed)
        val sock = FakeSocket()
        runBlocking {
            launch {
                sock.incoming.send(SUBSCRIBE)
                delay(STEP_MS)
                flow.value = "C1"
                delay(STEP_MS)
                sock.incoming.send(UNSUBSCRIBE)
                delay(STEP_MS)
                flow.value = "C2"
                delay(STEP_MS)
                sock.incoming.send(null)
            }
            ep.run(sock)
        }
        assertEquals(listOf("S", "C0", "C1"), sock.sent, "no reply and no snapshot for the subscription itself; nothing after leaving")
        assertEquals(1, subscribes.value)
    }
}
