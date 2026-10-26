// Port of src/measure/usage.rs tests: Claude Code and Codex requests, and the hosts `read` names as skipped.
package rtok.measure

import rtok.config.Config
import rtok.support.tmpDir
import rtok.sys.createDirAll
import rtok.sys.removeDirAll
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val SONNET_TS = 1_790_811_000L
private const val AFTER_SONNET = 1_790_812_000L
private const val INPUT = 10L
private const val CACHE_READ = 5L
private const val CODEX_INPUT = 60L
private const val CODEX_CACHED = 40L
private const val CODEX_OUT = 7L

class UsageLogsTest {
    private fun claudeLine(
        id: String,
        ts: String,
        model: String,
        u: String,
    ): String {
        val legs = listOf("input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens", "output_tokens").zip(u.split(",")).joinToString(",") { (k, n) -> "\"$k\":$n" }
        return """{"type":"assistant","timestamp":"$ts","message":{"id":"$id","model":"$model",""" + """"usage":{$legs}}}"""
    }

    @Test fun claudeRequestsKeepModelTimeAndTheParentSession() {
        val dir = tmpDir("usage-claude")
        createDirAll("$dir/p/s1/subagents")
        val body =
            listOf(
                claudeLine("m1", "2026-09-30T23:30:00Z", "claude-sonnet-4", "10,0,5,2"),
                claudeLine("m1", "2026-09-30T23:30:01Z", "claude-sonnet-4", "10,0,5,2"),
                claudeLine("m2", "2026-10-01T00:10:00Z", "<synthetic>", "0,0,0,0"),
                "not json",
            ).joinToString("\n")
        writeString("$dir/p/s1.jsonl", body)
        writeString("$dir/p/s1/subagents/agent-a.jsonl", claudeLine("m3", "2026-10-01T01:00:00Z", "claude-haiku-4", "1,2,3,4"))
        val rows = UsageLogs.claudeSlices(dir, 0).first.sortedBy { it.ts }
        assertEquals(2, rows.size)
        assertEquals<Triple<String, String?, Long>>(Triple("s1", "claude-sonnet-4", SONNET_TS), Triple(rows[0].session, rows[0].model, rows[0].ts))
        assertEquals(Triple(INPUT, CACHE_READ, 2L), Triple(rows[0].input, rows[0].cacheRead, rows[0].output))
        assertEquals("s1" to 2L, rows[1].session to rows[1].cacheCreate)
        assertEquals(1, UsageLogs.claudeSlices(dir, AFTER_SONNET).first.size)
        removeDirAll(dir)
    }

    @Test fun codexRequestsTakeTheModelFromTheLatestTurnContext() {
        val dir = tmpDir("usage-codex")

        fun count(
            ts: String,
            legs: String,
        ): String {
            val (input, cached, out) = legs.split(",")
            return """{"timestamp":"$ts","type":"event_msg","payload":{"type":"token_count","info":{""" +
                """"last_token_usage":{"input_tokens":$input,"cached_input_tokens":$cached,"output_tokens":$out}}}}"""
        }
        val body =
            listOf(
                """{"type":"session_meta","payload":{"id":"sess-1"}}""",
                """{"type":"turn_context","payload":{"model":"gpt-5"}}""",
                count("2026-09-30T23:30:00Z", "100,40,7"),
                count("not a time", "5,0,1"),
            ).joinToString("\n")
        writeString("$dir/rollout-a.jsonl", body)
        val rows = UsageLogs.codexSlices(dir, 0).first
        assertEquals(1, rows.size)
        val r = rows[0]
        assertEquals(Triple("sess-1", "gpt-5", "codex"), Triple(r.session, r.model, r.host))
        assertEquals(Triple(CODEX_INPUT, CODEX_CACHED, CODEX_OUT), Triple(r.input, r.cacheRead, r.output))
        removeDirAll(dir)
    }

    @Test fun readNamesUndocumentedHostsAsUnsupported() {
        val dir = tmpDir("usage-read")
        val base = Config().rebasePaths(dir)
        for (h in listOf("droid/-proj", "grok", "zcode", "antigravity")) createDirAll("$dir/$h")
        writeString("$dir/droid/-proj/a.settings.json", "{}")
        for (h in listOf("grok", "zcode", "antigravity")) writeString("$dir/$h/data", "x")

        fun cfg(present: Boolean): Config {
            fun d(h: String) = listOf(if (present) "$dir/$h" else "$dir/absent")
            val dirs =
                base.agents.usage.dirs
                    .copy(droid = listOf("$dir/droid".takeIf { present } ?: "$dir/absent", "$dir/none"), grok = d("grok"), zcode = d("zcode"), antigravity = d("antigravity"))
            return base.copy(agents = base.agents.copy(usage = base.agents.usage.copy(dirs = dirs)))
        }
        val logs = UsageLogs.read(cfg(true), 0)
        assertTrue(logs.slices.isEmpty())
        assertEquals(
            listOf(
                "droid" to UsageLogs.DROID_UNSUPPORTED,
                "grok" to UsageLogs.GROK_UNSUPPORTED,
                "zcode" to UsageLogs.ZCODE_UNSUPPORTED,
                "antigravity" to UsageLogs.ANTIGRAVITY_UNSUPPORTED,
            ),
            logs.skipped.map { it.host to it.reason },
        )
        assertTrue(UsageLogs.read(cfg(false), 0).skipped.isEmpty())
        removeDirAll(dir)
    }
}
