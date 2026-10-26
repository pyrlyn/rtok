// Port of src/plugins/inject/mod.rs tests. The hook-path tests drive InjectPlugin.sessionStart + apply
// directly; their UserPromptSubmit halves wait for the hook dispatcher (Phase 6).
package rtok.plugins.inject

import rtok.config.Config
import rtok.plugin.Runtime
import rtok.plugin.sdk.*
import rtok.store.measurementCount
import rtok.support.*
import rtok.sys.*
import kotlin.test.*

private fun blob(
    tokens: UInt,
    cx: Ctx,
): String {
    val rate = cx.config("estimator", rtok.config.Estimator.serializer(), rtok.config.Estimator()).prose.coerceAtLeast(0.1f)
    val s = StringBuilder("x".repeat((tokens.toFloat() * rate).toInt()))
    while (cx.estimate(s.toString(), Class.Prose) < tokens) s.append('x')
    while (cx.estimate(s.toString(), Class.Prose) > tokens && s.isNotEmpty()) s.setLength(s.length - 1)
    return s.toString()
}

private fun modes(
    dir: String?,
    vararg names: String,
): (Config) -> Config =
    { c ->
        c.copy(plugins = c.plugins.copy(inject = c.plugins.inject.copy(modes = names.toList(), modesDir = dir ?: c.plugins.inject.modesDir)))
    }

/** SessionStart as the hook dispatcher assembles it: the plugin's offer through the budgeted [apply]. */
private fun start(
    cx: Ctx,
    source: String = "startup",
) = apply(cx, listOfNotNull(InjectPlugin.sessionStart(SessionStart(source), cx)))

class InjectTest {
    @Test fun three500Budget800DropsOneAndIsByteStable() =
        withRuntime("inject-t24") { r ->
            val cx = Ctx(r)
            val text = blob(500u, cx)
            assertEquals(500u, r.estimate(text, Class.Prose))
            val offered = listOf(Injection("a", text, 3u), Injection("b", text, 2u), Injection("c", text, 1u))
            val once = apply(cx, offered)
            assertEquals(once, apply(cx, offered))
            assertTrue(once.startsWith("$text\n$text\n"), once)
            assertTrue(once.rustLines().any { it == "dropped:c:${r.estimate(text, Class.Prose)}" }, once)
            assertEquals(2, once.count { it == '\n' }, "two emitted + one dropped line")
            assertEquals(2, r.store.measurementCount("inject"))
        }

    @Test fun single10xCandidateFitsBudgetAndMarksDropped() =
        withRuntime("inject-t188-huge") { r ->
            val cx = Ctx(r)
            val budget = r.config.plugins.inject.budgetTokens
            val text = blob(budget * 10u, cx)
            val t = r.estimate(text, Class.Prose)
            assertEquals(budget * 10u, t)
            val offered = listOf(Injection("huge", text, 5u))
            val once = apply(cx, offered)
            assertEquals(once, apply(cx, offered))
            assertTrue(once.rustLines().any { it == "dropped:huge:$t" }, once)
            assertTrue(r.estimate(once, Class.Prose) <= budget, once)
        }

    @Test fun oneMbModeFileStaysUnderBudgetAndStable() {
        val dir = tmpDir("t188-big-mode")
        writeString("$dir/big.md", "y".repeat(1 shl 20))
        withRuntime("t188", modes(dir, "big")) { r ->
            val once = start(Ctx(r))
            assertEquals(once, start(Ctx(r)))
            assertTrue(r.estimate(once, Class.Prose) <= r.config.plugins.inject.budgetTokens, once)
        }
    }

    @Test fun sessionStartHasNudgesOnceAndStable() =
        withRuntime("t531", modes(null, "nudges")) { r ->
            val once = start(Ctx(r))
            assertEquals(once, start(Ctx(r)), "nudge bytes must be stable")
            assertEquals(1, Regex("# nudges").findAll(once).count(), once)
            assertTrue(r.estimate(NUDGES_MD, Class.Prose) <= 250u)
        }

    @Test fun sessionStartHasModeOnce() =
        withRuntime("t71", modes(null, "terse", "yagni")) { r ->
            val text = start(Ctx(r))
            assertEquals(1, Regex("# terse").findAll(text).count(), text)
            assertTrue("# yagni" in text, text)
            assertTrue(r.estimate(TERSE_MD, Class.Prose) <= 250u)
            assertTrue(r.estimate(YAGNI_MD, Class.Prose) <= 250u)
        }

    @Test fun readModeKeepsAFittingMultibyteModeWhole() =
        withRuntime("t606") { r ->
            val dir = tmpDir("t606-read")
            val tail = "\nKEEP_THIS_TAIL\n"
            for ((label, unit) in listOf("ascii" to "x", "two" to "ж", "three" to "世", "four" to "😀")) {
                val text = unit.repeat(3000) + tail
                assertTrue(r.estimate(text, Class.Prose) <= 800u, "$label must fit")
                writeString("$dir/$label.md", text)
                assertEquals(text, readMode("$dir/$label.md", null, 800u, 4.2f), label)
            }
            assertEquals("builtin", readMode("$dir/missing.md", "builtin", 800u, 4.2f))
            assertNull(readMode("$dir/missing.md", null, 800u, 4.2f))
        }

    @Test fun readModeBoundsAHugeMultibyteFileOnACharacterBoundary() {
        val path = "${tmpDir("t606-huge")}/huge.md"
        writeString(path, "😀".repeat(1 shl 18))
        val body = assertNotNull(readMode(path, null, 800u, 4.2f))
        assertEquals(3361, body.scalarList().size)
        assertFalse('\uFFFD' in body, "cut inside a character")
        assertEquals(1, assertNotNull(readMode(path, null, 0u, 4.2f)).scalarList().size)
    }

    @Test fun sessionStartKeepsTheTailOfAFittingUnicodeMode() {
        val dir = tmpDir("t606-hook")
        writeString("$dir/uni.md", "😀".repeat(1000) + "\nKEEP_THIS_TAIL\n")
        withRuntime("t606-hook", modes(dir, "uni")) { r ->
            val once = start(Ctx(r))
            assertEquals(once, start(Ctx(r)))
            assertTrue("KEEP_THIS_TAIL" in once, once)
            assertFalse("dropped:inject" in once, once)
        }
    }

    @Test fun compactRestoreLeadsWithTheCheckpointAtPriorityNine() =
        withRuntime("inject-compact", modes(null, "terse")) { r ->
            val cx = Ctx(r)
            cx.upsertNote("rtok", "checkpoint:${cx.session()}", "compact", "checkpoint\n- go on\n")
            val inj = assertNotNull(InjectPlugin.sessionStart(SessionStart("compact"), cx))
            assertEquals(9u.toUByte(), inj.priority)
            assertTrue(inj.text.startsWith("checkpoint\n- go on\n\n# terse"), inj.text)
            assertEquals(5u.toUByte(), assertNotNull(InjectPlugin.sessionStart(SessionStart("resume"), cx)).priority)
        }
}
