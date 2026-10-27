// Port of the `rtok man` check of tests/completions.rs (T53.2) and src/man.rs's contract (T316): a roff page that
// names the binary and documents its commands, one page per subcommand with `SEE ALSO` both ways, roff escaping.
package rtok.man

import rtok.cli.ArgSpec
import rtok.cli.CommandSpec
import rtok.cli.OptSpec
import rtok.sys.readToString
import rtok.sys.removeDirAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManTest {
    private val demo =
        CommandSpec(
            "demo",
            "Demo tool",
            options = listOf(OptSpec.Value("config", "Config file", "PATH", global = true)),
            subcommands = listOf(CommandSpec("pick", "Pick one", args = listOf(ArgSpec("shell", required = false)))),
        )

    @Test fun manPrintsARoffPageThatDocumentsTheCommands() {
        val text = Man.print(rtok.cli.RTOK, rtok.VERSION)
        assertTrue(".TH" in text, text)
        assertTrue("rtok" in text.lowercase())
        assertTrue("completions" in text)
    }

    @Test fun writeAllWritesAPagePerCommandWithSeeAlsoBothWays() {
        val dir = "${rtok.sys.tempDir()}/rtok-man-${rtok.sys.pid()}"
        removeDirAll(dir)
        try {
            val pages = Man.writeAll(demo, dir, "1.2.3")
            assertEquals(listOf("$dir/demo.1", "$dir/demo-pick.1"), pages)
            assertTrue("\\fBdemo\\-pick\\fR(1)" in readToString(pages[0]))
            val pick = readToString(pages[1])
            assertTrue(".TH demo-pick 1" in pick && "\"demo 1.2.3\"" in pick, pick)
            assertTrue("\\fBdemo\\fR(1)" in pick, "the child points at its parent")
            assertTrue("\\-\\-config" in pick, "a global option shows on the child")
            assertTrue("[\\fISHELL\\fR]" in pick, pick)
        } finally {
            removeDirAll(dir)
        }
    }

    @Test fun roffEscapesDashesBackslashesAndLeadingDots() {
        assertEquals("a\\-b \\\\ c\n\\&.TH x", Man.esc("a-b \\ c\n.TH x"))
    }
}
