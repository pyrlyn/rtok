// Port of agents/zcode/mod.rs tests: hooks under `hooks.events` with `timeoutMs`, `plugins.dirs` kept in step with the
// linked plugin (the only hook path once linked, D21), `mcp.servers.rtok` independent of it, foreign dirs left alone.
package rtok.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
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
import kotlin.test.assertTrue

class ZcodeTest {
    private companion object {
        const val MS_PER_S = 1000L
    }

    private class Scratch(
        val cfg: Config,
        val dir: String,
        val zcode: Zcode,
    ) {
        val path get() = cfg.setup.zcode.configPath
    }

    private fun scratch(
        name: String,
        dry: Boolean = false,
        yes: Boolean = false,
    ): Scratch {
        val dir = join(tempDir(), "rtok-zcode-$name-${kotlin.random.Random.nextLong().toULong()}").also(::createDirAll)
        val src = join(dir, "repo")
        createDirAll(join(src, "plugins/zcode"))
        writeString(join(src, "plugins/zcode/plugin.json"), "{\"name\":\"rtok\"}\n")
        val s = Config().setup
        val setup = s.copy(dryRun = dry, yes = yes, backup = false, zcode = s.zcode.copy(configPath = join(dir, "cli/config.json")))
        return Scratch(Config().copy(setup = setup), dir, Zcode { rel -> PluginSrc.joinRel(src, rel) })
    }

    private fun root(path: String) = Json.parseToJsonElement(readToString(path)).jsonObject

    private fun mcpArgs0(path: String) =
        root(path)["mcp"]!!
            .jsonObject["servers"]!!
            .jsonObject["rtok"]!!
            .jsonObject["args"]!!
            .jsonArray[0]

    @Test fun dryRunNamesFiveEntriesAndCreatesNothing() {
        val s = scratch("dry", dry = true)
        val report = Zcode.run(s.cfg, false)
        assertTrue(report.startsWith("+ hooks.enabled = true\n") && "5 additions" in report && "+ SessionStart " in report, report)
        assertFalse("SessionEnd" in report)
        assertFalse(exists(s.path))
        assertTrue(Agents.agent("zcode") is Zcode)
        removeDirAll(s.dir)
    }

    @Test fun plainHooksAreIdempotentAndRemoveKeepsForeign() {
        val s = scratch("hooks")
        createDirAll(join(s.dir, "cli"))
        writeString(s.path, """{"hooks":{"events":{"Stop":[{"hooks":[{"type":"command","command":"echo other"}]}]}}}""")
        assertTrue("5 additions" in Zcode.run(s.cfg, false))
        assertEquals(NO_CHANGES, Zcode.run(s.cfg, false))
        val bash = root(s.path)["hooks"]!!.jsonObject["events"]!!.jsonObject["PreToolUse"]!!.jsonArray
        val hook = bash[0].jsonObject["hooks"]!!.jsonArray[0].jsonObject
        assertEquals(
            JsonPrimitive(
                s.cfg.setup.hookTimeoutS
                    .toLong() * MS_PER_S,
            ),
            hook["timeoutMs"],
        )
        assertEquals("5 removed", Zcode.run(s.cfg, true))
        val left = readToString(s.path)
        assertTrue("echo other" in left && " hook PreToolUse" !in left, left)
        removeDirAll(s.dir)
    }

    @Test fun dryRunOfferNamesPluginAndLocal() {
        val s = scratch("offer", dry = true)
        val out = s.zcode.offerPlugin(s.cfg, false)
        assertTrue("plugins/zcode" in out && "~/.zcode/cli/plugins/local" in out && "ketch install pyrlyn/rtok" in out, out)
        assertFalse(exists(s.path))
        removeDirAll(s.dir)
    }

    @Test fun linkedPluginIsTheOnlyHookPathAndMcpIsIndependent() {
        val s = scratch("singleton")
        val linking = s.zcode.apply(s.cfg, Kind.Desktop, Mode.Install).joinToString("\n")
        assertTrue("+ plugin" in linking && "+ plugins.dirs +=" in linking, linking)
        val raw = readToString(s.path)
        assertFalse(" hook PreToolUse" in raw, raw)
        assertEquals(JsonPrimitive(Zcode.PLUGIN.path(s.cfg)), root(s.path)["plugins"]!!.jsonObject["dirs"]!!.jsonArray[0])
        assertEquals(JsonPrimitive("mcp"), mcpArgs0(s.path))
        assertEquals(listOf("hooks", "mcp", "plugin"), s.zcode.installed(s.cfg, Kind.Desktop))
        assertTrue(s.zcode.apply(s.cfg, Kind.Desktop, Mode.Install).all { it == NO_CHANGES })
        s.zcode.apply(s.cfg, Kind.Desktop, Mode.Remove)
        assertFalse(Zcode.PLUGIN.linked(s.cfg))
        assertTrue(Zcode.PLUGIN.path(s.cfg) !in readToString(s.path))
        assertEquals(emptyList(), s.zcode.installed(s.cfg, Kind.Desktop))
        removeDirAll(s.dir)
    }

    @Test fun aForeignPluginDirKeepsPlainHooksAndMcpWorking() {
        val s = scratch("foreign", yes = true)
        val dest = Zcode.PLUGIN.path(s.cfg)
        createDirAll(dest)
        writeString(join(dest, "mine.txt"), "not rtok's plugin")
        Zcode.run(s.cfg, false)
        val lines = s.zcode.apply(s.cfg, Kind.Desktop, Mode.Install).joinToString("\n")
        assertFalse("plugins.dirs" in lines, lines)
        assertEquals("not rtok's plugin", readToString(join(dest, "mine.txt")))
        assertTrue(" hook PreToolUse" in readToString(s.path))
        assertEquals(JsonPrimitive("mcp"), mcpArgs0(s.path))
        assertEquals(listOf("hooks", "mcp"), s.zcode.installed(s.cfg, Kind.Desktop))
        removeDirAll(s.dir)
    }
}
