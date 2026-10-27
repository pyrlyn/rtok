// Port of tests/otel.rs `cli_without_an_endpoint_does_nothing`, plus `rtok otel status --json` (model `otel_status`).
package rtok.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class OtelCliSpec :
    FunSpec({
        tags(Cli)

        fun ok(vararg args: String): String {
            val r = cli(*args)
            r.err shouldBe ""
            r.code shouldBe 0
            return r.out
        }
        // The env var turns export on without a key; these cases only hold when it is unset.
        val envEndpoint = !rtok.config.envVar("OTEL_EXPORTER_OTLP_ENDPOINT").isNullOrEmpty()
        test("otel flush and status without an endpoint do nothing").config(enabled = !envEndpoint) {
            withHome("otel-off") {
                ok("otel", "flush").trim() shouldBe "otel: no endpoint"
                val status = ok("otel", "status")
                status shouldStartWith "endpoint: none"
                status shouldContain "calls: mark 0 · 0 pending"
                status shouldContain "sessions: mark 0"
            }
        }
        test("otel status --json carries the marks and the endpoint").config(enabled = !envEndpoint) {
            withHome("otel-json") {
                val j = Json.parseToJsonElement(ok("otel", "status", "--json")).jsonObject
                j["endpoint"] shouldBe JsonNull
                for (k in listOf("calls_mark", "calls_pending", "logs_mark", "logs_pending", "sessions_mark")) j[k]!!.jsonPrimitive.content shouldBe "0"
                j.containsKey("last") shouldBe false
            }
        }
    })
