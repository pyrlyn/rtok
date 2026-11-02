package humantime

import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

class HumantimeTest {
    @Test fun singleUnits() {
        assertEquals(30.minutes, parseHumanDuration("30m"))
        assertEquals(0.seconds, parseHumanDuration("0s"))
        assertEquals(7.days, parseHumanDuration("7d"))
        assertEquals(24.hours, parseHumanDuration("24h"))
        assertEquals(250.milliseconds, parseHumanDuration("250ms"))
        assertEquals(2_630_016.seconds, parseHumanDuration("1month"))
        assertEquals(31_557_600.seconds, parseHumanDuration("1y"))
    }

    @Test fun groupsAddUp() {
        assertEquals(90.minutes, parseHumanDuration("1h 30m"))
        assertEquals(90.minutes, parseHumanDuration("1h30min"))
    }

    @Test fun largeSubsecondUnitsDoNotWrap() {
        assertEquals(9_223_372_036_855L.milliseconds, parseHumanDuration("9223372036855ms"))
        assertEquals(9_223_372_036.seconds + 854_775_807.nanoseconds, parseHumanDuration("9223372036854775807ns"))
        assertEquals(18_000_000_000L.seconds, parseHumanDuration("9000000000000000000ns 9000000000000000000ns"))
    }

    @Test fun subsecondGroupsCarryIntoSeconds() {
        assertEquals(2.seconds, parseHumanDuration("999ms 1000us 1000000000ns"))
        assertEquals(2.seconds + 1.nanoseconds, parseHumanDuration("1999999999ns 2ns"))
    }

    @Test fun durationArithmeticOverflowIsRejected() {
        assertFailsWith<DurationParseException> { parseHumanDuration("9223372036854775807s 1000ms") }
        assertFailsWith<DurationParseException> { parseHumanDuration("9223372036854775807m") }
    }

    @Test fun errorsMatchHumantime() {
        assertEquals("value was empty", assertFailsWith<DurationParseException> { parseHumanDuration("  ") }.message)
        assertTrue(assertFailsWith<DurationParseException> { parseHumanDuration("5x") }.message!!.startsWith("unknown time unit \"x\""))
        assertEquals("expected number at 0", assertFailsWith<DurationParseException> { parseHumanDuration("m5") }.message)
        assertTrue(assertFailsWith<DurationParseException> { parseHumanDuration("5") }.message!!.startsWith("time unit needed"))
        assertFailsWith<DurationParseException> { parseHumanDuration("not-a-duration") }
    }
}
