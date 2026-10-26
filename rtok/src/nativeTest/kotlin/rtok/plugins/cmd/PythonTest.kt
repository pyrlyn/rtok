// Port of the src/plugins/cmd/python.rs tests (7).
package rtok.plugins.cmd

import kotlin.test.*
import rtok.sys.rustLines

private const val FAILING = """============================= test session starts ==============================
platform linux -- Python 3.12.1, pytest-8.1.1
rootdir: /w
collected 5 items

tests/test_a.py ..F                                                      [ 60%]
tests/test_b.py ..                                                       [100%]

=================================== FAILURES ===================================
________________________________ test_boom _____________________________________

    def test_boom():
>       assert 1 == 2
E       assert 1 == 2

tests/test_a.py:7: AssertionError
=========================== short test summary info ============================
FAILED tests/test_a.py::test_boom - assert 1 == 2
========================= 1 failed, 4 passed in 0.10s =========================="""

class PythonTest {
    @Test fun failingSessionKeepsFailuresAndDropsHeaderAndPassingProgress() {
        val got = Python.pytest(FAILING)!!
        assertTrue(got.contains("assert 1 == 2") && got.contains("tests/test_a.py:7: AssertionError"))
        assertTrue(got.contains("FAILED tests/test_a.py::test_boom"))
        assertFalse(got.contains("test_a.py ..F"), "named by the FAILED line: $got")
        assertTrue(!got.contains("platform linux") && !got.contains("test_b.py .."), got)
        assertTrue(got.contains("1 failed, 4 passed in 0.10s"))
    }

    @Test fun passingSessionIsItsTotalsLine() {
        val raw = "============================= test session starts ==============================\ncollected 3 items\n\ntest_a.py ...                                                            [100%]\n\n============================== 3 passed in 0.02s ==============================="
        assertEquals("============================== 3 passed in 0.02s ===============================", Python.pytest(raw))
    }

    @Test fun quietModeWithoutBannersStillParses() {
        val got = Python.pytest("..F.. [100%]\n=================================== FAILURES ===================================\nboom\n1 failed, 4 passed in 0.10s")!!
        assertTrue(got.contains("..F.."), got); assertTrue(got.endsWith("1 failed, 4 passed in 0.10s"))
    }

    @Test fun nonPytestTextIsLeftAlone() { assertNull(Python.pytest("hello\nworld\n")); assertNull(Python.unittest("hello\n")) }

    @Test fun unittestDropsACleanDotsLineOnly() {
        assertFalse(Python.unittest("....\n----------------------------------------------------------------------\nRan 4 tests in 0.001s\n\nOK")!!.startsWith("...."))
        assertTrue(Python.unittest(".F..\n======\nFAIL: test_x\nRan 4 tests in 0.001s\n\nFAILED (failures=1)")!!.contains(".F.."))
    }

    @Test fun recursionFramesFoldAndEveryOtherFrameStays() {
        val raw = buildString {
            append("start\nTraceback (most recent call last):\n  File \"m.py\", line 9, in <module>\n    f(3)\n")
            repeat(40) { append("  File \"m.py\", line 5, in f\n    return f(n - 1)\n") }
            append("RecursionError: maximum recursion depth exceeded\nend\n")
        }
        val got = Python.collapseFrames(raw)
        assertTrue(got.contains("[previous frame repeated 39 more times]"), got)
        assertTrue(got.contains("File \"m.py\", line 9, in <module>") && got.contains("f(3)"))
        assertTrue(got.contains("RecursionError: maximum recursion depth exceeded"))
        assertTrue(got.startsWith("start\n") && got.endsWith("end\n"))
        assertTrue(got.rustLines().size < 12, got)
    }

    @Test fun distinctFramesAreNotFolded() {
        val raw = "Traceback (most recent call last):\n  File \"a.py\", line 1, in a\n    b()\n  File \"b.py\", line 2, in b\n    c()\nValueError: x\n"
        assertEquals(raw, Python.collapseFrames(raw))
    }
}
