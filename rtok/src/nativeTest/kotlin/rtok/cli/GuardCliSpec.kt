// In-process halves of `rtok guard check`, `rtok discover` and the deprecated `rtok setup`: parsing, errors and the
// empty store. The recorded-call cases (tests/guard_check.rs) run against the binary in GuardCheckE2eSpec.

package rtok.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class GuardCliSpec :
    FunSpec({
        tags(Cli)

        test("unparsable JSON allows") {
            withHome("guard-bad-json") {
                val r = cli("guard", "check", "--tool", "bash", "--json", "not-json", "--session", "s")
                r.code shouldBe 0
                Json
                    .parseToJsonElement(r.out)
                    .jsonObject["allow"]!!
                    .jsonPrimitive.boolean shouldBe true
            }
        }
        test("guard check needs --tool and --json") {
            cli("guard", "check", "--tool", "x").code shouldBe ExitCode.USAGE
        }

        test("discover over an empty store") {
            withHome("discover") {
                cli("discover").out shouldBe "bash calls 0  covered 0  unreadable rows 0\nno uncovered command family\n"
                cli("discover", "--json").out shouldBe "{\n  \"scanned\": 0,\n  \"covered\": 0,\n  \"unreadable\": 0,\n  \"gaps\": []\n}\n"
                cli("discover", "--limit", "x").code shouldBe ExitCode.USAGE
            }
        }

        test("setup is the deprecated spelling of agents install") {
            withHome("setup") {
                val r = cli("setup", "no-such-host", "--remove", "--dry-run")
                r.err shouldContain "warning: `rtok setup no-such-host` is deprecated; use `rtok agents install no-such-host`"
                r.code shouldBe 1
            }
        }
    })
