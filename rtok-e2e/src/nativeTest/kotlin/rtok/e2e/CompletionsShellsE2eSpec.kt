// Port of tests/completions_shells.rs (T943): what `rtok completions <shell> --install` writes completes in the real
// shell, over a temporary HOME. A shell that is not installed skips its test; no agent is started.

package rtok.e2e

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import kotlinx.cinterop.toKString
import rtok.sys.*

/** A working [name] on the outer PATH; a shim that cannot run it (mise without a version) counts as missing. */
private fun find(
    name: String,
    vararg probe: String,
): String? {
    val dirs = outerPath().split(':').filter { it.isNotEmpty() }
    val bin = dirs.map { "$it/$name" }.firstOrNull { isFile(it) } ?: return null
    return bin.takeIf { sh(listOf(it) + probe, "/").code == 0 }
}

/** A sandbox with `rtok` (this build) first on PATH, so the wrappers run this build and not an installed one. */
private class ShellHome(
    tag: String,
) : AutoCloseable {
    val box = Sandbox("completions-$tag")
    val home = box.user
    private val bin = "${box.root}/bin".also(::createDirAll)
    val path = "$bin:/usr/bin:/bin"

    init {
        platform.posix.symlink(RTOK, "$bin/rtok")
    }

    /** `rtok completions <shell> --install` in a cleared environment; the path of the first file written. */
    fun install(shell: String): String {
        val p = sh(listOf("env", "-i", "HOME=$home", "RTOK_HOME=$home", RTOK, "completions", shell, "--install"), home)
        check(p.code == 0) { "install $shell: ${p.err}" }
        return p.out
            .lines()
            .first()
            .removePrefix("wrote ")
            .also { check(it != p.out.lines().first()) { p.out } }
    }

    fun cleared(vararg argv: String) = sh(listOf("env", "-i", "HOME=$home", "PATH=$path") + argv, home)

    override fun close() = box.close()
}

class CompletionsShellsE2eSpec :
    FunSpec({
        // The creator's failure: `.zfunc` off `fpath` and a `compinit -C` dump made before `_rtok` existed.
        // After one install, with no `.zshrc` edit, Tab completes a subcommand.
        test("zsh: Tab completes with a stale compinit dump and no .zshrc edit") {
            val zsh = find("zsh", "-fc", "exit 0") ?: return@test
            ShellHome("zsh").use { h ->
                // A cleared environment, like the shell below: an exported FPATH can carry the developer's own _rtok.
                h.cleared(zsh, "-fc", "autoload -Uz compinit && compinit -u -d \"\$HOME/dump\"").code shouldBe 0
                h.install("zsh")
                writeString("${h.home}/.zshrc", "autoload -Uz compinit && compinit -C -d \"\$HOME/dump\"\nPS1='rtok-test> '\n")
                readToString("${h.home}/dump") shouldNotContain "_rtok"
                val env = mapOf("HOME" to h.home, "RTOK_HOME" to h.home, "PATH" to h.path, "TERM" to "xterm")
                Pty(listOf(zsh, "-i"), h.home, env).use { t ->
                    t.waitFor("rtok-test> ")
                    t.send("rtok work\t")
                    t.waitFor("worktree")
                    // Ctrl-A puts `print` in front of the completed line, so the shell echoes the buffer.
                    t.send("\u0001print -r -- RESULT: \r")
                    t.waitFor("RESULT: rtok worktree")
                }
            }
        }

        test("bash: the wrapper registers the generated completion") {
            val bash = find("bash", "-c", "exit 0") ?: return@test
            ShellHome("bash").use { h ->
                val script = h.install("bash")
                val out = h.cleared(bash, "--norc", "--noprofile", "-c", ". ${q(script)} && complete -p rtok").out
                out shouldContain "-F _rtok"
                out.trimEnd() shouldEndWith " rtok"
            }
        }

        test("fish: the wrapper completes a subcommand") {
            val fish = find("fish", "--no-config", "-c", "exit 0") ?: return@test
            ShellHome("fish").use { h ->
                val script = h.install("fish")
                h.cleared(fish, "--no-config", "-c", "source ${q(script)}; complete -C 'rtok work'").out shouldContain "worktree"
            }
        }

        test("PowerShell: the wrapper completes a subcommand") {
            val pwsh = find("pwsh", "-NoProfile", "-Command", "exit 0") ?: return@test
            ShellHome("pwsh").use { h ->
                val script = h.install("powershell")
                // pwsh needs more of the environment than a cleared one, so only PATH and HOME change.
                val outer = outerPath()
                val cmd = ". ${q(script)}; (TabExpansion2 'rtok work' 9).CompletionMatches.CompletionText"
                sh(listOf(pwsh, "-NoProfile", "-NonInteractive", "-Command", cmd), h.home, mapOf("HOME" to h.home, "PATH" to "${h.path}:$outer"))
                    .out shouldContain "worktree"
            }
        }
    })

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun outerPath(): String =
    platform.posix
        .getenv("PATH")
        ?.toKString()
        .orEmpty()
