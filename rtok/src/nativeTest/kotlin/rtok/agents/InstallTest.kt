// Tests of the agents/mod.rs install engine (`resolve`, `run`, `apply_all`, `carry_flags`, `list_ids`) and locate.rs
// `absent`/`not_found`, on Windsurf's one MCP file in a scratch dir and on stub hosts; the machine (which apps are
// present, whether `rtok` is on PATH, the block text) comes from [AgentsRun.Env].
package rtok.agents

import rtok.agent.sdk.NO_CHANGES
import rtok.config.Config
import rtok.sys.createDirAll
import rtok.sys.exists
import rtok.sys.join
import rtok.sys.listDir
import rtok.sys.removeDirAll
import rtok.sys.tempDir
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallTest {
    private fun scratch(name: String) = join(tempDir(), "rtok-engine-$name-${kotlin.random.Random.nextLong().toULong()}").also(::createDirAll)

    private fun windsurf(
        dir: String,
        backup: Boolean = true,
    ): Config {
        val c = Config()
        return c.copy(setup = c.setup.copy(backup = backup, windsurf = c.setup.windsurf.copy(configPath = join(dir, "mcp_config.json"))))
    }

    private fun header(
        v: Variant,
        o: Outcome,
    ) = "${v.kind.label}: ${v.name} ${o::class.simpleName}\n"

    private fun env(
        present: Boolean = true,
        spawns: Boolean = true,
        agent: (String) -> Agent? = Agents::agent,
    ) = AgentsRun.Env(agent, { _, _, _ -> present }, { spawns }, { _, v, _, o -> header(v, o) })

    private fun backups(dir: String) = join(dir, ".backup_configs").let { if (exists(it)) listDir(it) else emptyList() }

    @Test fun anInstallBacksUpFirstAndANoChangeRunDeletesItsCopy() {
        val dir = scratch("backup")
        val cfg = windsurf(dir)
        writeString(cfg.setup.windsurf.configPath, "{}\n")
        val req = Request(listOf("windsurf"), Mode.Install)
        val first = AgentsRun.run(cfg, req, env())
        assertTrue(first.startsWith("backup "), first)
        assertTrue("Desktop: Windsurf Applied" in first, first)
        assertEquals(1, backups(dir).size, first)
        val again = AgentsRun.run(cfg, req, env())
        assertFalse("backup " in again, again)
        assertEquals(1, backups(dir).size, "a no-change run leaves the directory as it found it")
        assertTrue("mcp" in Modules.installed(Agents.agent("windsurf")!!, Kind.Desktop, cfg))
        removeDirAll(dir)
    }

    @Test fun unknownAndAbsentHostsAreRefusedBeforeAnyBackup() {
        val dir = scratch("refuse")
        val cfg = windsurf(dir)
        writeString(cfg.setup.windsurf.configPath, "{}\n")
        val unknown = assertFailsWith<AgentsFailure> { AgentsRun.run(cfg, Request(listOf("windsurf", "nope"), Mode.Install), env()) }
        assertEquals("unknown host: nope", unknown.message)
        assertFailsWith<AgentsFailure> { AgentsRun.resolve(emptyList()) }
        val absent = assertFailsWith<AgentsFailure> { AgentsRun.run(cfg, Request(listOf("windsurf"), Mode.Install), env(present = false)) }
        assertEquals(
            "windsurf is not installed (not in its app folders or on PATH); if it is, set its path: `rtok config set setup.paths.windsurf <path>`",
            absent.message,
        )
        assertEquals(emptyList(), backups(dir))
        removeDirAll(dir)
    }

    @Test fun removeReachesAnAbsentAppAndWarnsNothingAboutPath() {
        val dir = scratch("remove")
        val cfg = windsurf(dir, backup = false)
        AgentsRun.run(cfg, Request(listOf("windsurf"), Mode.Install), env())
        val out = AgentsRun.run(cfg, Request(listOf("windsurf"), Mode.Remove), env(present = false, spawns = false))
        assertEquals("Desktop: Windsurf Applied\n", out)
        assertEquals(emptyList(), Modules.installed(Agents.agent("windsurf")!!, Kind.Desktop, cfg))
        removeDirAll(dir)
    }

    @Test fun anInstallWarnsWhenRtokIsNotOnPath() {
        val dir = scratch("path")
        val out = AgentsRun.run(windsurf(dir, backup = false), Request(listOf("windsurf"), Mode.Install), env(spawns = false))
        assertTrue(out.startsWith("warning: rtok is not on PATH;"), out)
        removeDirAll(dir)
    }

    @Test fun updateSkipsAVariantWithNothingOfRtokAndCarriesFlagModules() {
        val c = Config()
        val carried = AgentsRun.carryFlags(c, listOf("proxy", "plugin"))
        assertTrue(carried.setup.proxy && carried.setup.yes)
        val plain = AgentsRun.carryFlags(c, listOf("mcp"))
        assertFalse(plain.setup.proxy || plain.setup.yes)
        val dir = scratch("update")
        val cfg = windsurf(dir, backup = false)
        assertEquals("Desktop: Windsurf NotInstalled\n", AgentsRun.run(cfg, Request(listOf("windsurf"), Mode.Update), env()))
        assertEquals(emptyList(), AgentsList.installedHosts(cfg, env(agent = { if (it == "windsurf") Agents.agent(it) else null })))
        AgentsRun.run(cfg, Request(listOf("windsurf"), Mode.Install), env())
        assertEquals(listOf("windsurf"), AgentsList.installedHosts(cfg, env(agent = { if (it == "windsurf") Agents.agent(it) else null })))
        removeDirAll(dir)
    }

    /** A stub for a registry host: records each apply, reports [report]. */
    private class Stub(
        override val id: String,
        val report: String = "+ change",
    ) : Agent {
        val applied = mutableListOf<Kind>()

        override fun files(
            cfg: Config,
            kind: Kind,
        ) = emptyList<String>()

        override fun installed(
            cfg: Config,
            kind: Kind,
        ) = emptyList<String>()

        override fun apply(
            cfg: Config,
            kind: Kind,
            mode: Mode,
        ): List<String> {
            applied += kind
            return listOf(report)
        }
    }

    @Test fun aSharedHostAppliesOnceAndTheFlagsPickVariants() {
        val stub = Stub("cursor")
        val c = Config()
        val dry = c.copy(setup = c.setup.copy(dryRun = true))
        val e = env(agent = { stub.takeIf { _ -> it == "cursor" } })
        val (out, changed) = AgentsRun.applyAll(dry, Request(listOf("cursor"), Mode.Install), listOf(stub), { true }, e)
        assertEquals("CLI: Cursor CLI Applied\nDesktop: Cursor Shared\n", out)
        assertTrue(changed)
        assertEquals(listOf(Kind.Cli), stub.applied)
        val cliOnly = Request(listOf("cursor"), Mode.Install, desktop = true)
        assertEquals("Desktop: Cursor Applied\n", AgentsRun.applyAll(dry, cliOnly, listOf(stub), cliOnly::wants, e).first)
        assertEquals("skip cursor: not selected\n", AgentsRun.applyAll(dry, cliOnly, listOf(stub), { false }, e).first)
        val quiet = Stub("cursor", NO_CHANGES)
        assertFalse(AgentsRun.wouldChange(c, quiet, Request(listOf("cursor"), Mode.Install), e))
        assertTrue(AgentsRun.wouldChange(c, stub, Request(listOf("cursor"), Mode.Install), e))
    }
}
