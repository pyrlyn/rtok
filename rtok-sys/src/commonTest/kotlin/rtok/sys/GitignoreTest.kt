package rtok.sys

import kotlin.test.*

class GitignoreTest {
    private val g = Gitignore("/r", "# c\n*.log\n!keep.log\nbuild/\n/top.txt\ndocs/**/gen\nsrc/*.tmp\n[ab].x\n")

    @Test fun basenameGlobsMatchAtAnyDepth() {
        assertEquals(true, g.matched("/r/a.log", false))
        assertEquals(true, g.matched("/r/x/y/a.log", false))
        assertEquals(false, g.matched("/r/x/keep.log", false))
        assertNull(g.matched("/r/a.txt", false))
    }

    @Test fun dirOnlyAndAnchoredRules() {
        assertEquals(true, g.matched("/r/x/build", true))
        assertNull(g.matched("/r/x/build", false))
        assertEquals(true, g.matched("/r/top.txt", false))
        assertNull(g.matched("/r/x/top.txt", false))
        assertEquals(true, g.matched("/r/src/a.tmp", false))
        assertNull(g.matched("/r/src/x/a.tmp", false))
    }

    @Test fun doubleStarAndClasses() {
        assertEquals(true, g.matched("/r/docs/gen", true))
        assertEquals(true, g.matched("/r/docs/a/b/gen", true))
        assertEquals(true, g.matched("/r/a.x", false))
        assertNull(g.matched("/r/c.x", false))
        assertNull(g.matched("/elsewhere/a.log", false))
    }
}
