// Port of the src/plugins/cmd/grep.rs tests (10).
package rtok.plugins.cmd

import kotlin.test.*
import rtok.sys.rustLines

private fun argv(s: String) = s.split(' ').filter { it.isNotEmpty() }

class GrepTest {
    @Test fun printsEachPathOnceAndKeepsEveryPath() {
        val raw = "src/a.rs:10:    let x = hit;\nsrc/a.rs:20:    let y = hit;\nsrc/b.rs:3:fn hit() {}\n"
        assertEquals("src/a.rs\n10:let x = hit;\n20:let y = hit;\nsrc/b.rs:3:fn hit() {}", Grep.group(argv("grep -rn hit src"), raw))
    }

    @Test fun filesArePathSorted() {
        val got = Grep.group(argv("rg -n a"), "z.rs:1:a\nz.rs:2:a\na.rs:1:a\na.rs:2:a\n")!!
        assertTrue(got.indexOf("a.rs") < got.indexOf("z.rs"), got)
    }

    @Test fun perFileBudgetEndsInACount() {
        val got = Grep.group(argv("grep -rn hit ."), (1..30).joinToString("") { "big.rs:$it:hit $it\n" })!!
        assertTrue(got.contains("… +18 more"), got); assertTrue(got.contains("12:hit 12") && !got.contains("13:hit 13"), got)
    }

    @Test fun filesPastTheBudgetKeepPathAndLineNumbers() {
        val raw = (1..40).joinToString("") { n -> val f = "f${n.toString().padStart(2, '0')}.rs"; "$f:$n:fn main() {}\n$f:${n + 100}:fn main() {}\n" }
        val got = Grep.group(argv("grep -rn main ."), raw)!!
        for (n in 1..40) assertTrue(got.contains("f${n.toString().padStart(2, '0')}.rs"), got)
        assertTrue(got.contains("f40.rs:40,140"), got); assertTrue(got.rustLines().size < 80, got)
    }

    @Test fun longLineIsCutAroundTheMatch() {
        val pad = "x".repeat(300)
        val first = Grep.group(argv("grep -rn NEEDLE ."), "a.rs:1:${pad}NEEDLE$pad\na.rs:2:NEEDLE\n")!!.rustLines()[1]
        assertTrue(first.contains("NEEDLE") && first.byteLen < 160, first); assertTrue(first.startsWith("1:…") && first.endsWith('…'), first)
    }

    @Test fun caseInsensitivePatternStillFindsTheWindow() {
        val pad = "x".repeat(200)
        val got = Grep.group(argv("grep -rni needle ."), "a.rs:1:${pad}Needle$pad\na.rs:2:needle\n")!!
        assertTrue(got.rustLines()[1].contains("Needle"), got)
    }

    @Test fun otherShapesFallBack() {
        assertNull(Grep.group(argv("grep -rc hit src"), "a.rs:3\nb.rs:4\n")); assertNull(Grep.group(argv("rg -l hit"), "a.rs\nb.rs\n"))
        assertNull(Grep.group(argv("grep -rn -A2 hit ."), "a.rs:1:hit\na.rs-2-x\n")); assertNull(Grep.group(argv("grep -n hit f"), "3:hit\n"))
    }

    @Test fun stderrAndBinaryLinesSurvive() {
        val got = Grep.group(argv("grep -rn hit ."), "a.rs:1:hit\na.rs:2:hit\nBinary file x.bin matches\ngrep: gone: No such file or directory\n")!!
        assertTrue(got.contains("Binary file x.bin matches"), got); assertTrue(got.contains("grep: gone: No such file or directory"), got)
    }

    @Test fun neverLongerThanRaw() = assertNull(Grep.group(argv("grep -rn hit ."), "a.rs:1:hit\n"))

    @Test fun gitGrepAndUnnumberedOutput() =
        assertEquals("src/a.rs\nlet hit = 1;\nlet hit = 2;", Grep.group(argv("git grep hit"), "src/a.rs:let hit = 1;\nsrc/a.rs:let hit = 2;\n"))
}
