package rtok.plugins.cmd

import rtok.shortId
import rtok.store.Store
import rtok.store.listMeasurements
import rtok.sys.*
import kotlin.test.*

class RunTest {
    private fun sh(script: String) = ProgressRunner("t…", "sh", listOf("-c", script))

    private fun emit(
        c: rtok.config.Config,
        argv: List<String>,
        body: ByteArray,
        agent: String? = null,
    ): ByteArray {
        val out = ArrayList<Byte>()
        emitFilteredTo(c, argv, body, 0, agent) { out.addAll(it.toList()) }
        return out.toByteArray()
    }

    private fun dedups(c: rtok.config.Config) = cmdRows(c).count { it.kind == "dedup" }

    @Test fun captureBodyCapsAndAppendsTheNotice() {
        val (body, code) = captureBody(sh("head -c 5000 /dev/zero | tr '\\0' x; exit 2"), 1024)
        val notice = "[rtok: output capped at 1024 bytes; 3976 bytes not captured]\n"
        assertEquals(2, code)
        assertEquals(1024 + 1 + notice.length, body.size)
        assertTrue(body.decodeToString().endsWith(notice))
        assertTrue(body.take(1024).all { it == 'x'.code.toByte() })
    }

    @Test fun captureBodyWithoutOverflowHasNoNotice() {
        val (body, code) = captureBody(sh("printf hi"), 1024)
        assertEquals("hi", body.decodeToString())
        assertEquals(0, code)
    }

    @Test fun theSpinnerNamesTheCommandOnOneShortLine() {
        assertEquals("running cargo test…", runningLabel(listOf("cargo", "test")))
        assertEquals("running a…", runningLabel(listOf("a\nb")))
        assertEquals("running ".length + 60 + 1, runningLabel(listOf("x".repeat(100))).length)
    }

    @Test fun shortOutputAFilterLengthensPrintsRaw() {
        val (c, dir) = cmdConfig("never-worse")
        val body = (0 until 9).joinToString("") { "crates/c$it/Cargo.toml\n" }
        emitFiltered(c, listOf("find", "crates"), body.encodeToByteArray(), 0, null)
        assertEquals(0, archived(c).size)
        assertTrue(cmdRows(c).any { it.kind == "raw" }, cmdRows(c).toString())
        removeDirAll(dir)
    }

    @Test fun emitFilteredArchivesStdinAndRecords() {
        val (c, dir) = cmdConfig("emit-archive")
        val body = numbered(400)
        emit(c, listOf("cat"), body.encodeToByteArray())
        val files = archived(c)
        assertEquals(1, files.size)
        assertEquals(body, readToString(files[0]))
        assertEquals(1, cmdRows(c).size)
        removeDirAll(dir)
    }

    @Test fun emitFilteredSkillRecordsKind() {
        val (c, dir) = cmdConfig("emit-skill")
        emit(c, listOf("skill", "demo"), numbered(80).encodeToByteArray())
        assertEquals("skill", cmdRows(c)[0].kind)
        removeDirAll(dir)
    }

    @Test fun trailerNamesAnEightCharId() {
        val id = "0123456789abcdef".repeat(4)
        assertEquals("[rtok 01234567 · 5 lines]", trailer(id, 5u))
        assertTrue(namesTheId("… 4 lines omitted (expand 01234567)", shortId(id)))
    }

    @Test fun printfTwoLinesExit0NoTrailer() {
        val (c, dir) = cmdConfig("printf")
        assertEquals(0, run(c, listOf("printf", "a\nb\n"), null))
        assertTrue(archived(c).isEmpty())
        val rows = cmdRows(c)
        assertEquals(1, rows.size)
        assertTrue(rows[0].afterBytes <= rows[0].beforeBytes)
        removeDirAll(dir)
    }

    @Test fun identicalOutputFromDifferentCommandsDedups() {
        val (c, dir) = cmdConfig("dedup-hash")
        val payload = numbered(400) + "\n"
        for (name in listOf("a.txt", "b.txt")) {
            writeString("$dir/$name", payload)
            assertEquals(0, run(c, listOf("cat", "$dir/$name"), null))
        }
        val rows = cmdRows(c)
        assertEquals(1, rows.count { it.kind == "dedup" }, rows.toString())
        assertTrue(rows.any { it.kind == "dedup" && it.beforeBytes > it.afterBytes })
        removeDirAll(dir)
    }

    @Test fun dedupIsScopedToTheDispatchingAgent() {
        val (c, dir) = cmdConfig("dedup-agent-scope")
        val payload = (numbered(80) + "\n").encodeToByteArray()
        emit(c, listOf("printf"), payload, "agent-a")
        assertEquals(0, dedups(c), "nothing to dedup against yet")
        emit(c, listOf("printf"), payload, "agent-b")
        assertEquals(0, dedups(c), "agent-b never saw agent-a's body")
        emit(c, listOf("printf"), payload, "agent-a")
        assertEquals(1, dedups(c), "agent-a's own repeat still dedups")
        removeDirAll(dir)
    }

    @Test fun exit3IsPreserved() {
        val (c, dir) = cmdConfig("exit3")
        assertEquals(3, run(c, listOf("sh", "-c", "exit 3"), null))
        removeDirAll(dir)
    }

    @Test fun threeRunsRecordThreeRows() {
        val (c, dir) = cmdConfig("stats3")
        repeat(3) { assertEquals(0, run(c, listOf("printf", "a\nb\n"), null)) }
        val rows = cmdRows(c)
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.beforeBytes >= it.afterBytes })
        removeDirAll(dir)
    }

    @Test fun scriptKeepsOneArgAsAShellSnippet() {
        assertEquals("{ true && false\n} 2>&1", scriptFor(ShellKind.Posix, listOf("true && false")))
        assertEquals("{ 'git' 'status'\n} 2>&1", scriptFor(ShellKind.Posix, listOf("git", "status")))
    }

    @Test fun oneArgCompoundCommandRunsAsOneScript() {
        val (c, dir) = cmdConfig("compound")
        assertEquals(0, run(c, listOf("printf a; printf b"), null))
        assertEquals(2L, cmdRows(c)[0].beforeBytes)
        removeDirAll(dir)
    }

    @Test fun archiveKeepsBytesThatAreNotUtf8() {
        val (c, dir) = cmdConfig("bytes")
        val body = byteArrayOf(0xff.toByte(), 0xfe.toByte()) + "ok\n".encodeToByteArray() + ByteArray(300) { 'x'.code.toByte() } + byteArrayOf(10)
        emit(c, listOf("cat"), body)
        assertContentEquals(body, readBytes(archived(c)[0]))
        removeDirAll(dir)
    }

    @Test fun aShortenedOutputNeedsAPointerWhateverItsLength() {
        val log = numbered(29, "c").lines().joinToString("\n") { "$it msg" }
        val cut = numbered(20, "c").lines().joinToString("\n") { "$it msg" }
        val b = log.encodeToByteArray()
        assertTrue(needsPointer(29u, 40u, b, cut, false), "trimmed under the threshold")
        assertFalse(needsPointer(29u, 40u, b, log, false), "nothing dropped")
        assertTrue(needsPointer(400u, 40u, b, log, false), "long enough on its own")
        assertTrue(needsPointer(1u, 40u, """{"blob":"xxxx"}""".encodeToByteArray(), "blob: \"xx… \" (4)", false), "a string cut in place")
        assertTrue(needsPointer(1u, 40u, byteArrayOf(0xff.toByte(), 0xfe.toByte(), 'o'.code.toByte(), 'k'.code.toByte()), "\uFFFD\uFFFDok", false), "lossy bytes")
        assertFalse(needsPointer(10u, 40u, b, "c0 msg\n… 27 lines omitted (expand abc)\nc28 msg", true), "the marker names the archive")
    }

    @Test fun paddingAndAnsiOnlyChangesNeedNoPointer() {
        assertFalse(needsPointer(3u, 40u, "NAME   STATUS   AGE\nweb-0  Running  3d\napi-1  Pending  1h\n".encodeToByteArray(), "NAME STATUS AGE\nweb-0 Running 3d\napi-1 Pending 1h", false))
        assertFalse(needsPointer(1u, 40u, "\u001b[1mok\u001b[0m\n".encodeToByteArray(), "ok", false))
        assertFalse(needsPointer(1u, 40u, "hello-trycmd\n".encodeToByteArray(), "hello-trycmd", false))
    }

    @Test fun tinyBodyPassesThroughVerbatimWithNoNegativeSaving() {
        val (c, dir) = cmdConfig("tiny-passthrough")
        val line = "root         1  0.0  00:00:01 /sbin/init worker-7 extra-flag"
        val rows = mutableListOf("USER         PID  %CPU TIME     COMMAND")
        while (rows.joinToString("\n").length + 1 + line.length < 150) rows += line
        val body = rows.joinToString("\n")
        val filtered = Formatters.compress(Settings.builtin(), listOf("ps", "aux"), body, 0, "deadbeef").text
        assertNotEquals(body, filtered)
        assertTrue(needsPointer(body.rustLines().size.toUInt(), 40u, body.encodeToByteArray(), filtered, false))
        val printed = emit(c, listOf("ps", "aux"), body.encodeToByteArray())
        assertTrue(printed.size <= body.length + 1, printed.decodeToString())
        val m = cmdRows(c)
        assertEquals(1, m.size)
        assertTrue(m[0].afterBytes <= m[0].beforeBytes)
        removeDirAll(dir)
    }

    @Test fun rtokExpandOutputIsNeverDedupedOrShortened() {
        val (c, dir) = cmdConfig("expand-passthrough")
        val body = (0 until 400).joinToString("") { "line $it\n" }
        repeat(2) { assertEquals(body, emit(c, listOf("cat notes.txt; rtok expand abc12345"), body.encodeToByteArray()).decodeToString()) }
        assertTrue(runCatching { cmdRows(c) }.getOrDefault(emptyList()).none { it.kind == "dedup" })
        removeDirAll(dir)
    }

    @Test fun emitFilteredIgnoresBytesPastTheHostCap() {
        val (c, dir) = cmdConfig("host-cap")
        val body = (listOf("x".repeat(Bounded.HOST_VISIBLE_CHARS)) + (0 until 50).map { "tail line $it that the host already dropped" }).joinToString("\n")
        emit(c, listOf("echo"), body.encodeToByteArray())
        val m = cmdRows(c)
        assertEquals(1, m.size)
        assertTrue(m[0].beforeBytes > m[0].afterBytes, m.toString())
        assertEquals(m[0].estBefore, m[0].estAfter, "both estimates stop at the host prefix")
        removeDirAll(dir)
    }

    @Test fun emitFilteredUnderTheCapStillCountsAShrink() {
        val (c, dir) = cmdConfig("host-under")
        val body =
            (
                listOf("running 50 tests") + (0 until 50).map { "test a::ok$it ... ok" } +
                    listOf("test a::bad ... FAILED", "test result: FAILED. 1 failed; 50 passed")
            ).joinToString("\n")
        val shown = emit(c, listOf("cargo", "test"), body.encodeToByteArray()).decodeToString()
        assertTrue(shown.contains("[rtok "), shown)
        val m = cmdRows(c)
        assertEquals(1, m.size)
        assertTrue(m[0].estBefore > m[0].estAfter)
        removeDirAll(dir)
    }

    private fun rawBytesSurviveAFailingStore(storeOpens: Boolean) {
        val (c0, dir) = cmdConfig("raw-fallback")
        val blocker = "$dir/blocker"
        writeString(blocker, "a file, not a directory")
        val c = if (storeOpens) c0.copy(core = c0.core.copy(archiveDir = blocker)) else c0.copy(core = c0.core.copy(dbPath = "$blocker/rtok.db"))
        var body = ByteArray(0)
        for (i in 0 until 400) body += "line $i \u00e9".encodeToByteArray() + byteArrayOf(0xff.toByte(), 10)
        body += "no newline at the end".encodeToByteArray() + byteArrayOf(0xff.toByte())
        assertContentEquals(body + byteArrayOf(10), emit(c, listOf("echo"), body))
        removeDirAll(dir)
    }

    @Test fun rawBytesSurviveAStoreThatWillNotOpen() = rawBytesSurviveAFailingStore(false)

    @Test fun rawBytesSurviveAFailingArchiveWrite() = rawBytesSurviveAFailingStore(true)

    @Test fun resolveShellPrefersConfigThenEnvThenHostDefault() {
        assertEquals("C:\\shell\\bash.exe", resolveShell("C:\\shell\\bash.exe", "/bin/zsh", true, "cmd.exe"))
        assertEquals("/bin/zsh", resolveShell("", "/bin/zsh", true, "C:\\Windows\\System32\\cmd.exe"))
        assertEquals("C:\\Windows\\System32\\cmd.exe", resolveShell("", null, true, "C:\\Windows\\System32\\cmd.exe"))
        assertEquals("cmd.exe", resolveShell("", null, true, null))
        assertEquals("/bin/sh", resolveShell("", null, false, null))
        assertEquals("/bin/sh", resolveShell("", "", false, null))
    }

    @Test fun resolveShellIgnoresNonPosixEnvShell() {
        for (s in listOf("/opt/homebrew/bin/fish", "nu", "/usr/bin/xonsh", "fish")) {
            assertEquals("/bin/sh", resolveShell("", s, false, null), s)
            assertEquals("cmd.exe", resolveShell("", s, true, null), s)
        }
        for (s in listOf("/bin/bash", "/usr/local/bin/zsh", "dash", "ksh", "C:\\msys64\\usr\\bin\\BASH.EXE")) assertEquals(s, resolveShell("", s, false, null), s)
        assertEquals("/usr/bin/fish", resolveShell("/usr/bin/fish", "/bin/zsh", false, null))
    }

    @Test fun shellKindAndArgsMatchWindowsHosts() {
        assertEquals(ShellKind.Posix, shellKind("/bin/sh"))
        assertEquals(ShellKind.Cmd, shellKind("C:\\Windows\\System32\\cmd.exe"))
        assertEquals(ShellKind.PowerShell, shellKind("powershell.exe"))
        assertEquals(ShellKind.PowerShell, shellKind("pwsh"))
        assertEquals(listOf("-c", "true"), shellArgs("/bin/sh", "true"))
        assertEquals(listOf("/D", "/C", "echo hi"), shellArgs("C:\\Windows\\System32\\cmd.exe", "echo hi"))
        assertEquals(listOf("-NoProfile", "-NonInteractive", "-Command", "Get-Date"), shellArgs("pwsh.exe", "Get-Date"))
    }

    @Test fun scriptForCmdQuotesMultiArgv() {
        assertEquals("(echo \"hello world\") 2>&1", scriptFor(ShellKind.Cmd, listOf("echo", "hello world")))
        assertEquals("(echo hi) 2>&1", scriptFor(ShellKind.Cmd, listOf("echo hi")))
        assertEquals("\"say \"\"hi\"\"\"", cmdQuote("say \"hi\""))
    }

    @Test fun configuredPowershellUsesCommandFlag() {
        val (c0, dir) = cmdConfig("ps-shell")
        val c = c0.copy(plugins = c0.plugins.copy(cmd = c0.plugins.cmd.copy(shell = "powershell.exe")))
        val sh = shell(c)
        assertEquals("powershell.exe", sh)
        assertEquals(ShellKind.PowerShell, shellKind(sh))
        val args = shellArgs(sh, scriptFor(ShellKind.PowerShell, listOf("echo", "x")))
        assertEquals("-NoProfile", args[0])
        assertEquals("-Command", args[2])
        removeDirAll(dir)
    }

    @Test fun wrapQuoteIsHostSafeForApostrophe() {
        assertEquals(shQuote("echo it's"), wrapQuote("echo it's"))
        assertEquals("'echo it''s'", wrapQuote("echo it's", windows = true))
    }
}
