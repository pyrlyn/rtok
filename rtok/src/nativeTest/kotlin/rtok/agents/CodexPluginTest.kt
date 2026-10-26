// Port of agents/codex/mod.rs plugin tests (T140, T242.4, T275): the decision over Codex's own record (enabled,
// known/stale/old-repo marketplace, fresh), the steps run through a fake `codex`, a missing CLI keeping the offer, the
// enabled plugin as the only hook path with MCP still written, and `update` reading the cache.
package rtok.agents

import rtok.agent.sdk.KETCH_INSTALL
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

class CodexPluginTest {
    private val github = "[marketplaces.rtok]\nsource_type = \"git\"\nsource = \"${CodexPlugin.MARKETPLACE_GIT_SOURCE}\"\n"
    private val enabled = "[plugins.\"rtok@rtok\"]\nenabled = true\n"
    private val repoint = "codex plugin marketplace remove rtok && codex plugin marketplace add pyrlyn/rtok && codex plugin add rtok@rtok"

    private class Fake {
        val calls = mutableListOf<List<String>>()
        var fail: String? = null
        val plugin =
            CodexPlugin({ _, args, _ ->
                calls += args
                fail
            }, { true })
    }

    private fun scratch(
        name: String,
        dry: Boolean = false,
        body: String? = null,
    ): Pair<Config, String> {
        val dir = join(tempDir(), "rtok-codex-plugin-$name-${kotlin.random.Random.nextLong().toULong()}").also(::createDirAll)
        val s = Config().setup
        val setup = s.copy(dryRun = dry, backup = false, codex = s.codex.copy(configPath = join(dir, "config.toml")))
        body?.let { writeString(setup.codex.configPath, it) }
        return Config().copy(setup = setup) to dir
    }

    @Test fun enabledFromGithubIsNoChangeAndRemoveOfNothingToo() {
        val (c, dir) = scratch("enabled", body = "$github\n$enabled")
        val f = Fake()
        assertTrue(CodexPlugin.installed(c))
        assertEquals(NO_CHANGES, f.plugin.offer(c, false))
        assertEquals(NO_CHANGES, f.plugin.offer(c.copy(setup = c.setup.copy(dryRun = true)), false))
        val (bare, dir2) = scratch("none")
        assertEquals(NO_CHANGES, f.plugin.offer(bare, true))
        assertTrue(f.calls.isEmpty())
        removeDirAll(dir)
        removeDirAll(dir2)
    }

    @Test fun dryRunStepsFollowTheMarketplaceState() {
        val f = Fake()
        val (known, d1) = scratch("known", dry = true, body = github)
        val r1 = f.plugin.offer(known, false)
        assertTrue("codex plugin add rtok@rtok" in r1 && "marketplace" !in r1, r1)
        val (fresh, d2) = scratch("fresh", dry = true)
        val r2 = f.plugin.offer(fresh, false)
        assertTrue(r2.startsWith("offer plugins/codex → codex plugin marketplace add pyrlyn/rtok && codex plugin add rtok@rtok") && KETCH_INSTALL in r2, r2)
        assertFalse(exists(fresh.setup.codex.configPath))
        val (old, d3) = scratch("old", dry = true, body = "[marketplaces.rtok]\nsource_type = \"git\"\nsource = \"https://github.com/listepo/rtok.git\"\n")
        assertTrue(repoint in f.plugin.offer(old, false))
        val (local, d4) = scratch("local", dry = true, body = "[marketplaces.rtok]\nsource_type = \"local\"\nsource = \"/Users/x/rtok\"\n")
        val r4 = f.plugin.offer(local, false)
        assertTrue(repoint in r4 && "plugin remove" !in r4, r4)
        val (staleOn, d5) = scratch("stale-on", dry = true, body = "[marketplaces.rtok]\nsource_type = \"local\"\nsource = \"/x\"\n\n$enabled")
        assertTrue("codex plugin remove rtok@rtok && $repoint" in f.plugin.offer(staleOn, false))
        assertTrue(f.calls.isEmpty())
        listOf(d1, d2, d3, d4, d5).forEach(::removeDirAll)
    }

    @Test fun stepsRunThroughCodexAndAFailureKeepsTheOffer() {
        val (c, dir) = scratch("run")
        val f = Fake()
        assertEquals("+ plugin plugins/codex → rtok@rtok", f.plugin.offer(c, false))
        assertEquals(listOf(listOf("plugin", "marketplace", "add", "pyrlyn/rtok"), listOf("plugin", "add", "rtok@rtok")), f.calls)
        f.fail = "boom"
        assertTrue(f.plugin.offer(c, false).endsWith("(codex failed: boom)"))
        val missing = CodexPlugin({ _, _, _ -> null }, { false })
        assertTrue("codex not found on PATH" in missing.offer(c, false))
        writeString(c.setup.codex.configPath, "$github\n$enabled")
        f.fail = null
        f.calls.clear()
        assertEquals("- plugin rtok@rtok", f.plugin.offer(c, true))
        assertEquals(listOf(listOf("plugin", "remove", "rtok@rtok"), listOf("plugin", "marketplace", "remove", "rtok")), f.calls)
        removeDirAll(dir)
    }

    @Test fun anEnabledPluginIsTheHookPathAndMcpIsStillWritten() {
        val (c, dir) = scratch("d21", body = enabled)
        val codex = Codex(Fake().plugin)
        codex.apply(c, Kind.Cli, Mode.Install)
        assertTrue("[mcp_servers.rtok]" in readToString(c.setup.codex.configPath))
        assertFalse(exists(Codex.hooksPath(c)) && "rtok hook" in readToString(Codex.hooksPath(c)))
        assertEquals(listOf("mcp", "hooks", "plugin"), codex.installed(c, Kind.Cli))
        removeDirAll(dir)
    }

    @Test fun updateReadsTheCacheAndReinstallsOnAFailedUpgrade() {
        val (c, dir) = scratch("update", body = "$github\n$enabled")
        val cache = join(dir, "plugins/cache/rtok/rtok/1.0.0")
        createDirAll(cache)
        writeString(join(cache, "plugin.json"), "{}")
        var touch = false
        val calls = mutableListOf<List<String>>()
        var fail: String? = null
        val p =
            CodexPlugin({ _, args, _ ->
                calls += args
                if (touch) writeString(join(cache, "plugin.json"), "{\"v\":2}")
                fail.takeIf { args.contains("upgrade") }
            }, { true })
        assertEquals(NO_CHANGES, Codex(p).apply(c, Kind.Cli, Mode.Update)[0])
        touch = true
        assertEquals("~ plugin rtok@rtok updated", p.update(c))
        fail = "broken snapshot"
        assertEquals("~ plugin rtok@rtok reinstalled (update failed: broken snapshot)", p.update(c))
        assertEquals(listOf("plugin", "add", "rtok@rtok"), calls.last())
        assertTrue(p.update(c.copy(setup = c.setup.copy(dryRun = true))).startsWith("~ plugin rtok@rtok (codex plugin marketplace upgrade rtok)"))
        removeDirAll(dir)
    }
}
