// Port of agents/kimi/mod.rs tests. The Vfs twins collapse into the disk tests (the spaced-profile one runs on disk);
// plugin_manifest_matches_the_installer reads plugins/kimi, which stays in the Rust tree; the T275 lifecycle and the
// legacy-entry upgrade (mcp.rs helpers, not ported yet) are spelled out here.
package rtok.agents

import rtok.agent.sdk.NO_CHANGES
import rtok.config.Config
import rtok.sys.createDirAll
import rtok.sys.exists
import rtok.sys.join
import rtok.sys.parent
import rtok.sys.readToString
import rtok.sys.removeDirAll
import rtok.sys.tempDir
import rtok.sys.writeString
import tomlaot.TomlDoc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KimiTest {
    private val kimi = Kimi { "/src/$it" }

    @Test fun dryRunNamesNineTablesAndTouchesNothing() {
        val c = cfg("dry", dry = true)
        writeString(c.setup.kimi.configPath, "# mine\n")
        val out = Kimi.run(c, false)
        assertTrue("9 additions" in out && "+ [[hooks]] PreToolUse Bash " in out, out)
        assertEquals("# mine\n", readToString(c.setup.kimi.configPath))
        assertFalse(exists(Kimi.mcpPath(c)))
        assertTrue(Agents.agent("kimi") is Kimi)
        cleanup(c)
    }

    @Test fun applyKeepsCommentsAndForeignHooksAndIsIdempotent() {
        val c = cfg("apply profile")
        val path = c.setup.kimi.configPath
        writeString(path, "# kimi config\nmodel = \"k2\"\n\n[[hooks]]\nevent = \"Stop\"\ncommand = \"echo other\"\n")
        assertTrue("9 additions" in Kimi.run(c, false))
        assertEquals(NO_CHANGES, Kimi.run(c, false))
        val raw = readToString(path)
        assertTrue(raw.startsWith("# kimi config\nmodel = \"k2\"\n") && "command = \"echo other\"" in raw, raw)
        val hooks = TomlDoc.parse(raw).arrayOfTables("hooks")
        assertEquals(Kimi.entries().size + 1, hooks.size)
        val bash = hooks.first { it.str("matcher") == "Bash" }
        assertEquals("PreToolUse", bash.str("event"))
        assertEquals(c.setup.hookTimeoutS.toLong(), bash.int("timeout"))
        assertTrue(bash.str("command")!!.endsWith("rtok hook PreToolUse"))
        assertEquals(listOf("hooks"), kimi.installed(c, Kind.Cli))
        assertEquals("9 removed", Kimi.run(c, true))
        assertEquals(NO_CHANGES, Kimi.run(c, true))
        val left = readToString(path)
        assertTrue("echo other" in left && "rtok" !in left, left)
        cleanup(c)
    }

    @Test fun mcpLandsBesideTheConfigWithoutATypeField() {
        val c = cfg("mcp")
        assertTrue(McpRun.install(c, Kimi.mcpSpec(c)).startsWith("mcpServers.rtok: "))
        assertEquals(NO_CHANGES, McpRun.install(c, Kimi.mcpSpec(c)))
        assertEquals(join(parent(c.setup.kimi.configPath)!!, "mcp.json"), Kimi.mcpPath(c))
        val raw = readToString(Kimi.mcpPath(c))
        assertTrue("\"type\"" !in raw && "\"mcp\"" in raw, raw)
        assertEquals(listOf("mcp"), kimi.installed(c, Kind.Cli))
        assertEquals("- mcpServers.rtok", McpRun.remove(c, Kimi.mcpSpec(c)))
        assertFalse("rtok" in readToString(Kimi.mcpPath(c)))
        cleanup(c)
    }

    /** T86: the offer names `plugins/kimi` and `/plugins install` behind `--yes` and never writes the store. */
    @Test fun offerNamesThePluginPathAndKetchHint() {
        var c = cfg("offer-dry", dry = true)
        val store = join(parent(c.setup.kimi.configPath)!!, "plugins")
        assertEquals(NO_CHANGES, kimi.offerPlugin(c, false))
        c = c.copy(setup = c.setup.copy(yes = true))
        val line = kimi.offerPlugin(c, false)
        assertTrue("plugins/kimi" in line && "/plugins install" in line && "ketch install pyrlyn/rtok" in line, line)
        c = c.copy(setup = c.setup.copy(dryRun = false))
        assertTrue("/plugins install" in kimi.offerPlugin(c, false))
        assertFalse(exists(store), "apply writes nothing either")
        cleanup(c)
    }

    /** T86 D21: with the managed copy present an install strips rtok's tables; MCP stays (T275/D33). */
    @Test fun pluginDetectedStripsOwnHooksMcpIsIndependent() {
        val c = cfg("singleton", yes = true)
        val first = kimi.apply(c, Kind.Cli, Mode.Install)
        assertEquals(listOf("hooks", "mcp"), kimi.installed(c, Kind.Cli))
        assertTrue("/plugins install" in first[0], "$first")
        val repeat = kimi.apply(c, Kind.Cli, Mode.Install)
        assertTrue(repeat.all { it == NO_CHANGES }, "$repeat")
        val marker = Kimi.pluginMarker(c)
        createDirAll(parent(marker)!!)
        writeString(marker, "{}")
        val second = kimi.apply(c, Kind.Cli, Mode.Install)
        assertEquals(listOf("mcp", "plugin"), kimi.installed(c, Kind.Cli))
        assertFalse("rtok hook" in readToString(c.setup.kimi.configPath))
        assertTrue("\"rtok\"" in readToString(Kimi.mcpPath(c)))
        assertTrue("/plugins install" in second[0], "$second")
        val rm = kimi.apply(c, Kind.Cli, Mode.Remove)
        assertTrue("/plugins remove rtok" in rm[0], "$rm")
        assertTrue(exists(marker), "remove leaves the managed copy alone")
        assertEquals(listOf("plugin"), kimi.installed(c, Kind.Cli))
        cleanup(c)
    }

    @Test fun removeWithoutPluginIsNoChanges() {
        val c = cfg("rm-clean")
        val rm = kimi.apply(c, Kind.Cli, Mode.Remove)
        assertTrue(rm.all { it == NO_CHANGES }, "$rm")
        cleanup(c)
    }

    /** T242.5: a stale table is rewritten in place, one on an unlisted pair goes, a foreign one stays; then no change. */
    @Test fun staleRtokTablesAreRewrittenAndPruned() {
        val doc =
            TomlDoc.parse(
                "[[hooks]]\nevent = \"PreToolUse\"\nmatcher = \"Bash\"\ncommand = \"/old/store/rtok/v0.1.0/rtok hook PreToolUse\"\ntimeout = 1\n\n" +
                    "[[hooks]]\nevent = \"PostToolUse\"\nmatcher = \"Bash\"\ncommand = \"rtok hook PostToolUse\"\n\n" +
                    "[[hooks]]\nevent = \"Stop\"\ncommand = \"echo other\"\n",
            )
        val timeout = Config().setup.hookTimeoutS.toLong()
        val out = Kimi.insert(doc, timeout)
        assertTrue("- [[hooks]] PostToolUse Bash" in out && out.endsWith("8 additions, 1 updates, 1 removals"), out)
        val hooks = doc.arrayOfTables("hooks")
        assertEquals(Kimi.entries().size + 1, hooks.size, "$doc")
        assertEquals("${HookLine.rtokHookBin()} hook PreToolUse", hooks[0].str("command"))
        assertEquals(timeout, hooks[0].int("timeout"))
        assertEquals("echo other", hooks[1].str("command"))
        val current = doc.toString()
        assertEquals(NO_CHANGES, Kimi.insert(doc, timeout))
        assertEquals(current, doc.toString())
    }

    /** T275/D33 and T283.2: the entry is written with the plugin present, a legacy one is upgraded, a user edit is left. */
    @Test fun mcpEntryLifecycleAndLegacyUpgrade() {
        val c = cfg("t275-mcp")
        createDirAll(parent(Kimi.pluginMarker(c))!!)
        writeString(Kimi.pluginMarker(c), "{}")
        for (mode in listOf(Mode.Install, Mode.Update)) {
            kimi.apply(c, Kind.Cli, mode)
            assertTrue("mcp" in kimi.installed(c, Kind.Cli), "$mode")
        }
        kimi.apply(c, Kind.Cli, Mode.Remove)
        assertFalse("mcp" in kimi.installed(c, Kind.Cli))
        val legacy = """{"mcpServers":{"other":{"command":"x"},"rtok":{"command":"rtok","args":["mcp"]}}}"""
        writeString(Kimi.mcpPath(c), legacy)
        assertTrue(McpRun.install(c, Kimi.mcpSpec(c)) != NO_CHANGES)
        val upgraded = readToString(Kimi.mcpPath(c))
        assertTrue("--host" in upgraded && "kimi" in upgraded && "\"other\"" in upgraded, upgraded)
        assertEquals(NO_CHANGES, McpRun.install(c, Kimi.mcpSpec(c)))
        writeString(Kimi.mcpPath(c), legacy)
        assertTrue(McpRun.remove(c, Kimi.mcpSpec(c)).startsWith("-"))
        McpRun.install(c, Kimi.mcpSpec(c))
        writeString(Kimi.mcpPath(c), readToString(Kimi.mcpPath(c)).replace("\"kimi\"", "\"kimi\", \"--extra\""))
        assertTrue(McpRun.remove(c, Kimi.mcpSpec(c)).startsWith("leave "))
        cleanup(c)
    }
}

private fun cfg(
    name: String,
    dry: Boolean = false,
    yes: Boolean = false,
): Config {
    val dir = join(tempDir(), "rtok-kimi-$name-${kotlin.random.Random.nextLong().toULong()}").also(::createDirAll)
    val s = Config().setup
    return Config().copy(setup = s.copy(dryRun = dry, yes = yes, backup = false, kimi = s.kimi.copy(configPath = join(dir, "config.toml"))))
}

private fun cleanup(c: Config) = removeDirAll(parent(c.setup.kimi.configPath)!!)
