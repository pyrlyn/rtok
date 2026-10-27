// Port of the src/plugins/guard/mod.rs tests (35). The grep_symbol tests wait for the graph plugin.
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package rtok.plugins.guard

import kotlinx.cinterop.convert
import kotlinx.serialization.json.*
import rtok.config.Config
import rtok.plugin.Runtime
import rtok.plugin.sdk.*
import rtok.plugins.cmd.shQuote
import rtok.runGuard
import rtok.store.getReadCache
import rtok.store.listMeasurements
import rtok.store.measurementCount
import rtok.support.withRuntime
import rtok.sys.removeFile
import kotlin.test.*

private fun obj(vararg kv: Pair<String, Any?>) =
    buildJsonObject {
        for ((k, v) in kv) {
            when (v) {
                null -> put(k, JsonNull)
                is String -> put(k, v)
                is Number -> put(k, v)
                is Boolean -> put(k, v)
                is JsonElement -> put(k, v)
            }
        }
    }

private fun cmd(c: String) = obj("command" to c)

private fun k(
    c: String,
    cwd: String? = null,
) = cacheKey("Bash", cmd(c), null, cwd)

private fun denies(d: PreToolDecision?) = d is PreToolDecision.Deny

private fun post(
    cx: Ctx,
    tool: String,
    input: JsonElement,
    resp: JsonElement = obj("content" to "body"),
) = assertNull(GuardPlugin.postTool(PostToolUse(tool, input, resp), cx))

private fun pre(
    cx: Ctx,
    tool: String,
    input: JsonElement,
) = GuardPlugin.preTool(PreToolUse(tool, input), cx)

private fun readAt(
    r: Runtime,
    input: JsonElement,
) = denies(pre(Ctx(r), "Read", input))

private fun done(
    r: Runtime,
    tool: String,
    input: JsonElement,
) = post(Ctx(r), tool, input)

private fun noReadPlugin(c: Config) = c.copy(plugins = c.plugins.copy(read = c.plugins.read.copy(enabled = false)))

private fun denyGrepGlob(c: Config) = c.copy(plugins = c.plugins.copy(guard = c.plugins.guard.copy(denyGrepGlob = true)))

/** T445: the key is only a duplicate when the shell sits where it sat before. */
private class Shell(
    val cx: Runtime,
) {
    fun at(cwd: String) {
        cx.cwd = cwd
    }

    fun ran(c: String) = post(Ctx(cx), "Bash", cmd(c), obj("stdout" to "listing"))

    fun denied(c: String) = denies(pre(Ctx(cx), "Bash", cmd(c)))
}

private fun shell(
    tag: String,
    body: (Shell) -> Unit,
) = withRuntime(tag) { body(Shell(it)) }

class GuardTest {
    @Test fun bashKeyKeepsEveryAbsoluteCdHopAndTheCwd() {
        assertEquals("bash\tls", k("ls"))
        assertEquals("bash\tcd /a && ls", k("cd /a && ls"))
        assertEquals("bash\tcd /b && ls", k("cd /b && ls"))
        assertEquals("bash\tcd /a && cd /b && ls", k("cd /a && cd /b && ls"))
        assertNotEquals(k("cd /a && cd /a && ls"), k("cd /a && ls"))
        assertEquals("bash\tcd '/a && b' && ls", k("cd '/a && b' && ls"))
        for (abs in listOf("~", "~/x", "~u/x", "'/a b'", "\"/a b\"", "C:\\x", "d:/x")) assertNotNull(k("cd $abs && ls"), abs)
        assertEquals(k("cd /a && ls"), k("cd   /a   &&   ls"))
        assertEquals(k("cd /a && ls"), k("cd /a && rtok run -- 'ls'"))
        assertEquals(k("cd /a && cd /b && ls"), k("cd /a && cd /b && rtok run -- 'ls'"))
        assertEquals(k("ls"), k("rtok run --agent sub_1 -- 'ls'"))
        assertEquals(k("cd /a && ls"), k("cd /a && rtok run --agent sub_1 -- 'ls'"))
        val guard = runGuard()
        assertEquals(k("ls"), k("${guard}rtok run -- 'ls'"))
        assertEquals(k("cd /a && ls"), k("cd /a && ${guard}rtok run --agent sub_1 -- 'ls'"))
        assertNull(k("rtok run --agent ../x -- 'ls'"))
        assertNull(k("rtok run --agent -- 'ls'"))

        fun at(
            cwd: String?,
            agent: String?,
        ) = cacheKey("Bash", cmd("ls"), agent, cwd)
        assertEquals("bash\t/r\tls", at("/r", null))
        assertNotEquals(at("/r", null), at("/s", null))
        assertNotEquals(at("/r", null), at(null, null))
        assertEquals(at(null, null), at("", null))
        assertEquals("bash\t/r\tls\tag", at("/r", "ag"))
    }

    @Test fun bashBehindARelativeCdHopHasNoKey() {
        for (c in listOf("cd a && ls", "cd - && ls", "cd .. && ls", "cd \$HOME/x && ls", "cd 'a b' && ls", "cd /r && cd sub && ls", "cd sub && cd /r && ls", "cd a && rtok run -- 'ls'")) {
            for (cwd in listOf(null, "/s")) assertNull(k(c, cwd), c)
        }
    }

    @Test fun bashKeyKeepsWhitespaceInsideArguments() {
        fun kr(c: String) = k(c, "/r")
        for ((a, b) in listOf(
            "cat 'a  b'" to "cat 'a b'",
            "cat \"a  b\"" to "cat \"a b\"",
            "cat a\\ \\ b" to "cat a\\ b",
            "cat 'a\tb'" to "cat 'a b'",
            "cat 'a\nb'" to "cat 'a b'",
            "cat \"a\nb\"" to "cat \"a b\"",
            "rtok run -- 'cat '\"'\"'a  b'\"'\"''" to "cat 'a b'",
        )) {
            assertNotNull(kr(a), a)
            assertNotEquals(kr(a), kr(b), "$a vs $b")
        }
        assertEquals(kr("cat 'a  b'"), kr("rtok run -- 'cat '\"'\"'a  b'\"'\"''"))
        assertEquals(kr("cat 'a\nb'"), kr("cat 'a\nb'"))
        assertEquals(kr("cat 'a b' x"), kr("cat   'a b'\t x"))
        assertEquals(kr("cat x"), kr("rtok run -- 'cat   x'"))
        assertNotEquals(kr("ls\nrm x"), kr("ls rm x"))
        assertEquals(kr("ls ; rm x"), kr("ls\nrm x"))
        assertNull(kr("cat 'a  b"))
    }

    @Test fun quotedWhitespaceVariantIsNotDeniedAsADuplicate() =
        shell("g-qws") { sh ->
            sh.ran("cat 'a  b'")
            assertTrue(sh.denied("cat 'a  b'"))
            assertTrue(sh.denied("cat   'a  b'"))
            assertFalse(sh.denied("cat 'a b'"))
        }

    @Test fun redirectWithoutSpacesIsNotReadOnly() {
        for (w in listOf("echo x>file", "cat a>a", "ls>>out", "echo x > file", "grep foo bar 2>err")) {
            assertFalse(readOnly(w), w)
            assertNull(k(w))
        }
        for (r in listOf("ls", "cat file", "grep foo bar", "grep 'a>b' file")) assertTrue(readOnly(r), r)
    }

    @Test fun relativeCdRepeatIsNotDenied() =
        shell("g-relcd") { sh ->
            sh.at("/s/a")
            sh.ran("cd a && ls")
            assertFalse(sh.denied("cd a && ls"), "would run in /s/a/a")
            sh.ran("cd a && ls")
            assertFalse(sh.denied("cd a && ls"))
            assertFalse(sh.denied("ls"))
        }

    @Test fun absoluteCdRepeatIsDenied() =
        shell("g-abscd") { sh ->
            sh.at("/s")
            sh.ran("ls")
            sh.at("/r/sub")
            sh.ran("cd /r && cd /r/sub && ls -la")
            assertFalse(sh.denied("ls"), "the shell moved")
            assertTrue(sh.denied("cd /r && cd /r/sub && ls -la"))
            assertFalse(sh.denied("cd /r/sub && ls -la"), "one hop fewer is another key")
        }

    @Test fun cdHopDropsEarlierBashAnswers() {
        for (hop in listOf("cd /r && ls", "cd a && ls")) {
            shell("g-hop") { sh ->
                sh.at("/repo")
                sh.ran("ls")
                sh.ran("git status")
                sh.ran(hop)
                assertFalse(sh.denied("ls"), hop)
                assertFalse(sh.denied("git status"))
                sh.ran("ls")
                sh.ran("git status")
                assertTrue(sh.denied("ls"))
                assertTrue(sh.denied("git status"))
            }
        }
    }

    @Test fun bashRepeatFromAnotherCwdIsANewKey() =
        shell("g-cwd") { sh ->
            sh.at("/repo/a")
            sh.ran("ls")
            sh.at("/repo/b")
            assertFalse(sh.denied("ls"))
            sh.at("/repo/a")
            assertTrue(sh.denied("ls"))
        }

    @Test fun mutationBetweenCommandsAllowsTheRepeat() =
        withRuntime("g-mut") { r ->
            val cx = Ctx(r)
            val resp = obj("stdout" to "out")

            fun denied(i: JsonElement) = denies(pre(cx, "Bash", i))
            val test = cmd("cargo test")
            post(cx, "Bash", test, resp)
            assertFalse(denied(test))
            val cat = cmd("cat a.txt")
            post(cx, "Bash", cat, resp)
            assertTrue(denied(cat))
            post(cx, "Bash", test, resp)
            assertFalse(denied(cat), "a mutating Bash clears bash keys")
            post(cx, "Bash", cat, resp)
            assertTrue(denied(cat))
            post(cx, "Edit", obj("file_path" to "/proj/b.rs"), resp)
            assertFalse(denied(cat), "an Edit clears bash keys")
            post(cx, "Bash", cmd("git status"), resp)
            assertTrue(denied(cmd("git status")))
            assertFalse(denied(cmd("git add .")))
        }

    @Test fun twoIdenticalReadsSecondDeniesNamingArchive() =
        withRuntime("g-two") { r ->
            val cx = Ctx(r)
            val path = obj("file_path" to "/Users/dev/proj/src/main.rs")
            val other = obj("file_path" to "/Users/dev/proj/src/lib.rs")
            assertNull(pre(cx, "Read", path))
            post(cx, "Read", path, obj("content" to "fn main() {}"))
            val d = pre(cx, "Read", path)
            assertIs<PreToolDecision.Deny>(d)
            assertTrue(d.reason.contains("rtok expand "), d.reason)
            assertNull(pre(cx, "Read", other))
            assertTrue(r.store.measurementCount("guard") >= 1)
            assertTrue(r.store.listMeasurements("guard").any { it.beforeBytes > 0 })
        }

    @Test fun denyMeasurementBeforeBytesAndEstBeforeMatchTheAvoidedPayload() =
        withRuntime("g-meas") { r ->
            val cx = Ctx(r)
            val path = obj("file_path" to "/Users/dev/proj/src/inventory.rs")
            assertNull(pre(cx, "Read", path))
            val body = "x".repeat(4097)
            post(cx, "Read", path, obj("content" to body))
            assertTrue(denies(pre(cx, "Read", path)))
            val row = r.store.listMeasurements("guard").first { it.kind == "guard" && it.beforeBytes > 0 }
            assertEquals(body.length.toLong(), row.beforeBytes)
            assertEquals(maxOf(body.length / 4, 1), row.estBefore)
            assertEquals(0, row.afterBytes)
            assertEquals(0, row.estAfter)
        }

    @Test fun postToolOverCapSkipsArchiveAndTheRepeatIsAllowed() =
        withRuntime("g-cap") { r ->
            val cx = Ctx(r)
            val path = obj("file_path" to "/Users/dev/proj/src/big.rs")
            assertNull(pre(cx, "Read", path))
            post(cx, "Read", path, obj("content" to "fn main() {}"))
            assertNotNull(pre(cx, "Read", path), "small body cached")
            post(cx, "Read", path, obj("content" to "x".repeat(ARCHIVE_CAP_BYTES + 1)))
            assertNull(pre(cx, "Read", path))
            assertEquals(1, r.store.measurementCount("guard"))
        }

    @Test fun editClearsGuardReadSoTheNextReadIsAllowed() =
        withRuntime("g-edit") { r ->
            val cx = Ctx(r)
            val path = obj("file_path" to "/proj/src/main.rs")
            post(cx, "Read", path, obj("content" to "fn main() {}"))
            post(cx, "Edit", path, JsonObject(emptyMap()))
            assertNull(pre(cx, "Read", path))
        }

    @Test fun relativeReadIsKeyedByItsCwd() =
        withRuntime("g-relread") { r ->
            val rel = obj("file_path" to "file.txt")
            r.cwd = "/A"
            done(r, "Read", rel)
            assertTrue(readAt(r, rel))
            r.cwd = "/B"
            assertFalse(readAt(r, rel), "another directory's file.txt")
            r.cwd = "/A"
            assertTrue(readAt(r, rel))
            assertTrue(readAt(r, obj("file_path" to "./file.txt")))
            assertTrue(readAt(r, obj("file_path" to "/A/file.txt")))
            r.cwd = null
            assertFalse(readAt(r, rel))
            assertNull(cacheKey("Read", rel, null, null))
            assertNull(cacheKey("Read", obj("file_path" to "/A/x/../file.txt"), null, "/A"))
        }

    @Test fun editThroughAnAliasInvalidatesEverySpelling() =
        withRuntime("g-alias") { r ->
            r.cwd = "/A"
            val rel = obj("file_path" to "./file.txt")
            val slice = obj("file_path" to "file.txt", "offset" to 10, "limit" to 5)
            val other = obj("file_path" to "other.txt")
            for (i in listOf(rel, slice, other)) {
                done(r, "Read", i)
                assertTrue(readAt(r, i))
            }
            done(r, "Edit", obj("file_path" to "/A/file.txt"))
            assertFalse(readAt(r, rel))
            assertFalse(readAt(r, slice))
            assertTrue(readAt(r, other), "unrelated file keeps its cache")
            r.cwd = null
            done(r, "Edit", obj("file_path" to "other.txt"))
            r.cwd = "/A"
            assertFalse(readAt(r, other))
        }

    @Test fun missingArchiveFailsOpen() =
        withRuntime("g-gone") { r ->
            val cx = Ctx(r)
            val path = obj("file_path" to "/proj/gone.rs")
            post(cx, "Read", path, obj("content" to "old"))
            val key = cacheKey("Read", path, null, null)!!
            val id = r.store.getReadCache(r.session, key)!!.first!!
            removeFile("${r.config.core.archiveDir}/$id")
            assertNull(pre(cx, "Read", path))
        }

    @Test fun aSubAgentWindowScopesTheReadCache() =
        withRuntime("g-sub") { r ->
            val path = obj("file_path" to "/proj/p.rs")
            assertNull(pre(Ctx(r), "Read", path))
            post(Ctx(r), "Read", path)
            val sub = Ctx(r, "a1")
            val rows = r.store.measurementCount("guard")
            assertNull(pre(sub, "Read", path), "the sub-agent's first Read of P is new to its window")
            assertEquals(rows, r.store.measurementCount("guard"))
            post(sub, "Read", path)
            assertTrue((pre(sub, "Read", path) as PreToolDecision.Deny).reason.contains("expand"))
            assertTrue((pre(Ctx(r), "Read", path) as PreToolDecision.Deny).reason.contains("expand"))
        }

    @Test fun nativeGrepGlobDenyIsOptInAndPointsAtMcp() {
        val empty = JsonObject(emptyMap())
        withRuntime("g-gg-off") { r ->
            assertNull(pre(Ctx(r), "Grep", empty))
            assertNull(pre(Ctx(r), "Glob", empty))
        }
        withRuntime("g-gg-on", ::denyGrepGlob) { r ->
            assertTrue((pre(Ctx(r), "Grep", empty) as PreToolDecision.Deny).reason.contains("search"))
            assertTrue((pre(Ctx(r), "Glob", empty) as PreToolDecision.Deny).reason.contains("tree"))
            assertNull(pre(Ctx(r), "Read", empty))
            assertTrue(r.store.listMeasurements("guard").any { it.kind == "native_deny" && it.beforeBytes == 0L && it.afterBytes == 0L })
        }
        withRuntime("g-gg-noread", { noReadPlugin(denyGrepGlob(it)) }) { r ->
            assertNull(pre(Ctx(r), "Grep", empty))
            assertNull(pre(Ctx(r), "Glob", empty))
        }
    }

    @Test fun copilotPathKeyDedupsLikeFilePath() =
        withRuntime("g-copilot") { r ->
            for (key in listOf("path", "file_path")) {
                val input = obj(key to "/Users/dev/proj/src/main.rs")
                post(Ctx(r), "Read", input, obj("content" to "fn main() {}"))
                assertTrue((pre(Ctx(r), "Read", input) as PreToolDecision.Deny).reason.contains("rtok expand "), key)
            }
        }

    @Test fun bashMutationAllowsTheNextRead() =
        withRuntime("g-bashread") { r ->
            val path = obj("file_path" to "/proj/src/main.rs")
            post(Ctx(r), "Read", path)
            assertTrue(denies(pre(Ctx(r), "Read", path)))
            post(Ctx(r), "Bash", cmd("cargo fmt"))
            assertNull(pre(Ctx(r), "Read", path))
        }

    @Test fun editWithReadPluginOffAllowsTheNextRead() =
        withRuntime("g-edit-noread", ::noReadPlugin) { r ->
            val path = obj("file_path" to "/proj/src/main.rs")
            for (n in listOf("Read", "Edit")) post(Ctx(r), n, path)
            assertNull(pre(Ctx(r), "Read", path))
        }

    @Test fun denyDoesNotReadTheArchiveBody() =
        withRuntime("g-meta") { r ->
            val path = obj("file_path" to "/proj/src/hot.rs")
            post(Ctx(r), "Read", path, obj("content" to "fn main() {}"))
            val id = r.store.getReadCache(r.session, cacheKey("Read", path, null, null)!!)!!.first!!
            val file = "${r.config.core.archiveDir}/$id"
            platform.posix.chmod(file, 0u)
            try {
                assertTrue(denies(pre(Ctx(r), "Read", path)), "only metadata was consulted")
            } finally {
                platform.posix.chmod(file, "644".toUInt(8).convert())
            }
        }

    @Test fun compoundCommandsWithAWriterAreNotKeyed() {
        for (w in listOf("ls && rm -rf build", "cat a; rm a", "ls || rm x", "ls & rm x", "ls\nrm x", "cat \$(rm x)", "cat `rm x`", "cd d && ls && rm x")) assertNull(k(w), w)
        for (r in listOf("ls && cat a", "cat a; ls", "grep 'a;b' f", "cd /d && ls; wc f")) assertNotNull(k(r), r)
    }

    @Test fun gitBranchIsReadOnlyOnlyWhenListing() {
        for (w in listOf("git branch -D x", "git branch -m a b", "git branch newname")) assertNull(k(w), w)
        for (r in listOf("git branch", "git branch -a", "git branch -vv", "git branch --show-current")) assertNotNull(k(r), r)
    }

    @Test fun awkWritersAreNotReadOnly() {
        assertNull(k("awk 'BEGIN{system(\"rm x\")}'"))
        assertNull(k("awk '{print > \"f\"}' g"))
        assertNull(k("awk '{print | \"sh\"}' g"))
        assertNotNull(k("awk '{print \$1}' f"))
    }

    @Test fun readSlicesGetDistinctKeys() {
        fun kr(v: JsonElement) = cacheKey("Read", v, null, null)!!
        val plain = kr(obj("file_path" to "/f"))
        assertEquals("read\t/f", plain)
        val a = kr(obj("file_path" to "/f", "offset" to 1, "limit" to 50))
        val b = kr(obj("file_path" to "/f", "offset" to 300, "limit" to 50))
        assertNotEquals(a, b)
        assertNotEquals(a, plain)
        assertEquals(a, kr(obj("file_path" to "/f", "offset" to 1, "limit" to 50)))
        assertTrue(a.startsWith("read\t/f\t"))
        assertNotEquals(kr(obj("file_path" to "/f.pdf", "pages" to "1-3")), kr(obj("file_path" to "/f.pdf", "pages" to "4-6")))
    }

    @Test fun flagAwareReadOnlyKeys() {
        assertNotNull(k("sed -n 1,40p f"))
        assertNull(k("sed -i s/a/b/ f"))
        assertNull(k("find . -name x -delete"))
        assertNull(k("cat a > b"))
        assertNull(k("tail -f log"))
        assertNotNull(k("cat a | grep b"))
        assertNull(k("ls | xargs rm"))
        assertNotNull(k("jq . f"))
        assertNotNull(k("awk '{print \$1}' f"))
        assertNotNull(k("git rev-parse HEAD"))
        assertNotNull(k("cargo metadata"))
        assertNull(k("cargo test"))
    }

    @Test fun sideEffectVariantsOfReadOnlyStemsHaveNoKey() {
        for (w in listOf(
            "sed 'w output' input",
            "sed -n 'w output' input",
            "sed -e 'w output' input",
            "sed --expression='w output' input",
            "sed -ne 'w output' input",
            "sed 's/a/b/w output' input",
            "sed 's/a/b/e' input",
            "sed '1e touch x' input",
            "sed 'r other' input",
            "sed -f script input",
            "sed --file=script input",
            "sed --in-place s/a/b/ input",
            "sed 's/[/]/x/w out' input",
            "find . -fprint output",
            "find . -fprint0 output",
            "find . -fprintf output '%p'",
            "find . -fls output",
            "find . \"-fprint\" output",
            "find . -ok rm {} ;",
            "find . -okdir rm {} ;",
            "git diff --output=output",
            "git diff --output output",
            "git log --out=output",
            "git show '--output=output'",
        )) {
            assertNull(k(w), w)
        }
        for (r in listOf(
            "sed -n 1,40p f",
            "sed -n '10,20p' f",
            "sed -n '/foo/p' f",
            "sed 's/a/b/g' f",
            "sed -E 's/(a|b)+/x/gI;5q' f",
            "sed -e 's/a/b/' -e '\$d' f",
            "sed -ne '2p' f",
            "sed '/^#/d' f",
            "find . -name '*.rs' -type f",
            "find . -name x -print",
            "git diff --stat",
            "git log --oneline -5",
            "git diff --name-only HEAD~1",
        )) {
            assertNotNull(k(r), r)
        }
    }

    @Test fun aDisguisedWriterInvalidatesAnEarlierRead() =
        shell("g-disguise") { sh ->
            val read = obj("file_path" to "/r/input")
            done(sh.cx, "Read", read)
            assertTrue(readAt(sh.cx, read))
            sh.ran("sed 'w output' input")
            assertFalse(readAt(sh.cx, read))
            assertFalse(sh.denied("sed 'w output' input"))
            sh.ran("sed -n 1,2p input")
            assertTrue(sh.denied("sed -n 1,2p input"))
        }

    @Test fun findDeleteAllowsTheNextLs() =
        shell("g-finddel") { sh ->
            sh.ran("ls")
            assertTrue(sh.denied("ls"))
            sh.ran("find . -delete")
            assertFalse(sh.denied("ls"))
        }

    @Test fun mutatingBashClearsThenAllowsRepeatLs() =
        withRuntime("g-mutls") { r ->
            post(Ctx(r), "Bash", cmd("ls"), obj("stdout" to "a"))
            post(Ctx(r), "Bash", cmd("find . -delete"), obj("stdout" to ""))
            assertNull(pre(Ctx(r), "Bash", cmd("ls")))
        }

    @Test fun agentWrapMatchesTheInnerCommandAndKeepsOtherKeys() =
        withRuntime("g-agentwrap") { r ->
            val path = obj("file_path" to "/proj/src/main.rs")
            post(Ctx(r), "Read", path, obj("content" to "fn main() {}"))
            post(Ctx(r), "Bash", cmd("ls"), obj("stdout" to "a\n"))
            post(Ctx(r), "Bash", cmd("rtok run --agent sub_1 -- ${shQuote("git status")}"), obj("stdout" to "clean"))
            assertTrue(denies(pre(Ctx(r), "Read", path)), "a read-only sub-agent wrap must not drop read keys")
            assertTrue(denies(pre(Ctx(r), "Bash", cmd("ls"))), "must not drop bash keys")
            assertTrue(denies(pre(Ctx(r), "Bash", cmd("git status"))))
            post(Ctx(r), "Bash", cmd("rtok run --agent sub_1 -- ${shQuote("echo x>f")}"), obj("stdout" to ""))
            assertNull(pre(Ctx(r), "Read", path))
        }

    @Test fun wrappedBashPostMatchesUnwrappedPre() =
        withRuntime("g-wrap") { r ->
            post(Ctx(r), "Bash", cmd("rtok run -- ${shQuote("git status")}"), obj("stdout" to "ok"))
            assertTrue(denies(pre(Ctx(r), "Bash", cmd("git status"))))
        }

    @Test fun nonAsciiInputNeverPanicsInPreOrPostTool() =
        withRuntime("g-nonascii") { r ->
            val words =
                listOf(
                    "–",
                    "⌘",
                    "a–bc",
                    "⌘ab",
                    "x–",
                    "–x",
                    "é.exe",
                    "ab–.exe",
                    "cd –",
                    "cd ⌘ && ls",
                    "cd '⌘' && ls –",
                    "'⌘'",
                    "'–",
                    "rtok run -- '⌘'",
                    "rtok run -- –",
                    "ls –a | ⌘c",
                    "echo ⌘>–",
                    "git ⌘",
                    "/usr/bin/–",
                    "\\⌘\\é",
                )
            for (w in words) {
                for ((tool, input) in listOf(
                    "Bash" to obj("command" to w),
                    "Read" to obj("file_path" to w),
                    "Read" to obj("path" to w, "offset" to w),
                    "Edit" to obj("file_path" to w),
                    "Write" to obj("path" to w),
                    "Grep" to obj("pattern" to w, "path" to w),
                    "Glob" to obj("pattern" to w),
                    "Skill" to obj("skill" to w),
                    "Skill" to obj("skill" to "$w:$w"),
                )) {
                    pre(Ctx(r), tool, input)
                    GuardPlugin.postTool(PostToolUse(tool, input, obj("content" to w)), Ctx(r))
                }
            }
        }

    /** New: `rtok guard check` returns the same allow/deny as preTool, renaming `filePath`. */
    @Test fun checkReportsAllowAndDenyAsJson() =
        withRuntime("g-check") { r ->
            assertEquals("{\"allow\":true}", check("Read", "{\"filePath\":\"/p/a.rs\"}", r))
            post(Ctx(r), "Read", obj("file_path" to "/p/a.rs"))
            assertTrue(check("Read", "{\"filePath\":\"/p/a.rs\"}", r).startsWith("{\"allow\":false,\"reason\":\"duplicate; rtok expand "))
            assertEquals("{\"allow\":true}", check("Read", "not json", r))
        }
}
