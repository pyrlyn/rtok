@file:Suppress("MagicNumber")

// Port of the file-writing tests of src/doctor/fix.rs over the in-memory [FixMachine]. The two proptests run as
// seeded random loops (64 cases each), since the Kotlin side has no property-testing dependency.

package rtok.doctor

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.doctor.FixMachine.Companion.BROKEN
import rtok.doctor.FixMachine.Companion.PROJECT_DUP
import rtok.doctor.FixMachine.Companion.SETTINGS
import rtok.doctor.FixMachine.Companion.USER_DUP
import rtok.doctor.FixMachine.Companion.cfg
import rtok.doctor.FixMachine.Companion.machine
import rtok.mcp.config.Jsonc
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Suppress("LargeClass", "TooManyFunctions")
class FixTest {
    private fun fix(
        m: FixMachine,
        apply: Boolean,
    ) = Fix.fixBroken(cfg(), m.probes(), m, apply, 3)

    private fun fixAll(
        m: FixMachine,
        apply: Boolean,
        only: List<String> = Fix.KINDS,
    ) = Fix.fixFor(cfg(), m.probes(), m, apply, 3, null, only)

    @Test
    fun theExtraCopyOfADuplicateHookGoesAndTheKeptOneStays() {
        val m = machine(USER_DUP)
        m.put("/proj/.claude/settings.json", PROJECT_DUP)
        val r = fixAll(m, true)
        assertEquals(1, r.files.size, "$r")
        assertEquals(Outcome.Written, r.files[0].outcome)
        // The project's shared copy is kept; the user's copy loses its group and event too.
        assertEquals("{\n  // mine\n  \"theme\": \"dark\",\n  \"hooks\": {}\n}\n", m.text(SETTINGS))
        assertEquals(PROJECT_DUP, m.text("/proj/.claude/settings.json"))
        assertEquals(1, m.backups.size)
        assertEquals(0, r.left)
        assertTrue(Fix.render(r, true).contains("1 entry removed, 0 left"))
        assertEquals("nothing to remove\n", Fix.render(fixAll(m, true), true))
    }

    @Test
    fun threeCopiesInOneFileLeaveExactlyOne() {
        val hook = """{"type": "command", "command": "/h/.claude/settings.json"}"""
        val m = machine("""{"hooks": {"Stop": [{"hooks": [$hook, $hook]}, {"hooks": [$hook]}]}}""")
        val r = fixAll(m, true)
        assertEquals(2, r.files[0].removed.size, "$r")
        assertEquals(1, Regex("settings\\.json").findAll(m.text(SETTINGS)).count())
        assertEquals(0, r.left)
    }

    @Test
    fun aHookBrokenAndDuplicatedIsRemovedOnceAndOnlyWhatWasSelected() {
        val m = machine(BROKEN)
        m.put("/proj/.claude/settings.json", BROKEN)
        assertEquals(2, fixAll(m, false).files.sumOf { it.removed.size })
        // Only the duplicate class: the extra copy goes, one (broken) copy stays.
        val only = fixAll(m, true, Fix.KINDS.subList(1, 2))
        assertEquals(1, only.files.size)
        assertFalse(m.text(SETTINGS).contains("old.sh"))
        assertEquals(BROKEN, m.text("/proj/.claude/settings.json"))
        // Then the broken class takes the last one: that is the user's call, not a duplicate rule.
        fixAll(m, true, Fix.KINDS.take(1))
        assertFalse(m.text("/proj/.claude/settings.json").contains("old.sh"))
    }

    @Test
    fun anMcpCopyGoesFromJsonAndTheRestOfTheFileIsByteForByte() {
        val m = FixMachine()
        m.put(
            "/h/.claude.json",
            "{\n  \"theme\": \"dark\",\n  \"mcpServers\": {\n    \"a\": {\"command\": \"/bin/tool\", \"args\": [\"--x\"]},\n    \"b\": {\"command\": \"/bin/tool\", \"args\": [\"--x\"]},\n    \"c\": {\"command\": \"/bin/other\"}\n  }\n}\n",
        )
        val r = fixAll(m, true)
        assertEquals(1, r.files.size, "$r")
        assertEquals(
            "{\n  \"theme\": \"dark\",\n  \"mcpServers\": {\n    \"a\": {\"command\": \"/bin/tool\", \"args\": [\"--x\"]},\n    \"c\": {\"command\": \"/bin/other\"}\n  }\n}\n",
            m.text("/h/.claude.json"),
        )
        val planned = FixMachine()
        planned.put("/h/.claude.json", """{"mcpServers": {"a": {"command": "/bin/t"}, "b": {"command": "/bin/t"}}}""")
        assertTrue(Fix.render(fixAll(planned, false), false).contains("would remove mcp mcpServers.b"))
    }

    @Test
    fun aShadowedServerIsRemovedAndTheOneTheHostUsesStays() {
        val m = FixMachine()
        m.put("/h/.claude.json", """{"mcpServers": {"srv": {"command": "/bin/old"}}}""")
        val project = """{"mcpServers": {"srv": {"command": "/bin/new"}}}"""
        m.put("/proj/.mcp.json", project)
        val r = fixAll(m, true)
        assertEquals(1, r.files.size, "$r")
        assertEquals("""{"mcpServers": {}}""", m.text("/h/.claude.json"))
        assertEquals(project, m.text("/proj/.mcp.json"))
    }

    @Test
    fun aTomlServerTableIsRemovedAndEveryOtherByteStays() {
        val m = FixMachine()
        val head = "# my codex config\nmodel = \"x\"\n\n[mcp_servers.a]\ncommand = \"/bin/tool\"\nargs = [\"--x\"]\n"
        val gone = "\n# the copy\n[mcp_servers.b]\ncommand = \"/bin/tool\"\nargs = [\"--x\"]\n"
        val tail = "\n[other]\nk = 1 # keep\n"
        m.put("/h/.codex/config.toml", head + gone + tail)
        val r = fixAll(m, true)
        assertEquals(Outcome.Written, r.files[0].outcome, "$r")
        assertEquals(head + tail, m.text("/h/.codex/config.toml"))
    }

    /** Answers read from a script; every screen shown is kept. */
    private class Script(
        answers: List<String>,
    ) : Prompt {
        val left = answers.toMutableList()
        val screens = mutableListOf<String>()

        override fun ask(screen: String): String? {
            screens += screen
            return left.removeFirstOrNull()
        }
    }

    private fun pick(
        m: FixMachine,
        vararg answers: String,
    ): Pair<String, Script> {
        val script = Script(answers.toList())
        val (text, _) = Fix.interactive(cfg(), m.probes(), m, FixOpts(3, null, Fix.KINDS), script)
        return text to script
    }

    @Test
    fun aSharedProjectFileStartsUnselectedAndTheRestIsWrittenAfterConfirmation() {
        val m = machine(BROKEN)
        m.put("/proj/.claude/settings.json", BROKEN)
        val (text, script) = pick(m, "y", "y")
        assertTrue(script.screens[0].contains("[x] broken hook Stop"), script.screens[0])
        assertTrue(script.screens[0].contains("(shared project file)"))
        assertTrue(script.screens[0].contains("[ ] broken hook Stop"))
        assertTrue(script.screens[1].contains("Write 1 change(s)? [y/N] "), script.screens[1])
        assertTrue(script.screens[1].contains("--- a/"), "the diff is shown first")
        assertTrue(text.contains("1 entry removed"), text)
        assertFalse(m.text(SETTINGS).contains("old.sh"))
        assertEquals(BROKEN, m.text("/proj/.claude/settings.json"))
    }

    @Test
    fun togglingDeselectsAndNothingIsWrittenUntilTheLastYes() {
        val m = machine(BROKEN)
        for (answers in listOf(listOf("1", "y"), listOf("q"), listOf(), listOf("y", "n", "q"), listOf("x", "9", "k 1", "q"))) {
            val (text, _) = pick(m, *answers.toTypedArray())
            assertTrue(text.startsWith("cancelled") || text.isEmpty(), "$answers: $text")
            assertEquals(BROKEN, m.text(SETTINGS), "$answers")
        }
        assertTrue(m.backups.isEmpty())
    }

    @Test
    fun kKeepsTheChosenCopyAndRemovesTheOther() {
        val m = machine(USER_DUP)
        m.put("/proj/.claude/settings.json", PROJECT_DUP)
        // Item 1 is the user's extra copy. Keeping it turns the shared project copy into the extra one, which starts
        // unselected: toggle it on.
        val (text, script) = pick(m, "k 1", "1", "y", "y")
        assertTrue(script.screens[1].contains("[ ] extra hook Stop"), script.screens[1])
        assertTrue(text.contains("1 entry removed"), text)
        assertTrue(m.text(SETTINGS).contains("/h/.claude/settings.json"))
        assertFalse(m.text("/proj/.claude/settings.json").contains("Stop"))
    }

    @Test
    fun noAnswersRemoveTheLastCopyOrAnUnrelatedHook() {
        val pool = listOf("1", "2", "3", "k 1", "k 2", "k 3", "d", "y", "n", "q", "k 9", "?")
        val rnd = Random(331)
        val hook = """{"type": "command", "command": "/h/.claude/settings.json"}"""
        val other = """{"type": "command", "command": "/h/.claude/other.sh"}"""
        repeat(64) {
            val answers = List(rnd.nextInt(0, 14)) { pool[rnd.nextInt(pool.size)] }
            val m = machine("""{"hooks": {"Stop": [{"hooks": [$hook, $other]}]}}""")
            m.put("/h/.claude/other.sh", "#!/bin/sh\n")
            val dup = """{"hooks": {"Stop": [{"hooks": [$hook]}]}}"""
            m.put("/proj/.claude/settings.json", dup)
            m.put("/proj/.claude/settings.local.json", dup)
            pick(m, *answers.toTypedArray())
            val all = listOf(SETTINGS, "/proj/.claude/settings.json", "/proj/.claude/settings.local.json").joinToString("") { m.text(it) }
            assertTrue(all.contains("\"/h/.claude/settings.json\""), "$answers")
            assertTrue(m.text(SETTINGS).contains("other.sh"), "$answers")
        }
    }

    @Test
    fun theRtokEntryStaysAndOnlyACopyUnderAnotherNameGoes() {
        val m = FixMachine()
        val own = """{"command": "rtok", "args": ["mcp"]}"""
        m.put("/h/.claude.json", """{"mcpServers": {"rtok": $own, "rtok-mcp": $own}}""")
        val r = fixAll(m, true)
        assertEquals(1 to 0, r.files.size to r.left, "$r")
        assertTrue(Fix.render(r, true).endsWith("1 entry removed, 0 left\n"))
        assertEquals("""{"mcpServers": {"rtok": $own}}""", m.text("/h/.claude.json"))
        // Nothing fixable is left, so a second run has nothing to do.
        assertEquals("nothing to remove\n", Fix.render(fixAll(m, true), true))
    }

    @Test
    fun sameNameAndPluginCopiesOfRtokAreInformationThatFixNeverTouches() {
        val m = FixMachine()
        val own = """{"mcpServers": {"rtok": {"command": "rtok", "args": ["mcp"]}}}"""
        val files = listOf("/h/.claude.json", "/proj/.mcp.json", "/h/.gemini/settings.json", "/h/.gemini/extensions/rtok/gemini-extension.json")
        for (f in files) m.put(f, own)
        val info = Fix.candidates(cfg(), m.probes(), null)
        assertEquals(4, info.count { it.kind == "own-mcp" })
        assertTrue(info.none { Fix.removable(it, Fix.KINDS) })
        val r = fixAll(m, true)
        assertEquals("nothing to remove\n" to 0, Fix.render(r, true) to r.left)
        for (f in files) assertEquals(own, m.text(f))
    }

    @Test
    fun aDryRunWritesNothingAndShowsTheDiff() {
        val m = machine(TWO_HOOKS)
        val r = fix(m, false)
        assertEquals(1, r.files.size)
        assertEquals(Outcome.Planned, r.files[0].outcome)
        assertTrue(r.files[0].diff.contains("-          { \"type\": \"command\", \"command\": \"/h/gone/old.sh\" },"), r.files[0].diff)
        assertEquals(TWO_HOOKS, m.text(SETTINGS))
        assertTrue(m.backups.isEmpty())
        assertEquals(0, r.exitCode())
        val text = Fix.render(r, false)
        assertTrue(text.startsWith("dry run: nothing is written."), text)
        assertTrue(text.contains("would remove PreToolUse[Bash]"), text)
    }

    @Test
    fun applyBacksUpFirstThenRemovesOnlyTheBrokenEntry() {
        val m = machine(TWO_HOOKS)
        val r = fix(m, true)
        assertEquals(Outcome.Written, r.files[0].outcome)
        assertEquals(1, m.backups.size)
        assertEquals(m.backups.first(), r.files[0].backup)
        assertEquals(TWO_HOOKS.replace("{ \"type\": \"command\", \"command\": \"/h/gone/old.sh\" },", ""), m.text(SETTINGS))
        assertEquals(0, r.left)
        assertTrue(Fix.render(r, true).contains("1 entry removed, 0 left"))
        // Idempotent: the second run has nothing to do.
        assertEquals("nothing to remove\n", Fix.render(fix(m, true), true))
    }

    @Test
    fun theLastHookTakesItsGroupAndItsEventWithIt() {
        val raw =
            """{
  "hooks": {
    "Stop": [ { "hooks": [ { "type": "command", "command": "/h/gone/a.sh" } ] } ],
    "PreToolUse": [ { "matcher": "Bash", "hooks": [ { "type": "command", "command": "/h/gone/b.sh" } ] } ]
  },
  "theme": "dark"
}
"""
        val m = machine(raw)
        val r = fix(m, true)
        assertEquals(Outcome.Written, r.files[0].outcome)
        val doc = Jsonc.parse(m.text(SETTINGS)) as JsonObject
        assertEquals(JsonObject(emptyMap()), doc["hooks"])
        assertEquals(JsonPrimitive("dark"), doc["theme"])
        assertEquals(0, r.left)
    }

    @Test
    fun aFileThatChangedSinceTheCheckIsSkippedAndUntouched() {
        val m = machine(TWO_HOOKS)
        val edited = TWO_HOOKS.replace("\"dark\"", "\"light\"")
        assertEquals(Outcome.Planned, fix(m, false).files[0].outcome)
        // The apply run repeats the dry run's reads and then re-reads once before the write.
        val dryReads = m.reads
        m.reads = 0
        m.raceAt = dryReads + 1
        m.race = SETTINGS to edited
        val r = fix(m, true)
        assertEquals(Outcome.Skipped("changed since the check"), r.files[0].outcome)
        assertEquals(1, r.exitCode())
        assertTrue(m.backups.isEmpty())
        assertEquals(edited, m.text(SETTINGS))
    }

    @Test
    fun aFailedBackupOrWriteLeavesTheFileAndExits1() {
        val m = machine(TWO_HOOKS).apply { failBackup = true }
        val r = fix(m, true)
        assertTrue((r.files[0].outcome as Outcome.Failed).why.startsWith("backup failed"))
        assertEquals(1, r.exitCode())
        assertEquals(TWO_HOOKS, m.text(SETTINGS))

        val w = machine(TWO_HOOKS).apply { failWrite = true }
        val r2 = fix(w, true)
        assertTrue((r2.files[0].outcome as Outcome.Failed).why.startsWith("write failed"))
        assertEquals(1, r2.exitCode())
        assertEquals(TWO_HOOKS, w.text(SETTINGS))
    }

    @Test
    fun rtokOwnEntriesAndNonBrokenHooksAreNeverRemoved() {
        val raw =
            """{"hooks":{"PreToolUse":[{"hooks":[
{"type":"command","command":"/h/gone/rtok hook pre-tool-use"},
{"type":"command","command":"/h/gone/rtok-hook"},
{"type":"command","command":"/h/.claude/settings.json"},
{"type":"command","command":"echo hi"}
]}]}}"""
        val m = machine(raw)
        val r = fix(m, true)
        assertTrue(r.files.isEmpty(), "$r")
        assertEquals(2, r.refused.size)
        assertEquals(raw, m.text(SETTINGS))
        assertEquals(0, r.exitCode())
        assertTrue(Fix.render(r, true).contains("not removed:"))
    }

    @Test
    fun aBrokenTomlHookIsReportedAndNeverEdited() {
        val toml = "[[hooks]]\nevent = \"Stop\"\ncommand = \"/h/gone.sh\"\n"
        val m = machine("{}")
        m.put("/h/.kimi-code/config.toml", toml)
        val base = cfg()
        val c = base.copy(setup = base.setup.copy(kimi = base.setup.kimi.copy(configPath = "/h/.kimi-code/config.toml")))
        val r = Fix.fixBroken(c, m.probes(), m, true, 3)
        assertTrue(r.files.isEmpty(), "$r")
        assertEquals(1, r.refused.size)
        assertEquals("TOML hook files are not edited yet", r.refused[0].second)
        assertEquals(toml, m.text("/h/.kimi-code/config.toml"))
        assertTrue(m.backups.isEmpty())
    }

    @Test
    fun removingOneHookChangesOnlyThatHook() {
        val rnd = Random(902)
        var ran = 0
        repeat(64) {
            val (raw, path, command) = build(rnd)
            if (path.isEmpty()) return@repeat
            ran++
            val body = Fix.removeEntries(raw, "settings.json", listOf(path)) ?: error("entry not located: $raw $path")
            assertTrue(onlyARegionIsGone(raw, body), "before: $raw\nafter: $body")
            val expect = Fix.hookList(raw)!!.toMutableList()
            expect.removeAt(expect.indexOfFirst { it.third == command })
            assertEquals(expect, Fix.hookList(body))
            assertFalse(body.contains("\"$command\""))
        }
        assertTrue(ran > 0)
    }

    private companion object {
        const val TWO_HOOKS = """{
  // keep this comment
  "theme": "dark",
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Bash",
        "hooks": [
          { "type": "command", "command": "/h/gone/old.sh" },
          { "type": "command", "command": "/h/.claude/settings.json" }
        ]
      }
    ]
  }
}
"""

        /** Filler placed between JSON tokens: whitespace and both comment forms. */
        val FILLERS = listOf("", " ", "\n", "\n    ", "// note\n", "/* é — ü */", "\t")
        val EVENTS = listOf("PreToolUse", "PostToolUse", "Stop")

        /** A random document, the key path to remove, and the command that path holds (fix.rs `build`). */
        @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")
        fun build(rnd: Random): Triple<String, String, String> {
            val events = List(rnd.nextInt(1, 4)) { List(rnd.nextInt(1, 4)) { if (rnd.nextBoolean()) rnd.nextInt(1, 4) else null } }
            val fillers = List(64) { rnd.nextInt(FILLERS.size) }
            var fi = 0

            fun next() = FILLERS[fillers[fi++ % fillers.size]]
            val pe = rnd.nextInt(8) % events.size
            val pg = rnd.nextInt(8) % events[pe].size
            val ph = rnd.nextInt(8)
            var path = ""
            var command = ""
            val out = StringBuilder("${next()}{${next()}\"theme\"${next()}:${next()}\"dark\",${next()}\"hooks\"${next()}:${next()}{${next()}")
            for ((e, groups) in events.withIndex()) {
                if (e > 0) out.append(",${next()}")
                out.append("\"${EVENTS[e]}\"${next()}:${next()}[${next()}")
                for ((g, group) in groups.withIndex()) {
                    if (g > 0) out.append(",${next()}")

                    fun cmd(h: Int) = "/gone/$e-$g-$h.sh"
                    if (group == null) {
                        out.append("{\"type\":${next()}\"command\",${next()}\"command\":\"${cmd(0)}\"}")
                        if (e == pe && g == pg) {
                            path = "hooks.${EVENTS[e]}[$g]"
                            command = cmd(0)
                        }
                    } else {
                        out.append("{${next()}\"matcher\":${next()}\"Bash\",${next()}\"hooks\"${next()}:${next()}[${next()}")
                        for (h in 0 until group) {
                            if (h > 0) out.append(",${next()}")
                            out.append("{\"type\":${next()}\"command\",\"command\":\"${cmd(h)}\"}")
                        }
                        out.append("${next()}]${next()}}")
                        if (e == pe && g == pg) {
                            val h = ph % group
                            path = "hooks.${EVENTS[e]}[$g].hooks[$h]"
                            command = cmd(h)
                        }
                    }
                }
                out.append("${next()}]")
            }
            out.append("${next()}}${next()}}${next()}")
            return Triple(out.toString(), path, command)
        }

        /** [after] is [before] with one contiguous region removed. */
        fun onlyARegionIsGone(
            before: String,
            after: String,
        ): Boolean {
            val a = before.encodeToByteArray()
            val b = after.encodeToByteArray()
            var lcp = 0
            while (lcp < a.size && lcp < b.size && a[lcp] == b[lcp]) lcp++
            val tail = b.copyOfRange(lcp, b.size)
            return a.size >= b.size && a.copyOfRange(a.size - tail.size, a.size).contentEquals(tail)
        }
    }
}
