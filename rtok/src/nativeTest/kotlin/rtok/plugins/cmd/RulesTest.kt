// Port of the src/plugins/cmd/rules.rs tests (47; rstest cases folded into loops).
package rtok.plugins.cmd

import kotlin.test.*
import kotlin.time.TimeSource
import rtok.support.tmpDir
import rtok.sys.createDirAll
import rtok.sys.removeDirAll
import rtok.sys.rustLines
import rtok.sys.writeString

private fun settings(failTailLines: UInt) = Settings(maxOf(failTailLines, 1u).toInt(), Rules.defaults())
private fun apply(s: Settings, body: String, exit: Int, rule: Rule, id: String) = Rules.apply(s, body, exit, rule, id)
private fun loadVfs(v: VfsRuleFiles, file: String, dir: String?) = Settings.load(v, file, dir, 80u)
private fun uncut(group: Group) = Rule(maxLines = 40u, head = 10u, tail = 10u, dedupe = Dedupe.Off, collapseColumns = false, group = group)

/** Sum of every `… N lines omitted` trailer in [out]. */
private fun omittedSum(out: String) = out.rustLines().mapNotNull { it.removePrefixOrNull("… ")?.split(' ')?.firstOrNull()?.toIntOrNull() }.sum()

class RulesTest {
    @Test fun exitNonzeroFailTailLines() {
        for ((fail, count, start, end) in listOf(listOf<Any>(80u, 80, "line 20\n", "line 99"), listOf<Any>(3u, 3, "line 97\n", "line 99"))) {
            val out = apply(settings(fail as UInt), numbered(100), 3, Rule(), "id")
            assertEquals(count, out.rustLines().size); assertTrue(out.startsWith(start as String), out); assertTrue(out.endsWith(end as String), out)
            assertFalse(out.contains("omitted"))
        }
    }

    @Test fun threeHundredOkKeepsErrorUnder20() {
        val body = (0 until 300).map { "ok $it" } + "error: boom"
        val out = apply(settings(80u), body.joinToString("\n"), 0, Rule(maxLines = 20u, head = 5u, tail = 5u), "abc")
        assertTrue(out.rustLines().size <= 20, out); assertTrue(out.contains("error: boom"), out)
    }

    @Test fun maxCapReportsTrueOmittedCount() {
        val out = apply(settings(80u), numbered(10), 0, Rule(maxLines = 4u, head = 3u, tail = 3u, dedupe = Dedupe.Off), "arc")
        assertTrue(out.contains("7 lines omitted"), out)
        assertEquals(7, 10 - out.rustLines().count { !it.contains("lines omitted") }, out)
    }

    /** A trace block behind a spent budget must not make the cut count a dropped line twice. */
    @Test fun omittedCountsAddUpWithATracePastTheBudget() {
        for ((max, head, tail) in listOf(Triple(4u, 3u, 1u), Triple(5u, 2u, 2u), Triple(3u, 1u, 1u), Triple(6u, 4u, 1u))) {
            val lines = (0 until 5).map { "a$it" } + "error: boom" + listOf("Traceback (most recent call last):", "  File \"x\", line 1", "    foo()", "ValueError: x") + (0 until 3).map { "z$it" }
            val out = apply(settings(80u), lines.joinToString("\n"), 0, Rule(maxLines = max, head = head, tail = tail, dedupe = Dedupe.Off), "arc")
            val shown = out.rustLines().count { l -> lines.any { it == l } }
            assertEquals(lines.size, shown + omittedSum(out), out)
        }
    }

    @Test fun traceLinesPastTheCapGoThroughTheBudget() {
        val lines = (0 until 2000).flatMap { listOf("Exception in thread \"t$it\" java.lang.IllegalStateException", "\tat Svc.run$it(Svc.java:1)", "\tat Svc.main$it(Svc.java:2)") }
        val rule = Rule(maxLines = 50u, head = 10u, tail = 10u, dedupe = Dedupe.Off)
        val out = apply(settings(80u), lines.joinToString("\n"), 0, rule, "arc")
        val shown = out.rustLines().count { !it.startsWith("… ") }
        assertTrue(shown <= Rules.MAX_TRACE_LINES + 2 * 50, "$shown lines kept")
        assertTrue(out.contains("lines omitted (expand arc)"), out)
        assertEquals(lines.size, shown + omittedSum(out))
        assertTrue(out.contains("Svc.main0(Svc.java:2)")); assertFalse(out.contains("Svc.run1900("))
    }

    @Test fun capTraceLinesDemotesTraceLinesPastTheCap() {
        val kept = BooleanArray(Rules.MAX_TRACE_LINES + 11) { it != 5 }
        Rules.capTraceLines(kept)
        assertEquals(Rules.MAX_TRACE_LINES, kept.count { it })
    }

    @Test fun omittedMarkerWithoutAnArchiveIdHasNoEmptyExpand() {
        val out = apply(settings(80u), numbered(200), 0, Rule(), "")
        assertTrue(out.contains("lines omitted"), out); assertFalse(out.contains("(expand )"), out)
    }

    @Test fun durationClockAndSSuffixDoNotMergeDistinctLines() {
        assertEquals(Rules.normalizeLineKey("took 1:2:3"), Rules.normalizeLineKey("took 9:8:7"))
        assertNotEquals(Rules.normalizeLineKey("copied 1src/a.rs"), Rules.normalizeLineKey("copied 2src/a.rs"))
        assertEquals(Rules.normalizeLineKey("wait 12ms"), Rules.normalizeLineKey("wait 3ms"))
    }

    @Test fun normalizeLineKeyKeepsDistinctErrorCodes() {
        assertNotEquals(Rules.normalizeLineKey("ERR-404 file missing"), Rules.normalizeLineKey("ERR-500 file missing"))
        assertEquals(Rules.normalizeLineKey("req id=abcd1234abcd1234 done"), Rules.normalizeLineKey("req id=feed9999aaaa1111 done"))
    }

    @Test fun normalizedDedupeFoldsNonAdjacentMatches() {
        val out = Rules.normalizedDedupe(listOf("2026-09-18T10:00:01 warn: slow", "other", "2026-09-18T10:00:02 warn: slow"))
        assertEquals(2, out.size); assertTrue(out[0].contains("(×2, also lines 3)"), out[0])
    }

    @Test fun uuidAtMultibyteSuffixIsNoMatch() = assertNull(Rules.placeholderToken("1234567é-x"))

    @Test fun normalizedDedupeSurvivesMultibyteLines() {
        val out = Rules.normalizedDedupe(listOf("日本語テスト 1234567é-x done", "日本語テスト 1234567é-x done", "café 2026-09-18T10:00:01 warn: slow", "café 2026-09-18T10:00:02 warn: slow", "plain line"))
        assertEquals(3, out.size, out.toString())
        assertTrue(out[0].contains("(×2, also lines 2)"), out[0]); assertTrue(out[1].contains("(×2, also lines 4)"), out[1])
    }

    @Test fun normalizedDedupeBeatsPlainOnThreeKLogFixture() {
        val lines = ArrayList<String>()
        for (i in 0 until 1000) { lines += "2026-09-18T12:${(i % 60).toString().padStart(2, '0')}:00Z 127.0.0.1 GET /x 200"; if (i % 2 == 0) lines += "separator" }
        for (i in 0 until 1000) { if (i % 2 == 1) lines += "separator"; lines += "warning: unused variable `x`" }
        val body = lines.joinToString("\n"); val s = settings(80u)
        val plain = apply(s, body, 0, Rule(maxLines = 10_000u, dedupe = Dedupe.Adjacent), "arc")
        val norm = apply(s, body, 0, Rule(maxLines = 10_000u, dedupe = Dedupe.Normalized), "arc")
        assertTrue(norm.length < plain.length, "plain=${plain.length} norm=${norm.length}")
    }

    @Test fun collapseColumnsFoldsPaddingKeepsIndentAndTabs() {
        assertEquals("NAME STATUS AGE", Rules.collapseColumns("NAME   STATUS      AGE"))
        assertEquals("  indented four spaces", Rules.collapseColumns("  indented    four  spaces"))
        assertEquals("trailing", Rules.collapseColumns("trailing   "))
        assertEquals("\tgo frame\ttab", Rules.collapseColumns("\tgo frame\ttab"))
        assertEquals("single already", Rules.collapseColumns("single already"))
    }

    @Test fun collapseShrinksColumnarOutputAndStandsDownForTraces() {
        val s = settings(80u)
        val rows = (0 until 30).joinToString("") { "pod-${it.toString().padStart(4, '0')}     Running     0         3d2h  10.0.0.$it\n" }
        val on = apply(s, rows, 0, Rule(), "arc").byteLen; val off = apply(s, rows, 0, Rule(collapseColumns = false), "arc").byteLen
        assertTrue(on * 2 < off * 3, "$on vs $off")
        val withTrace = "NAME    STATUS    AGE\n" + rows + "Traceback (most recent call last):\n  File \"m.py\", line 1\n"
        val out = apply(s, withTrace, 0, Rule(), "arc")
        assertTrue(out.contains("NAME    STATUS    AGE"), out)
    }

    @Test fun stackTracesSurviveTheHeadTailCutPerLanguage() {
        val traces = listOf(
            listOf("Traceback (most recent call last):", "  File \"m.py\", line 3, in main", "    run()", "ValueError: boom"),
            listOf("thread 'main' panicked at src/m.rs:2:5:", "explicit panic", "stack backtrace:", "   0: rtok::main", "   1: core::ops::function::FnOnce::call_once"),
            listOf("Error: cannot read properties of undefined", "    at main (/x/app.js:10:13)", "    at Object.<anonymous> (/x/app.js:3:1)"),
            listOf("goroutine 1 [running]:", "main.main()", "\t/src/m.go:9 +0x2c"),
            listOf("Exception in thread \"main\" java.lang.NullPointerException", "\tat Main.run(Main.java:8)", "\tat Main.main(Main.java:4)"),
        )
        for (trace in traces) {
            val lines = (0 until 15).map { "ok $it" } + trace + (15 until 45).map { "ok $it" }
            val out = apply(settings(80u), lines.joinToString("\n"), 0, Rule(), "arc1")
            for (frame in trace) assertTrue(out.contains(frame), "`$frame` must survive:\n$out")
            assertTrue(out.contains("lines omitted (expand arc1)"), out); assertFalse(out.contains("ok 30"), out)
        }
    }

    // ---- disk Settings.load twins ----

    @Test fun userRulesFileChangesOutputForMatchCmd() {
        val dir = tmpDir("rules"); val path = "$dir/rules.toml"
        writeString(path, "[echo]\nmax_lines = 2\nhead = 1\ntail = 1\ndedupe = false\n")
        val s = Settings.load(DiskRuleFiles, path, null, 80u)
        assertEquals(2, apply(s, numbered(10), 0, s.pick("echo"), "id").rustLines().size)
        removeDirAll(dir)
    }

    @Test fun userRuleOverridesBuiltinMatchCmd() {
        val dir = tmpDir("rules-ovr"); val path = "$dir/rules.toml"
        writeString(path, "[grep]\nmax_lines = 5\nhead = 2\ntail = 2\ndedupe = false\n")
        assertEquals(5u, Settings.load(DiskRuleFiles, path, null, 80u).pick("grep").maxLines)
        removeDirAll(dir)
    }

    /** Drop-ins merge after the single user file in name order; a new family is appended. */
    @Test fun dropInsMergeInNameOrderAfterTheUserFile() {
        val dir = tmpDir("rules-d"); val d = "$dir/rules.d"; createDirAll(d)
        writeString("$dir/rules.toml", "[grep]\nmax_lines = 5\n")
        writeString("$d/b.toml", "[grep]\nmax_lines = 7\n"); writeString("$d/a.toml", "[grep]\nmax_lines = 6\n[pytest]\nmax_lines = 9\n")
        writeString("$d/skip.txt", "[grep]\nmax_lines = 1\n")
        val s = Settings.load(DiskRuleFiles, "$dir/rules.toml", d, 80u)
        assertEquals(7u, s.pick("grep").maxLines, "b.toml wins in name order"); assertEquals(9u, s.pick("pytest").maxLines); assertEquals(80u, s.pick("cat").maxLines)
        removeDirAll(dir)
    }

    /** A malformed drop-in is skipped, fail open. */
    @Test fun aBrokenDropInIsSkippedFailOpen() {
        val dir = tmpDir("rules-bad"); val d = "$dir/rules.d"; createDirAll(d)
        writeString("$d/bad.toml", "[grep\nmax_lines = \n"); writeString("$d/good.toml", "[grep]\nmax_lines = 7\n")
        assertEquals(7u, Settings.load(DiskRuleFiles, "$dir/no-such-rules.toml", d, 80u).pick("grep").maxLines)
        assertEquals(40u, Settings.load(DiskRuleFiles, "$dir/no-such-rules.toml", "$dir/no-such-d", 80u).pick("grep").maxLines)
        removeDirAll(dir)
    }

    // ---- Vfs twins (T56.3) ----

    @Test fun userRulesFileChangesOutputForMatchCmdFromVfs() {
        val v = VfsRuleFiles().apply { write("rules.toml", "[echo]\nmax_lines = 2\nhead = 1\ntail = 1\ndedupe = false\n") }
        val s = loadVfs(v, "rules.toml", null)
        assertEquals(2, apply(s, numbered(10), 0, s.pick("echo"), "id").rustLines().size)
    }

    @Test fun userRuleOverridesBuiltinMatchCmdFromVfs() {
        val v = VfsRuleFiles().apply { write("rules.toml", "[grep]\nmax_lines = 5\nhead = 2\ntail = 2\ndedupe = false\n") }
        assertEquals(5u, loadVfs(v, "rules.toml", null).pick("grep").maxLines)
    }

    @Test fun dropInsMergeInNameOrderAfterTheUserFileFromVfs() {
        val v = VfsRuleFiles().apply {
            write("rules.toml", "[grep]\nmax_lines = 5\n"); write("rules.d/b.toml", "[grep]\nmax_lines = 7\n")
            write("rules.d/a.toml", "[grep]\nmax_lines = 6\n[pytest]\nmax_lines = 9\n"); write("rules.d/skip.txt", "[grep]\nmax_lines = 1\n")
        }
        val s = loadVfs(v, "rules.toml", "rules.d")
        assertEquals(7u, s.pick("grep").maxLines); assertEquals(9u, s.pick("pytest").maxLines); assertEquals(80u, s.pick("cat").maxLines)
    }

    @Test fun aBrokenDropInIsSkippedFailOpenFromVfs() {
        val v = VfsRuleFiles().apply { write("rules.d/bad.toml", "[grep\nmax_lines = \n"); write("rules.d/good.toml", "[grep]\nmax_lines = 7\n") }
        assertEquals(7u, loadVfs(v, "no-such-rules.toml", "rules.d").pick("grep").maxLines)
        assertEquals(40u, loadVfs(v, "no-such-rules.toml", "no-such-d").pick("grep").maxLines)
    }

    @Test fun emptyRulesFileFromVfsKeepsBuiltins() {
        val s = loadVfs(VfsRuleFiles().apply { write("rules.toml", "") }, "rules.toml", null)
        assertEquals(40u, s.pick("grep").maxLines); assertEquals(80u, s.pick("cat").maxLines); assertEquals(40u, s.pick("echo").maxLines, "unknown cmds use Rule()")
    }

    @Test fun vfsRulesUnderSpacedProfilePath() {
        val p = "Users/Ivan Tuhai/.config/rtok/rules.toml"
        val s = loadVfs(VfsRuleFiles().apply { write(p, "[rg]\nmax_lines = 3\nhead = 1\ntail = 1\ndedupe = false\n") }, p, null)
        assertEquals(3u, s.pick("rg").maxLines)
        assertEquals(3, apply(s, numbered(10, "L"), 0, s.pick("rg"), "id").rustLines().size)
    }

    @Test fun vfsDropinLaterFileOverridesEarlierAndUser() {
        val v = VfsRuleFiles().apply { write("rules.toml", "[grep]\nmax_lines = 1\n"); write("rules.d/01-a.toml", "[grep]\nmax_lines = 2\n"); write("rules.d/02-b.toml", "[grep]\nmax_lines = 9\n") }
        assertEquals(9u, loadVfs(v, "rules.toml", "rules.d").pick("grep").maxLines)
    }

    @Test fun vfsRulesUnderUnicodePathString() {
        val v = VfsRuleFiles(); val path = "профіль/настройки/rules.toml"
        v.write(path, "[cat]\nmax_lines = 4\nhead = 2\ntail = 2\ndedupe = false\n")
        assertEquals(4u, loadVfs(v, path, null).pick("cat").maxLines)
        val dropDir = "профіль/настройки/rules.d"; v.write("$dropDir/zz.toml", "[cat]\nmax_lines = 11\n")
        assertEquals(11u, loadVfs(v, path, dropDir).pick("cat").maxLines)
    }

    /** `max_lines = 0` parses; apply clamps the budget to 1, so multi-line output becomes one trailer. */
    @Test fun vfsMaxLinesZeroClampsToOneOnApply() {
        val s = loadVfs(VfsRuleFiles().apply { write("rules.toml", "[echo]\nmax_lines = 0\nhead = 1\ntail = 1\ndedupe = false\n") }, "rules.toml", null)
        assertEquals(0u, s.pick("echo").maxLines)
        val out = apply(s, "a\nb\nc\n", 0, s.pick("echo"), "id")
        assertEquals(1, out.rustLines().size, out); assertTrue(out.contains("3 lines omitted"), out)
    }

    @Test fun vfsManyDropinsMergeNameOrderSkippingNonToml() {
        val v = VfsRuleFiles().apply {
            write("rules.toml", "[grep]\nmax_lines = 1\n"); write("rules.d/10.toml", "[grep]\nmax_lines = 3\n[jq]\nmax_lines = 2\n")
            write("rules.d/20.toml", "[grep]\nmax_lines = 5\n"); write("rules.d/30.toml", "[grep]\nmax_lines = 8\n[fd]\nmax_lines = 6\n")
            write("rules.d/note.md", "[grep]\nmax_lines = 99\n"); write("rules.d/00.bak", "[grep]\nmax_lines = 99\n")
        }
        val s = loadVfs(v, "rules.toml", "rules.d")
        assertEquals(8u, s.pick("grep").maxLines); assertEquals(2u, s.pick("jq").maxLines); assertEquals(6u, s.pick("fd").maxLines)
    }

    @Test fun strictRejectsSyntaxTypesAndUnknownFields() {
        assertTrue(Rules.parseStrict("[grep]\nmax_lines = 5\n").isSuccess); assertTrue(Rules.parseStrict("").isSuccess)
        val bad = Rules.parseStrict("[grep\nmax_lines = \n").exceptionOrNull()!!.message!!
        assertTrue(bad.contains("line", ignoreCase = true), bad) // ktoml says `Line N`, toml_edit `line N`
        for ((body, want) in listOf("[grep]\nmax_lines = \"many\"\n" to "max_lines", "[grep]\nmax_lines = -1\n" to "max_lines", "[grep]\ndrop = \"x\"\n" to "drop",
            "[grep]\ndrop = [1]\n" to "drop", "[grep]\ndedupe = \"yes\"\n" to "dedupe", "[grep]\ngroup = \"nope\"\n" to "group", "[grep]\nnope = 1\n" to "nope", "title = \"x\"\n" to "title")) {
            val err = Rules.parseStrict(body).exceptionOrNull()?.message ?: fail("$body parsed")
            assertTrue(err.contains(want), "$body → $err")
        }
        assertTrue(Rules.parseStrict("[grep]\ndedupe = \"normalized\"\n").isSuccess)
    }

    @Test fun issuesInNamesEveryMalformedFile() {
        val dir = tmpDir("rules-iss"); val d = "$dir/rules.d"; createDirAll(d)
        writeString("$d/bad.toml", "[grep]\nmax_lines = \"many\"\n"); writeString("$d/good.toml", "[echo]\nmax_lines = 2\n")
        val errs = Rules.issuesIn(DiskRuleFiles, "$dir/no-such-rules.toml", d)
        assertEquals(1, errs.size, errs.toString()); assertTrue(errs[0].contains("bad.toml") && errs[0].contains("max_lines"), errs.toString())
        assertTrue(Rules.issuesIn(DiskRuleFiles, "$dir/nope.toml", "$dir/nope-d").isEmpty())
        removeDirAll(dir)
    }

    @Test fun configValidateSeesTheRulesCheckOnceInstalled() {
        val dir = tmpDir("rules-validate"); writeString("$dir/rules.toml", "[grep]\nmax_lines = \"many\"\n")
        Rules.installValidateCheck()
        val errs = rtok.config.Validate.rulesIssues("$dir/rules.toml", "$dir/rules.d")
        assertEquals(1, errs.size, errs.toString()); assertTrue(errs[0].contains("max_lines"), errs.toString())
        removeDirAll(dir)
    }

    @Test fun issuesInNamesEveryMalformedFileFromVfs() {
        val v = VfsRuleFiles().apply { write("rules.d/bad.toml", "[grep]\nmax_lines = \"many\"\n"); write("rules.d/good.toml", "[echo]\nmax_lines = 2\n") }
        val errs = Rules.issuesIn(v, "no-such-rules.toml", "rules.d")
        assertEquals(1, errs.size, errs.toString()); assertTrue(errs[0].contains("bad.toml") && errs[0].contains("max_lines"), errs.toString())
        assertTrue(Rules.issuesIn(v, "nope.toml", "nope-d").isEmpty())
    }

    @Test fun issuesInFromVfsNamesSpacedPath() {
        val v = VfsRuleFiles().apply { write("Users/Ivan Tuhai/.config/rtok/rules.d/bad.toml", "[grep]\nmax_lines = \"many\"\n") }
        val errs = Rules.issuesIn(v, "missing.toml", "Users/Ivan Tuhai/.config/rtok/rules.d")
        assertEquals(1, errs.size, errs.toString()); assertTrue(errs[0].contains("Ivan Tuhai") && errs[0].contains("max_lines"), errs.toString())
    }

    @Test fun parseStrictReadsGroupDirAndDiag() {
        assertEquals(Group.Dir, Rules.parseStrict("[find]\ngroup = \"dir\"\n").getOrThrow()[0].group)
        assertEquals(Group.Diag, Rules.parseStrict("[tsc]\ngroup = \"diag\"\n").getOrThrow()[0].group)
        assertEquals(Group.Off, Rules.parseStrict("[grep]\nmax_lines = 5\n").getOrThrow()[0].group)
    }

    @Test fun parseStrictReadsJsonItemsAndString() {
        val r = Rules.parseStrict("[gh]\njson_items = 5\njson_string = 40\n").getOrThrow()[0]
        assertEquals(5u, r.jsonItems); assertEquals(40u, r.jsonString)
        assertEquals(20u, Rules.parseStrict("[gh]\nmax_lines = 5\n").getOrThrow()[0].jsonItems)
    }

    @Test fun jsonCompactionDropsEmptiesKeepsKeysAndCapsArrays() {
        val s = settings(80u)
        val items = (0 until 25).joinToString(",") { "{\"id\":$it,\"name\":\"n$it\",\"empty\":\"\",\"nil\":null,\"tags\":[],\"meta\":{}}" }
        val out = apply(s, "{\"kind\":\"List\",\"unused\":null,\"items\":[$items]}", 0, Rule(), "arc")
        assertTrue(out.contains("kind: \"List\"") && out.contains("items:"), out)
        for (gone in listOf("unused", "empty", "nil", "n24")) assertFalse(out.contains(gone), out)
        assertTrue(out.contains("… +5 more") && out.contains("n0"), out)
        val cut = apply(s, "{\"blob\":\"${"x".repeat(250)}\"}", 0, Rule(), "arc")
        assertTrue(cut.contains("(250)") && cut.contains('…'), cut)
    }

    @Test fun jsonUniformArrayPrintsOneTableWithTheSameRows() {
        val body = "[" + (0 until 30).joinToString(",") { "{\"id\":$it,\"name\":\"user$it\",\"role\":\"dev\",\"active\":true}" } + "]"
        val rule = Rule(); val out = apply(settings(80u), body, 0, rule, "arc")
        assertTrue(out.contains("{active,id,name,role}:"), out); assertTrue(out.contains("true,0,user0,dev"), out)
        val shown = rule.jsonItems.toInt()
        assertTrue(out.contains(",user${shown - 1},"), out); assertFalse(out.contains(",user$shown,"), out)
        assertTrue(out.contains("… +${30 - shown} more"), out); assertFalse(out.contains("name: "), out)
    }

    @Test fun jsonMixedArrayKeepsThePerElementLines() {
        val out = apply(settings(80u), "[{\"id\":1,\"a\":\"x\",\"b\":2},3,\"s\",{\"id\":2,\"a\":\"y\",\"b\":4}]", 0, Rule(), "arc")
        assertTrue(out.contains("a: \"x\""), out); assertFalse(out.contains("]{"), out)
    }

    @Test fun jsonUnparseableBodyIsUntouched() {
        val body = "not json\n{\n  broken\n"
        assertEquals(body.trimEnd(), apply(settings(80u), body, 0, Rule(maxLines = 80u, collapseColumns = false, dedupe = Dedupe.Off), "arc"))
    }

    @Test fun groupDirBeatsTheSameRuleWithoutGroup() {
        val body = (0 until 30).flatMap { listOf("src/a$it.rs", "tests/t$it.rs") }.joinToString("\n"); val s = settings(80u)
        val off = apply(s, body, 0, uncut(Group.Off), "id"); val on = apply(s, body, 0, uncut(Group.Dir), "id")
        assertTrue(on.length < off.length, on)
        assertTrue(on.contains("src/ (30 files): a0.rs, a1.rs, a2.rs …"), on); assertTrue(on.contains("tests/ (30 files): t0.rs, t1.rs, t2.rs …"), on)
        assertFalse(on.contains("lines omitted"), on)
    }

    /** T685: a short `find` keeps every `dir/name`; only a longer list folds by directory. */
    @Test fun groupDirKeepsFullPathsUpToFiftyHits() {
        fun paths(n: Int) = (0 until n).map { "./src/deep/dir/a$it.rs" }
        assertEquals(paths(50), Rules.groupDir(paths(50)))
        val mixed = paths(49) + "find: ./x: Permission denied" + "find: ./y: Permission denied"
        assertEquals(mixed, Rules.groupDir(mixed))
        val folded = Rules.groupDir(paths(51))
        assertEquals(1, folded.size, folded.toString()); assertTrue(folded[0].contains("(51 files)"), folded.toString())
    }

    /** `find .` over a big tree: grouping must stay linear. */
    @Test fun groupDirScalesToAFindOverALargeTree() {
        val body = (0 until 200_000).joinToString("\n") { "./d${it % 20_000}/sub/f$it.rs" }
        val t = TimeSource.Monotonic.markNow()
        val out = apply(settings(80u), body, 0, uncut(Group.Dir), "id")
        val ms = t.elapsedNow().inWholeMilliseconds
        assertTrue(ms < 5000, "$ms ms") // debug-build budget, as in Rust
        assertTrue(out.contains("d0/sub/ (10 files): f0.rs, f20000.rs, f40000.rs …"), out)
    }

    @Test fun groupDiagBeatsTheSameRuleWithoutGroup() {
        val lines = (0 until 20).flatMap { listOf("src/f$it.ts($it,1): error TS2322: Type 'string' is not assignable to type 'number'.", "error[E0308]: mismatched types", "  $it:1  error  Unexpected var  no-var") }
        val body = lines.joinToString("\n"); val s = settings(80u)
        val off = apply(s, body, 0, uncut(Group.Off), "id"); val on = apply(s, body, 0, uncut(Group.Diag), "id")
        assertTrue(on.length < off.length, on)
        assertTrue(on.contains("TS2322 ×20: Type 'string' is not assignable to type 'number'. (src/f0.ts:0, src/f1.ts:1, src/f2.ts:2, …)"), on)
        assertTrue(on.contains("E0308 ×20: mismatched types"), on); assertTrue(on.contains("no-var ×20: Unexpected var (0:1, 1:1, 2:1, …)"), on)
    }

    @Test fun groupDiagReadsRuffMypyAndGolangciLines() {
        val lines = (0 until 20).flatMap { listOf("src/f$it.py:$it:8: F401 [*] `os` imported but unused", "src/g$it.py:$it: error: Argument 1 has incompatible type  [arg-type]",
            "pkg/h$it.go:$it:2: Error return value is not checked (errcheck)", "app/m$it.rb:$it:4: C: [Correctable] Style/StringLiterals: Prefer single quotes.") }
        val body = lines.joinToString("\n"); val s = settings(80u)
        val off = apply(s, body, 0, uncut(Group.Off), "id"); val on = apply(s, body, 0, uncut(Group.Diag), "id")
        assertTrue(on.length < off.length, on)
        for (w in listOf("F401 ×20: `os` imported but unused (src/f0.py:0:8", "arg-type ×20: Argument 1 has incompatible type (src/g0.py:0",
            "errcheck ×20: Error return value is not checked (pkg/h0.go:0:2", "Style/StringLiterals ×20: Prefer single quotes. (app/m0.rb:0:4")) assertTrue(on.contains(w), on)
        assertNull(Rules.parseLocated("note: see above")); assertNull(Rules.parseLocated("src/a.py:3: just text"))
    }

    /** `apply` groups once, so grouping must be a fixed point on its own output. */
    @Test fun groupingIsAFixedPointOnItsOwnOutput() {
        val once = Rules.groupDir((0 until 30).flatMap { listOf("./src/a$it.rs", "tests/t$it.rs", "x.md") })
        assertEquals(once, Rules.groupDir(once))
        val diags = (0 until 8).flatMap { listOf("src/f$it.ts($it,1): error TS2322: Type 'a' is not 'b'.", "error[E0308]: mismatched types", "  $it:1  error  Unexpected var  no-var",
            "FAILED t$it.py::t - ValueError: bad $it", "E   KeyError: 'k'", "plain line $it") }
        val d = Rules.groupDiag(diags)
        assertEquals(d, Rules.groupDiag(d))
    }

    @Test fun groupDiagLineStaysPinnedThroughTheCut() {
        val lines = (0 until 15).map { "ok $it" } + "src/app.ts(10,5): error TS2322: Type 'string' is not assignable to type 'number'." + (15 until 45).map { "ok $it" }
        val out = apply(settings(80u), lines.joinToString("\n"), 0, uncut(Group.Diag), "arc")
        assertTrue(out.contains("TS2322 ×1:"), out); assertTrue(out.contains("lines omitted (expand arc)"), out)
    }

    @Test fun singleLineJsonWithManyItemsDoesNotPanic() {
        val out = apply(settings(80u), "[" + (0 until 30).joinToString(", ") + "]", 0, Rule(maxLines = 5u, head = 2u, tail = 2u), "id")
        assertTrue(out.contains("… +10 more"), out)
    }

    /** T683: 300 logfmt lines (one ERROR, one WARN) cut to head/tail. */
    private fun logfmtBody() = (0 until 300).joinToString("\n") { i ->
        val level = when (i) { 150 -> "error"; 200 -> "warn"; else -> "info" }
        "ts=2026-10-16T10:${(i / 60).toString().padStart(2, '0')}:${(i % 60).toString().padStart(2, '0')}Z level=$level svc=api msg=\"request done\" method=GET status=200 dur_ms=${10 + i % 7} req=r$i"
    }

    @Test fun collapsedLogfmtRunListsConstantsOnceAndVaryingKeys() {
        val body = logfmtBody(); val out = apply(settings(80u), body, 0, Rule(), "deadbeef")
        val first = out.rustLines()[0]
        for (w in listOf("svc=api", "status=200", "method=GET", "msg=\"request done\"")) assertTrue(first.startsWith("[every record:") && first.contains(w), first)
        assertEquals(3, Regex("svc=api").findAll(out).count(), "header + error + warn: $out")
        val marker = out.rustLines().firstOrNull { it.contains("lines omitted") } ?: fail(out)
        assertTrue(marker.contains("(expand deadbeef)"), marker)
        for (w in listOf("varying:", "ts=", "dur_ms=", "req=r10..r149")) assertTrue(marker.contains(w), "$w missing: $marker")
        val bl = body.rustLines(); val ol = out.rustLines()
        assertTrue(bl[150] in ol && bl[200] in ol, out)
        for (a in listOf(0, 299)) assertTrue(out.contains("req=r$a"), out)
        val est = { s: String -> rtok.estimate(s, rtok.plugin.sdk.Class.Code, rtok.config.Estimator()) }
        assertTrue(est(out) <= est(body), "${est(out)} > ${est(body)}")
    }

    @Test fun shortOrUnstructuredOmittedRunsGetNoFieldSummary() {
        val out = apply(settings(80u), numbered(200), 0, Rule(), "id")
        assertTrue(out.contains("… 180 lines omitted (expand id)"), out); assertTrue(!out.contains("every record") && !out.contains("varying:"), out)
    }
}
