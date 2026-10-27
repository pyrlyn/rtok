package tui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TuiTest {
    @Test fun widthsAndFitting() {
        assertEquals(5, width("hello"))
        assertEquals(4, width("日本"))
        assertEquals(2, width("😀"))
        assertEquals(1, width("e\u0301"))
        assertEquals("hel…", truncate("hello", 4))
        assertEquals("hello", truncate("hello", 5))
        assertEquals("ab  ", fit("ab", 4))
        assertEquals("  ab", fit("ab", 4, right = true))
        assertEquals("日…", truncate("日本語", 4))
    }

    @Test fun sgr() {
        assertEquals("\u001b[0m", Style.Default.sgr())
        assertEquals("\u001b[0;1;31m", Style(fg = Color.Red, bold = true).sgr())
        assertEquals("\u001b[0;90;48;5;236m", Style(fg = Color.Gray, bg = Color.Indexed(236)).sgr())
    }

    @Test fun bufferPutClipsAndWide() {
        val b = Buffer(6, 2)
        assertEquals(6, b.put(4, 0, "abc"))
        assertEquals(listOf("    ab", "      "), b.lines())
        b.put(0, 1, "日x")
        assertEquals("日x   ", b.lines()[1])
        assertEquals("", b[1, 1].text)
        b.put(5, 1, "日")
        assertEquals(" ", b[5, 1].text)
    }

    @Test fun diffSendsOnlyChanges() {
        val a = Buffer(3, 1).apply { put(0, 0, "abc") }
        val full = a.diff(null)
        assertTrue(full.startsWith("\u001b[0m\u001b[2J\u001b[1;1H"))
        val b = Buffer(3, 1).apply { put(0, 0, "abX") }
        assertEquals("\u001b[1;3H\u001b[0mX\u001b[0m", b.diff(a))
        assertEquals("", b.diff(b))
    }

    @Test fun layout() {
        assertEquals(listOf(1, 7, 2), lengths(10, listOf(Size.Fixed(1), Size.Fill(), Size.Percent(20))))
        assertEquals(listOf(3, 4), lengths(7, listOf(Size.Fill(), Size.Fill())))
        assertEquals(listOf(5, 0), lengths(5, listOf(Size.Fixed(8), Size.Fill())))
        val (top, body) = Rect(0, 0, 10, 10).rows(Size.Fixed(1), Size.Fill())
        assertEquals(Rect(0, 1, 10, 9), body)
        assertEquals(Rect(0, 0, 10, 1), top)
        assertEquals(listOf(Rect(0, 0, 4, 2), Rect(4, 0, 6, 2)), Rect(0, 0, 10, 2).cols(Size.Fixed(4), Size.Fill()))
    }

    @Test fun widgetsRender() {
        val b = Buffer(12, 4)
        val inner = b.block(b.area, "hi")
        assertEquals(Rect(1, 1, 10, 2), inner)
        assertEquals("╭─ hi ─────╮", b.lines()[0])
        assertEquals("╰──────────╯", b.lines()[3])
        b.table(inner, listOf(Col("a", 3), Col("b", right = true)), listOf(listOf("x", "12")))
        assertEquals("│a   b     │".replace("b     ", "     b"), b.lines()[1])
        assertEquals("│x       12│", b.lines()[2])
        assertEquals("▁▄█", sparkline(listOf(0.0, 1.0, 2.0), 3))
        assertEquals(3, follow(5, 0, 3))
        assertEquals(1, follow(1, 4, 3))
        assertEquals(2, follow(3, 2, 3))
    }

    @Test fun keys() {
        fun k(s: String) = decodeKeys(s.encodeToByteArray())
        assertEquals(listOf(Key.Char("q")), k("q"))
        assertEquals(listOf(Key.Up, Key.Down), k("\u001b[A\u001b[B"))
        assertEquals(listOf(Key.PageDown, Key.BackTab), k("\u001b[6~\u001b[Z"))
        assertEquals(listOf(Key.Esc), k("\u001b"))
        assertEquals(listOf(Key.Ctrl('c'), Key.Enter, Key.Tab, Key.Backspace), k("\u0003\r\t\u007f"))
        assertEquals(listOf(Key.Char("я"), Key.Home), k("я\u001bOH"))
    }

    @Test fun wrapCutsSpansAtTheWidthAndKeepsTheirStyle() {
        val bold = Style(bold = true)
        val rows = wrap(listOf(Span("abc", bold), Span("defg")), 4)
        assertEquals(listOf(listOf(Span("abc", bold), Span("d")), listOf(Span("efg"))), rows)
        assertEquals(listOf(emptyList<Span>()), wrap(emptyList(), 4))
    }
}
