package rtok.plugins.cmd

import kotlinx.cinterop.convert
import kotlinx.serialization.json.*
import rtok.config.Cmd
import rtok.plugin.Runtime
import rtok.plugin.sdk.*
import rtok.runGuard
import rtok.support.installDb
import rtok.sys.*
import kotlin.test.*

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class HookTest {
    private fun decideAs(
        agent: String?,
        command: String,
    ): PreToolDecision? {
        installDb()
        return Runtime.inMemory("wrap").use { cx ->
            CmdHook.preTool(
                PreToolUse(
                    "Bash",
                    buildJsonObject {
                        put("command", command)
                        put("description", "t")
                    },
                ),
                Ctx(cx, agent),
            )
        }
    }

    private fun decide(command: String) = decideAs(null, command)

    private fun rewritten(d: PreToolDecision?): String {
        val r = assertIs<PreToolDecision.Rewrite>(d)
        assertEquals("wrapped by rtok", r.reason)
        return r.input.jsonObject["command"]!!
            .jsonPrimitive.content
    }

    private fun wrapped(d: PreToolDecision?) = rewritten(d).replaceFirst(runGuard(), "")

    private val stems = Cmd().interactiveStems

    @Test fun gitStatusIsWrapped() = assertEquals("rtok run -- 'git status'", wrapped(decide("git status")))

    @Test fun heredocAndSudoUntouched() {
        assertNull(decide("cat <<EOF"))
        assertNull(decide("sudo ls"))
    }

    @Test fun metacharactersStayInsideOneQuotedArgument() {
        for (cmd in listOf("true && false", "git status | head", "foo; bar", "echo x >/tmp/x")) assertEquals("rtok run -- ${wrapQuote(cmd)}", wrapped(decide(cmd)))
    }

    @Test fun sleepThenPipedCatIsWrapped() = assertEquals("rtok run -- 'sleep 90; cat /tmp/tasks/x.output | tail -30'", wrapped(decide("sleep 90; cat /tmp/tasks/x.output | tail -30")))

    @Test fun leadingCdHopsStayOutsideTheWrap() {
        assertEquals("cd crates/rtok && rtok run -- 'cargo test'", wrapped(decide("cd crates/rtok && cargo test")))
        assertEquals("cd \"a && b\" && cd c && rtok run -- 'git status | head'", wrapped(decide("cd \"a && b\" && cd c && git status | head")))
        assertEquals("cd x && rtok run --agent sub_1 -- 'ls'", wrapped(decideAs("sub_1", "cd x && ls")))
    }

    @Test fun shellStateBuiltinsAreNotWrapped() {
        for (cmd in listOf(
            "cd /repo",
            "cd /repo; ls",
            "ls && cd /repo",
            "cd a && cargo test && cd b",
            "export A=1",
            "export A=1 && cargo test",
            "source .venv/bin/activate && pytest",
            ". ./env.sh",
            "unset A",
            "pushd x && ls",
            "popd",
            "alias g=git",
        )) {
            assertNull(decide(cmd), cmd)
        }
        assertNotNull(decide("git checkout -b export"))
    }

    @Test fun skipRulesApplyBehindCdHops() {
        for (cmd in listOf("cd x && rtok expand abc", "cd x && cd y && rtok expand abc", "cd x && sudo ls", "cd x && python -i", "cd x && sleep 10 &")) assertNull(decide(cmd), cmd)
        assertEquals("cd x && rtok run -- 'cargo test'", wrapped(decide("cd x && cargo test")))
    }

    @Test fun escapedQuoteDoesNotHideACd() {
        assertNull(decide("echo it\\'s && cd sub"))
        assertNull(decide("echo \"a\\\" b\" && cd sub"))
        assertNotNull(decide("echo it\\'s fine"))
    }

    @Test fun moreStateBuiltinsAndBareAssignmentsStayUnwrapped() {
        for (cmd in listOf(
            "set -e",
            "shopt -s globstar",
            "umask 022",
            "trap 'rm x' EXIT",
            "declare -x A=1",
            "typeset A=1",
            "readonly A=1",
            "A=1",
            "A=1 B=2 && cargo test",
            "A='x y'",
        )) {
            assertNull(decide(cmd), cmd)
        }
        assertEquals("rtok run -- 'A=1 cargo test'", wrapped(decide("A=1 cargo test")))
        assertNotNull(decide("echo a=b"))
    }

    @Test fun trailingAmpersandUntouched() {
        assertNull(decide("sleep 10 &"))
        assertNull(decide("sleep 10&"))
        assertNotNull(decide("true && false"), "&& is not background")
    }

    @Test fun windowsPathAndExeHonorNeverWrap() {
        assertNull(decide("C:\\Windows\\System32\\sudo.exe ls"))
        assertNull(decide("C:\\tools\\rtok.exe run -- true"))
        assertNull(decide("sudo.exe ls"))
        assertNotNull(decide("C:\\Program Files\\Git\\cmd\\git.exe status"))
    }

    @Test fun sudoExeCaseInsensitiveNeverWrap() {
        for (cmd in listOf("Sudo.exe ls", "SUDO.EXE ls", "RTOK.EXE run -- true", "Sudo ls")) assertNull(decide(cmd), cmd)
    }

    @Test fun wrapKeepsApostropheHostSafe() {
        val cmd = "echo it's fine"
        val w = wrapped(decide(cmd))
        assertTrue(w.startsWith("rtok run -- "), w)
        assertEquals(wrapQuote(cmd), w.removePrefix("rtok run -- "))
    }

    @Test fun windowsApostropheCommandsStayUnwrapped() {
        for (cmd in listOf("echo it's fine", "git commit -m 'fix it'", "jq '.' data.json")) assertTrue(CmdHook.skipWrapHost(true, cmd, emptyList(), stems), cmd)
        assertFalse(CmdHook.skipWrapHost(false, "echo it's fine", emptyList(), stems))
        assertFalse(CmdHook.skipWrapHost(false, "git commit -m 'fix it'", emptyList(), stems))
    }

    @Test fun perStemInteractiveITable() {
        fun skip(c: String) = CmdHook.skipWrapHost(false, c, emptyList(), stems)
        for (c in listOf("ffmpeg -i x", "ssh -i key host")) {
            assertFalse(skip(c), c)
            assertNotNull(decide(c), c)
        }
        for (c in listOf("python -i", "docker run -i alpine sh", "ffmpeg --interactive x")) {
            assertTrue(skip(c), c)
            assertNull(decide(c), c)
        }
    }

    /** Minimal POSIX word splitter: enough to show PowerShell `''` quoting does not survive sh. */
    private fun shWords(input: String): List<String> {
        val words = ArrayList<String>()
        val w = StringBuilder()
        var inWord = false
        var i = 0
        while (i < input.length) {
            val c = input[i++]
            when {
                c == '\'' -> {
                    inWord = true
                    while (i < input.length && input[i] != '\'') w.append(input[i++])
                    i++
                }

                c == '"' -> {
                    inWord = true
                    while (i < input.length && input[i] != '"') {
                        if (input[i] == '\\' && i + 1 < input.length) i++
                        w.append(input[i++])
                    }
                    i++
                }

                c.isWhitespace() -> {
                    if (inWord) {
                        words += w.toString()
                        w.clear()
                        inWord = false
                    }
                }

                else -> {
                    inWord = true
                    w.append(c)
                }
            }
        }
        if (inWord) words += w.toString()
        return words
    }

    @Test fun psQuotingDoesNotRoundTripUnderSh() {
        val cmd = "echo it's fine"
        val ps = wrapQuote(cmd, windows = true)
        assertEquals(listOf("echo its fine"), shWords(ps))
        assertNotEquals(listOf(cmd), shWords(ps))
        assertEquals(listOf(cmd), shWords(shQuote(cmd)))
    }

    @Test fun subAgentDispatchEmbedsItsAgentId() = assertEquals("rtok run --agent agent-a1 -- 'cargo test'", wrapped(decideAs("agent-a1", "cargo test")))

    @Test fun mainWindowDispatchHasNoAgentFlag() = assertEquals("rtok run -- 'git status'", wrapped(decideAs(null, "git status")))

    @Test fun malformedAgentIdIsDropped() {
        for (bad in listOf("has space", "semi;colon", "", "a".repeat(65), "quote'here")) assertEquals("rtok run -- 'cargo test'", wrapped(decideAs(bad, "cargo test")), bad)
    }

    @Test fun subAgentGitCommandsAreNeverWrapped() {
        for (cmd in listOf(
            "git status",
            "cd /repo/.claude/worktrees/agent-a1 && git status",
            "/usr/bin/git add -A",
            "git -C /repo/.claude/worktrees/agent-a1 commit -m x",
            "cargo test && git status",
            "C:\\Program Files\\Git\\cmd\\git.exe status",
            "bash -c 'git status'",
            "echo $(git rev-parse HEAD)",
            "env GIT_DIR=x GIT_PAGER=cat git log",
        )) {
            assertNull(decideAs("agent-a1", cmd), cmd)
        }
    }

    @Test fun weft20261016RefusedCommandsAreNeverWrapped() {
        val wt = "/work/_worktrees/weft-agent-a10131a73e855515f"
        val cmds =
            listOf(
                "git fetch origin -q && git checkout -q -B t105.17-winui-keywords origin/main && git log --oneline -3 && ls && grep -n \"T105.17\" -A12 plan.md | head -40",
                "git fetch origin -q && git checkout -q -B t105.17-winui-keywords origin/main && git log --oneline -3",
                "git fetch origin -q && git checkout -B t903-macos-save-as origin/main 2>&1 | tail -2 && ls && grep -n \"T903\" -A15 plan.md | head -60",
                "git fetch origin -q && git checkout -B t105.15-winui-tokens origin/main 2>&1 | tail -2 && git log --oneline -3 && ls",
                "git -C $wt fetch origin -q",
                "git status",
                "git status --short --branch",
                "git fetch origin -q",
                "git fetch origin -q && echo ok",
                "/usr/bin/git fetch origin -q && echo ok",
                "/usr/bin/git status | head -3",
                "/usr/bin/env git log --oneline -S\"@class\" | cat; grep -n \"keyword\\|@class\\|verbatim\" research.md | head -20; grep -n \"account\" crates/weft-winui/tests/common/mod.rs",
            )
        for (cmd in cmds) {
            assertNull(decideAs("a10131a73e855515f", cmd), "sub-agent git wrapped: $cmd")
            assertTrue(wrapped(decideAs(null, cmd)).startsWith("rtok run -- '"), "main session: $cmd")
        }
    }

    @Test fun subAgentNonGitCommandsStayWrapped() {
        for (cmd in listOf("cargo test", "cargo test github", "ls my_git digit")) assertEquals("rtok run --agent agent-a1 -- '$cmd'", wrapped(decideAs("agent-a1", cmd)))
    }

    @Test fun mainSessionGitCommandsStayWrapped() = assertEquals("cd /repo && rtok run -- 'git status'", wrapped(decideAs(null, "cd /repo && git status")))

    /** Runs [line] in [shell] with nothing but PATH in the environment. */
    private fun runLine(
        shell: String,
        line: String,
        path: String,
    ): Pair<String, Int> {
        val (c, _) = ProgressRunner("t", "/usr/bin/env", listOf("-i", "PATH=$path", shell, "-c", line)).runCapped(Long.MAX_VALUE)
        return c.stdout.decodeToString() to exitCode(c)
    }

    @Test fun rewriteRunsTheOriginalCommandWhenRtokIsNotOnPath() {
        for (shell in listOf("/bin/sh", "/bin/bash", "/bin/zsh").filter(::exists)) {
            for ((cmd, want) in listOf("echo \"it's fine\"; exit 3" to ("it's fine\n" to 3), "cd / && pwd" to ("/\n" to 0))) {
                val line = rewritten(decide(cmd))
                assertEquals(want, runLine(shell, line, "/usr/bin:/bin"), "$shell: $line")
            }
            assertEquals("hi\n" to 0, runLine(shell, rewritten(decideAs("agent-a1", "echo hi")), "/usr/bin:/bin"))
        }
    }

    @Test fun rewriteCallsRtokUntouchedWhenItIsOnPath() {
        val dir = "${tempDir()}/rtok-t922-${pid()}"
        createDirAll(dir)
        writeString("$dir/rtok", "#!/bin/sh\nfor a; do echo \"[\$a]\"; done\n")
        platform.posix.chmod("$dir/rtok", 0b111101101.toUShort().convert())
        val got = runLine("/bin/sh", rewritten(decide("echo hi")), "$dir:/usr/bin:/bin")
        removeDirAll(dir)
        assertEquals("[run]\n[--]\n[echo hi]\n" to 0, got)
    }
}
