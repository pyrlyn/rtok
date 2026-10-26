// Port of agents/pi/mod.rs and agents/omp/mod.rs tests: both link `plugins/pi`; omp adds `mcpServers.rtok`.
package rtok.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import rtok.agent.sdk.NO_CHANGES
import rtok.config.Config
import rtok.sys.createDirAll
import rtok.sys.exists
import rtok.sys.join
import rtok.sys.readToString
import rtok.sys.removeDirAll
import rtok.sys.tempDir
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PiTest {
    private class Scratch(
        val cfg: Config,
        val dir: String,
        val plugins: PluginSource,
    )

    private fun scratch(
        name: String,
        dry: Boolean = false,
        yes: Boolean = false,
    ): Scratch {
        val dir = join(tempDir(), "rtok-pi-$name-${kotlin.random.Random.nextLong().toULong()}").also(::createDirAll)
        val src = join(dir, "repo")
        createDirAll(join(src, "plugins/pi"))
        writeString(join(src, "plugins/pi/package.json"), "{\"name\":\"rtok\"}\n")
        val s = Config().setup
        val setup =
            s.copy(
                dryRun = dry,
                yes = yes,
                backup = false,
                pi = s.pi.copy(extensionsPath = join(dir, "pi-ext")),
                omp = s.omp.copy(extensionsPath = join(dir, "omp-ext"), mcpPath = join(dir, "mcp.json")),
            )
        return Scratch(Config().copy(setup = setup), dir) { rel -> PluginSrc.joinRel(src, rel) }
    }

    @Test fun piDryRunOfferNamesThePluginAndKetch() {
        val s = scratch("dry", dry = true)
        val out = Pi(s.plugins).apply(s.cfg, Kind.Cli, Mode.Install).single()
        assertTrue("plugins/pi" in out && "ketch install pyrlyn/rtok" in out, out)
        assertFalse(exists(Pi.PLUGIN.path(s.cfg)))
        removeDirAll(s.dir)
    }

    @Test fun piLinksByDefaultSecondApplyIsNoChangesRemoveUnlinks() {
        val s = scratch("link")
        val pi = Pi(s.plugins)
        assertTrue(pi.apply(s.cfg, Kind.Cli, Mode.Install).single().startsWith("+ plugin"))
        assertEquals(listOf("plugin"), pi.installed(s.cfg, Kind.Cli))
        assertEquals(listOf(NO_CHANGES), pi.apply(s.cfg, Kind.Cli, Mode.Install))
        assertEquals(listOf("- plugin ${Pi.PLUGIN.path(s.cfg)}"), pi.apply(s.cfg, Kind.Cli, Mode.Remove))
        assertFalse(Pi.PLUGIN.linked(s.cfg, s.plugins))
        assertTrue(Agents.agent("pi") is Pi)
        removeDirAll(s.dir)
    }

    @Test fun ompDryRunOffersThePiExtensionAndWritesNothing() {
        val s = scratch("omp-dry", dry = true)
        val lines = Omp(s.plugins).apply(s.cfg, Kind.Cli, Mode.Install)
        assertTrue("plugins/pi" in lines[0] && "ketch install pyrlyn/rtok" in lines[0], "$lines")
        assertFalse(exists(Omp.PLUGIN.path(s.cfg)))
        assertFalse(exists(s.cfg.setup.omp.mcpPath))
        removeDirAll(s.dir)
    }

    @Test fun ompYesLinksAndRegistersThenRemoveTakesBackOnlyOurs() {
        val s = scratch("omp-yes", yes = true)
        val omp = Omp(s.plugins)
        val path = s.cfg.setup.omp.mcpPath
        writeString(path, """{"mcpServers":{"other":{"command":"other","args":[]}}}""")
        assertTrue(omp.apply(s.cfg, Kind.Cli, Mode.Install)[0].startsWith("+ plugin"))
        val rtok =
            Json
                .parseToJsonElement(readToString(path))
                .jsonObject["mcpServers"]!!
                .jsonObject["rtok"]!!
                .jsonObject
        assertEquals(JsonArray(listOf("mcp", "--host", "omp").map(::JsonPrimitive)), rtok["args"])
        assertNull(rtok["type"])
        assertEquals(listOf("plugin", "mcp"), omp.installed(s.cfg, Kind.Cli))
        assertEquals(listOf(NO_CHANGES, NO_CHANGES), omp.apply(s.cfg, Kind.Cli, Mode.Install))
        omp.apply(s.cfg, Kind.Cli, Mode.Remove)
        val servers = Json.parseToJsonElement(readToString(path)).jsonObject["mcpServers"]!!.jsonObject
        assertNull(servers["rtok"])
        assertTrue("other" in servers)
        assertEquals(emptyList(), omp.installed(s.cfg, Kind.Cli))
        removeDirAll(s.dir)
    }

    @Test fun aForeignDirectoryAtTheDestIsNotInstalled() {
        val s = scratch("foreign", yes = true)
        createDirAll(Omp.PLUGIN.path(s.cfg))
        createDirAll(Pi.PLUGIN.path(s.cfg))
        assertFalse("plugin" in Omp(s.plugins).installed(s.cfg, Kind.Cli))
        assertFalse("plugin" in Pi(s.plugins).installed(s.cfg, Kind.Cli))
        removeDirAll(s.dir)
    }
}
