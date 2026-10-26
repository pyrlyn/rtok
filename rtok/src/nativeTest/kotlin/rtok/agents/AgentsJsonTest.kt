package rtok.agents

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith

class AgentsJsonTest :
    FunSpec({
        test("module states are serde's snake_case") {
            AgentsJson.state(ModuleState.Installed) shouldBe "installed"
            AgentsJson.state(ModuleState.NotInstalled) shouldBe "not_installed"
            AgentsJson.state(ModuleState.NotSupported) shouldBe "not_supported"
        }
        test("an absent app reads as not present with empty rows, in Rust's key order") {
            val cfg = rtok.config.Config()
            val env = AgentsRun.Env(present = { _, _, _ -> false })
            val text = AgentsJson.encode(AgentsJson.rows(cfg, listOf("windsurf"), env))
            text shouldStartWith "[\n  {\n    \"host\": \"windsurf\",\n    \"kind\": \"desktop\",\n    \"name\": \"Windsurf\",\n    \"present\": false,\n    \"app\": null,\n    \"version\": null,"
        }
    })
