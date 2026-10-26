// Port of agents/skill.rs tests: `dest_maps_documented_roots_and_skips_the_rest` and the install/reinstall/remove cycle
// (on disk through a fake plugin source instead of the Vfs), plus the pre-T380 `worktrees` retirement and the pi-bundled
// fallback. `every_shipped_skill_exists_in_the_hub` stays in Rust: this repo ships no `skills/` tree.
package rtok.agents

import rtok.agent.sdk.NO_CHANGES
import rtok.agent.sdk.OWNED_MARKER
import rtok.config.Config
import rtok.sys.createDirAll
import rtok.sys.exists
import rtok.sys.isFile
import rtok.sys.join
import rtok.sys.readToString
import rtok.sys.removeDirAll
import rtok.sys.tempDir
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class Scratch(
    val dir: String,
    val cfg: Config,
    val plugins: PluginSource,
) {
    val root = join(dir, "copilot/skills")
}

private fun scratch(
    name: String,
    hub: String = "skills",
): Scratch {
    val dir = join(tempDir(), "rtok-skills-$name-${kotlin.random.Random.nextLong().toULong()}").also(::createDirAll)
    val repo = join(dir, "repo")
    for (skill in Skills.SKILLS) {
        createDirAll(join(repo, "$hub/$skill"))
        writeString(join(repo, "$hub/$skill/SKILL.md"), "# $skill\n")
    }
    val s = Config().setup
    val setup = s.copy(backup = false, copilot = s.copilot.copy(dir = join(dir, "copilot")))
    return Scratch(dir, Config().copy(setup = setup)) { rel -> PluginSrc.joinRel(repo, rel) }
}

class SkillsTest {
    @Test fun destMapsDocumentedRootsAndSkipsTheRest() {
        val cfg = Config()
        val documented = setOf("claude", "cursor", "codex", "opencode", "copilot", "commandcode", "pi", "antigravity")
        for (host in Hosts.ALL.map { it.id }) {
            if (host in documented) {
                assertNotNull(Skills.root(host, cfg), "$host has a skill root")
                assertNotNull(Skills.label(host), "$host root has a label")
            } else {
                assertNull(Skills.root(host, cfg), "$host has no skill format")
            }
        }
        assertNull(Skills.root("gemini", cfg))
        for ((host, tail) in listOf(
            "claude" to ".claude/skills",
            "cursor" to ".cursor/skills",
            "codex" to ".codex/skills",
            "opencode" to "opencode/skills",
            "copilot" to ".copilot/skills",
            "commandcode" to ".commandcode/skills",
            "pi" to ".pi/agent/skills",
            "antigravity" to ".gemini/config/skills",
            "antigravity-cli" to ".gemini/antigravity-cli/skills",
        )) {
            assertTrue(Skills.label(host)!!.endsWith(tail), "$host label")
            assertTrue(Skills.root(host, cfg)!!.endsWith(tail), "$host root")
            for (name in Skills.SKILLS) assertTrue(Skills.dest(host, cfg, name)!!.endsWith("$tail/$name"), "$host $name")
        }
    }

    @Test fun installReinstallRemoveKeepsForeign() {
        val s = scratch("cycle")
        val foreign = join(s.root, "other/SKILL.md")
        createDirAll(join(s.root, "other"))
        writeString(foreign, "# other\n")
        val first = Skills.sync("copilot", s.cfg, false, s.plugins).lines()
        assertEquals(Skills.SKILLS.size, first.size, "$first")
        assertTrue(first.all { it.startsWith("+ skill → ~/.copilot/skills/rtok") }, "$first")
        for (name in Skills.SKILLS) assertTrue(isFile(join(s.root, "$name/$OWNED_MARKER")), name)
        assertEquals(NO_CHANGES, Skills.sync("copilot", s.cfg, false, s.plugins))
        assertEquals(Skills.SKILLS.size, Skills.sync("copilot", s.cfg, true, s.plugins).lines().count { it.startsWith("- skill") })
        assertEquals(NO_CHANGES, Skills.sync("copilot", s.cfg, true, s.plugins))
        assertEquals("# other\n", readToString(foreign))
        removeDirAll(s.dir)
    }

    /** A host with no documented skill root is never touched. */
    @Test fun aHostWithoutASkillRootIsNoChanges() {
        val s = scratch("none")
        assertEquals(NO_CHANGES, Skills.sync("gemini", s.cfg, false, s.plugins))
        removeDirAll(s.dir)
    }

    /** Pre-T380 `worktrees`: a byte copy of the hub goes on install, a foreign tree stays and is named. */
    @Test fun installRetiresAnOwnedLegacyCopyAndNamesAForeignOne() {
        val s = scratch("legacy")
        val legacy = join(s.root, "worktrees")
        createDirAll(legacy)
        writeString(join(legacy, "SKILL.md"), "# rtok-worktrees\n")
        val out = Skills.sync("copilot", s.cfg, false, s.plugins)
        assertTrue("- skill $legacy" in out, out)
        assertFalse(exists(legacy))
        createDirAll(legacy)
        writeString(join(legacy, "SKILL.md"), "# mine\n")
        assertEquals("leave $legacy (not an rtok skill; remove by hand)", Skills.sync("copilot", s.cfg, false, s.plugins))
        assertFalse(legacy in Skills.sync("copilot", s.cfg, true, s.plugins), "remove never takes the legacy name")
        assertTrue(exists(legacy))
        removeDirAll(s.dir)
    }

    /** Older ketch archives ship only `plugins/`; the pi-bundled skills stand in for the hub. */
    @Test fun srcFallsBackToThePiBundledCopy() {
        val s = scratch("pi-fallback", hub = "plugins/pi/skills")
        assertTrue(Skills.src("rtok", s.plugins).endsWith("plugins/pi/skills/rtok"))
        assertTrue(Skills.sync("copilot", s.cfg, false, s.plugins).startsWith("+ skill"))
        removeDirAll(s.dir)
    }

    /** A dev build with no shipped skills copies nothing. */
    @Test fun aMissingHubInstallsNothing() {
        val s = scratch("bare")
        assertEquals(NO_CHANGES, Skills.sync("copilot", s.cfg, false) { rel -> join(join(s.dir, "nowhere"), rel) })
        assertFalse(exists(s.root))
        removeDirAll(s.dir)
    }
}
