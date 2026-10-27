@file:Suppress("MagicNumber")

// Port of the tests of src/doctor/hooks.rs (dupes.rs is covered there too): an in-memory machine through Probes.

package rtok.doctor

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import rtok.config.Config
import rtok.json.StrictJson
import rtok.sys.createDirAll
import rtok.sys.join
import rtok.sys.removeDirAll
import rtok.sys.removeFile
import rtok.sys.symlink
import rtok.sys.tempDir
import kotlin.native.OsFamily
import kotlin.native.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An in-memory machine: files, kinds, programs on `PATH` and the environment. */
@Suppress("TooManyFunctions")
private class Mock(
    val files: MutableMap<String, String> = linkedMapOf(),
    val kinds: MutableMap<String, PathKind> = linkedMapOf(),
    val path: MutableMap<String, String> = linkedMapOf(),
    val env: MutableMap<String, String> = linkedMapOf(),
    val unreadable: MutableSet<String> = linkedSetOf(),
    /** Windows rules for hook commands. */
    val win: Boolean = false,
    /** A case-insensitive file system, as on Windows. */
    val insensitive: Boolean = false,
) : ProbeFs,
    ProbeEnv,
    Which {
    fun copy() = Mock(files.toMutableMap(), kinds.toMutableMap(), path.toMutableMap(), env.toMutableMap(), unreadable.toMutableSet(), win, insensitive)

    private fun key(p: String) = p.replace('\\', '/').let { if (insensitive) it.lowercase() else it }

    override fun canonical(path: String) = path

    override fun read(path: String): String {
        if (unreadable.any { key(it) == key(path) }) throw ReadError(false, "Permission denied")
        return files.entries.firstOrNull { key(it.key) == key(path) }?.value ?: throw ReadError(true, "not found")
    }

    override fun kind(path: String) = kinds.entries.firstOrNull { key(it.key) == key(path) }?.value ?: PathKind.Missing

    override fun variable(name: String) = env[name]

    override fun home() = "/h"

    override fun cwd() = "/proj"

    override fun windows() = win

    override fun find(program: String) = path[program]

    fun script(
        p: String,
        executable: Boolean,
    ) {
        kinds[p] = PathKind.File(executable)
    }

    fun settings(
        p: String,
        commands: List<String>,
    ) {
        files[p] = hooksDoc("PreToolUse", "Bash", commands)
    }

    fun probes() = Probes(this, this, this)
}

private fun hooksDoc(
    event: String,
    matcher: String?,
    commands: List<String>,
) = hooksObj(event, matcher, commands).toString()

private fun hooksObj(
    event: String,
    matcher: String?,
    commands: List<String>,
) = buildJsonObject {
    put(
        "hooks",
        buildJsonObject {
            put(
                event,
                buildJsonArray {
                    add(
                        buildJsonObject {
                            if (matcher != null) put("matcher", matcher)
                            put(
                                "hooks",
                                JsonArray(
                                    commands.map {
                                        buildJsonObject {
                                            put("type", "command")
                                            put("command", it)
                                        }
                                    },
                                ),
                            )
                        },
                    )
                },
            )
        },
    )
}

private fun cfg(): Config {
    val c = Config()
    return c.copy(
        doctor = c.doctor.copy(settingsPath = "/h/.claude/settings.json"),
        setup =
            c.setup.copy(
                claude = c.setup.claude.copy(settingsPath = "/h/.claude/settings.json"),
                cursor = c.setup.cursor.copy(hooksPath = "/h/.cursor/hooks.json"),
                gemini = c.setup.gemini.copy(dir = "/h/.gemini"),
                kimi = c.setup.kimi.copy(configPath = "/h/.kimi-code/config.toml"),
                codewhale = c.setup.codewhale.copy(dir = "/h/.codewhale"),
                codex = c.setup.codex.copy(configPath = "/h/.codex/config.toml"),
            ),
    )
}

private fun run(
    m: Mock,
    commands: List<String>,
): List<Problem> {
    val m2 = m.copy()
    m2.settings("/h/.claude/settings.json", commands)
    return Hooks.check(cfg(), m2.probes())
}

private fun verdicts(
    m: Mock,
    commands: List<String>,
) = run(m, commands).filter { it.kind != "duplicate-hook" }.map { it.kind to it.detail }

private fun checkWith(m: Mock) = Hooks.check(cfg(), m.probes())

private fun windowsMock() = Mock(win = true, insensitive = true)

/** `(source, keep)` of every duplicate copy, in report order. */
private fun copies(m: Mock) = checkWith(m).filter { it.kind == "duplicate-hook" }.map { it.source to it.keep }

private fun pluginHome(
    m: Mock,
    enabled: Boolean,
    installDir: Boolean,
    hooks: String,
) {
    m.files["/h/.claude/plugins/installed_plugins.json"] =
        """{"plugins": {"demo@mkt": [{"installPath": "/h/plug/demo"}], "off@mkt": [{"installPath": "/h/plug/off"}]}}"""
    m.files["/h/.claude/settings.json"] = """{"enabledPlugins": {"demo@mkt": $enabled, "off@mkt": false}}"""
    if (installDir) {
        m.kinds["/h/plug/demo"] = PathKind.Dir
        m.files["/h/plug/demo/hooks/hooks.json"] = hooks
    }
}

private fun withEnabled(
    doc: String,
    id: String,
): String {
    val o = StrictJson.parse(doc) as JsonObject
    return JsonObject(o + ("enabledPlugins" to buildJsonObject { put(id, true) })).toString()
}

/** What the TOML hosts report, one `agent kind event [matcher] path fixable` line each. */
private fun tomlRows(m: Mock) =
    checkWith(m).map { p ->
        val matcher = p.matcher?.let { " [$it]" }.orEmpty()
        "${p.agent} ${p.kind} ${p.event}$matcher ${p.path} ${p.fixable}"
    }

@Suppress("TooManyFunctions")
class HooksTest {
    @Test fun aMissingScriptIsBrokenAndFixable() {
        val found = run(Mock(), listOf("~/scripts/old-guard.sh"))
        assertEquals(1, found.size)
        assertEquals("broken-hook", found[0].kind)
        assertTrue(found[0].fixable)
        assertEquals("file not found: /h/scripts/old-guard.sh", found[0].detail)
        assertEquals("PreToolUse", found[0].event)
        assertEquals("Bash", found[0].matcher)
        assertEquals("hooks.PreToolUse[0].hooks[0]", found[0].path)
    }

    @Test fun aDanglingSymlinkAndADirectoryAreBroken() {
        val m = Mock()
        val base = join(tempDir(), "rtok-hooks-test")
        val target = join(base, "gone.js")
        m.kinds[join(base, "link.sh")] = PathKind.DanglingSymlink(target)
        m.kinds[join(base, "dir")] = PathKind.Dir
        val v = verdicts(m, listOf("'${join(base, "link.sh")}'", "'${join(base, "dir")}'"))
        assertEquals("broken-hook" to "dangling symlink to $target", v[0])
        assertEquals("broken-hook", v[1].first)
        assertTrue(v[1].second.contains("is a directory"))
    }

    @Test fun aPathOnAnUnmountedVolumeIsBroken() {
        val v = verdicts(Mock(), listOf("/Volumes/X/hook.js"))

        // `/Volumes` is macOS-only; elsewhere the path is ordinary and the missing-file rule reports it.
        @OptIn(kotlin.experimental.ExperimentalNativeApi::class)
        val mac = Platform.osFamily == OsFamily.MACOSX
        val want = if (mac) "path is on /Volumes/X, which is not mounted" else "file not found: /Volumes/X/hook.js"
        assertEquals("broken-hook" to want, v[0])
    }

    @Test fun everyInterpreterWithAMissingScriptIsBrokenAndWithAPresentOneIsFine() {
        val m = Mock()
        m.script("/proj/ok.js", false)
        for (interp in listOf("bash", "sh", "zsh", "node", "python", "python3", "deno run", "bun", "ruby", "pwsh", "npx tsx", "uv run")) {
            val missing = verdicts(m, listOf("$interp /proj/gone.js"))
            assertEquals(1, missing.size, interp)
            assertEquals("broken-hook", missing[0].first, interp)
            assertTrue(missing[0].second.contains("/proj/gone.js"), "$interp: $missing")
            assertTrue(verdicts(m, listOf("$interp /proj/ok.js")).isEmpty(), interp)
        }
    }

    @Test fun aProgramIsLookedUpOnPathAndABuiltinNeedsNoFile() {
        val m = Mock()
        m.path["jq"] = "/usr/bin/jq"
        assertEquals(listOf("broken-hook" to "`foo` not on PATH"), verdicts(m, listOf("jq .", "foo --x", "echo hi", "true")))
    }

    @Test fun variablesExpandAndRelativePathsResolveAgainstTheProject() {
        val m = Mock()
        m.script("/proj/.claude/hooks/a.sh", true)
        m.script("/h/b.sh", true)
        m.script("/proj/rel/c.sh", true)
        m.env["MY_DIR"] = "/proj/rel"
        val v = verdicts(m, listOf("\"\$CLAUDE_PROJECT_DIR\"/.claude/hooks/a.sh", "\$HOME/b.sh", "\${HOME}/b.sh", "./rel/c.sh", "\$MY_DIR/c.sh"))
        assertTrue(v.isEmpty(), "$v")
    }

    @Test fun aQuotedPathWithSpacesIsOneWord() {
        val m = Mock()
        m.script("/h/My Scripts/guard.sh", true)
        assertTrue(verdicts(m, listOf("\"/h/My Scripts/guard.sh\"")).isEmpty())
        assertEquals("file not found: /h/Other Scripts/guard.sh", verdicts(m, listOf("'/h/Other Scripts/guard.sh'"))[0].second)
    }

    @Test fun leadingVariableAssignmentsAreSkipped() {
        val m = Mock()
        m.script("/h/a.sh", true)
        assertTrue(verdicts(m, listOf("RTOK_X=1 /h/a.sh")).isEmpty())
    }

    @Test fun aScriptThatExistsButIsNotExecutableIsSuspectOnlyWhenRunDirectly() {
        val m = Mock()
        m.script("/h/a.sh", false)
        val direct = run(m, listOf("/h/a.sh"))
        assertEquals("suspect-hook", direct[0].kind)
        assertFalse(direct[0].fixable)
        assertTrue(direct[0].detail.contains("chmod +x"))
        m.path["bash"] = "/bin/bash"
        assertTrue(verdicts(m, listOf("bash /h/a.sh")).isEmpty())
    }

    @Test fun whatCannotBeJudgedStaticallyIsUnverifiedAndNeverFixable() {
        val m = Mock()
        for (cmd in listOf(
            "\$(which foo)",
            "`which foo`",
            "eval \"\$X\"",
            "cat x | sh",
            "a && b",
            "\$UNSET_THING/run.sh",
            "\${CLAUDE_PLUGIN_ROOT}/hooks/run.sh",
            "bash -c 'echo hi'",
            "bash",
            "",
            "'unbalanced",
        )) {
            val found = run(m, listOf(cmd))
            assertEquals(1, found.size, cmd)
            assertEquals("unverified-hook", found[0].kind, "$cmd: $found")
            assertFalse(found[0].fixable, cmd)
        }
    }

    @Test fun userLocalAndProjectFilesAreAllRead() {
        val m = Mock()
        m.settings("/h/.claude/settings.json", listOf("/h/a"))
        m.settings("/h/.claude/settings.local.json", listOf("/h/b"))
        m.settings("/proj/.claude/settings.json", listOf("/h/c"))
        m.settings("/proj/.claude/settings.local.json", listOf("/h/d"))
        assertEquals(
            listOf("/h/.claude/settings.json", "/h/.claude/settings.local.json", "/proj/.claude/settings.json", "/proj/.claude/settings.local.json"),
            checkWith(m).map { it.source }.sorted(),
        )
    }

    @Test fun jsoncCommentsAndTheFlatHookShapeAreRead() {
        val m = Mock()
        m.files["/h/.claude/settings.json"] = "{ // user hooks\n \"hooks\": { \"Stop\": [ { \"command\": \"/h/gone.sh\", }, ], },\n}"
        val found = checkWith(m)
        assertEquals(1, found.size)
        assertEquals("hooks.Stop[0]", found[0].path)
        assertEquals("Stop", found[0].event)
    }

    @Test fun anUnparsableOrUnreadableFileIsReportedAndAMissingOneIsNot() {
        val m = Mock()
        m.files["/h/.claude/settings.json"] = "{ nope"
        m.files["/h/.claude/settings.local.json"] = "{}"
        m.unreadable += "/proj/.claude/settings.json"
        val found = checkWith(m)
        assertEquals(listOf("unreadable-config", "unreadable-config"), found.map { it.kind })
        assertTrue(found.all { !it.fixable && it.detail.startsWith("cannot read ") })
    }

    @Test fun aFileWithoutHooksHasNoProblemAndNonCommandHooksAreIgnored() {
        val m = Mock()
        m.files["/h/.claude/settings.json"] = """{"hooks": {"Stop": [{"hooks": [{"type": "prompt", "prompt": "x"}]}]}}"""
        m.files["/h/.claude/settings.local.json"] = "{\"model\": \"x\"}"
        assertTrue(checkWith(m).isEmpty())
    }

    @Test fun anotherHostsJsonHooksAreCheckedUnderItsOwnNameAndASharedFileOnce() {
        val m = Mock()
        m.files["/h/.cursor/hooks.json"] = """{"version": 1, "hooks": {"beforeShellExecution": [{"command": "/h/gone.sh"}]}}"""
        m.files["/h/.gemini/settings.json"] =
            """{"hooks": {"BeforeTool": [{"matcher": "run_shell_command", "hooks": [{"type": "command", "command": "/h/gone2.sh"}]}]}}"""
        val found = checkWith(m)
        assertEquals(
            listOf(Triple("cursor", "beforeShellExecution", true), Triple("gemini", "BeforeTool", true)),
            found.map { Triple(it.agent, it.event, it.fixable) },
        )
        assertEquals("run_shell_command", found[1].matcher)
    }

    @Test fun theHostFilterKeepsOneHostsFindingsAndNamesTheValidIds() {
        val m = Mock()
        m.files["/h/.cursor/hooks.json"] = """{"version": 1, "hooks": {"beforeShellExecution": [{"command": "/h/gone.sh"}]}}"""
        m.files["/h/.gemini/settings.json"] = """{"hooks": {"BeforeTool": [{"hooks": [{"type": "command", "command": "/h/gone2.sh"}]}]}}"""
        m.settings("/h/.claude/settings.json", listOf("/h/gone3.sh"))

        fun agents(only: String?) = Hooks.checkFor(cfg(), m.probes(), only).map { it.agent }
        assertEquals(listOf("claude", "cursor", "gemini"), agents(null))
        assertEquals(listOf("cursor"), agents("cursor"))
        assertEquals(listOf("claude"), agents("claude"))
        assertTrue(agents("zed").isEmpty(), "a host without hooks has none")
        assertEquals("gemini", Hooks.hostId("gemini").getOrThrow())
        val err =
            Hooks
                .hostId("nope")
                .exceptionOrNull()
                ?.message
                .orEmpty()
        assertTrue(err.startsWith("unknown host `nope`; valid hosts: claude, cursor"), err)
    }

    @Test fun windowsWordsKeepBackslashesAndQuotesGroup() {
        fun words(s: String) = HookCommand.splitWords(s, true)!!
        assertEquals(listOf("C:\\hooks\\x.cmd", "a", "b"), words("C:\\hooks\\x.cmd a  b"))
        assertEquals(listOf("C:\\Program Files (x86)\\x.cmd", "--y"), words("\"C:\\Program Files (x86)\\x.cmd\" --y"))
        assertEquals(listOf("a", "b\"c", "'d'"), words("a \"b\\\"c\" 'd'"))
        assertEquals(listOf("a", "", "b"), words("a \"\" b"))
        assertNull(HookCommand.splitWords("a \"b", true), "unbalanced")
    }

    @Test fun aWindowsPathResolvesByPathextIgnoringCase() {
        val m = windowsMock()
        m.script("C:\\Hooks\\guard.cmd", false)
        m.script("C:\\Hooks\\run.EXE", false)
        m.env["PATHEXT"] = ".COM;.EXE;.BAT;.CMD"
        // The extension is optional; the file's own case does not matter; no exec bit exists.
        assertTrue(verdicts(m, listOf("c:\\hooks\\GUARD", "C:\\Hooks\\run", "C:\\hooks\\guard.cmd")).isEmpty())
        assertEquals(listOf("broken-hook" to "file not found: C:\\Hooks\\missing"), verdicts(m, listOf("C:\\Hooks\\missing")))
        // An extension PATHEXT does not list is not tried.
        m.env["PATHEXT"] = ".EXE"
        assertEquals(1, verdicts(m, listOf("C:\\Hooks\\guard")).size)
        assertTrue(verdicts(m, listOf("C:\\Hooks\\guard.cmd")).isEmpty())
    }

    @Test fun aWindowsProgramIsSearchedOnPathAndBuiltinsNeedNoFile() {
        val m = windowsMock()
        m.script("C:\\Tools\\rtok.exe", false)
        m.env["PATH"] = "C:\\bin;c:\\tools\\"
        assertTrue(verdicts(m, listOf("rtok hook PreToolUse", "echo hi", "COPY a b")).isEmpty())
        assertEquals(listOf("broken-hook" to "`nothere` not on PATH"), verdicts(m, listOf("nothere run")))
    }

    @Test fun windowsQuotesVariablesAndLaunchersAreReadByTheirOwnRules() {
        val m = windowsMock()
        m.script("C:\\Program Files (x86)\\rtok\\hook.cmd", false)
        m.script("C:\\Users\\me\\hooks\\x.cmd", false)
        m.script("C:\\h\\run.bat", false)
        m.script("C:\\h\\x.ps1", false)
        m.env["USERPROFILE"] = "C:\\Users\\me"
        assertTrue(
            verdicts(
                m,
                listOf(
                    "\"C:\\Program Files (x86)\\rtok\\hook.cmd\" --x",
                    "%USERPROFILE%\\hooks\\x.cmd",
                    "cmd /c C:\\h\\run.bat arg",
                    "powershell -NoProfile -File C:\\h\\x.ps1",
                ),
            ).isEmpty(),
        )
        val found = verdicts(m, listOf("cmd /c C:\\h\\gone.bat", "powershell -File C:\\h\\gone.ps1", "powershell -Command Get-Date", "C:\\a & C:\\b"))
        assertEquals(listOf("broken-hook", "broken-hook", "unverified-hook", "unverified-hook"), found.map { it.first })
    }

    @Test fun aWindowsRelativePathJoinsTheProjectForClaudeOnly() {
        val m = windowsMock()
        m.script("/proj\\hook.cmd", false)
        assertTrue(verdicts(m, listOf(".\\hook.cmd")).isEmpty())
        assertEquals("broken-hook", verdicts(m, listOf(".\\gone.cmd"))[0].first)
    }

    @Test fun aRelativePathIsNotJudgedForAHostThatDoesNotDocumentItsBase() {
        val m = Mock()
        m.files["/h/.cursor/hooks.json"] = """{"hooks": {"stop": [{"command": "./hooks/a.sh"}, {"command": "/h/gone.sh"}]}}"""
        val found = checkWith(m)
        assertEquals("unverified-hook", found[0].kind)
        assertTrue(found[0].detail.startsWith("relative path"))
        assertFalse(found[0].fixable)
        assertEquals("broken-hook", found[1].kind)
        // Claude Code runs hooks in the project directory.
        m.settings("/h/.claude/settings.json", listOf("./hooks/a.sh"))
        val claude = checkWith(m)
        assertEquals("claude", claude[0].agent)
        assertEquals("file not found: /proj/hooks/a.sh", claude[0].detail)
    }

    @Test fun aPluginHookResolvesThePluginRootAndIsNeverFixable() {
        val m = Mock()
        val hooks =
            """{"hooks": {"SessionStart": [{"hooks": [
            {"type": "command", "command": "${'$'}{CLAUDE_PLUGIN_ROOT}/scripts/ok.sh"},
            {"type": "command", "command": "bash \"${'$'}{CLAUDE_PLUGIN_ROOT}/scripts/gone.sh\" start"}]}]}}"""
        pluginHome(m, true, true, hooks)
        m.script("/h/plug/demo/scripts/ok.sh", true)
        // A disabled plugin is not loaded, so its hooks are not checked.
        m.files["/h/plug/off/hooks/hooks.json"] = """{"hooks": {"Stop": [{"command": "/h/never.sh"}]}}"""
        val found = checkWith(m)
        assertEquals(1, found.size, "$found")
        assertEquals("broken-hook", found[0].kind)
        assertEquals("file not found: /h/plug/demo/scripts/gone.sh", found[0].detail)
        assertEquals("/h/plug/demo/hooks/hooks.json", found[0].source)
        assertFalse(found[0].fixable)
    }

    @Test fun theInstallDirectoryOfEachEnabledPresentPluginIsReturned() {
        val m = Mock()
        pluginHome(m, true, true, "{}")
        assertEquals(listOf("demo@mkt" to "/h/plug/demo"), Hooks.checkWithPlugins(cfg(), m.probes()).second)
        // Disabled, and enabled but gone: nothing for a later check to read.
        pluginHome(m, false, true, "{}")
        assertTrue(Hooks.checkWithPlugins(cfg(), m.probes()).second.isEmpty())
        pluginHome(m, true, false, "")
        m.kinds.clear()
        assertTrue(Hooks.checkWithPlugins(cfg(), m.probes()).second.isEmpty())
    }

    /** T925: Cursor's plugin link into a ketch store version an upgrade removed loads nothing; doctor says so. */
    @Test fun aDanglingHostPluginLinkIsReportedAsAStalePlugin() {
        val home = join(tempDir(), "rtok-doctor-plugin-${kotlin.random.Random.nextLong().toULong()}")
        val local = join(home, ".cursor/plugins/local")
        createDirAll(local)
        try {
            symlink(join(home, ".ketch/store/rtok/v0.15.1/plugins/cursor"), join(local, "rtok"))
            val base = cfg()
            val c = base.copy(setup = base.setup.copy(cursor = base.setup.cursor.copy(hooksPath = join(home, ".cursor/hooks.json"))))
            val m = Mock()
            val found = Hooks.check(c, m.probes())
            assertEquals(1, found.size, "$found")
            assertEquals("stale-plugin", found[0].kind)
            assertEquals("cursor", found[0].agent)
            assertTrue(found[0].detail.contains("v0.15.1"), found[0].detail)
            assertTrue(found[0].detail.contains("agents install cursor"))
            assertFalse(found[0].fixable)
            removeFile(join(local, "rtok"))
            assertTrue(Hooks.check(c, m.probes()).isEmpty(), "nothing installed, nothing to report")
        } finally {
            removeDirAll(home)
        }
    }

    @Test fun anEnabledPluginWhoseDirectoryIsGoneIsStaleAndADisabledOneIsIgnored() {
        val m = Mock()
        pluginHome(m, true, false, "")
        val found = checkWith(m)
        assertEquals(1, found.size, "$found")
        assertEquals("stale-plugin", found[0].kind)
        assertEquals("plugins.demo@mkt[0]", found[0].path)
        assertTrue(found[0].detail.contains("/h/plug/demo is gone"))
        assertFalse(found[0].fixable)
        pluginHome(m, false, false, "")
        assertTrue(checkWith(m).isEmpty())
    }

    @Test fun pluginRootOutsideAPluginIsStillUnverified() {
        assertEquals("unverified-hook", run(Mock(), listOf("\${CLAUDE_PLUGIN_ROOT}/x.sh"))[0].kind)
    }

    @Test fun aForeignNonJsonHostFileIsLeftAlone() {
        val m = Mock()
        m.files["/h/.cursor/hooks.json"] = "{ nope"
        val found = checkWith(m)
        assertEquals(1, found.size)
        assertEquals("unreadable-config" to "cursor", found[0].kind to found[0].agent)
    }

    @Test fun theSameHookInUserAndProjectSettingsIsADuplicateAndTheSharedCopyIsKept() {
        val m = Mock()
        val doc = hooksDoc("PreToolUse", "Bash", listOf("jq ."))
        m.files["/h/.claude/settings.json"] = doc
        m.files["/proj/.claude/settings.json"] = doc
        m.path["jq"] = "/usr/bin/jq"
        val d = checkWith(m).filter { it.kind == "duplicate-hook" }
        assertEquals(2, d.size)
        assertEquals(d[0].group, d[1].group)
        assertTrue(d[0].detail.startsWith("runs 2 times"))
        assertTrue(d.all { it.fixable }, "both files are the user's to edit")
        assertEquals(listOf("/h/.claude/settings.json" to false, "/proj/.claude/settings.json" to true), copies(m))
    }

    @Test fun aUserFileWinsOverALocalOneAndARepeatInOneFileKeepsTheFirst() {
        val m = Mock()
        m.files["/h/.claude/settings.local.json"] = hooksDoc("Stop", null, listOf("jq ."))
        m.files["/h/.claude/settings.json"] = hooksDoc("Stop", null, listOf("jq .", "jq ."))
        m.path["jq"] = "/usr/bin/jq"
        val d = checkWith(m).filter { it.kind == "duplicate-hook" }
        assertEquals(3, d.size)
        assertTrue(d[0].detail.startsWith("runs 3 times"))
        assertEquals(
            listOf("hooks.Stop[0].hooks[0]" to true, "hooks.Stop[0].hooks[1]" to false, "hooks.Stop[0].hooks[0]" to false),
            d.map { it.path to it.keep },
        )
        assertEquals("/h/.claude/settings.local.json", d[2].source)
    }

    @Test fun aPluginCopyIsKeptOverAHandWrittenOneAndIsNeverTheExtra() {
        val m = Mock()
        m.files["/h/.claude/plugins/installed_plugins.json"] = """{"plugins": {"demo@mkt": [{"installPath": "/h/plug/demo"}]}}"""
        m.files["/h/.claude/settings.json"] = withEnabled(hooksDoc("PreToolUse", "Bash", listOf("jq .")), "demo@mkt")
        m.kinds["/h/plug/demo"] = PathKind.Dir
        m.files["/h/plug/demo/hooks/hooks.json"] = hooksDoc("PreToolUse", "Bash", listOf("jq ."))
        m.path["jq"] = "/usr/bin/jq"
        assertEquals(listOf("/h/.claude/settings.json" to false, "/h/plug/demo/hooks/hooks.json" to true), copies(m))
    }

    @Test fun onlyAHandWrittenExtraIsFixableNeverAPluginCopy() {
        val m = Mock()
        m.files["/h/.claude/plugins/installed_plugins.json"] = """{"plugins": {"demo@mkt": [{"installPath": "/h/plug/demo"}]}}"""
        m.files["/h/.claude/settings.json"] = withEnabled(hooksDoc("Stop", null, listOf("jq .")), "demo@mkt")
        m.kinds["/h/plug/demo"] = PathKind.Dir
        // The plugin repeats the hook inside its own file too: an extra, but not the user's to edit.
        m.files["/h/plug/demo/hooks/hooks.json"] = hooksDoc("Stop", null, listOf("jq .", "jq ."))
        m.path["jq"] = "/usr/bin/jq"
        assertEquals(
            listOf(
                Triple("/h/.claude/settings.json", false, true),
                Triple("/h/plug/demo/hooks/hooks.json", true, false),
                Triple("/h/plug/demo/hooks/hooks.json", false, false),
            ),
            checkWith(m).filter { it.kind == "duplicate-hook" }.map { Triple(it.source, it.keep, it.fixable) },
        )
    }

    @Test fun whatDiffersInEventMatcherCommandAgentOrLoadStateIsNotADuplicate() {
        val m = Mock()
        m.path["jq"] = "/usr/bin/jq"
        m.path["fmt"] = "/usr/bin/fmt"
        // Different events, different matchers, different commands.
        m.files["/h/.claude/settings.json"] =
            """{"hooks": {
                "Stop": [{"hooks": [{"type": "command", "command": "jq ."}]}],
                "SessionEnd": [{"hooks": [{"type": "command", "command": "jq ."}]}],
                "PreToolUse": [
                    {"matcher": "Bash", "hooks": [{"type": "command", "command": "jq ."}]},
                    {"matcher": "Edit", "hooks": [{"type": "command", "command": "jq ."}]},
                    {"matcher": "Bash", "hooks": [{"type": "command", "command": "jq -r ."}]}
                ]}}"""
        // The same command under another agent is a different process tree.
        m.files["/h/.cursor/hooks.json"] = """{"hooks": {"stop": [{"command": "jq ."}]}}"""
        m.files["/h/.gemini/settings.json"] = """{"hooks": {"Stop": [{"hooks": [{"type": "command", "command": "jq ."}]}]}}"""
        // A disabled plugin is not loaded, so its copy does not run.
        m.files["/h/.claude/plugins/installed_plugins.json"] = """{"plugins": {"off@mkt": [{"installPath": "/h/plug/off"}]}}"""
        m.kinds["/h/plug/off"] = PathKind.Dir
        m.files["/h/plug/off/hooks/hooks.json"] = hooksDoc("Stop", null, listOf("jq ."))
        assertTrue(copies(m).isEmpty(), "${checkWith(m)}")
    }

    @Test fun copiesThatDifferOnlyInSpellingAreDuplicates() {
        val m = Mock()
        m.path["jq"] = "/usr/bin/jq"
        // Matcher: order, spacing, and "no matcher" equal `*`.
        m.files["/h/.claude/settings.json"] =
            """{"hooks": {"PreToolUse": [
                {"matcher": "Bash|Edit", "hooks": [{"type": "command", "command": "jq  -r  ."}]},
                {"matcher": " Edit | Bash ", "hooks": [{"type": "command", "command": "jq -r '.'", "timeout": 5}]},
                {"hooks": [{"type": "command", "command": "jq a"}]},
                {"matcher": "*", "hooks": [{"type": "command", "command": "jq \"a\""}]}
            ]}}"""
        val found = checkWith(m)
        val d = found.filter { it.kind == "duplicate-hook" }
        assertEquals(4, d.size, "$found")
        assertEquals(2, d.count { it.group == 0 })
        assertEquals(2, d.count { it.group == 1 })
    }

    @Test fun variablesExpandBeforeCopiesAreCompared() {
        val m = Mock()
        m.path["jq"] = "/usr/bin/jq"
        m.script("/h/hooks/a.sh", true)
        m.files["/h/.claude/settings.json"] = hooksDoc("Stop", null, listOf("/h/hooks/a.sh", "\$HOME/hooks/a.sh", "~/hooks/a.sh"))
        assertEquals(3, copies(m).size)
    }

    @Test fun aRelativePathEqualsItsAbsoluteFormForClaudeOnly() {
        val m = Mock()
        m.script("/proj/hooks/a.sh", true)
        m.files["/h/.claude/settings.json"] = hooksDoc("Stop", null, listOf("./hooks/a.sh", "/proj/hooks/a.sh"))
        assertEquals(2, copies(m).size)
    }

    @Test fun theTextListsEachGroupOnceWithItsCopiesAndSaysNoneFound() {
        assertEquals("duplicate hooks none found\n", Dupes.render(emptyList()))
        val m = Mock()
        val doc = hooksDoc("Stop", null, listOf("jq ."))
        m.files["/h/.claude/settings.json"] = doc
        m.files["/proj/.claude/settings.json"] = doc
        m.path["jq"] = "/usr/bin/jq"
        val text = Hooks.render(checkWith(m))
        assertTrue(text.contains("hooks check none found\n"), text)
        assertTrue(text.contains("duplicate hooks\n  claude Stop `jq .`: runs 2 times\n"), text)
        assertEquals(1, Regex("runs 2 times").findAll(text).count())
        assertTrue(text.contains("    keep "), text)
        assertTrue(text.contains("    extra "), text)
    }

    @Test fun theTextGroupsByClassAndSaysNoneFound() {
        assertTrue(Hooks.render(emptyList()).startsWith("hooks check none found\n"))
        val text = Hooks.render(run(Mock(), listOf("/h/gone.sh", "\$(x)")))
        assertTrue(text.startsWith("hooks check\n"), text)
        assertTrue(text.contains("broken claude PreToolUse[Bash] `/h/gone.sh`: file not found: /h/gone.sh (can be cleaned up)"), text)
        assertTrue(text.contains("cannot verify claude PreToolUse[Bash] `\$(x)`"), text)
        assertTrue(text.contains("/h/.claude/settings.json hooks.PreToolUse[0].hooks[0]"), text)
    }

    @Test fun kimiTomlHooksAreCheckedByTheirArrayIndexAndNeverFixable() {
        val m = Mock()
        m.script("/h/ok.sh", true)
        m.files["/h/.kimi-code/config.toml"] =
            """
theme = "dark"

[[hooks]]
event = "PreToolUse"
matcher = "Bash"
command = "/h/gone.sh"
timeout = 5

[[hooks]]
event = "Stop"
command = "/h/ok.sh"

[[hooks]]
event = "SessionStart"
matcher = ""
command = "echo ${'$'}(date)"
"""
        assertEquals(
            listOf("kimi broken-hook PreToolUse [Bash] hooks[0] false", "kimi unverified-hook SessionStart hooks[2] false"),
            tomlRows(m),
        )
    }

    @Test fun codewhaleTomlHooksUseTheNestedHooksHooksTable() {
        val m = Mock()
        m.files["/h/.codewhale/config.toml"] =
            """
[hooks]
enabled = true

[[hooks.hooks]]
name = "rtok"
event = "message_submit"
command = "/h/gone/rtok-hook UserPromptSubmit"
timeout_secs = 5
"""
        assertEquals(listOf("codewhale broken-hook message_submit hooks.hooks[0] false"), tomlRows(m))
    }

    @Test fun codexInlineTomlHooksKeepTheSettingsShape() {
        val m = Mock()
        m.script("/h/ok.sh", true)
        m.files["/h/.codex/config.toml"] =
            """
model = "gpt"

[[hooks.PreToolUse]]
matcher = "^Bash$"

[[hooks.PreToolUse.hooks]]
type = "command"
command = "/h/gone.sh"
timeout = 30

[[hooks.PreToolUse.hooks]]
type = "command"
command = "/h/ok.sh"

[[hooks.Stop.hooks]]
type = "mcp_tool"
command = "/h/never-looked-at.sh"
"""
        assertEquals(listOf("codex broken-hook PreToolUse [^Bash$] hooks.PreToolUse[0].hooks[0] false"), tomlRows(m))
    }

    @Test fun anUnparsableTomlIsReportedAndAValidOneWithoutHooksIsQuiet() {
        val m = Mock()
        m.files["/h/.kimi-code/config.toml"] = "[[hooks]\nevent = "
        m.files["/h/.codex/config.toml"] = "model = \"gpt\"\n"
        val found = checkWith(m)
        assertEquals(1, found.size)
        assertEquals("unreadable-config" to "kimi", found[0].kind to found[0].agent)
        assertFalse(found[0].fixable)
    }

    @Test fun aTomlOfAHostWithNoDocumentedHookShapeIsNotRead() {
        val base = cfg()
        val c = base.copy(setup = base.setup.copy(grok = base.setup.grok.copy(configPath = "/h/.grok/config.toml")))
        val m = Mock()
        m.files["/h/.grok/config.toml"] = "[[hooks]]\nevent = \"Stop\"\ncommand = \"/h/gone.sh\"\n"
        val found = Hooks.check(c, m.probes())
        assertTrue(found.isEmpty(), "$found")
    }
}
