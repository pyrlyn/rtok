// Port of src/web/mod.rs socket tests: T206 build coalescing, the reply envelope and the socket loop.
package rtok.web

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val CONCURRENT = 8
private const val SLOW_US = 200_000u
private const val FAST_TICK_MS = 50L
private const val REQ = 7L

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
}
