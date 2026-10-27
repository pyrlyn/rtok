package globset

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlobTest {
    private fun kind(glob: String): GlobErrorKind? =
        try {
            Glob.new(glob)
            null
        } catch (e: GlobError) {
            e.kind
        }

    @Test fun reportsGlobsetSyntaxErrors() {
        listOf("[", "[]", "[!", "[!]").forEach { assertEquals(GlobErrorKind.UnclosedClass, kind(it), it) }
        assertEquals(GlobErrorKind.InvalidRange('z', 'a'), kind("[z-a]"))
        assertEquals(GlobErrorKind.InvalidRange('z', '-'), kind("[z--]"))
        assertEquals(GlobErrorKind.UnclosedAlternates, kind("{a,b"))
        assertEquals(GlobErrorKind.UnclosedAlternates, kind("{a,{b,c}"))
        assertEquals(GlobErrorKind.UnopenedAlternates, kind("a,b}"))
        assertEquals(GlobErrorKind.UnopenedAlternates, kind("{a,b}}"))
        assertEquals(GlobErrorKind.DanglingEscape, kind("a\\"))
    }

    @Test fun errorMessageIsGlobsets() {
        val e = runCatching { Glob.new("[z-a]") }.exceptionOrNull()
        assertEquals("error parsing glob '[z-a]': invalid range; 'z' > 'a'", e?.message)
    }

    @Test fun starCrossesSlashByDefault() {
        assertTrue(Glob.new("/home/*.log").isMatch("/home/a/b.log"))
        assertFalse(Glob.new("/home/*.log", GlobOptions(literalSeparator = true)).isMatch("/home/a/b.log"))
        assertTrue(Glob.new("/x/{a,b/**}").isMatch("/x/b/c/d"))
        assertTrue(Glob.new("{a,{b,c}}").isMatch("c"))
        assertFalse(Glob.new("*").isMatch("a\nb"))
    }

    @Test fun matchesUtf8Bytes() {
        assertTrue(Glob.new("caf?").isMatch("cafe"))
        assertFalse(Glob.new("caf?").isMatch("café"))
        assertTrue(Glob.new("caf??").isMatch("café"))
        assertTrue(Glob.new("café").isMatch("café"))
        assertTrue(GlobSet.EMPTY.isEmpty)
        assertFalse(GlobSet.EMPTY.isMatch("a"))
    }
}
