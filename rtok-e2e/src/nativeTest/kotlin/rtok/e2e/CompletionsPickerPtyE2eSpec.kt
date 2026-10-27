// Port of tests/completions_picker_pty.rs (T408): `rtok completions` on a pseudo-terminal opens the shell picker and
// applies what was checked. The binary runs against a sandbox HOME; no agent is started.

package rtok.e2e

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import rtok.sys.*

private fun Sandbox.bash() = "$user/.local/share/bash-completion/completions/rtok"

private fun Sandbox.zsh() = "$user/.zfunc/_rtok"

/** `rtok completions` on a terminal: wait for the prompt, type [keys], and return what was shown once it exits. */
private fun Sandbox.onATerminal(keys: String): String =
    Pty(listOf(RTOK, "completions"), user, mapOf("HOME" to user, "RTOK_HOME" to home, "TERM" to "xterm"), cols = 120).use { t ->
        t.waitFor("esc cancels")
        t.send(keys)
        t.waitExit()
        t.text()
    }

class CompletionsPickerPtyE2eSpec :
    FunSpec({
        test("checking a shell installs it") {
            Sandbox("picker-install").use { s ->
                // The first row is bash: space checks it, enter applies.
                val out = s.onATerminal(" \r")
                exists(s.bash()) shouldBe true
                out shouldContain "wrote "
                exists(s.zsh()) shouldBe false
            }
        }

        test("unchecking an installed shell removes it") {
            Sandbox("picker-remove").use { s ->
                s.rtok("completions", "zsh", "--install", env = mapOf("HOME" to s.user)).code shouldBe 0
                exists(s.zsh()) shouldBe true
                // Down to the zsh row (pre-checked), space unchecks it, enter applies.
                val out = s.onATerminal("\u001b[B \r")
                exists(s.zsh()) shouldBe false
                out shouldContain "removed "
            }
        }

        test("escape changes nothing") {
            Sandbox("picker-cancel").use { s ->
                val out = s.onATerminal(" \u001b")
                exists(s.bash()) shouldBe false
                out shouldContain "cancelled"
            }
        }
    })
