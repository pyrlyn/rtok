// Port of src/modes/compress.rs tests.
package rtok.modes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import rtok.modes.CaveIntensity.*

class CompressTest {
    private val all = listOf(Lite, Full, Ultra)

    @Test fun fullDropsFillerKeepsFence() {
        val out = compressProse("Sure! I'd be happy to help. The bug is just really in auth.\n```rs\nfn f() { let the = 1; }\n```\nThen fix it.", Full)
        assertTrue(out.contains("```rs\nfn f() { let the = 1; }\n```"), out)
        assertFalse(out.lowercase().startsWith("sure"), out)
        assertFalse(out.contains("really"), out); assertFalse(out.contains("just"), out)
    }
    @Test fun neverDropsNegation() {
        val out = compressProse("Do not delete the table. Never run drop. This is no joke.", Full)
        assertTrue(out.contains("not")); assertTrue(out.contains("Never") || out.contains("never")); assertTrue(out.contains("no"))
    }
    @Test fun liteOnlyPleasantries() {
        val out = compressProse("Sure! The function really works.", Lite)
        assertFalse(out.startsWith("Sure")); assertTrue(out.contains("really")); assertTrue(out.contains("The"))
    }
    @Test fun protectsInlineBacktickSpans() = assertEquals("answer is `Sure! The really exact value` and result.",
        compressProse("Sure! The answer is just `Sure! The really exact value` and the result.", Full))
    @Test fun protectsDoubleBacktickSpans() = assertEquals("Use ``the really `simple` value`` and helper.",
        compressProse("Of course! Use ``the really `simple` value`` and the helper.", Full))
    @Test fun ultraPhraseStripNeverCutsAWord() = assertTrue(compressProse("Run the command then check the output.", Ultra).contains("command then"))
    @Test fun ultraPhraseStripStillDropsTheStandalonePhrase() {
        val out = compressProse("Build it and then run it.", Ultra)
        assertFalse(out.contains("and then"), out); assertTrue(out.contains("Build it") && out.contains("run it"), out)
    }
    @Test fun anArticleInsideANameIsKept() {
        for ((input, want) in listOf("Edit a.txt now" to "a.txt", "Open a-b next" to "a-b", "Check a/b here" to "a/b", "Keep a1 there" to "a1"))
            assertTrue(compressProse(input, Full).contains(want), input)
        assertEquals("Edit file", compressProse("Edit the file", Full))
    }
    @Test fun aSuffixInsideANameIsKept() {
        for (i in all) for (name in listOf("foo_a", "foo_the", "/tmp/a", "C:\\tmp\\the", "item1a", "v2an", "x-a", "x.the", "_a_", "naïvea", "a_b", "1_a_2")) {
            val out = compressProse("Edit $name now", i); assertTrue(out.contains(name), "$i: $name → $out")
        }
        assertEquals("Edit file and test", compressProse("Edit the file and a test", Ultra))
        assertEquals("foo_a is one", compressProse("foo_a is really the one", Full))
    }
    @Test fun aSuffixNameNextToCodeIsKept() = assertEquals("Use `x` then foo_a, and `y`.", compressProse("Use `x` then foo_a, and the `y`.", Full))
    @Test fun aLongerFenceSurvivesAShorterInnerRun() {
        val fence = "````md\nSure! Example:\n```rs\nlet the = 1;\n```\nThe really exact content\n````"
        for (i in all) assertTrue(compressProse("Sure! The bug is really here.\n$fence\nFix the thing.", i).contains(fence))
        val out = compressProse("The bug is really here.\n$fence\nFix the thing.", Full)
        assertTrue(out.startsWith("bug is here.\n"), out); assertTrue(out.endsWith("Fix thing."), out)
    }
    @Test fun fencesFollowMarkdownOpeningAndClosingRules() {
        for (fence in listOf("```\nthe really\n`````", "~~~\nthe really\n~~~", "   ```\nthe really\n   ```", "```\nthe really\n```   ")) {
            val out = compressProse("The intro\n$fence\nThe outro", Full)
            assertTrue(out.contains(fence.trimEnd()), out); assertTrue(out.endsWith("outro"), out)
        }
        for (body in listOf("``` the really", "~~~", "`` the really ``", "````` the")) {
            val fence = "````\nx\n$body\nthe really exact\n````"
            assertEquals(fence, compressProse(fence, Full))
        }
        assertEquals("intro\n```\nthe really\n```x\nthe end", compressProse("The intro\n```\nthe really\n```x\nthe end", Full))
    }
    @Test fun inlineTripleBacktickSpansStayInline() = assertEquals("```the really``` span and rest.", compressProse("The ```the really``` span and the rest.", Full))
    @Test fun emptyNeedleIsIdentity() = assertEquals("command then", replaceCi("command then", "", " "))
    @Test fun preservesWhitespaceInsideBacktickSpans() = assertEquals("result is `left   middle\n  right` for test.",
        compressProse("The result is `left   middle\n  right` for the test.", Full))
    @Test fun removesOnlyCompleteLeadingPleasantries() {
        assertEquals("Assure! Sure! The result.", compressProse("Assure! Sure! The result.", Lite))
        assertEquals("Done.", compressProse("Sure! Sure thing! Done.", Lite))
    }
}
