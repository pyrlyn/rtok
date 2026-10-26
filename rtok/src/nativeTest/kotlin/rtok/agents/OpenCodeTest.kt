// Port of agents/opencode, agents/kilo and agents/mimo mod.rs tests: the proxy env, the local argv MCP entry (JSON and
// a lone JSONC), and the `plugins/opencode/rtok.ts` link beside each config.
package rtok.agents

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import rtok.agent.sdk.NO_CHANGES
import rtok.config.Config
import rtok.mcp.config.Jsonc
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

class OpenCodeTest {
    private class Scratch(
        val cfg: Config,
        val dir: String,
        val plugins: PluginSource,
    )

    private fun scratch(
        name: String,
        dry: Boolean = false,
    ): Scratch {
        val dir = join(tempDir(), "rtok-opencode-$name-${kotlin.random.Random.nextLong().toULong()}").also(::createDirAll)
        val src = join(dir, "repo")
        createDirAll(join(src, "plugins/opencode"))
        writeString(join(src, OpenCodeFamily.PLUGIN_REL), "export const p = { 'tool.execute.after': () => {} }\n")
        val s = Config().setup
        val setup =
            s.copy(
                dryRun = dry,
                backup = false,
                opencode = s.opencode.copy(configPath = join(dir, "opencode/opencode.json")),
                kilo = s.kilo.copy(configPath = join(dir, "kilo/kilo.json")),
                mimo = s.mimo.copy(configPath = join(dir, "mimo/mimocode.json")),
            )
        return Scratch(Config().copy(setup = setup), dir) { rel -> PluginSrc.joinRel(src, rel) }
    }

    private fun root(path: String) = Jsonc.parse(readToString(path)) as JsonObject

    private fun rtokEntry(path: String) = root(path)["mcp"]!!.jsonObject["rtok"]?.jsonObject

    @Test fun proxyDryRunShowsOneChangeAndCreatesNothing() {
        val s = scratch("dry", dry = true)
        val out = OpenCode.runProxy(s.cfg, false)
        assertTrue("OPENAI_BASE_URL" in out && "8790/v1" in out && "revert:" in out, out)
        assertFalse(exists(s.cfg.setup.opencode.configPath))
        removeDirAll(s.dir)
    }

    @Test fun proxyIsIdempotentAndRemoveStrips() {
        val s = scratch("proxy")
        val path = s.cfg.setup.opencode.configPath
        assertTrue("8790/v1" in OpenCode.runProxy(s.cfg, false))
        assertEquals(NO_CHANGES, OpenCode.runProxy(s.cfg, false))
        assertTrue(readToString(path).let { "OPENAI_BASE_URL" in it && it.endsWith("\n") })
        assertEquals("- env.OPENAI_BASE_URL", OpenCode.runProxy(s.cfg, true))
        assertEquals(NO_CHANGES, OpenCode.runProxy(s.cfg, true))
        assertFalse("OPENAI_BASE_URL" in readToString(path))
        removeDirAll(s.dir)
    }

    @Test fun openCodeInstallsEverythingOnceAndRemoveKeepsForeign() {
        val s = scratch("all")
        val oc = OpenCode(s.plugins)
        val path = s.cfg.setup.opencode.configPath
        createDirAll(join(s.dir, "opencode"))
        writeString(path, """{"mcp":{"other":{"type":"remote","url":"x"}}}""")
        val first = oc.apply(s.cfg, Kind.Cli, Mode.Install)
        assertTrue(first[1].startsWith("mcp.rtok: ") && first[2].startsWith("+ plugin"), "$first")
        val e = rtokEntry(path)!!
        assertEquals(JsonPrimitive("local"), e["type"])
        assertEquals(listOf("mcp", "--host", "opencode"), e["command"]!!.jsonArray.drop(1).map { (it as JsonPrimitive).content })
        assertEquals(JsonPrimitive(true), e["enabled"])
        assertEquals(join(join(s.dir, "opencode/plugins"), "rtok.ts"), OpenCode.PLUGIN.path(s.cfg))
        assertEquals(listOf("proxy", "mcp", "plugin"), oc.installed(s.cfg, Kind.Cli))
        assertTrue(oc.apply(s.cfg, Kind.Cli, Mode.Install).all { it == NO_CHANGES })
        val gone = oc.apply(s.cfg, Kind.Cli, Mode.Remove)
        assertEquals("- mcp.rtok", gone[1])
        assertNull(rtokEntry(path))
        assertTrue("other" in root(path)["mcp"]!!.jsonObject)
        assertEquals(emptyList(), oc.installed(s.cfg, Kind.Cli))
        assertTrue(Agents.agent("opencode") is OpenCode)
        removeDirAll(s.dir)
    }

    @Test fun theDesktopVariantUsesItsOwnConfig() {
        val c = OpenCode.forKind(Config(), Kind.Desktop)
        assertEquals(McpPaths.opencodeDesktop(), c.setup.opencode.configPath)
        assertEquals(McpPaths.opencodeDesktop(), OpenCode().files(Config(), Kind.Desktop).single())
    }

    @Test fun kiloRegistersAndLinksSecondApplyNoChangesRemoveTakesBack() {
        val s = scratch("kilo")
        val kilo = Kilo(s.plugins)
        val dry = Kilo(s.plugins).apply(s.cfg.copy(setup = s.cfg.setup.copy(dryRun = true)), Kind.Cli, Mode.Install)
        assertTrue("plugins/opencode" in dry[1], "$dry")
        assertFalse(exists(s.cfg.setup.kilo.configPath))
        kilo.apply(s.cfg, Kind.Cli, Mode.Install)
        assertEquals(JsonPrimitive("kilo"), rtokEntry(s.cfg.setup.kilo.configPath)!!["command"]!!.jsonArray.last())
        assertEquals(listOf("mcp", "plugin"), kilo.installed(s.cfg, Kind.Cli))
        assertTrue(kilo.apply(s.cfg, Kind.Cli, Mode.Install).all { it == NO_CHANGES })
        kilo.apply(s.cfg, Kind.Cli, Mode.Remove)
        assertEquals(emptyList(), kilo.installed(s.cfg, Kind.Cli))
        assertTrue(kilo.apply(s.cfg, Kind.Cli, Mode.Remove).all { it == NO_CHANGES })
        assertTrue(Agents.agent("kilo") is Kilo)
        removeDirAll(s.dir)
    }

    @Test fun mimoJsoncOnlyHomeGetsTheEntryWithItsCommentsKept() {
        val s = scratch("mimo")
        val mimo = Mimo(s.plugins)
        val json = s.cfg.setup.mimo.configPath
        val jsonc = join(join(s.dir, "mimo"), "mimocode.jsonc")
        val starter = "{\n  // keep me\n  \"theme\": \"dark\", // inline\n}\n"
        createDirAll(join(s.dir, "mimo"))
        writeString(jsonc, starter)
        assertEquals(listOf(jsonc), mimo.files(s.cfg, Kind.Cli))
        assertTrue(mimo.apply(s.cfg.copy(setup = s.cfg.setup.copy(dryRun = true)), Kind.Cli, Mode.Install)[0].startsWith("mcp.rtok: "))
        assertEquals(starter, readToString(jsonc))
        mimo.apply(s.cfg, Kind.Cli, Mode.Install)
        assertFalse(exists(json))
        // The shared JSONC editor keeps every comment; it places the entry before the inline comment, unlike Rust.
        readToString(jsonc).let { assertTrue(it.startsWith("{\n  // keep me\n  \"theme\": \"dark\",") && "// inline" in it, it) }
        assertEquals(JsonPrimitive("mimo"), rtokEntry(jsonc)!!["command"]!!.jsonArray.last())
        assertEquals(listOf("mcp", "plugin"), mimo.installed(s.cfg, Kind.Cli))
        assertEquals("- mcp.rtok", mimo.apply(s.cfg, Kind.Cli, Mode.Remove)[0])
        val left = readToString(jsonc)
        assertTrue("// keep me" in left && "// inline" in left, left)
        // Both present: the .json wins and the .jsonc is never touched again.
        writeString(json, """{"mcp":{"other":{"type":"remote","url":"x"}}}""")
        mimo.apply(s.cfg, Kind.Cli, Mode.Install)
        assertEquals(JsonPrimitive("local"), rtokEntry(json)!!["type"])
        assertEquals(left, readToString(jsonc))
        removeDirAll(s.dir)
    }
}
