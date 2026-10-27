@file:Suppress("MagicNumber")

// Port of the src/doctor.rs tests for the report renderers, the advice and the skills and instruction audits.

package rtok.doctor

import kotlinx.serialization.json.JsonElement
import rtok.agents.Skills
import rtok.config.Validate
import rtok.json.StrictJson
import rtok.support.Vfs
import rtok.support.tmpDir
import rtok.support.worktreeLayout
import rtok.sys.createDirAll
import rtok.sys.removeDirAll
import rtok.sys.symlink
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("TooManyFunctions")
class DoctorReportTest {
    private fun json(s: String): JsonElement = StrictJson.parse(s)

    @Test
    fun toonPinnedOffIsAConfigNote() {
        val dir = tmpDir("t381-toon")
        val path = "$dir/config.toml"
        writeString(path, "[plugins.toon]\nenabled = false\n")
        val notes = Validate.pinnedNotes(path)
        assertTrue(
            notes.any { "plugins.toon.enabled" in it && "= false" in it && "default true" in it && ":2:" in it },
            "$notes",
        )
        val text = Report(configNotes = notes).toText()
        assertTrue("config\n  note " in text, text)
        writeString(path, "[plugins.toon]\nenabled = true\n")
        assertTrue(Validate.pinnedNotes(path).isEmpty(), "the current default is not a note")
        removeDirAll(dir)
    }

    private fun vfsAudit(
        vfs: Vfs,
        roots: List<Pair<String, List<String>>>,
        invocations: Map<String, Long>?,
    ): SkillsAudit =
        SkillsAuditProbe.auditFrom(
            roots,
            { p -> runCatching { vfs.readToString(p) }.getOrNull() },
            { d -> vfs.keys().filter { it.startsWith("$d/") && it.endsWith("/SKILL.md") }.map { it.removeSuffix("/SKILL.md") } },
            invocations,
        )

    @Test
    fun skillsAuditFlagsTheMeasuredWarnsOnAVfsTree() {
        val vfs = Vfs()
        vfs.write("home/.claude/skills/tiny/SKILL.md", "---\ndescription: small\n---\nbody\n")
        vfs.write("home/.claude/skills/wordy/SKILL.md", "---\ndescription: ${"w".repeat(201)}\n---\nbody\n")
        vfs.write("home/.claude/skills/big/SKILL.md", "---\ndescription: fine\n---\n${"b".repeat(9000)}\n")
        vfs.write("home/.claude/plugins/cache/x/y/1.0/skills/plug/SKILL.md", "---\ndescription: from a plugin\n---\nbody\n")
        val roots =
            listOf(
                "user" to listOf("home/.claude/skills"),
                "plugin" to listOf("home/.claude/plugins/cache/x/y/1.0/skills [x@market]"),
            )
        val audit = vfsAudit(vfs, roots, mapOf("tiny" to 8L))
        assertEquals(listOf("big", "plug", "tiny", "wordy"), audit.rows.map { it.name })
        val wordy = audit.rows.first { it.name == "wordy" }
        assertTrue(wordy.warnDesc && wordy.warnNever, "$wordy")
        assertTrue(audit.rows.first { it.name == "big" }.warnBody)
        val tiny = audit.rows.first { it.name == "tiny" }
        assertEquals(8L, tiny.invocations)
        assertFalse(tiny.warnNever || tiny.warnBody || tiny.warnDesc, "$tiny")
        assertEquals("plugin:x@market", audit.rows.first { it.name == "plug" }.source)
        assertEquals(223L, audit.descBytes)
        assertTrue(vfsAudit(vfs, roots, null).rows.none { it.warnNever })
    }

    @Test
    fun skillsAuditFlagsANameListedFromTwoRoots() {
        val vfs = Vfs()
        val same = "---\ndescription: shared body\n---\n# s\n"
        vfs.write("home/.claude/skills/twin/SKILL.md", same)
        vfs.write("proj/.agents/skills/twin/SKILL.md", same)
        vfs.write("home/.agents/skills/twin/SKILL.md", same)
        vfs.write("home/.claude/skills/fork/SKILL.md", "---\ndescription: the old copy\n---\n# a\n")
        vfs.write("plug/skills/fork/SKILL.md", "---\ndescription: newer copy ok\n---\n# b\n")
        vfs.write("home/.claude/skills/solo/SKILL.md", "---\ndescription: alone\n---\n# c\n")
        val roots =
            listOf(
                "user" to listOf("home/.claude/skills", "home/.agents/skills"),
                "project" to listOf("proj/.agents/skills", "home/.claude/skills"),
                "plugin" to listOf("plug/skills [x@market]"),
            )
        val audit = vfsAudit(vfs, roots, null)
        assertEquals(listOf("fork", "twin"), audit.duplicates.map { it.name })
        val fork = audit.duplicates[0]
        assertFalse(fork.identical)
        assertEquals(3L, fork.extraTokens)
        assertEquals(listOf("user" to "home/.claude/skills/fork", "plugin:x@market" to "plug/skills/fork"), fork.copies)
        val twin = audit.duplicates[1]
        assertTrue(twin.identical)
        assertEquals(2L, twin.extraTokens)
        assertEquals(listOf("user" to "home/.agents/skills/twin", "project" to "proj/.agents/skills/twin"), twin.copies)
        val text = Report(skills = audit).toText()
        assertTrue(
            "WARN duplicate skill `fork`: 2 listings, bodies differ, extra listings ≈ 3 tokens per request\n" +
                "    user home/.claude/skills/fork\n    plugin:x@market plug/skills/fork\n" in text,
            text,
        )
        assertTrue("`twin`: 2 listings, bodies identical" in text, text)
    }

    private fun duplicateNames(vararg roots: String): List<String> {
        val vfs = Vfs()
        for (r in roots) vfs.write("${r.substringBefore(" [")}/dup/SKILL.md", "---\ndescription: d\n---\n# d\n")
        return vfsAudit(vfs, listOf("user" to roots.toList()), null).duplicates.map { it.name }
    }

    @Test
    fun aSkillInClaudeAndAgentsRootsIsNotADuplicate() {
        assertTrue(duplicateNames("home/.claude/skills", "home/.agents/skills").isEmpty())
        assertTrue(duplicateNames("home/.codex/skills", "home/.claude/skills").isEmpty())
    }

    @Test
    fun aSkillInUserAndProjectAgentsRootsIsADuplicate() {
        assertEquals(listOf("dup"), duplicateNames("home/.agents/skills", "proj/.agents/skills"))
    }

    @Test
    fun aSkillInTheClaudeRootAndAPluginIsADuplicate() {
        assertEquals(listOf("dup"), duplicateNames("home/.claude/skills", "plug/skills [x@market]"))
    }

    private fun row(
        name: String,
        descChars: Int,
    ) = SkillRow(name, "user", descChars, 100, warnDesc = descChars > Skills.SKILL_DESC_MAX)

    @Test
    fun skillsAuditPrintsRepeatLoads() {
        val repeats = SkillsAuditProbe.repeatRows(mapOf("a" to (1L to 5L), "rtok" to (3L to 2200L)))
        val text = Report(skills = SkillsAudit(rows = listOf(row("rtok", 10)), repeats = repeats)).toText()
        val big = text.indexOf("repeat load `rtok`: 3 times in one context window ≈ 2200 tokens")
        val small = text.indexOf("repeat load `a`: 1 times")
        assertTrue(big >= 0 && small > big, text)
    }

    @Test
    fun overlapChecksNameTheRtokOffKeyAndStayOffWhenQuiet() {
        val lines = Advice.overlapLines(true, true, false, listOf("claude", "opencode", "cursor"))
        assertEquals(3, lines.size, "$lines")
        assertTrue("[plugins.memory] enabled = false" in lines[0])
        assertTrue("[plugins.archive] enabled = false" in lines[1] && "[plugins.archive] enabled = false" in lines[2])
        for (l in lines) assertTrue(l.startsWith("duplicate:") && "saves" !in l, l)
        assertTrue(Advice.overlapLines(false, true, false, listOf("opencode")).isEmpty())
        assertTrue(Advice.overlapLines(true, false, true, listOf("claude")).isEmpty())
        assertTrue(Advice.overlapLines(true, true, false, emptyList()).isEmpty())
        val text = Report(overlaps = Advice.overlapLines(true, true, false, listOf("opencode"))).toText()
        assertTrue("overlaps\n  duplicate: OpenCode" in text, text)
        val sync = Advice.overlapLines(false, true, true, emptyList())
        assertEquals(1, sync.size)
        assertTrue("[plugins.memory] enabled = false" in sync[0] && sync[0].startsWith("duplicate:") && "saves" !in sync[0])
    }

    @Test
    fun skillsAuditRendersASectionWithFlags() {
        val r = SkillRow("update-config", "user", 205, 248_175, 4, warnDesc = true, warnBody = true)
        val text = Report(skills = SkillsAudit(listOf(r), 205)).toText()
        assertTrue("skills (1 listed, 205 desc bytes ≈ 51 tokens per request)" in text, text)
        assertTrue("update-config user desc 205c body 248175B calls 4" in text, text)
        assertTrue("WARN desc>120" in text && "WARN body>8K (references/)" in text, text)
    }

    @Test
    fun skillsAuditPricesTheDescriptionCap() {
        val render = { rows: List<SkillRow> -> Report(skills = SkillsAudit(rows, rows.sumOf { it.descChars.toLong() })).toText() }
        val text = render(listOf(row("long", 200), row("short", 100)))
        assertTrue("  descriptions over 120 chars: 1 skills, ≈ 20 tokens/request recoverable\n" in text, text)
        assertTrue("long user desc 200c" in text && "WARN desc>120" in text, text)
        assertFalse("descriptions over" in render(listOf(row("cap", 120), row("short", 100))))
    }

    @Test
    fun countsNestedHookCommands() {
        val c =
            Advice.countHooks(
                json(
                    """{"hooks":{"PreToolUse":[{"matcher":"Bash","hooks":[{"type":"command"},{"type":"command"}]},""" +
                        """{"hooks":[{"type":"command"}]}]}}""",
                ),
            )
        assertEquals(3, c.total)
        assertEquals(3, c.byEvent["PreToolUse"])
    }

    @Test
    fun missingHookClientIsAdvisedOnlyWhenHooksPreferIt() {
        val cmd = "command -v rtok-hook >/dev/null 2>&1 && exec rtok-hook PreToolUse; exec rtok hook PreToolUse"
        val withClient = """{"hooks":{"PreToolUse":[{"hooks":[{"type":"command","command":"$cmd"}]}]}}"""
        val without = """{"hooks":{"PreToolUse":[{"hooks":[{"type":"command","command":"rtok hook PreToolUse"}]}]}}"""
        assertTrue(Advice.countHooks(json(withClient)).prefersClient)
        assertFalse(Advice.countHooks(json(without)).prefersClient)
        val dir = tmpDir("t349-path")
        val bin = "$dir/bin"
        createDirAll(bin)
        val line = assertNotNull(Advice.hookClientAdvice(true, bin))
        assertTrue("rtok-hook" in line && "slower" in line, line)
        assertNotNull(Advice.hookClientAdvice(true, null), "no PATH at all")
        assertNull(Advice.hookClientAdvice(false, bin))
        writeString("$bin/rtok-hook", "")
        assertNull(Advice.hookClientAdvice(true, bin))
        removeDirAll(dir)
    }

    @Test
    fun anthropicBasePrefersSettingsAndIgnoresEmpty() {
        val s = json("""{"env": {"ANTHROPIC_BASE_URL": "http://a"}}""")
        val empty = json("""{"env": {"ANTHROPIC_BASE_URL": ""}}""")
        assertEquals("http://a", Advice.anthropicBase(s, ""))
        assertEquals("http://a", Advice.anthropicBase(s, "http://b"))
        assertEquals("http://b", Advice.anthropicBase(empty, "http://b"))
        assertNull(Advice.anthropicBase(null, ""))
    }

    @Test
    fun anthropicBaseIgnoresTheDefaultEndpoint() {
        val plain = json("""{"env": {"ANTHROPIC_BASE_URL": "https://api.anthropic.com"}}""")
        val slash = json("""{"env": {"ANTHROPIC_BASE_URL": "https://api.anthropic.com/"}}""")
        val custom = json("""{"env": {"ANTHROPIC_BASE_URL": "http://127.0.0.1:8790"}}""")
        assertNull(Advice.anthropicBase(plain, null))
        assertNull(Advice.anthropicBase(slash, null))
        assertNull(Advice.anthropicBase(null, "https://api.anthropic.com"))
        assertEquals("http://127.0.0.1:8790", Advice.anthropicBase(custom, null))
        assertNull(Advice.anthropicBase(plain, "http://a"))
    }

    @Test
    fun toolSearchFollowsTheOverrideNotTheBaseUrlAlone() {
        val ts = { settings: JsonElement?, env: String, custom: Boolean -> Advice.resolveToolSearch(settings, env, custom) }
        assertEquals(ToolSearch(ToolSearchState.Unknown, "heuristic: ANTHROPIC_BASE_URL set"), ts(null, "", true))
        assertEquals(ToolSearch(ToolSearchState.Enabled, "ENABLE_TOOL_SEARCH=true in environment"), ts(null, "true", true))
        for (on in listOf("auto", "AUTO:5", " auto:100 ")) assertEquals(ToolSearchState.Enabled, ts(null, on, true).state, on)
        assertEquals(ToolSearchState.Disabled, ts(null, "false", true).state)
        assertEquals(ToolSearchState.Disabled, ts(null, "false", false).state)
        for (bad in listOf("auto:101", "auto:", "0", "yes")) {
            assertEquals(ToolSearchState.Unknown, ts(null, bad, true).state, bad)
            assertEquals(ToolSearch(), ts(null, bad, false), bad)
        }
        assertEquals(ToolSearch(), ts(null, "", false))
        assertEquals("", ToolSearch().render())
        val off = json("""{"env": {"ENABLE_TOOL_SEARCH": "false"}}""")
        assertEquals(ToolSearch(ToolSearchState.Disabled, "ENABLE_TOOL_SEARCH=false in settings.json env"), ts(off, "true", true))
        assertEquals(ToolSearchState.Enabled, ts(json("""{"env": {"ENABLE_TOOL_SEARCH": ""}}"""), "true", true).state)
        assertEquals(ToolSearchState.Enabled, ts(json("""{"env": {"ENABLE_TOOL_SEARCH": "maybe"}}"""), "true", true).state)
        assertEquals(ToolSearchState.Disabled, ts(json("""{"env": {"ENABLE_TOOL_SEARCH": false}}"""), "", false).state)
    }

    @Test
    fun toolSearchRendersStateAndSource() {
        assertEquals("mcp_tool_search unknown (heuristic: ANTHROPIC_BASE_URL set)\n", Advice.resolveToolSearch(null, null, true).render())
        val t = Advice.resolveToolSearch(null, "true", true)
        assertEquals("mcp_tool_search enabled (ENABLE_TOOL_SEARCH=true in environment)\n", t.render())
        assertFalse(t.likelyOff())
    }

    @Test
    fun aSymlinkedInstructionFileIsOneSource() {
        val dir = tmpDir("doctor-link")
        writeString("$dir/AGENTS.md", "one rule\n")
        symlink("AGENTS.md", "$dir/CLAUDE.md")
        val srcs = mutableListOf<InstructionAudit.Source>()
        InstructionAudit.pushFile(srcs, "claude-project", "$dir/CLAUDE.md")
        InstructionAudit.pushFile(srcs, "agents-project", "$dir/AGENTS.md")
        removeDirAll(dir)
        assertEquals(1, srcs.size)
        assertTrue(InstructionAudit.duplicates(srcs).isEmpty())
    }

    @Test
    fun projectInstructionsComeFromTheLinkedWorktree() {
        val dir = tmpDir("t660-doctor")
        val (main, wt) = worktreeLayout(dir)
        createDirAll("$wt/src")
        writeString("$main/CLAUDE.md", "main rules")
        writeString("$wt/CLAUDE.md", "worktree rules")
        val srcs = mutableListOf<InstructionAudit.Source>()
        InstructionAudit.pushProjectFiles(srcs, "$wt/src")
        assertEquals(listOf("worktree rules"), srcs.map { it.text })
        removeDirAll(dir)
    }

    @Test
    fun repeatedLongLinesAreDuplicatesAcrossSources() {
        val long = "x".repeat(70)
        val srcs = listOf(InstructionAudit.Source("b", "/b", "$long\nshort"), InstructionAudit.Source("a", "/a", long))
        assertEquals(listOf("x".repeat(60) to listOf("a", "b")), InstructionAudit.duplicates(srcs))
        val text = Report(instructions = Instructions(listOf(InstructionRow("a", 2000, "/a", true)), InstructionAudit.duplicates(srcs))).toText()
        assertTrue("instructions\n  a 2000 tokens /a WARN\n  duplicate `${"x".repeat(60)}` in a, b\n" in text, text)
    }

    @Test
    fun toolsRewriteAdvice() {
        val a = assertNotNull(Advice.toolsRewriteAdvice(true, "8790→api.anthropic.com", 8790, 2500, false, 2000))
        assertTrue("2500" in a && "[proxy.tools_rewrite]" in a, a)
        assertNotNull(Advice.toolsRewriteAdvice(true, "localhost:8790→x:443", 8790, 2500, false, 2000))
        assertNull(Advice.toolsRewriteAdvice(false, "8790→api.anthropic.com", 8790, 2500, false, 2000))
        assertNull(Advice.toolsRewriteAdvice(true, "8788→api.anthropic.com", 8790, 2500, false, 2000))
        assertNull(Advice.toolsRewriteAdvice(true, "8790→api.anthropic.com", 8790, 2500, true, 2000))
        assertNull(Advice.toolsRewriteAdvice(true, "8790→api.anthropic.com", 8790, 1500, false, 2000))
    }

    @Test
    fun alwaysLoadAdviceNamesTheSettingAndItsPrice() {
        val rtok = ServerInfo("rtok", "rtok", 25, 1400)
        val entry = json("""{"command": "rtok", "args": ["mcp"]}""")
        val line = assertNotNull(Advice.alwaysLoadAdvice(entry, false, rtok))
        assertTrue("\"alwaysLoad\": true" in line && "25 tools (~1400 tokens)" in line, line)
        assertNull(Advice.alwaysLoadAdvice(json("""{"command": "rtok", "alwaysLoad": true}"""), false, rtok))
        assertNull(Advice.alwaysLoadAdvice(null, false, rtok))
        assertNull(Advice.alwaysLoadAdvice(entry, true, rtok))
        assertNull(Advice.alwaysLoadAdvice(entry, false, null))
    }

    @Test
    fun theCompactTextIsOneLinePerVariantAndJunkPointsAtTheList() {
        val text = Report(junkBytes = 2L shl 30).toCompactText()
        assertTrue(text.startsWith("hooks 0\nhooks check none found\n") && "mcp\nproxy \nproxy openai \nBASH_MAX_OUTPUT_LENGTH (unset)\n" in text, text)
        assertTrue("agents (yes installed" in text && "(run `rtok agents junk list`)" in text, text)
    }
}
