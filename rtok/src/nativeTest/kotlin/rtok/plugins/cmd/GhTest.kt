// Port of the src/plugins/cmd/gh.rs tests (7).
package rtok.plugins.cmd

import rtok.sys.rustLines
import kotlin.test.*

private fun argv(s: String) = s.split(' ').filter { it.isNotEmpty() }

private const val VIEW = "title:\tFix the thing\nstate:\tOPEN\nauthor:\tada\nlabels:\t\nassignees:\t\nreviewers:\t\nprojects:\t\nmilestone:\t\nnumber:\t12\nurl:\thttps://github.com/o/r/pull/12\nadditions:\t10\ndeletions:\t2\n--\nFirst paragraph.   \n\n\n\nSecond paragraph.\n\n\n"
private const val CHECKS = "build\tpass\t1m2s\thttps://ci/1\nlint\tpass\t10s\thttps://ci/2\ntest (ubuntu)\tfail\t3m\thttps://ci/3\ntest (mac)\tpending\t0\thttps://ci/4\ndocs\tskipping\t0\thttps://ci/5\ncodeql\tpass\t5m\thttps://ci/6\n"

private fun log() =
    buildString {
        for (i in 0 until 30) append("build\tSet up job\t2026-10-15T10:00:${i.toString().padStart(2, '0')}.1234567Z line $i\n")
        for (i in 0 until 12) append("build\tRun tests\t2026-10-15T10:01:${i.toString().padStart(2, '0')}.1234567Z compiling crate $i\n")
        append("build\tRun tests\t2026-10-15T10:02:00.0000000Z test a ... ok\n")
        append("build\tRun tests\t2026-10-15T10:02:01.0000000Z ##[error]test b failed: expected 1\n")
        append("build\tRun tests\t2026-10-15T10:02:02.0000000Z summary line\n")
        for (i in 0 until 12) append("build\tRun tests\t2026-10-15T10:03:${i.toString().padStart(2, '0')}.1234567Z tail noise $i\n")
    }

class GhTest {
    @Test fun viewDropsEmptyFieldsAndBlankRunsButKeepsTheRest() {
        val got = Gh.format(argv("gh pr view 12"), VIEW)!!
        for (kept in listOf("title:\tFix the thing", "number:\t12", "url:\thttps://github.com/o/r/pull/12", "additions:\t10")) assertTrue(got.contains(kept), got)
        assertTrue(!got.contains("labels:") && !got.contains("milestone:"), got)
        assertTrue(got.endsWith("First paragraph.\n\nSecond paragraph."), got)
    }

    @Test fun viewOfJsonOrOtherShapesStandsDown() {
        assertNull(Gh.view("{\"title\":\"x\"}"))
        assertNull(Gh.view(""))
    }

    @Test fun checksKeepFailingAndPendingRowsWithUrlsAndCountTheRest() {
        val got = Gh.format(argv("gh pr checks"), CHECKS)!!
        assertTrue(got.startsWith("6 checks: 3 pass, 1 fail, 1 pending, 1 skipping"), got)
        assertTrue(got.contains("test (ubuntu)\tfail\t3m\thttps://ci/3"), got)
        assertTrue(got.contains("test (mac)\tpending"), got)
        assertTrue(!got.contains("lint") && !got.contains("codeql"), got)
    }

    @Test fun checksWithAnUnknownStateStandDown() = assertNull(Gh.checks("a\tweird\t1s\thttps://x\n"))

    @Test fun runLogKeepsTheErrorWithContextAndCountsQuietSteps() {
        val raw = log()
        val got = Gh.format(argv("gh run view 7 --log"), raw)!!
        assertTrue(got.contains("## build / Run tests"), got)
        assertTrue(got.contains("##[error]test b failed: expected 1"), got)
        assertTrue(got.contains("test a ... ok") && got.contains("summary line"), got)
        assertFalse(got.contains("2026-10-15T"), got)
        assertTrue(got.contains("1 steps without errors (30 lines)"), got)
        assertTrue(got.rustLines().size < raw.rustLines().size / 2, got)
    }

    @Test fun warningsInAQuietLookingStepAreKeptNotCountedAway() = assertTrue(Gh.runLog("b\tbuild\t2026-10-15T10:00:00.0Z warning: unused import\nb\tbuild\t2026-10-15T10:00:01.0Z ok\n")!!.contains("warning: unused import"))

    @Test fun outputThatIsNotShorterIsLeftToTheRule() {
        assertNull(Gh.format(argv("gh pr checks"), "a\tfail\t1s\thttps://x\n"))
        assertNull(Gh.format(argv("gh repo view"), VIEW))
    }
}
