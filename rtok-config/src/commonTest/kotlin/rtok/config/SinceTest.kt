// since.rs has no unit tests in Rust (covered by tests/stats*.rs); these pin the grammar.
package rtok.config

import kotlin.test.*

class SinceTest {
    @Test fun bareIsDays() = assertEquals(7 * 86_400L, parseSince("7"))
    @Test fun daysAndHours() { assertEquals(86_400L, parseSince("1d")); assertEquals(3 * 3600L, parseSince(" 3h ")) }
    @Test fun badUnitNamesTheSource() = assertEquals("bad stats.since unit in 3w", assertFailsWith<ConfigException> { parseSinceFrom("3w", "stats.since") }.message)
    @Test fun badNumber() = assertEquals("bad --since d", assertFailsWith<ConfigException> { parseSince("d") }.message)
    @Test fun overflowIsAnError() = assertTrue(assertFailsWith<ConfigException> { parseSince("99999999999999999d") }.message!!.endsWith("out of range"))
}
