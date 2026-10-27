// Port of view.rs `help_overlay_lists_the_keys_and_toggles` (T60.8) and the KEYS table's shape.
@file:Suppress("MagicNumber")

package rtok.tui

import tui.Buffer
import tui.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TuiKeysTest {
    private fun screen(a: TuiApp) = Buffer(120, 40).also { draw(it, a, { "00:00:00" }) }.lines().joinToString("\n")

    @Test fun helpOverlayListsThePagesKeysAndToggles() {
        val a = TuiApp("sessions")
        assertFalse("keys —" in screen(a), "closed until ?")
        a.key(Key.Char("?"))
        val open = screen(a)
        assertTrue("keys — sessions" in open, open)
        assertTrue("refresh now" in open && "live-only filter" in open, open)
        assertFalse("toggle plugin" in open, "another page's rows stay off: $open")
        assertTrue("? closes" in open, open)
        a.key(Key.Char("?"))
        assertFalse("keys —" in screen(a), "? closes the overlay")
    }

    @Test fun keysForPutsGlobalsFirst() {
        val rows = keysFor("calls")
        assertEquals("q/Esc", rows.first().first)
        assertEquals(listOf("↑/↓", "Enter/z", "e", "/"), rows.drop(KEYS.count { it.first.isEmpty() }).map { it.first })
        assertTrue(keysFor("no-such-page").all { k -> KEYS.any { it.first.isEmpty() && it.second == k.first } })
    }

    @Test fun footerComesFromTheTable() {
        val s = screen(TuiApp("config"))
        assertTrue("/ filter entries" in s, s)
    }
}
