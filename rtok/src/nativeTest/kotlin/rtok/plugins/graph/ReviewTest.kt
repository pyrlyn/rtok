package rtok.plugins.graph

import kotlinx.serialization.json.*
import rtok.plugin.Runtime
import rtok.plugin.sdk.Ctx
import rtok.plugin.sdk.SymDef
import rtok.store.measurementCount
import rtok.support.tmpDir
import rtok.support.withRuntime
import rtok.sys.*
import kotlin.test.*

class ReviewTest {
    @Test fun riskScoreEndpoints() {
        assertEquals(0.3, Review.riskScore(0, false, false))
        assertEquals(0.05, Review.riskScore(0, true, false))
        assertEquals(0.6, Review.riskScore(100, false, true))
        assertEquals("medium", Review.level(0.6))
        assertEquals("low", Review.level(0.3))
        assertEquals("high", Review.level(0.71))
    }

    @Test fun securityTokenIsAWholeToken() {
        assertTrue(Review.securityToken("sign"))
        assertTrue(Review.securityToken("sign_in"))
        assertTrue(Review.securityToken("httpClient"))
        assertFalse(Review.securityToken("assign"))
        assertFalse(Review.securityToken("signature"))
    }

    @Test fun parseHunksReadsNewFileRangesAndSkipsDeletes() {
        val diff =
            "diff --git a/src/a.rs b/src/a.rs\n--- a/src/a.rs\n+++ b/src/a.rs\n@@ -10 +10,2 @@ fn\n context\n" +
                "diff --git a/gone.rs b/gone.rs\n--- a/gone.rs\n+++ /dev/null\n@@ -1 +0,0 @@\n-fn gone() {}\n"
        val hunks = Review.parseHunks(diff)
        assertEquals(listOf(10 to 11), hunks["src/a.rs"])
        assertFalse("gone.rs" in hunks)
    }

    @Test fun widenCoversAShortDefinitionAndNotALongOne() {
        assertEquals(listOf(Region(1, 10)), Review.widen(listOf(5 to 5), listOf(SymDef("small", "function", 1, 10))))
        val r = Review.widen(listOf(200 to 200), listOf(SymDef("big", "function", 1, 400)))
        assertTrue(r[0].end - r[0].start + 1 < 40, "$r")
        assertTrue(r[0].start <= 200 && r[0].end >= 200)
    }

    @Test fun allocateServesTheHigherScoreFirst() {
        fun file(
            path: String,
            score: Double,
        ) = FileReview(path, score, emptyList(), listOf(Region(1, 4)))
        // Budget 10, per-file share 4, each region 4 lines: the lowest-ranked file is left out.
        val files = listOf(file("a.rs", 0.5), file("b.rs", 0.3), file("c.rs", 0.1))
        Review.allocateBudget(files, 10)
        assertEquals(1, files[0].regions.size, "$files")
        assertEquals(1, files[1].regions.size, "$files")
        assertTrue(files[2].regions.isEmpty(), "$files")
        assertEquals(1, files[2].regionsOmitted)
    }

    private fun git(
        dir: String,
        vararg args: String,
    ) {
        val (c, _) = ProgressRunner("", "git", listOf("-C", dir, "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false") + args).quiet().runCapped(1L shl 20)
        check(c.exitCode == 0) { "git ${args.toList()}: ${c.stderr.decodeToString()}" }
    }

    private fun repo(
        tag: String,
        lib: String,
        test: String?,
        body: (Runtime, String) -> Unit,
    ) = withRuntime(tag) { rt ->
        val dir = canonicalize(tmpDir(tag))!!
        try {
            writeString("$dir/lib.rs", lib)
            if (test != null) {
                createDirAll("$dir/tests")
                writeString("$dir/tests/parse.rs", test)
            }
            git(dir, "init", "-q", "-b", "main")
            git(dir, "add", "-A")
            git(dir, "commit", "-q", "-m", "init")
            body(rt, dir)
        } finally {
            removeDirAll(dir)
        }
    }

    private val lib = "fn parse() {\n    let _ = 1;\n}\n"

    @Test fun reviewMarksAnUnreachedFunction() =
        repo("review-gap", lib, null) { rt, dir ->
            writeString("$dir/lib.rs", "fn parse() {\n    let _ = 2;\n}\n")
            val out = Review.review(Ctx(rt), dir, "HEAD", false, false)
            assertTrue(out.contains("untested: parse"), out)
            assertTrue(out.startsWith("review: "), out)
            assertTrue(out.contains("lib.rs"), out)
            assertEquals(0, rt.store.measurementCount("graph"))
        }

    @Test fun reviewClearsItWhenATestCallsIt() =
        repo("review-tested", lib, "fn test_parse() {\n    parse();\n}\n") { rt, dir ->
            writeString("$dir/lib.rs", "fn parse() {\n    let _ = 2;\n}\n")
            val cx = Ctx(rt)
            val out = Review.review(cx, dir, "HEAD", false, false)
            assertFalse(out.contains("untested: parse"), out)
            assertEquals(0, rt.store.measurementCount("graph"))
            val v = Json.parseToJsonElement(Review.review(cx, dir, "HEAD", false, true)).jsonObject
            assertNotNull(v["level"])
            assertNotNull(v["score"])
            assertTrue(
                v["files"]!!
                    .jsonArray[0]
                    .jsonObject["path"]!!
                    .jsonPrimitive.content
                    .contains("lib.rs"),
            )
            assertEquals(Scope.reviewGit(cx, listOf(Member("p", dir)), "HEAD", false, false), out)
        }

    @Test fun reviewWithoutAGitRepoNamesTheFailure() =
        withRuntime("review-nogit") { rt ->
            val dir = canonicalize(tmpDir("review-nogit"))!!
            try {
                writeString("$dir/lib.rs", "fn parse() {}\n")
                val err = assertFails { Review.review(Ctx(rt), dir, null, false, false) }.message!!
                assertTrue(err.startsWith("git diff failed"), err)
            } finally {
                removeDirAll(dir)
            }
        }

    @Test fun reviewOfACleanTreeSaysNoChanges() =
        repo("review-clean", "fn parse() {}\n", null) { rt, dir ->
            assertEquals("no changes\n", Review.review(Ctx(rt), dir, "HEAD", false, false))
            assertEquals("no changes\n", Scope.reviewGit(Ctx(rt), listOf(Member("a", dir), Member("b", "$dir/none")), "HEAD", false, false))
        }
}
