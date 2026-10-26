// Port of the tests of src/measure/usage/ (sqlite.rs, copilot.rs, gemini.rs, pi.rs, kimi.rs). Expected rows are
// compared as `field=value` text, so the Rust numbers read as they are written there.
package rtok.measure.usage

import rtok.store.UsageSlice
import rtok.store.seedHostMessages
import rtok.support.tmpDir
import rtok.sys.createDirAll
import rtok.sys.removeDirAll
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PI_SINCE = 1_700_000_000L
private const val KIMI_SINCE = 1_700_000_000L
private const val SQL_SINCE = 1_790_800_000L
private const val COPILOT_AFTER = 1_788_346_691L

/** `session model api ts | input cacheCreate cacheRead output`. */
private fun UsageSlice.text() = "$session $model $api $ts | $input $cacheCreate $cacheRead $output"

/** `"k1":v1,"k2":v2` from [keys] and the space-separated [values]. */
private fun fields(
    keys: String,
    values: String,
) = keys.split(" ").zip(values.split(" ")).joinToString(",") { (k, v) -> "\"$k\":$v" }

private fun UsageSlice.legs() = "$input $cacheCreate $cacheRead $output"

class SqliteUsageTest {
    private fun assistant(
        model: String,
        t: String,
        cache: String = "0 0",
    ) = """{"role":"assistant","modelID":"$model","providerID":"anthropic","time":{"created":1790811000500},""" +
        """"tokens":{${fields("input output reasoning", t)},"cache":{${fields("read write", cache)}}}}"""

    private fun ms(s: String) = s.toLong()

    @Test fun assistantMessagesBecomeRequestsAndTheRestIsIgnored() {
        val d = tmpDir("usage-sql-ok")
        seedHostMessages(
            "$d/opencode.db",
            listOf(
                Triple("s1", ms("1790811000500"), assistant("claude-x", "10 4 3", "5 2")),
                Triple("s1", ms("1790811001000"), """{"role":"user","time":{"created":1}}"""),
                Triple("s2", ms("1790811002000"), """{"role":"assistant","modelID":"m"}"""),
                Triple("s2", ms("1790000000000"), assistant("old", "1 1 1", "1 1")),
            ),
        )
        writeString("$d/opencode.db.bak", "x")
        writeString("$d/opencodex.db", "x")
        val (rows, bad) = Sqlite.slices("opencode", listOf(d), SQL_SINCE)
        assertNull(bad)
        assertEquals(listOf("s1 claude-x anthropic 1790811000 | 10 2 5 7"), rows.map { it.text() })
        removeDirAll(d)
    }

    @Test fun aPathWithUriDelimitersIsReadWhereItIs() {
        val d = tmpDir("usage-sql-odd #1 %41 ?x")
        seedHostMessages("$d/opencode.db", listOf(Triple("s", ms("1790811000500"), assistant("m", "3 0 0"))))
        val (rows, bad) = Sqlite.slices("opencode", listOf(d), 0)
        assertNull(bad)
        assertEquals(1, rows.size)
        removeDirAll(d)
    }

    @Test fun aFileThatIsNotADatabaseIsNamedAndTheChannelDatabaseIsRead() {
        val d = tmpDir("usage-sql-bad")
        writeString("$d/kilo.db", "this is not sqlite")
        seedHostMessages("$d/kilo-beta.db", listOf(Triple("s", ms("1790811000500"), assistant("m", "1 0 0"))))
        val (rows, bad) = Sqlite.slices("kilo", listOf(d), 0)
        assertEquals(1, rows.size)
        assertTrue(bad!!.endsWith("kilo.db"))
        assertTrue(Sqlite.slices("kilo", listOf("$d/missing"), 0).first.isEmpty())
        removeDirAll(d)
    }

    @Test fun aRealKilo781AssistantRowIsReadWithItsExtraFields() {
        val d = tmpDir("usage-sql-kilo-real")
        val row =
            """{"parentID":"msg_140be6e7e001Ob6pv5dn7AvLSN","role":"assistant","mode":"code","agent":"code",""" +
                """"path":{"cwd":"/work","root":"/"},"cost":0,"tokens":{"total":17074,"input":17049,"output":4,"reasoning":21,""" +
                """"cache":{"write":0,"read":0}},"modelID":"prism-ml/bonsai-27b","providerID":"lmstudio",""" +
                """"time":{"created":1792087585129,"completed":1792087671578},"finish":"stop"}"""
        seedHostMessages("$d/kilo.db", listOf(Triple("ses_1", ms("1792087585129"), row)))
        val (rows, bad) = Sqlite.slices("kilo", listOf(d), 0)
        assertNull(bad)
        assertEquals("ses_1 prism-ml/bonsai-27b lmstudio 1792087585 | 17049 0 0 25", rows.single().text())
        removeDirAll(d)
    }
}

class CopilotUsageTest {
    private fun shutdown(
        ts: String,
        metrics: String,
    ) = """{"type":"session.shutdown","timestamp":"$ts","data":{"modelMetrics":{$metrics}}}"""

    private fun metric(
        u: String,
        details: String = "",
    ) = """{"requests":{"count":3},"usage":{${fields("inputTokens cacheReadTokens cacheWriteTokens outputTokens", u)},"reasoningTokens":4}$details}"""

    @Test fun eachModelOfAShutdownIsOneSliceDatedWhenTheSessionEnded() {
        val d = tmpDir("usage-cop")
        createDirAll("$d/sess-1")
        createDirAll("$d/sess-2")
        val log =
            listOf(
                """{"type":"assistant.message","data":{"outputTokens":99}}""",
                shutdown("2026-09-02T10:58:10.672Z", """"gpt-x":${metric("100 60 0 7")},"claude-y":${metric("50 10 20 5")}"""),
                "not json \"session.shutdown\"",
            ).joinToString("\n")
        writeString("$d/sess-1/events.jsonl", log)
        writeString("$d/sess-2/events.jsonl", "{\"type\":\"session.start\"}\n")
        writeString("$d/sess-1/other.jsonl", shutdown("2026-09-02T10:58:10Z", ""))
        val (rows, bad) = Copilot.slices(listOf(d), 0, 0)
        assertNull(bad)
        assertEquals(
            listOf("sess-1 claude-y github 1788346690 | 20 20 10 5", "sess-1 gpt-x github 1788346690 | 40 0 60 7"),
            rows.sortedBy { it.model }.map { it.text() },
        )
        assertTrue(Copilot.slices(listOf(d), COPILOT_AFTER, 0).first.isEmpty())
        removeDirAll(d)
    }

    @Test fun tokenDetailsCarryTheCompactionRequestsThatUsageLeavesOut() {
        val d = tmpDir("usage-cop-td")
        createDirAll("$d/sess-td")
        val full =
            metric(
                "1000 600 0 50",
                ""","tokenDetails":{"input":{"tokenCount":700},"cache_read":{"tokenCount":600},"cache_write":{"tokenCount":0},"output":{"tokenCount":80}}""",
            )
        val partial = metric("200 50 0 9", ""","tokenDetails":{"input":{"tokenCount":999}}""")
        writeString("$d/sess-td/events.jsonl", shutdown("2026-09-02T10:58:10Z", """"full":$full,"partial":$partial"""))
        val rows = Copilot.slices(listOf(d), 0, 0).first.sortedBy { it.model }
        assertEquals(listOf("700 0 600 80", "150 0 50 9"), rows.map { it.legs() })
        removeDirAll(d)
    }

    @Test fun aResumedSessionCountsItsLastShutdownNotTheSumOfAll() {
        val d = tmpDir("usage-cop-rs")
        for (s in listOf("sess-a", "sess-b", "sess-c")) createDirAll("$d/$s")

        fun line(
            ts: String,
            u: String,
        ) = shutdown(ts, """"gpt-x":${metric(u)}""")
        writeString(
            "$d/sess-a/events.jsonl",
            listOf(
                line("2026-09-02T10:00:00Z", "100 0 0 7"),
                """{"type":"session.resume","data":{"eventCount":9}}""",
                """{"type":"session.usage_checkpoint","data":{"totalNanoAiu":5}}""",
                line("2026-09-03T10:00:00Z", "140 0 0 10"),
            ).joinToString("\n"),
        )
        writeString("$d/sess-b/events.jsonl", line("2026-09-02T10:00:00Z", "50 0 0 5"))
        writeString("$d/sess-c/events.jsonl", line("2026-09-02T10:00:00Z", "30 0 0 2") + "\n{\"type\":\"session.shutdown\",\"data\":{}}")
        val rows = Copilot.slices(listOf(d), 0, 0).first.sortedBy { it.session }
        assertEquals(
            listOf("sess-a 140 10 1788429600", "sess-b 50 5 1788343200", "sess-c 30 2 1788343200"),
            rows.map { "${it.session} ${it.input} ${it.output} ${it.ts}" },
        )
        removeDirAll(d)
    }
}

class GeminiUsageTest {
    private fun msg(
        id: String,
        ts: String,
        t: String,
    ) = """{"id":"$id","timestamp":"$ts","type":"gemini","content":"","model":"gemini-x","tokens":{${fields("input output cached thoughts tool", t)}}}"""

    @Test fun jsonlAndLegacyJsonChatsCountEachMessageOnce() {
        val d = tmpDir("usage-gem")
        val chats = "$d/proj/chats"
        createDirAll("$chats/parent")
        val log =
            listOf(
                """{"sessionId":"sess-a","projectHash":"h","messages":[]}""",
                msg("g1", "2026-09-30T23:30:00Z", "100 7 40 3 2"),
                msg("g1", "2026-09-30T23:30:00Z", "100 7 40 3 2"),
                """{"id":"u1","timestamp":"2026-09-30T23:29:00Z","type":"user","content":"hi"}""",
                """{"${'$'}patch":{"id":"g1","content":"x"}}""",
                """{"${'$'}rewindTo":"u1"}""",
            ).joinToString("\n")
        writeString("$chats/session-1-aaaa.jsonl", log)
        writeString("$chats/parent/session-2-bbbb.jsonl", msg("g2", "2026-10-01T00:00:00Z", "10 1 0 0 0"))
        writeString("$chats/session-3-cccc.json", """{"sessionId":"sess-c","messages":[${msg("g3", "2026-10-02T00:00:00Z", "5 5 5 0 0")}]}""")
        writeString("$d/proj/logs.json", "[]")
        writeString("$chats/notes.jsonl", msg("x", "2026-10-02T00:00:00Z", "1 1 0 0 0"))
        val (rows, bad) = Gemini.slices(listOf(d), 0, 0)
        assertNull(bad)
        assertEquals(
            listOf("sess-a gemini-x google 1790811000 | 62 0 40 10", "session-2-bbbb gemini-x google 1790812800 | 10 0 0 1", "sess-c gemini-x google 1790899200 | 0 0 5 5"),
            rows.sortedBy { it.ts }.map { it.text() },
        )
        removeDirAll(d)
    }

    @Test fun aChatFileWithNoJsonIsNamed() {
        val d = tmpDir("usage-gem-bad")
        createDirAll("$d/p/chats")
        writeString("$d/p/chats/session-1-x.jsonl", "garbage\n")
        val (rows, bad) = Gemini.slices(listOf(d), 0, 0)
        assertTrue(rows.isEmpty())
        assertTrue(bad!!.endsWith("session-1-x.jsonl"))
        removeDirAll(d)
    }
}

class PiUsageTest {
    private fun run(text: String) = Pi.session("/s/2024_abc.jsonl", text, PI_SINCE, HashSet())!!

    private fun assistant(
        id: String,
        ts: String,
        u: String,
    ) = """{"type":"message","id":"$id","timestamp":"$ts","message":{"role":"assistant","provider":"anthropic","model":"claude-sonnet-4-5","usage":$u}}"""

    @Test fun anAssistantMessageAndASummaryEntryAreCountedWithTheirLegs() {
        val text =
            listOf(
                """{"type":"session","id":"sess-1","timestamp":"2024-12-03T14:00:00.000Z"}""",
                """{"type":"message","id":"u1","timestamp":"2024-12-03T14:00:01.000Z","message":{"role":"user","content":"hi"}}""",
                assistant("a1", "2024-12-03T14:00:02.000Z", """{"input":10,"output":5,"cacheRead":100,"cacheWrite":7,"totalTokens":122}"""),
                """{"type":"compaction","id":"c1","timestamp":"2024-12-03T14:10:00.000Z","provider":"anthropic","model":"claude-haiku-4-5",""" +
                    """"usage":{"input":1,"output":2,"cacheRead":0,"cacheWrite":0}}""",
            ).joinToString("\n")
        val rows = run(text)
        assertEquals(
            listOf("sess-1 claude-sonnet-4-5 anthropic 1733234402 | 10 7 100 5", "sess-1 claude-haiku-4-5 anthropic 1733235000 | 1 0 0 2"),
            rows.map { it.text() },
        )
        assertEquals("pi", rows[0].host)
    }

    @Test fun reasoningAndTheOneHourCacheKeyOfARealEntryAreNotAddedAgain() {
        val u = """{"input":10,"output":50,"reasoning":20,"cacheRead":100,"cacheWrite":0,"cacheWrite1h":0,"totalTokens":160,"cost":{"total":0.0}}"""
        val text = """{"type":"session","version":3,"id":"sess-1","timestamp":"2024-12-03T14:00:00.000Z","cwd":"/p"}""" + "\n" + assistant("a1", "2024-12-03T14:00:02.000Z", u)
        assertEquals(listOf("10 0 100 50"), run(text).map { it.legs() })
    }

    @Test fun anEntryCopiedIntoAForkedSessionCountsOnce() {
        val line = assistant("a1", "2024-12-03T14:00:02.000Z", """{"input":3,"output":4,"cacheRead":0,"cacheWrite":0}""")
        val seen = HashSet<Pair<String, String>>()
        val first = Pi.session("/s/a.jsonl", line, PI_SINCE, seen)!!
        val fork = Pi.session("/s/b.jsonl", line, PI_SINCE, seen)!!
        assertEquals(1 to 0, first.size to fork.size)
    }

    @Test fun anOldEntryAndAnEmptyUsageAreDroppedAndANonJsonFileIsUnreadable() {
        val text =
            assistant("a1", "2001-01-01T00:00:00.000Z", """{"input":1,"output":1,"cacheRead":0,"cacheWrite":0}""") + "\n" +
                assistant("a2", "2024-12-03T14:00:02.000Z", """{"input":0,"output":0,"cacheRead":0,"cacheWrite":0}""")
        assertTrue(run(text).isEmpty())
        val seen = HashSet<Pair<String, String>>()
        assertNull(Pi.session("/s/x.jsonl", "not json\n", PI_SINCE, seen))
        assertEquals(emptyList(), Pi.session("/s/x.jsonl", "", PI_SINCE, seen))
    }
}

class KimiUsageTest {
    private fun record(
        model: String,
        ms: String,
        u: String,
    ) = """{"type":"usage.record","model":"$model","usageScope":"turn","time":$ms,"usage":{${fields("inputOther inputCacheCreation inputCacheRead output", u)}}}"""

    @Test fun everyUsageRecordOfAWireLogIsOneRequestInItsSession() {
        val text =
            listOf(
                """{"type":"metadata","protocol_version":"1.1"}""",
                """{"type":"context.append_loop_event","time":1779256800300,"event":{"type":"step.end","usage":{"inputOther":10,"output":5}}}""",
                record("kimi-k2", "1779256800302", "10 2 30 5"),
                record("kimi-k2", "1779256900000", "1 0 0 1"),
            ).joinToString("\n")
        val rows = Kimi.wire("/h/sessions/key/session_9/agents/main/wire.jsonl", text, KIMI_SINCE)
        assertEquals(2, rows.size)
        assertEquals("session_9 kimi-k2 moonshot 1779256800 | 10 2 30 5", rows[0].text())
        assertEquals("kimi", rows[0].host)
    }

    @Test fun aRecordBeforeSinceOrWithoutTokensIsDropped() {
        val text = record("kimi-k2", "1000000", "5 0 0 5") + "\n" + record("kimi-k2", "1779256800000", "0 0 0 0")
        assertTrue(Kimi.wire("/h/sessions/k/s/agents/main/wire.jsonl", text, KIMI_SINCE).isEmpty())
    }
}
