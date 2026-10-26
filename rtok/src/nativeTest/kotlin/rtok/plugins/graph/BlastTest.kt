package rtok.plugins.graph

import kotlin.test.*
import rtok.plugin.sdk.Class
import rtok.plugin.sdk.Ctx
import rtok.support.withRuntime

class BlastTest {
    private fun hub(files: Int, per: Int) = (0 until files).flatMap { f -> (0 until per).map { d -> WalkRow(1, "src/f${f.toString().padStart(3, '0')}.rs", "fn_${f}_$d") } }
    private fun text(cx: Ctx, rows: List<WalkRow>, budget: UInt, all: Boolean = false) = Blast.render(cx, rows, "hub", Budget(budget, all = all)) { emptyMap() }

    @Test fun aSmallWalkIsTheFlatListing() = withRuntime("t377-small") { rt ->
        val rows = hub(3, 2); assertEquals(Blast.linesText(rows), text(Ctx(rt), rows, 1500u))
    }

    @Test fun allAndAZeroBudgetKeepTheFlatListing() = withRuntime("t377-all") { rt ->
        val cx = Ctx(rt); val rows = hub(200, 5); val flat = Blast.linesText(rows)
        assertEquals(flat, text(cx, rows, 300u, true)); assertEquals(flat, text(cx, rows, 0u))
    }

    @Test fun aHubFitsTheBudgetAndEndsWithTheCutLine() = withRuntime("t377-hub") { rt ->
        val cx = Ctx(rt); val out = text(cx, hub(200, 5), 300u)
        assertTrue(cx.estimate(out, Class.Code) <= 300u, out)
        val shown = Regex(" refs, depth 1\\)\n").findAll(out).count()
        assertTrue(shown in 1 until 200, out)
        assertEquals("+${200 - shown} files, ${(200 - shown) * 5} refs not shown — impact hub --all", out.trimEnd('\n').lines().last())
        assertTrue(out.startsWith("src/f000.rs (5 refs, depth 1)\n  1  fn_0_0\n  1  fn_0_1\n  1  fn_0_2\n  +2 more\n"), out)
    }

    @Test fun filesLeadByDepthThenRankThenReferenceCount() = withRuntime("t377-order") { rt ->
        val cx = Ctx(rt)
        val rows = listOf(WalkRow(2, "near_but_deep.rs", "a"), WalkRow(1, "many.rs", "a"), WalkRow(1, "many.rs", "b"), WalkRow(1, "central.rs", "a"), WalkRow(1, "few.rs", "a")) +
            hub(60, 1).map { WalkRow(3, "z/${it.path}", it.scope) }
        fun heads(rank: Map<String, Float>) = Blast.render(cx, rows, "hub", Budget(120u)) { rank }.lines()
            .filter { it.contains(" (") && it.endsWith(")") }.map { it.substringBefore(' ') }
        assertEquals(listOf("many.rs", "central.rs", "few.rs", "near_but_deep.rs"), heads(emptyMap()).take(4))
        assertEquals(listOf("few.rs", "central.rs", "many.rs", "near_but_deep.rs"), heads(mapOf("few.rs" to 0.5f, "central.rs" to 0.1f)).take(4))
    }

    @Test fun marksAndOverheadComeOffTheBudget() = withRuntime("t377-overhead") { rt ->
        val cx = Ctx(rt); val rows = hub(200, 5)
        val out = Blast.render(cx, rows, "hub", Budget(300u, overhead = 40u, mark = true)) { emptyMap() }
        assertTrue(cx.estimate("x".repeat(120) + Tools.markAmbiguousLines(out), Class.Code) <= 300u, out)
        val one = Blast.render(cx, rows, "hub", Budget(300u, overhead = 900u)) { emptyMap() }
        assertEquals(1, Regex(" refs, depth 1\\)\n").findAll(one).count(), one)
        assertTrue(one.contains("+199 files, 995 refs not shown"), one)
    }
}
