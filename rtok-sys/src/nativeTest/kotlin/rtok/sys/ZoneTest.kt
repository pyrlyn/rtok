package rtok.sys

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val KYIV_LATE = 1_790_811_000L
private const val KYIV_MIDNIGHT_OCT1 = 1_790_802_000L

class ZoneTest {
    @Test fun aNamedZoneMovesTheDayAndItsMidnight() {
        val kyiv = Zone.named("Europe/Kyiv")!!
        // 2026-09-30T23:30:00Z is already October 1st in Kyiv (UTC+3 in summer).
        assertEquals("2026-10-01", kyiv.date(KYIV_LATE).toString())
        assertEquals("2026-09-30", Zone.named("UTC")!!.date(KYIV_LATE).toString())
        assertEquals(KYIV_MIDNIGHT_OCT1, kyiv.startOf(CivilDate.parse("2026-10-01")!!))
        assertEquals("Europe/Kyiv", kyiv.iana)
    }

    @Test fun unknownZonesAndBadDatesAreRefused() {
        assertNull(Zone.named("Mars/Olympus"))
        assertNull(Zone.named("../etc/passwd"))
        assertNull(CivilDate.parse("2026-02-29"))
        assertNull(CivilDate.parse("2026-9-30"))
        assertEquals("2024-02-29", CivilDate.parse("2024-02-28")!!.tomorrow().toString())
        assertEquals("2027-01-01", CivilDate.parse("2026-12-31")!!.tomorrow().toString())
    }
}
