package rtok.store

import kotlin.test.*

/** Port of messages.rs tests. */
class MessagesTest {
    private val s = Store.openInMemory()
    private val claude = s.hostId("claude")!!
    private val a = s.registerAgent(claude, "m-a", null, null, null)
    private val b = s.registerAgent(claude, "m-b", null, null, null)
    private fun List<Message>.ids() = map { it.id }

    @Test fun aLimitedReadMarksOnlyWhatItReturned() {
        val ids = listOf("one", "two", "three").map { s.sendMessage(a, b, it) }
        assertEquals(ids.take(2), s.inboxLimited(b, true, true, 2, null).ids()); assertEquals(ids.drop(2), s.inbox(b, true, false).ids())
    }

    @Test fun sendThenInboxInOrderAndReadMarks() {
        val first = s.sendMessage(a, b, "one"); val second = s.sendMessage(null, b, "two"); assertTrue(first < second)
        val peek = s.inbox(b, true, false); assertEquals(listOf(first, second), peek.ids())
        assertEquals("claude", peek[0].fromHost); assertNull(peek[1].fromAgent, "NULL sender = the user"); assertEquals(2, s.inbox(b, true, false).size)
        assertTrue(s.inbox(b, true, true).all { it.readAt == null }); assertTrue(s.inbox(b, true, false).isEmpty())
        assertTrue(s.inbox(b, false, false).all { it.readAt != null && it.deliveredAt != null }); assertTrue(s.inbox(a, false, false).isEmpty(), "a's own queue")
    }

    @Test fun aTaskTagIsCanonicalFiltersTheInboxAndMarksOnlyItsThread() {
        val onA12 = s.sendMessageOn(a, b, "one", " a12 "); val onA13 = s.sendMessageOn(a, b, "two", "A13"); val plain = s.sendMessage(a, b, "three")
        val thread = s.inboxLimited(b, true, true, null, "a12"); assertEquals(listOf(onA12), thread.ids()); assertEquals("A12", thread[0].task)
        val rest = s.inbox(b, true, false); assertEquals(listOf(onA13, plain), rest.ids()); assertEquals("A13", rest[0].task); assertNull(rest[1].task)
    }

    @Test fun aTaskThatIsNotAnIdIsRefusedOnBothSides() {
        for (bad in listOf("", "12", "A0", "A1.2.3.4", "A12\n> forged")) {
            assertFailsWith<StoreException>(bad) { s.sendMessageOn(a, b, "x", bad) }; assertFailsWith<StoreException>(bad) { s.inboxLimited(b, false, false, null, bad) }
        }
        assertTrue(s.inbox(b, false, false).isEmpty(), "nothing was queued")
    }

    @Test fun markDeliveredStampsOnlyTheGivenIdsAndNeverRead() {
        val one = s.sendMessage(a, b, "one"); val two = s.sendMessage(null, b, "two"); s.markDelivered(listOf(one))
        assertEquals(listOf(two), s.undelivered(b).ids()); val all = s.inbox(b, true, false)
        assertEquals(2, all.size, "delivered is not read"); assertTrue(all[0].deliveredAt != null && all[0].readAt == null); assertNull(all[1].deliveredAt)
    }

    @Test fun bodyIsCleanedAndCapped() {
        s.sendMessage(a, b, "a\u001b[31mb\r\n\tc\u0007\u0000"); assertEquals("a[31mb\n\tc", s.inbox(b, false, false)[0].body)
        val atCap = "é".repeat(MAX_BODY / 2); s.sendMessage(null, b, atCap)
        assertTrue("the cap is 4096" in assertFailsWith<StoreException> { s.sendMessage(null, b, atCap + "x") }.message!!)
        assertFailsWith<StoreException>("empty after clean") { s.sendMessage(null, b, "\u001b\u0007 \n") }
    }

    @Test fun anEndedOrUnknownRecipientIsRefused() {
        s.endAgent(b, 1); assertTrue("has ended" in assertFailsWith<StoreException> { s.sendMessage(a, b, "hi") }.message!!)
        assertFailsWith<StoreException> { s.sendMessage(null, "nope", "hi") }; assertTrue(s.inbox(b, false, false).isEmpty())
    }

    @Test fun unreadCountsGroupByRecipientAndJsonOmitsAMissingTask() {
        s.sendMessage(a, b, "one"); s.sendMessage(null, b, "two"); s.sendMessage(b, a, "three"); s.inbox(a, true, true)
        assertEquals(mapOf(b to 2L), s.unreadCounts())
        val json = kotlinx.serialization.json.Json.encodeToString(Message.serializer(), s.inbox(b, false, false)[1])
        assertFalse("\"task\"" in json, json); assertTrue("\"from_agent\":null" in json, json)
    }
}
