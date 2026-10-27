package rtok.proxy

import kotlinx.coroutines.*
import kotlin.test.*

/** Port of src/proxy/gate.rs tests. */
class GateTest {
    @Test fun zeroMeansNoGate() = assertNull(Gate.of(0u, QUEUE))

    private companion object {
        const val QUEUE = 8u
    }

    @Test fun slotsThenLineThenTurnedAway() =
        runBlocking {
            val gate = Gate.of(1u, 1u)!!
            val first = gate.enter()!!
            val queued = async(Dispatchers.Default) { gate.enter() != null }
            while (gate.waiting() == 0) yield()
            assertNull(gate.enter(), "line of one is full")
            first.release()
            assertTrue(queued.await(), "the queued request gets the slot")
            assertEquals(0, gate.waiting())
        }

    @Test fun aDroppedWaiterLeavesTheLine() =
        runBlocking {
            val gate = Gate.of(1u, 1u)!!
            gate.enter()!!
            val waiter = launch(Dispatchers.Default) { gate.enter() }
            while (gate.waiting() == 0) yield()
            waiter.cancelAndJoin()
            assertEquals(0, gate.waiting())
        }
}
