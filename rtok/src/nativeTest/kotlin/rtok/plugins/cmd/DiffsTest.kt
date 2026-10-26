// Port of the src/plugins/cmd/diffs.rs tests (6).
package rtok.plugins.cmd

import rtok.sys.rustLines
import kotlin.test.*

private fun file(
    name: String,
    body: String,
) = "diff --git a/$name b/$name\nindex 1111111..2222222 100644\n--- a/$name\n+++ b/$name\n$body"

private fun ctx(
    n: Int,
    tag: String,
) = (0 until n).joinToString("") { " $tag$it\n" }

private fun big() =
    buildString {
        for (i in 0 until 8) {
            val body =
                buildString {
                    append("@@ -10,40 +10,40 @@ fn func_$i\n")
                    append(ctx(3, "c"))
                    for (k in 0 until i * 2 + 1) append("-old$k\n+new$k\n")
                    append(ctx(40 - 3 - i * 2 - 1, "t"))
                }
            append(file("crates/m$i/src/lib.rs", body))
        }
    }

class DiffsTest {
    @Test fun smallDiffKeepsChangesWithOneLineOfContext() {
        val raw = file("src/a.rs", "@@ -1,12 +1,12 @@ fn main\n${ctx(5, "a")}-old\n+new\n${ctx(6, "b")}")
        assertEquals("+++ b/src/a.rs\n@@ -1,12 +1,12 @@ fn main\n ⋯ 4 unchanged\n a4\n-old\n+new\n b0\n ⋯ 5 unchanged", Diffs.tiered(raw))
    }

    @Test fun interiorContextBetweenTwoChangesFoldsTheMiddle() {
        val got = Diffs.tiered(file("f.rs", "@@ -1,7 +1,7 @@\n-x\n+y\n${ctx(5, "m")}-p\n+q\n"))!!
        assertTrue(got.contains(" m0\n ⋯ 3 unchanged\n m4\n-p\n+q"), got)
    }

    @Test fun aDeletedLineThatLooksLikeAFileHeaderStaysAHunkLine() {
        val raw = file("f.txt", "@@ -1,2 +1,2 @@\n a\n--- a/x\n+++ b/x\n") + file("g.txt", "@@ -1 +1 @@\n-p\n+q\n")
        val parsed = Diffs.parse(raw)!!
        assertEquals(2, parsed.files.size)
        assertEquals(listOf(" a", "--- a/x", "+++ b/x"), parsed.files[0].hunks[0].body)
        assertEquals(listOf("-p", "+q"), parsed.files[1].hunks[0].body)
    }

    @Test fun bigDiffListsEveryPathWithCountsAndHunkHeaders() {
        val raw = big()
        assertTrue(raw.rustLines().size > Diffs.SMALL)
        val got = Diffs.tiered(raw)!!
        assertTrue(got.startsWith("8 files, +"), got)
        for (i in 0 until 8) {
            assertTrue(got.contains("crates/m$i/src/lib.rs +"), got)
            assertTrue(got.contains("@@ -10,40 +10,40 @@ fn func_$i"), got)
        }
        assertTrue(got.contains("-old0\n+new0"), got)
        assertFalse(got.contains("old13"), got)
        assertTrue(got.byteLen * 3 < raw.byteLen)
    }

    @Test fun newDeletedRenamedAndBinaryFilesSaySo() {
        val raw =
            "commit abc\nAuthor: a\n\n    msg\n\n" +
                "diff --git a/n.rs b/n.rs\nnew file mode 100644\nindex 0000000..1111111\n--- /dev/null\n+++ b/n.rs\n@@ -0,0 +1,2 @@\n+a\n+b\n" +
                "diff --git a/o.rs b/p.rs\nsimilarity index 90%\nrename from o.rs\nrename to p.rs\n" +
                "diff --git a/i.png b/i.png\nindex 1..2 100644\nBinary files a/i.png and b/i.png differ\n" + big().replace("crates/m", "z/m")
        val got = Diffs.tiered(raw)!!
        assertTrue(got.startsWith("commit abc\nAuthor: a\n\n    msg\n\n"), got)
        assertTrue(got.contains("n.rs +2 -0 (new)"), got)
        assertTrue(got.contains("o.rs -> p.rs +0 -0"), got)
        assertTrue(got.contains("i.png +0 -0 (binary)"), got)
    }

    @Test fun combinedOrMalformedDiffsStandDown() {
        assertNull(Diffs.tiered("diff --cc f\n@@@ -1 -1 +1 @@@\n"))
        assertNull(Diffs.tiered("nothing here\n"))
        assertNull(Diffs.tiered(file("f", "@@ -1,2 +1,2 @@\n a\ngarbage\n")))
    }
}
