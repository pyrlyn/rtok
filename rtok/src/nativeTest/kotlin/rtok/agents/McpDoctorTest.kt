// Port of agents/mcp.rs `doctor_warns_when_an_installed_agent_lacks_the_entry` (the bare-`rtok` case is checked only
// where an absolute rtok resolves, as `unresolved_command` decides) plus the own-row hosts and the shared-host flag.
package rtok.agents

import rtok.config.Config
import rtok.sys.createDirAll
import rtok.sys.join
import rtok.sys.removeDirAll
import rtok.sys.tempDir
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun scratchDir(name: String) = join(tempDir(), "rtok-mcp-doctor-$name-${kotlin.random.Random.nextLong().toULong()}").also(::createDirAll)

class McpDoctorTest {
    private val claude = Claude({ _, _, _ -> null }, { false })
    private val cliOnly: (Variant) -> Boolean = { it.kind == Kind.Cli }

    @Test fun doctorWarnsWhenAnInstalledAgentLacksTheEntry() {
        val dir = scratchDir("claude")
        createDirAll(join(dir, ".claude/plugins"))
        val c = Config()
        var cfg =
            c.copy(
                setup = c.setup.copy(claude = c.setup.claude.copy(settingsPath = join(dir, ".claude/settings.json"))),
                doctor = c.doctor.copy(claudeJson = join(dir, "claude.json")),
            )

        fun write(servers: String) = writeString(cfg.doctor.claudeJson, """{"mcpServers":$servers}""")

        fun warn() = McpDoctor.hostDoctorLines(claude, cfg, cliOnly)

        // Not installed: no plugin, no hooks.
        write("{}")
        assertTrue(warn().isEmpty())

        writeString(join(dir, ".claude/plugins/installed_plugins.json"), """{"plugins":{"rtok@rtok":[{}]}}""")
        val fix = "— rtok side: rtok agents install claude --cli"
        val missing = warn()
        assertEquals(1, missing.size, "$missing")
        assertTrue(missing[0].startsWith("missing: ") && "claude.json has no \"rtok\"" in missing[0] && missing[0].endsWith(fix), "$missing")

        write("""{"rtok":{"command":"rtok","args":["mcp","--extra"]}}""")
        val stale = warn()
        assertEquals(1, stale.size, "$stale")
        assertTrue(stale[0].startsWith("stale: ") && "runs `rtok mcp --extra`" in stale[0] && stale[0].endsWith(fix), "$stale")

        // T932: a command a GUI host cannot spawn is stale even though it runs `rtok mcp`.
        write("""{"rtok":{"command":"/nowhere/bin/rtok","args":["mcp"]}}""")
        assertTrue(warn().single().let { "`/nowhere/bin/rtok`, which does not exist" in it && it.endsWith(fix) })
        if (rtokMcpCommand().startsWith('/')) {
            write("""{"rtok":{"command":"rtok","args":["mcp"]}}""")
            assertTrue("runs bare `rtok`" in warn().single())
        }

        val bin = join(dir, "bin/rtok")
        createDirAll(join(dir, "bin"))
        writeString(bin, "#!/bin/sh\n")
        write("""{"rtok":{"command":"$bin","args":["mcp"]}}""")
        assertTrue(warn().isEmpty(), "${warn()}")

        write("{}")
        cfg = cfg.copy(setup = cfg.setup.copy(mcp = false))
        assertTrue(McpDoctor.doctorLines(cfg).isEmpty(), "[setup] mcp = false")
        removeDirAll(dir)
    }

    /** A host whose entry `rtok_mcp` does not parse gets one row from its own `installed`; a host without MCP none. */
    @Test fun ownRowFollowsTheHostsInstalledAndAHostWithoutMcpHasNone() {
        val dir = scratchDir("own")
        val c = Config()
        val cfg = c.copy(setup = c.setup.copy(goose = c.setup.goose.copy(configPath = join(dir, "config.yaml"))))
        val row = McpDoctor.rows(Goose, cfg, Kind.Cli).single()
        assertEquals(EntryState.Missing, row.entry)
        writeString(join(dir, "config.yaml"), "extensions:\n  rtok:\n    name: rtok\n    type: stdio\n    cmd: rtok\n    args: [mcp, --host, goose]\n    enabled: true\n")
        assertEquals(EntryState.Present, McpDoctor.rows(Goose, cfg, Kind.Cli).single().entry)
        assertTrue(McpDoctor.rows(Aider, cfg, Kind.Cli).isEmpty())
        removeDirAll(dir)
    }
}
