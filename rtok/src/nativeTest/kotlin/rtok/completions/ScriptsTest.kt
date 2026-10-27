// The generators over a small tree (shell-completions' Clink test, T317) and over rtok's own: every shell renders a
// non-empty script that names the binary, hidden-free, with values and descriptions where the shell shows them.
package rtok.completions

import rtok.cli.ArgSpec
import rtok.cli.CommandSpec
import rtok.cli.OptSpec
import kotlin.test.Test
import kotlin.test.assertTrue

class ScriptsTest {
    private val demo =
        CommandSpec(
            "demo",
            options = listOf(OptSpec.Value("path", "", "PATH")),
            subcommands = listOf(CommandSpec("pick", "Say \"hi\" \\ bye", args = listOf(ArgSpec("shell")))),
        )

    @Test fun clinkNamesValuesAndDescriptions() {
        val lua = Completions.generate(Shell.Clink, demo)
        for (want in listOf("clink.argmatcher(\"demo\")", "\"pick\" .. clink.argmatcher()", "\"--path\" .. clink.argmatcher():addarg(clink.filematches)", "[\"pick\"] = \"Say \\\"hi\\\" \\\\ bye\"")) {
            assertTrue(want in lua, "$want in $lua")
        }
    }

    @Test fun everyShellRendersNonEmptyCompletionsForRtok() {
        for (shell in Shell.entries) {
            val text = Completions.generate(shell, rtok.cli.RTOK)
            assertTrue(text.isNotBlank(), "$shell")
            assertTrue("rtok" in text, "$shell")
            assertTrue("report" in text && "completions" in text, "$shell lists the subcommands")
        }
    }

    @Test fun bashMapsNestedCommandsToClapIds() {
        val bash = Completions.generate(Shell.Bash, demo)
        assertTrue("demo,pick)\n                cmd=\"demo__subcmd__pick\"" in bash, bash)
        assertTrue("complete -F _demo -o nosort -o bashdefault -o default demo" in bash, bash)
    }
}
