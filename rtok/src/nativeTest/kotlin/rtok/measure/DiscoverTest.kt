@file:Suppress("MagicNumber")

// Port of the src/measure/discover.rs tests (T688).

package rtok.measure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import rtok.plugins.cmd.Settings
import rtok.plugins.cmd.VfsRuleFiles
import rtok.store.Store
import rtok.store.hookInputsSince
import rtok.store.insertCall
import rtok.store.insertHookCallIo
import rtok.store.setCallTs
import rtok.store.upsertSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiscoverTest {
    private fun postJson(
        command: String,
        out: String,
    ) = buildJsonObject {
        put("hook_event_name", "PostToolUse")
        put("tool_name", "Bash")
        putJsonObject("tool_input") { put("command", command) }
        putJsonObject("tool_response") { put("stdout", out) }
    }

    private fun post(
        command: String,
        out: String,
    ) = postJson(command, out).toString()

    private fun settings() = Settings.builtin()

    @Test fun ranksUncoveredFamiliesByOutputTokens() {
        val bodies = listOf(post("terraform plan", "x".repeat(4000)), post("terraform plan -out p", "x".repeat(2000)), post("ping -c 3 host", "y".repeat(8000)), post("du -sh .", "z"))
        val d = Discover.rank(bodies, settings(), 10)
        // terraform (6000 B in 2 calls) and ping (8000 B in 1 call): ping reads more.
        assertEquals(listOf("ping", "terraform", "du"), d.gaps.map { it.family })
        assertEquals(2L to 6000L, d.gaps[1].calls to d.gaps[1].bytes)
        assertEquals(1500, d.gaps[1].estTokens)
        assertEquals("terraform plan", d.gaps[1].example)
    }

    @Test fun aRuleOrAFormatterMeansCovered() {
        val bodies = listOf(post("git status", "a".repeat(500)), post("cargo build", "b".repeat(500)), post("cd /r && grep -rn x .", "c".repeat(500)), post("mise exec -- cargo test", "d".repeat(500)))
        val d = Discover.rank(bodies, settings(), 10)
        assertEquals(4L to 4L, d.scanned to d.covered)
        assertTrue(d.gaps.isEmpty())
        assertTrue(d.toTable().contains("no uncovered command family"))
    }

    @Test fun aUserRuleCoversItsFamily() {
        val with = Settings.load(VfsRuleFiles().apply { write("rules.toml", "[ping]\nmax_lines = 5\n") }, "rules.toml", null, 80u)
        val bodies = listOf(post("ping -c 3 host", "y".repeat(800)))
        assertEquals(1, Discover.rank(bodies, settings(), 10).gaps.size)
        assertTrue(Discover.rank(bodies, with, 10).gaps.isEmpty())
    }

    @Test fun rtokRunWrappedAndForeignRowsAreReadCorrectly() {
        val bodies =
            listOf(
                post("rtok run -- 'ping -c 1 h'", "y".repeat(400)),
                post("rtok expand abc123", "e".repeat(400)),
                // Not Bash, no command, not JSON, no kept body.
                """{"tool_name":"Read","tool_input":{"file_path":"/x"}}""",
                """{"tool_name":"Bash","tool_input":{}}""",
                "not json",
                "",
            )
        val d = Discover.rank(bodies, settings(), 10)
        assertEquals(1L to 2L, d.scanned to d.unreadable)
        assertEquals("ping", d.gaps[0].family)
    }

    @Test fun outputIsReadFromStringStdoutStderrOrWholeValue() {
        fun bytes(json: String) = outputBytes(Json.parseToJsonElement(json))
        assertEquals(3, bytes("\"abc\""))
        assertEquals(3, bytes("""{"stdout":"ab","stderr":"c"}"""))
        assertEquals(2, bytes("""{"stderr":"cc"}"""))
        assertEquals("""{"n":1}""".length.toLong(), bytes("""{"n":1}"""))
    }

    @Test fun limitKeepsTheTopRowsAndTiesBreakByName() {
        val d = Discover.rank(listOf(post("b-tool x", "1234"), post("a-tool x", "1234"), post("c-tool", "1")), settings(), 2)
        assertEquals(listOf("a-tool", "b-tool"), d.gaps.map { it.family })
    }

    /** The whole path on a fixture store: two sessions, one row past the window, one `PreToolUse`, one body over the inline cap. */
    @Test fun fixtureStoreFeedsTheRanking() {
        val store = Store.openInMemory()
        for (s in listOf("s1", "s2")) store.upsertSession(s, null, null, null, "hook")

        fun hook(
            session: String,
            event: String,
            stdin: String,
            cap: Int,
        ): Int {
            val id = store.insertCall(session, "hook", "hook", null, null, null, null, event)
            store.insertHookCallIo(id, stdin.encodeToByteArray(), null, cap)
            return id
        }

        fun withSession(
            s: String,
            cmd: String,
            out: String,
        ): String {
            val v: Map<String, JsonElement> = postJson(cmd, out).jsonObject + mapOf("session_id" to JsonPrimitive(s), "cwd" to JsonPrimitive("/r"))
            return kotlinx.serialization.json
                .JsonObject(v)
                .toString()
        }
        hook("s1", "PostToolUse", withSession("s1", "ping -c 1 h", "p".repeat(900)), 65536)
        hook("s2", "PostToolUse", withSession("s2", "ping -c 1 h", "p".repeat(900)), 65536)
        hook("s2", "PostToolUse", withSession("s2", "git log", "g".repeat(900)), 65536)
        hook("s2", "PreToolUse", withSession("s2", "du -sh .", "-"), 65536)
        val old = hook("s2", "PostToolUse", withSession("s2", "ps aux", "o".repeat(900)), 65536)
        store.setCallTs(old, 10)
        hook("s2", "PostToolUse", withSession("s2", "ls", "h".repeat(300)), 100)

        val bodies = store.hookInputsSince("PostToolUse", 1000, 100)
        assertEquals(4, bodies.size, "old row and PreToolUse row are not read")
        val d = Discover.rank(bodies, settings(), 10)
        assertEquals(Triple(3L, 1L, 1L), Triple(d.scanned, d.covered, d.unreadable))
        assertEquals(1, d.gaps.size)
        assertEquals("ping" to 2L, d.gaps[0].family to d.gaps[0].calls)
        assertTrue(d.toTable().contains("ping"), d.toTable())
    }
}
