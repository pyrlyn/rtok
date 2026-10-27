@file:Suppress("MagicNumber")
// Port of src/bench.rs's tests (T9.1, T68.9): the schedule, the offline tables, the task and graph file checks.

package rtok.bench

import rtok.sys.exists
import rtok.sys.readToString
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BenchTest {
    @Test fun dryRunListsSixByTwoByThree() =
        BenchFixture().use { f ->
            val s = Bench.run(f.cfg(true))
            assertEquals(6 * 2 * 3, s.lines().count { it.isNotEmpty() }, s)
            assertTrue("add-fn a 1" in s && "run-tests b 3" in s, s)
        }

    @Test fun anOfflineRunPrintsATableAndWritesAResultPerArm() =
        BenchFixture().use { f ->
            val s = Bench.run(f.cfg(false, runs = 1u))
            val lines = s.lines()
            assertEquals("config        input      cache     output       cost     pass", lines[0])
            assertEquals("a                 0          0          0     0.0000   3/6  ", lines[1])
            assertTrue(lines[2].startsWith("b "), s)
            val a = readToString("${f.dir}/results/a.json")
            assertTrue("\"pass\": 3" in a && "\"live\": false" in a && "\"tasks\": 6" in a, a)
            assertTrue(exists("${f.dir}/results/b.json"))
        }

    @Test fun aTaskListThatIsNotSixTasksIsRefused() =
        BenchFixture(tasks = 5).use { f ->
            val e = assertFailsWith<BenchException> { Bench.run(f.cfg(true)) }
            assertEquals("expected 6 tasks, got 5", e.message)
        }

    @Test fun anUnknownSuiteAndAMissingFieldAreRefused() =
        BenchFixture().use { f ->
            assertEquals("unknown bench suite \"nope\"", assertFailsWith<BenchException> { Bench.run(f.cfg(true, "nope")) }.message)
            writeString("${f.dir}/tasks.toml", "[[tasks]]\nid = \"x\"\n")
            assertTrue("missing field `prompt`" in assertFailsWith<BenchException> { Bench.run(f.cfg(true)) }.message!!)
        }

    @Test fun graphDryRunListsEachQuestionOnBothArms() =
        BenchFixture().use { f ->
            val s = Bench.run(f.cfg(true, "graph", 1u))
            assertTrue(s.lines().count { it.isNotEmpty() } >= 20, s)
            assertTrue("plugin rtok mcp 1" in s && "config-class mini-py native 1" in s, s)
        }

    @Test fun graphOfflineTableNamesTheMetrics() =
        BenchFixture().use { f ->
            val s = Bench.run(f.cfg(false, "graph", 1u))
            assertTrue("tools" in s && "resident" in s && "TOTAL" in s, s)
            assertTrue("plugin rtok mcp 0 0 0 0 0 0.0000 0 0/1" in s, s)
            assertTrue("TOTAL * native 0 0 0 0 0 0.0000 0 0/10" in s, s)
        }

    @Test fun theGraphFileIsCheckedAtLoad() =
        BenchFixture().use { f ->
            val path = "${f.dir}/graph.toml"
            val good = readToString(path)
            writeString(path, good.replace("repo = \"mini-py\"", "repo = \"gone\""))
            assertEquals("question config-class repo gone is not in [[repos]]", assertFailsWith<BenchException> { GraphSuite.load(path) }.message)
            writeString(path, good.substringBefore("[[questions]]"))
            assertEquals("graph suite needs ≥ 10 questions, got 0", assertFailsWith<BenchException> { GraphSuite.load(path) }.message)
            writeString(path, good.replace("(?i)plugin", "(unclosed"))
            assertTrue("plugin expect" in assertFailsWith<BenchException> { GraphSuite.load(path) }.message!!)
            assertEquals(path, GraphSuite.tomlPath(path))
            assertEquals("${f.dir}/graph.toml", GraphSuite.tomlPath("${f.dir}/tasks.toml"))
        }
}
