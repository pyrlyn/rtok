// Port of tests/guard_check.rs (T70.5): `rtok guard check` prints the allow/deny `plugins.guard` returns on PreToolUse,
// over calls the binary recorded through `rtok hook PostToolUse`; plus `rtok discover` over the same rows (T688).
package rtok.e2e

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class GuardCheckE2eSpec :
    FunSpec({
        // No `cwd`, like the pi and OpenCode plugins: `guard check` has none either, and the Bash key carries the cwd.
        fun Sandbox.post(
            input: String,
            stdout: String,
        ) {
            val body =
                """{"session_id":"s","tool_name":"Bash","tool_input":$input,"hook_event_name":"PostToolUse","tool_response":{"stdout":"$stdout"}}"""
            rtok("hook", "PostToolUse", stdin = body).code shouldBe 0
        }

        fun Sandbox.check(
            tool: String,
            input: String,
        ): String {
            val r = rtok("guard", "check", "--tool", tool, "--json", input, "--session", "s")
            r.code shouldBe 0
            return r.out.trim()
        }

        test("the first call allows, a repeat denies with a reason and a guard measurement") {
            Sandbox("guard-deny").use { s ->
                val input = """{"command":"cat dup-t705"}"""
                s.check("bash", input) shouldBe """{"allow":true}"""
                s.post(input, "dup-output-body")
                val v = s.check("bash", input)
                v shouldContain "\"allow\":false"
                v shouldContain "rtok expand"
                s.rtok("stats", "--plugin", "guard").out shouldContain "\"kind\": \"guard\""
            }
        }
        test("find -delete allows the next ls") {
            Sandbox("guard-find").use { s ->
                val ls = """{"command":"ls"}"""
                s.post(ls, "out")
                s.check("Bash", ls) shouldContain "\"allow\":false"
                s.post("""{"command":"find . -delete"}""", "gone")
                s.check("bash", ls) shouldBe """{"allow":true}"""
            }
        }
        test("sed -n is keyed, sed -i is not") {
            Sandbox("guard-sed").use { s ->
                val ro = """{"command":"sed -n 1,40p f"}"""
                s.post(ro, "body")
                s.check("bash", ro) shouldContain "\"allow\":false"
                s.post("""{"command":"sed -i s/a/b/ f"}""", "x")
                s.check("bash", ro) shouldBe """{"allow":true}"""
            }
        }
        test("discover ranks a recorded family no rule covers") {
            Sandbox("discover").use { s ->
                s.post("""{"command":"terraform plan"}""", "x".repeat(400))
                s.post("""{"command":"git status"}""", "y")
                val out = s.rtok("discover").out
                out shouldContain "bash calls 2  covered 1  unreadable rows 0\n"
                out shouldContain "terraform"
                s.rtok("discover", "--json").out shouldContain "\"est_tokens\": 100"
            }
        }
    })
