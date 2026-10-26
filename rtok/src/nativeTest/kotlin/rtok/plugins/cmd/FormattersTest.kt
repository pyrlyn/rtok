package rtok.plugins.cmd

import rtok.config.Estimator
import rtok.config.envVar
import rtok.estimate
import rtok.plugin.sdk.Class
import rtok.sys.listDir
import rtok.sys.readToString
import rtok.sys.rustLines
import kotlin.test.*

/** Port of the formatters.rs tests, driven by the shared `cmd_golden` fixtures. */
class FormattersTest {
    private data class Golden(
        val argv: List<String>,
        val exit: Int,
        val minSaving: Int?,
        val output: String,
    )

    private val dir = "${envVar("RTOK_FIXTURES")}/cmd_golden"
    private val settings = Settings.builtin()

    /** `argv: 'a && b'` (single-quoted) is one argv element, the shape the hook sends. */
    private fun parseIn(s: String): Golden {
        var argv = emptyList<String>()
        var exit = 0
        var min: Int? = null
        var rest = s
        for (line in s.rustLines()) {
            line.removePrefixOrNull("argv: ")?.let { a ->
                argv = if (a.length >= 2 && a.startsWith('\'') && a.endsWith('\'')) listOf(a.substring(1, a.length - 1)) else words(a)
            }
            line.removePrefixOrNull("exit: ")?.let { exit = it.toIntOrNull() ?: 0 }
            line.removePrefixOrNull("min_saving: ")?.let { min = it.trim().toIntOrNull() }
            if (line == "---") {
                rest = s.substringAfter("---\n", "")
                break
            }
        }
        return Golden(argv, exit, min, rest)
    }

    private fun words(s: String) = s.split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() }

    private fun ins() = listDir(dir).filter { it.endsWith(".in") }.sorted()

    private fun golden(name: String) = parseIn(readToString("$dir/$name"))

    private fun compress(
        argv: List<String>,
        out: String,
        exit: Int = 0,
        id: String = "id",
    ) = Formatters.compress(settings, argv, out, exit, id)

    /** Fixed rates so a config default change cannot shift the saving floors. */
    private val rates = Estimator(3.5f, 4.2f, 3.0f, 1.0f)
    private val awsExampleKeyId = "AKIAIOSFODNN7EXAMPLE"

    @Test fun tenFamiliesAndAwsKeyUnredacted() {
        var n = 0
        for (f in ins()) {
            n++
            val g = golden(f)
            val got = compress(g.argv, g.output, g.exit, "deadbeef").text
            assertEquals(readToString("$dir/${f.removeSuffix(".in")}.out").trimEnd(), got.trimEnd(), f)
            val floor = assertNotNull(g.minSaving, "$f: missing `min_saving:` header")
            val b = estimate(g.output, Class.Code, rates)
            val a = estimate(got, Class.Code, rates)
            val pct = if (b == 0) 0 else (b - a) * 100 / b
            assertTrue(a <= b, "$f: output ($a tok) larger than input ($b tok)")
            assertTrue(pct >= floor, "$f: saving $pct% below floor $floor%")
        }
        assertTrue(n >= 10, "need 10 families, got $n")
        val secret = golden("cat.in").output
        assertTrue(secret.contains(awsExampleKeyId))
        // Never echo the output: it carries a credential-shaped fixture.
        assertTrue(compress(listOf("cat", "secrets.env"), secret).text.contains(awsExampleKeyId), "AWS key line altered")
    }

    @Test fun ruleIsPickedByTheCommandNotAnArgument() {
        fun pick(vararg a: String) = settings.pick(Formatters.bin(Formatters.familyArgv(a.toList()))).matchCmd
        assertEquals("grep", pick("/usr/bin/grep", "-rn", "x"))
        assertEquals("", pick("git", "commit", "-m", "fix grep"))
        assertEquals("", pick("docker", "run", "node"))
    }

    private fun lines(
        n: Int,
        f: (Int) -> String,
    ) = (0 until n).joinToString("\n") { f(it) }

    @Test fun newlineJoinedScriptUsesTheScriptRule() {
        val script = "git status\ncat file.txt\n" + lines(40) { "line $it of mixed output" }
        val (got, kind) = compress(listOf(script), script)
        assertEquals("rule", kind, got)
        assertTrue(got.length < script.length && got.contains("omitted (expand id)"), got)
        assertNotEquals("rule", compress(listOf("git status"), "clean\n").kind)
    }

    @Test fun sameProgramChainKeepsItsOwnFamily() {
        val diag = lines(40) { "warning: unused variable `x$it`" }
        for (cmd in listOf("cargo build && cargo test", "cd crates/rtok && cargo test")) {
            assertNotEquals("rule", compress(listOf(cmd), diag).kind, cmd)
        }
    }

    @Test fun aPickedRuleThatShrinksNothingIsUnmatched() {
        val body = "one\ntwo\nthree\nfour\nfive"
        assertEquals(Compressed(body, "unmatched"), compress(listOf("cat", "f"), body))
    }

    @Test fun unboundedMultiFileCatAndLargeGrepGetTheirRule() {
        for ((cmd, body) in listOf(
            "cat -n a.rs b.rs c.rs" to lines(200) { "line $it" },
            "grep -rn -A3 needle src" to lines(200) { "src/f$it.rs:$it:needle found here" },
        )) {
            val (got, kind) = compress(listOf(cmd), body)
            assertEquals("rule", kind, cmd)
            assertTrue(got.length < body.length, cmd)
        }
    }

    @Test fun oneQuotedArgvFiltersLikeASplitArgv() {
        var checked = 0
        for (f in ins()) {
            val g = golden(f)
            if (g.argv.isEmpty()) continue
            checked++
            assertEquals(compress(g.argv, g.output, g.exit), compress(listOf(g.argv.joinToString(" ")), g.output, g.exit), f)
        }
        assertTrue(checked >= 10)
    }

    @Test fun familyNames() {
        assertEquals("git", Formatters.family(listOf("git status | head")))
        assertEquals("git", Formatters.family(listOf("/usr/bin/git", "status")))
        assertEquals("other", Formatters.family(emptyList()))
        assertEquals("git", Formatters.family(listOf("C:\\tools\\git.exe", "status")))
        assertEquals("cargo", Formatters.family(listOf("cargo.exe", "test")))
    }

    private val failed = "test a::bad ... FAILED\ntest result: FAILED. 1 failed\n"

    @Test fun wrappersUseTheInnerFormatterWhenTheCommandIsVisible() {
        for (cmd in listOf(
            "mise exec -- cargo test",
            "mise x node@20 -- cargo test",
            "mise exec -c 'cargo test'",
            "just --command cargo test",
            "just -c cargo test",
            "cd /tmp && mise exec -- cargo test",
        )) {
            val (got, kind) = compress(listOf(cmd), failed)
            assertEquals("formatter", kind, cmd)
            assertTrue(got.contains("FAILED"), cmd)
        }
        assertEquals("formatter", compress(listOf("mise run test"), "[test] $ cargo test\n$failed").kind)
        assertEquals("formatter", compress(listOf("just test"), "mise exec -- cargo test\n$failed").kind)
        assertNotEquals("formatter", compress(listOf("mise run test"), failed).kind)
        assertNotEquals("formatter", compress(listOf("just test"), failed).kind)
    }

    @Test fun twoEchoedProgramsUseTheScriptRule() {
        val body = lines(40) { "line $it of mixed output" }
        val output = "mise exec -- git status\n$body\nmise exec -- cargo test\n$body"
        assertEquals("script", Formatters.familyOf(settings, listOf("just check"), output))
        val (got, kind) = compress(listOf("just check"), output)
        assertEquals("rule", kind)
        assertTrue(got.contains("omitted"))
    }

    @Test fun tableFormatterBeatsDefaultRuleAndKeepsEveryObject() {
        for ((file, argv, prefix) in listOf(
            Triple("docker_ps.in", "docker ps", "web-"),
            Triple("podman_ps.in", "podman ps", "web-"),
            Triple("kubectl_get.in", "kubectl get", "web-deploy-"),
            Triple("ps_aux.in", "ps aux", "worker-"),
        )) {
            val g = golden(file)
            val (got, kind) = compress(words(argv), g.output, g.exit, "deadbeef")
            assertEquals("formatter", kind, file)
            assertTrue(got.length < Rules.apply(settings, g.output, g.exit, Rule(), "deadbeef").length, file)
            for (i in 0 until 40) {
                val nn = i.toString().padStart(2, '0')
                val name =
                    when (prefix) {
                        "web-" -> "web-$nn"
                        "web-deploy-" -> "web-deploy-$nn-abcd"
                        else -> "worker-$i"
                    }
                assertTrue(got.contains(name), "$file dropped $name")
            }
        }
    }

    @Test fun tableFormatterStandsDownOnUnrecognized() {
        for ((argv, out, exit, needle) in listOf(
            listOf("docker ps", "Cannot connect to the Docker daemon\n", "0", "Cannot connect"),
            listOf("podman ps", "Cannot connect to the Podman daemon\n", "0", "Cannot connect"),
            listOf("kubectl get", "error: the server doesn't have a resource type \"pods\"\n", "1", "doesn't have a resource type"),
            listOf("ps aux", "ps: invalid option -- z\n", "1", "invalid option"),
            listOf("df -h", "df: /nope: No such file or directory\n", "1", ""),
            listOf("sqlite3 db", "1|alpha\n2|beta\n", "0", ""),
        )) {
            val (got, kind) = compress(words(argv), out, exit.toInt(), "deadbeef")
            assertNotEquals("formatter", kind, argv)
            assertTrue(got.contains(needle), argv)
        }
    }

    @Test fun dfSqliteAndAwkBeatTheDefaultRule() {
        for (f in listOf("df.in", "sqlite3.in", "awk.in")) {
            val g = golden(f)
            val got = compress(g.argv, g.output, g.exit, "deadbeef").text
            assertTrue(got.length < Rules.apply(settings, g.output, g.exit, Rule(), "deadbeef").length, f)
        }
    }

    @Test fun groupGoldensBeatTheSameRuleWithoutGroup() {
        for (f in listOf(
            "ls.in",
            "find.in",
            "rg.in",
            "tsc_dup.in",
            "eslint_dup.in",
            "cargo_check.in",
            "dotnet_dup.in",
            "ruff_check.in",
            "mypy_run.in",
            "rubocop_run.in",
            "golangci_lint_run.in",
        )) {
            val g = golden(f)
            val got = compress(g.argv, g.output, g.exit, "deadbeef").text
            val off = settings.pick(Formatters.bin(Formatters.familyArgv(g.argv))).copy(group = Group.Off)
            assertTrue(got.length < Rules.apply(settings, g.output, g.exit, off, "deadbeef").length, f)
        }
    }

    @Test fun psAuxSkipsABadLine() {
        val got = assertNotNull(Tables.psAux("USER PID %CPU COMMAND\nroot 1 0.0 /sbin/init\nUSER PID %CPU COMMAND\nroot 42 0.1 /usr/bin/worker-7\n"))
        assertTrue(got.contains("1 init") && got.contains("42 worker-7") && !got.contains("PID"), got)
    }

    private fun commit(
        sha: Char,
        subject: String,
        author: String = "Ann <a@x>",
        date: String = "Wed Oct 14 10:00:00 2026 +0300",
        tail: String = "",
    ) = "commit ${sha.toString().repeat(40)}\nAuthor: $author\nDate:   $date\n\n    $subject\n\n$tail"

    @Test fun gitLogListsEveryCommitWithAuthorsWhenMixed() {
        val raw = commit('1', "first", tail = "    body line\n\n") + commit('2', "second", "Bob <b@x>", "Tue Oct 13 10:00:00 2025 +0300")
        assertEquals("[2 commits] Ann unless named\n1111111 Oct 14 2026 first\n2222222 Oct 13 2025 second — Bob", Formatters.gitLog(raw))
    }

    @Test fun gitLogKeepsStatLinesUnderTheirCommit() {
        val out = Formatters.gitLog(commit('1', "first", tail = " src/a.rs | 2 +-\n 1 file changed, 1 insertion(+), 1 deletion(-)\n"))!!
        assertTrue(out.contains("1111111 Oct 14 first") && out.contains("  src/a.rs | 2 +-") && out.contains("  1 file changed"), out)
    }

    @Test fun gitLogHeaderCountsCommitsAndStatTotals() {
        fun c(
            sha: Char,
            stat: String,
        ) = commit(sha, "s$sha", tail = " src/a.rs | 3 ++-\n$stat\n\n")
        val raw = c('1', " 1 file changed, 2 insertions(+), 1 deletion(-)") + c('2', " 1 file changed, 3 insertions(+)")
        assertEquals("[2 commits, +5/-1] Ann · 2026", Formatters.gitLog(raw)!!.rustLines().first())
        assertEquals("[1 commit] Ann · 2026", Formatters.gitLog(commit('1', "one"))!!.rustLines().first())
    }

    @Test fun gitLogDropsTheAuthorWhenAllAreEqual() {
        val out = Formatters.gitLog(commit('1', "s1") + commit('2', "s2"))!!
        assertEquals(1, out.split("Ann").size - 1, out)
        assertFalse(out.contains(" — "), out)
    }

    @Test fun gitLogOtherShapesKeepTheHeadCut() {
        assertNull(Formatters.gitLog("1111111 first\n2222222 second\n"))
        assertNull(Formatters.gitLog(commit('1', "first", tail = "diff --git a/x b/x\n")))
    }

    @Test fun gitLogCapsTheListing() {
        val out = Formatters.gitLog(commit('1', "c").repeat(105))!!
        assertTrue(out.endsWith("… +5 more"), out)
        assertEquals(102, out.rustLines().size)
    }

    @Test fun everyFormatterArmHasAGolden() {
        val arms =
            listOf(
                "cargo" to "test",
                "cargo" to "build",
                "cargo" to "clippy",
                "git" to "status",
                "git" to "diff",
                "git" to "show",
                "git" to "log",
                "gh" to "",
                "pytest" to "",
                "python" to "",
                "python3" to "",
                "py" to "",
                "jest" to "",
                "vitest" to "",
                "go" to "test",
                "tree" to "",
                "docker" to "ps",
                "podman" to "ps",
                "kubectl" to "get",
                "ps" to "aux",
                "df" to "",
                "sqlite3" to "",
                "wc" to "",
                "mise" to "",
                "for" to "",
                "while" to "",
                "until" to "",
            )
        for (stem in Formatters.FORMATTER_STEMS) assertTrue(arms.any { it.first == stem }, "$stem missing from arms")
        val parsed = ins().map(::golden).filter { it.argv.isNotEmpty() }
        val missing =
            arms.filter { (b, s) ->
                parsed.none { g ->
                    val split = Formatters.familyArgv(g.argv)
                    Formatters.bin(split) == b && (s.isEmpty() || Formatters.sub(split) == s) && Formatters.format(split, g.output) != null
                }
            }
        assertTrue(missing.isEmpty(), "formatter arms without a golden: $missing")
    }

    @Test fun everyBuiltinRuleFamilyHasAGolden() {
        val bins =
            ins()
                .map(::golden)
                .filter { it.argv.isNotEmpty() }
                .map { Formatters.bin(Formatters.familyArgv(it.argv)) }
                .toSet()
        val missing = Rules.defaults().map { it.matchCmd }.filter { it != "script" && it !in bins }
        assertTrue(missing.isEmpty(), "families without a golden: $missing")
    }

    @Test fun wrappersUseTheInnerCommandWhenItIsVisible() {
        fun fam(
            cmd: String,
            out: String,
        ) = Formatters.familyOf(settings, words(cmd), out)
        assertEquals("cargo", fam("mise exec -- cargo test", failed))
        assertEquals("git", fam("mise x -C /tmp -- git status", "On branch\n"))
        assertEquals("node", fam("mise exec node@20 -- node -v", "v20\n"))
        assertEquals("cargo", fam("just -c cargo test", failed))
        assertEquals("mise", fam("mise exec -c", failed))
        assertEquals("cargo", fam("mise run test", "[test] $ cargo test\n$failed"))
        assertEquals("mise", fam("mise run test", failed))
        assertEquals("mise", fam("mise exec cargo test", failed))
        assertEquals("git", fam("just status", "git status\nOn branch main\n"))
        assertEquals("git", fam("just status", "\u001b[32mgit status\u001b[0m\nOn branch main\n"))
        assertEquals("just", fam("just check", "Checking foo v0.1.0\nFinished dev\n"))
        assertEquals("script", fam("just check", "mise exec -- cargo test\nmise exec -- git status\n"))
        assertEquals("cargo", fam("mise exec -- just test", "[test] $ cargo test\n$failed"))
    }

    @Test fun familyNamesPeeledWrappers() {
        fun fam(cmd: String) = Formatters.familyOf(settings, words(cmd), "ok\n")
        assertEquals("cargo", fam("uv run cargo test"))
        assertEquals("cargo", fam("FOO=1 cargo test"))
        assertEquals("pytest", fam(".venv/bin/pytest -q"))
        assertEquals("pytest", fam("uv run python -m pytest -q"))
        assertEquals("cargo", fam("mise exec -- cargo test"))
        assertEquals("mise", fam("mise exec --"))
        assertEquals("git", Formatters.familyOf(settings, listOf("git commit -m \"a|b\""), "ok\n"))
    }
}
