// Port of agents/outdated.rs and plugin_install.rs tests, plus the report, `plugin_status` and JSON rows over a stub
// host whose plugin the receipt places in a scratch dir.
package rtok.agents

import rtok.config.Config
import rtok.sys.createDirAll
import rtok.sys.join
import rtok.sys.removeDirAll
import rtok.sys.tempDir
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutdatedTest {
    private val target = "1.2.3"

    private fun dir(name: String) = join(tempDir(), "rtok-outdated-$name-${kotlin.random.Random.nextLong().toULong()}").also(::createDirAll)

    /** Cursor with only its plugin installed; nothing on disk is read but the receipt and the version file. */
    private val cursor =
        object : Agent {
            override val id = "cursor"

            override fun files(
                cfg: Config,
                kind: Kind,
            ) = emptyList<String>()

            override fun installed(
                cfg: Config,
                kind: Kind,
            ) = listOf("plugin")

            override fun apply(
                cfg: Config,
                kind: Kind,
                mode: Mode,
            ) = emptyList<String>()
        }

    private fun home(
        name: String,
        version: String?,
    ): Pair<String, Config> {
        val d = dir(name)
        val plugin = join(d, "plugin").also(::createDirAll)
        val receipt = Receipt().upsert("cursor", ReceiptEntry(Source.Local, "main", null, plugin, version ?: "0.0.0", "2026-01-01T00:00:00Z"))
        receipt.write(join(d, "plugins.json"))
        if (version != null) VersionFile(plugin = "rtok", version = version, source = Source.Local).write(join(plugin, PluginVersion.FILE))
        return d to Config().also { it.pluginReceiptPath = join(d, "plugins.json") }
    }

    private fun report(cfg: Config) = Outdated.report(cfg, OutdatedSelection(listOf("cursor")), target) { if (it == "cursor") cursor else null }

    @Test fun isBehindIgnoresBuildMetadataOnEqualBase() = assertFalse(Outdated.isBehind(PluginVersion.localVersion(target, "12c7e91"), target))

    @Test fun isBehindFlagsAnOlderPatch() = assertTrue(Outdated.isBehind("0.0.1", target))

    @Test fun aNewerInstallIsNotBehind() = assertFalse(Outdated.isBehind("1.2.4", target))

    @Test fun anOlderPluginIsReportedWithTheUpdateHint() {
        val (d, cfg) = home("old", "1.0.0")
        val rep = report(cfg)
        assertEquals(listOf(OutdatedRow("cursor", "cli", "1.0.0", target, "local", false), OutdatedRow("cursor", "desktop", "1.0.0", target, "local", false)), rep.outdated)
        assertEquals(2, rep.installed)
        val text = Outdated.human(rep)
        assertTrue(text.startsWith("agent  installed available source\ncursor 1.0.0     1.2.3     local \n"), text)
        assertTrue(text.endsWith("\nrun: rtok agents update cursor\n"), text)
        assertTrue(Outdated.json(rep).startsWith("""{"rtok":"1.2.3","outdated":[{"agent":"cursor","variant":"cli","installed":"1.0.0""""))
        val status = Outdated.pluginStatus(cursor, Kind.Cli, cfg, target)
        assertEquals(PluginStatus("1.0.0", "local", "1.0.0 (local), rtok is 1.2.3 — rtok agents update cursor"), status)
        removeDirAll(d)
    }

    @Test fun aCurrentPluginIsUpToDate() {
        val (d, cfg) = home("current", target)
        assertEquals("all rtok plugins are up to date (2 installed, rtok 1.2.3)\n", Outdated.human(report(cfg)))
        assertEquals("1.2.3 (local)", Outdated.pluginStatus(cursor, Kind.Cli, cfg, target)?.note)
        removeDirAll(d)
    }

    @Test fun noPluginAndAnUnknownHost() {
        val cfg = Config().also { it.pluginReceiptPath = join(dir("none"), "plugins.json") }
        assertEquals("no rtok plugins installed\n", Outdated.human(Outdated.report(cfg, OutdatedSelection(listOf("windsurf")), target)))
        val err = runCatching { Outdated.report(cfg, OutdatedSelection(listOf("nope")), target) }.exceptionOrNull()
        assertTrue(err is AgentsFailure && err.message == "unknown host: nope", "$err")
        assertNull(Outdated.pluginStatus(Agents.agent("windsurf")!!, Kind.Desktop, cfg, target))
    }

    @Test fun theJsonRowCarriesThePluginVersionAndSource() {
        val (d, cfg) = home("json", "1.0.0")
        val env = AgentsRun.Env(agent = { cursor }, present = { _, _, _ -> true })
        val row = AgentsJson.row(cfg, cursor, Hosts.host("cursor")!!.variants.first(), env, { "1" }, { a, v -> Outdated.pluginStatus(a, v.kind, cfg, target) })
        assertEquals("\"1.0.0\"", row["plugin_version"].toString())
        assertEquals("\"local\"", row["plugin_source"].toString())
        val absent = AgentsJson.row(cfg, cursor, Hosts.host("cursor")!!.variants.first(), AgentsRun.Env(agent = { cursor }, present = { _, _, _ -> false }), { "1" })
        assertFalse("plugin_version" in absent)
        removeDirAll(d)
    }

    /** T628: the cached copy is the highest SemVer, not the last name in sort order. */
    @Test fun theNewestCachedVersionWinsBySemverNotByName() {
        for ((case, dirs, want) in listOf(
            Triple("minor", listOf("0.9.0", "0.10.0"), "0.10.0"),
            Triple("patch", listOf("0.1.9", "0.1.10", "0.1.2"), "0.1.10"),
            Triple("pre", listOf("0.10.0-rc.1", "0.10.0", "0.9.9"), "0.10.0"),
            Triple("build", listOf("0.9.0+b9", "0.10.0+b1"), "0.10.0+b1"),
            Triple("junk", listOf("latest", "0.2.0", "tmp"), "0.2.0"),
            Triple("junk-only", listOf("tmp", "latest"), "tmp"),
        )) {
            val home = dir(case)
            val base = join(home, ClaudeUpdate.PLUGIN_CACHE)
            for (v in dirs) {
                createDirAll(join(base, v))
                writeString(join(join(base, v), "plugin.json"), """{"version": "$v"}""")
            }
            writeString(join(base, "zzz.txt"), "x")
            val probe = PluginInstall.cacheProbe(home, Source.Github)
            assertEquals(join(join(base, want), PluginVersion.FILE), probe.versionFile, case)
            assertEquals(want, probe.hostRecordVersion, case)
            removeDirAll(home)
        }
    }
}
