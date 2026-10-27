// `rtok proxy --dry-run` (src/cli/proxy.rs): the flags overlay `[proxy]` and the effective settings are printed;
// nothing binds.
package rtok.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ProxyCliSpec :
    FunSpec({
        tags(Cli)

        test("proxy --dry-run prints the effective settings with the flags applied") {
            withHome("proxy-dry-run") {
                val r = cli("proxy", "--dry-run", "--port", "9911", "--upstream", "http://127.0.0.1:1", "--mode", "compress")
                r.err shouldBe ""
                r.code shouldBe 0
                r.out shouldBe
                    "bind = 127.0.0.1\nport = 9911\nmode = compress\nupstream = http://127.0.0.1:1\nopenai_upstream = https://api.openai.com\n" +
                    "timeout_s = 600\ninclude_usage = true\ndry_run = true\n"
            }
        }
        test("proxy --port rejects a non-port") {
            withHome("proxy-bad-port") {
                val r = cli("proxy", "--dry-run", "--port", "x")
                (r.code != 0) shouldBe true
            }
        }
    })
