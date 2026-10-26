// Port of agents/claude/migrate.rs tests: legacy token hooks out, rtok and unrelated hooks kept, legacy loopback proxy
// retargeted (foreign URLs untouched), legacy MCP dropped, and `--replace` needing `--yes`.
package rtok.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import rtok.config.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaudeMigrateTest {
    private val proxy = "http://127.0.0.1:8790"
    private val rtokLeft = 8
    private val today = Json.parseToJsonElement("{ \"env\": {\"ANTHROPIC_BASE_URL\": \"http://127.0.0.1:8788\"}, \"mcpServers\": { \"lean-ctx\": {\"command\": \"lean-ctx\"}, \"code-review-graph\": {\"command\": \"crg\"}, \"serena\": {\"command\": \"serena\"} }, \"hooks\": { \"PreToolUse\": [ {\"matcher\":\"Bash\",\"hooks\":[{\"type\":\"command\",\"command\":\"rtok hook PreToolUse\"}]}, {\"matcher\":\"Read\",\"hooks\":[{\"type\":\"command\",\"command\":\"rtok hook PreToolUse\"}]}, {\"matcher\":\"Bash\",\"hooks\":[{\"type\":\"command\",\"command\":\"rtk hook PreToolUse\"}]}, {\"matcher\":\"Bash\",\"hooks\":[{\"type\":\"command\",\"command\":\"lean-ctx hook PreToolUse\"}]} ], \"PostToolUse\": [ {\"matcher\":\"*\",\"hooks\":[{\"type\":\"command\",\"command\":\"rtok hook PostToolUse\"}]}, {\"hooks\":[{\"type\":\"command\",\"command\":\"caveman-proxy\"}]} ], \"UserPromptSubmit\": [ {\"hooks\":[{\"type\":\"command\",\"command\":\"rtok hook UserPromptSubmit\"}]}, {\"hooks\":[{\"type\":\"command\",\"command\":\"caveman shrink-hook\"}]} ], \"SessionStart\": [ {\"hooks\":[{\"type\":\"command\",\"command\":\"rtok hook SessionStart\"}]}, {\"hooks\":[{\"type\":\"command\",\"command\":\"orca hook\"}]} ], \"PreCompact\": [{\"hooks\":[{\"type\":\"command\",\"command\":\"rtok hook PreCompact\"}]}], \"PostCompact\": [{\"hooks\":[{\"type\":\"command\",\"command\":\"rtok hook PostCompact\"}]}], \"Stop\": [{\"hooks\":[{\"type\":\"command\",\"command\":\"rtok hook Stop\"}]}], \"Notification\": [ {\"hooks\":[{\"type\":\"command\",\"command\":\"/opt/token-optimizer/python-launcher.sh\"}]}, {\"hooks\":[{\"type\":\"command\",\"command\":\"holdmylid\"}]}, {\"hooks\":[{\"type\":\"command\",\"command\":\"tokenbar\"}]}, {\"hooks\":[{\"type\":\"command\",\"command\":\"cbm\"}]} ] } }").jsonObject

    private fun env(u: String) = JsonObject(mapOf("env" to JsonObject(mapOf("ANTHROPIC_BASE_URL" to JsonPrimitive(u)))))

    private fun url(
        u: String,
        to: String = proxy,
    ): Pair<String, Boolean> {
        val (root, line) = ClaudeMigrate.retarget(env(u), to)
        return ProxyEnv.value(root, "ANTHROPIC_BASE_URL")!! to (line != null)
    }

    @Test fun keepsEightRtokAndNonTokenHooks() {
        val (root, report) = ClaudeMigrate.rewrite(today, proxy)
        assertTrue("remaining rtok hooks: 8" in report, report)
        assertEquals(rtokLeft, ClaudeMigrate.countRtok(root))
        val raw = root.toString()
        listOf("orca", "holdmylid", "tokenbar", "cbm", "serena").forEach { assertTrue(it in raw, it) }
        listOf("rtk hook", "lean-ctx hook", "caveman-proxy", "caveman shrink-hook", "python-launcher.sh", "\"lean-ctx\"", "code-review-graph")
            .forEach { assertFalse(it in raw, it) }
        assertEquals(proxy, ProxyEnv.value(root, "ANTHROPIC_BASE_URL"))
    }

    @Test fun onlyLegacyLoopbackEndpointsAreRetargeted() {
        listOf(
            "http://127.0.0.1:8788" to "http://127.0.0.1:8790",
            "http://localhost:8787/w/claude?x=1" to "http://127.0.0.1:8790/w/claude?x=1",
            "http://[::1]:8788/" to "http://127.0.0.1:8790/",
        ).forEach { (old, new) -> assertEquals(new to true, url(old), old) }
        assertEquals("http://127.0.0.1:9001", url("http://127.0.0.1:8788", "http://127.0.0.1:9001").first)
        listOf(
            "https://api.example/v1/8788",
            "https://api.example:8788/v1",
            "https://api.example/?p=8787",
            "https://127.0.0.1:8788@api.example/v1",
            "https://8788.example/v1",
            "http://127.0.0.1:18788",
            "http://127.0.0.1:8790",
            "not a url 8788",
        ).forEach { assertEquals(it to false, url(it), it) }
        val (once, _) = ClaudeMigrate.retarget(env("http://127.0.0.1:8788"), proxy)
        assertNull(ClaudeMigrate.retarget(once, proxy).second)
    }

    @Test fun replaceWithoutYesIsAnError() {
        val c = Config()
        val err = assertFailsWith<IllegalArgumentException> { ClaudeMigrate.run(c.copy(setup = c.setup.copy(dryRun = false, yes = false))) }
        assertTrue("--yes" in err.message.orEmpty())
    }
}
