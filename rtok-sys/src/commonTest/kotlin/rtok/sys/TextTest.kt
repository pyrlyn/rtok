package rtok.sys

import kotlin.test.*

class TextTest {
    @Test fun asciiLowercaseLeavesNonAsciiAlone() = assertEquals("abcÉ_1", "ABcÉ_1".asciiLowercase())
    @Test fun utf8OrderMatchesRustAboveTheBmp() {
        assertTrue(compareUtf8("\uFFFF", "\uD83D\uDE00") < 0, "U+FFFF sorts before an emoji, as in UTF-8") // UTF-16 order says the opposite
        assertTrue(compareUtf8("a", "ab") < 0); assertEquals(0, compareUtf8("x", "x")); assertTrue(compareUtf8("b", "a") > 0)
    }
}
