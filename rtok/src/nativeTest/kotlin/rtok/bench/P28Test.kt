// Port of the src/bench/p28.rs tests: the shipped suite (fixtures/bench, copied from bench/p28.toml) and scratch
// suites in a temp dir, never live, never a real bench store under /tmp/rtok-p28.
@file:Suppress("MagicNumber")

package rtok.bench

import rtok.config.Bench
import rtok.config.Config
import rtok.config.decodeConfig
import rtok.store.Store
import rtok.store.insertCall
import rtok.store.insertUsage
import rtok.store.putArchive
import rtok.store.putArchiveSummary
import rtok.store.upsertSession
import rtok.support.installDb
import rtok.sys.createDirAll
import rtok.sys.exists
import rtok.sys.pid
import rtok.sys.readToString
import rtok.sys.removeDirAll
import rtok.sys.removeFile
import rtok.sys.tempDir
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private val BENCH = "${rtok.config.envVar("RTOK_FIXTURES")}/bench"
private val SHIPPED = "$BENCH/p28.toml"
private val FIXTURE = "$BENCH/p28_must_keep.toml"

class P28Test {
    private fun scratch(name: String) =
        "${tempDir()}/rtok-p28-test-${pid()}-$name".also {
            removeDirAll(it)
            createDirAll(it)
        }

    /** The shipped arms rewritten into [dir] with their homes there too. */
    private fun suiteIn(
        dir: String,
        tasks: String,
    ): String =
        "$dir/p28.toml".also {
            writeString(
                it,
                "fixture = \"$FIXTURE\"\n[[arms]]\nid = \"lossless\"\nsettings = \"none.json\"\nhome = \"lossless\"\n" +
                    "[[arms]]\nid = \"llm\"\nsettings = \"none.json\"\nhome = \"llm\"\n$tasks",
            )
        }

    private fun cfg(
        tasks: String,
        dry: Boolean,
        runs: UInt = 1u,
    ) = Config().copy(bench = Bench(tasks = tasks, suite = "p28", dryRun = dry, runs = runs))

    @Test fun dryRunListsEachTaskOnEachArm() {
        val s = rtok.bench.Bench.run(cfg(SHIPPED, true, 2u))
        assertEquals(6 * 2 * 2, s.lines().count { it.isNotEmpty() }, s)
        assertTrue(s.contains("build-e0599 lossless 1\n"), s)
        assertTrue(s.contains("note-feature llm 2\n"), s)
    }

    /** Each shipped answer sits where the lossless pointer (8 head, 4 tail lines) hides it. */
    @Test fun everyShippedAnswerIsOutsideThePointerView() {
        val file = P28Suite.load(SHIPPED)
        for (t in file.tasks) {
            val lines =
                file.bodies
                    .first { it.id == t.first }
                    .text
                    .lines()
                    .let { if (it.last().isEmpty()) it.dropLast(1) else it }
            val shown = lines.take(8) + lines.takeLast(4)
            assertFalse(shown.any { t.expect.containsMatchIn(it) }, "${t.id} visible")
        }
    }

    /** The arm proxies load, sit on the ports their settings name, and differ only in the compressor switch. */
    @Test fun theArmProxiesDifferOnlyInTheCompressor() {
        fun arm(id: String) = decodeConfig(readToml("$BENCH/p28/$id.toml")) to readToString("$BENCH/p28/$id.json")
        val (lossless, losslessJson) = arm("lossless")
        val (llm, llmJson) = arm("llm")
        for ((c, json) in listOf(lossless to losslessJson, llm to llmJson)) {
            assertEquals("compress", c.proxy.mode)
            assertEquals(2u to 300u, c.plugins.archive.keepTurns to c.plugins.archive.minTokens)
            assertTrue(json.contains("http://127.0.0.1:${c.proxy.port}\""), json)
        }
        assertNotEquals(lossless.proxy.port, llm.proxy.port)
        assertTrue(!lossless.plugins.compress.llm.enabled && llm.plugins.compress.llm.enabled)
        val homes = P28Suite.load(SHIPPED).arms.map { it.home }
        assertNotEquals(homes[0], homes[1], "each arm reads its own store")
    }

    @Test fun anAnswerThatIsNotAMustKeepSpanIsRefused() {
        val dir = scratch("refuse")

        fun err(tasks: String) = runCatching { P28Suite.load(suiteIn(dir, tasks)) }.exceptionOrNull()?.message.orEmpty()
        val notKept = err("[[tasks]]\nid = \"x\"\nfirst = \"grep-records\"\nquestion = \"?\"\nexpect = 'Compiling'\n")
        assertTrue(notKept.contains("matches no must-keep span"), notKept)
        val noBody = err("[[tasks]]\nid = \"x\"\nfirst = \"nope\"\nquestion = \"?\"\nexpect = '1'\n")
        assertTrue(noBody.contains("is not a fixture body"), noBody)
        val repeat =
            err(
                "[[arms]]\nid = \"llm\"\nsettings = \"x.json\"\nhome = \"elsewhere\"\n" +
                    "[[tasks]]\nid = \"x\"\nfirst = \"grep-records\"\nquestion = \"?\"\nexpect = '402'\n",
            )
        assertTrue(repeat.contains("repeats an earlier arm"), repeat)
        removeDirAll(dir)
    }

    @Test fun thePromptReadsFirstFirstAndEveryFileOnce() {
        val file = P28Suite.load(SHIPPED)
        val t = file.tasks[2]
        val p = P28Suite.prompt(t, file.bodies)
        assertTrue(p.contains("order: ${t.first}.txt, "), p)
        for (b in file.bodies) assertEquals(1, Regex(Regex.escape("${b.id}.txt")).findAll(p).count(), p)
        assertTrue(p.endsWith("prints the original."), p)
    }

    @Test fun offlineTableNamesTheGateColumns() {
        val dir = scratch("offline")
        val path = suiteIn(dir, "[[tasks]]\nid = \"log-addr\"\nfirst = \"service-log-trace\"\nquestion = \"?\"\nexpect = '10\\.20'\n")
        val s = rtok.bench.Bench.run(cfg(path, false))
        assertTrue(s.contains("log-addr lossless 0 0 0 0.0000 0/1"), s)
        assertTrue(s.contains("compressor_tokens") && s.contains("usd_per_pass"), s)
        assertTrue(s.contains("\nllm 1 0/1 0.0000 0 0.0000 - 0 0/0 0/0\n"), s)
        removeDirAll(dir)
    }

    /** Internal-lane spend only; a summary that drops a span is not kept; one whose original is gone is not recovered. */
    @Test fun armStorePricesTheInternalLaneAndChecksEachSummary() {
        val dir = scratch("store")
        installDb()
        val bodies = P28Suite.load(SHIPPED).bodies
        val archive = "$dir/archive"

        fun numbered(b: P28Body) =
            b.text
                .lines()
                .let { if (it.last().isEmpty()) it.dropLast(1) else it }
                .mapIndexed { i, l -> "${(i + 1).toString().padStart(6)}\t$l\n" }
                .joinToString("")
        val all = bodies[3].keep.joinToString("") { "$it\n" }
        Store.open("$dir/rtok.db").use { store ->
            store.upsertSession("s", null, null, null, "proxy")
            for (kind in listOf("api_request", "api_request:internal")) {
                val call = store.insertCall("s", "proxy", kind, null, null, null, null, "/x")
                store.insertUsage("s", "claude-haiku-4-5", "anthropic", 1_000_000, 0, 0, 0, call)
            }
            val kept = store.putArchive("s", numbered(bodies[3]).encodeToByteArray(), archive)
            store.putArchiveSummary(kept, "v", all)
            val dropped = store.putArchive("s", numbered(bodies[5]).encodeToByteArray(), archive)
            store.putArchiveSummary(dropped, "v", "it failed")
            val gone = store.putArchive("s", numbered(bodies[1]).encodeToByteArray(), archive)
            store.putArchiveSummary(gone, "v", all)
            removeFile("$archive/$gone")
        }
        val prices = Config().stats.prices
        val r = P28Suite.armStore(dir, bodies, prices)
        assertEquals(1_000_000L, r.tokens, "agent lane not counted")
        assertTrue(r.cost > 0.0 && !r.partial)
        assertEquals(Triple(3, 2, 1), Triple(r.summaries, r.recovered, r.kept))
        assertEquals(0, P28Suite.armStore("$dir/absent", bodies, prices).summaries)
        assertFalse(exists("$dir/absent/rtok.db"), "never created")
        removeDirAll(dir)
    }
}
