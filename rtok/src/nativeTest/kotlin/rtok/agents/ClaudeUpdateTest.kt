// Port of agents/claude/mod.rs `plugin_update_*` tests and plugin_version.rs decision/receipt tests: skip, legacy update,
// fresh install, forced reinstall, the copy's version file first (broken file named, `--force` over it), the receipt.
package rtok.agents

import rtok.agent.sdk.NO_CHANGES
import rtok.config.Config
import rtok.sys.createDirAll
import rtok.sys.join
import rtok.sys.removeDirAll
import rtok.sys.tempDir
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClaudeUpdateTest {
    private val running = "1.2.3"
    private val github = """{"source":"github","repo":"pyrlyn/rtok"}"""

    private fun dir(name: String) = join(tempDir(), "rtok-claude-up-$name-${kotlin.random.Random.nextLong().toULong()}").also { createDirAll("$it/plugins") }

    private fun cfg(
        dir: String,
        dry: Boolean,
        force: Boolean = false,
    ): Config {
        val c = Config()
        val setup = c.setup.copy(dryRun = dry, force = force, backup = false, claude = c.setup.claude.copy(settingsPath = "$dir/settings.json"))
        return c.copy(setup = setup).also { it.pluginReceiptPath = "$dir/plugins.json" }
    }

    private fun record(
        dir: String,
        version: String,
        source: String = github,
    ) {
        writeString("$dir/plugins/installed_plugins.json", """{"version":2,"plugins":{"rtok@rtok":[{"scope":"user","version":"$version"}]}}""")
        writeString("$dir/plugins/known_marketplaces.json", """{"rtok":{"source":$source}}""")
    }

    private fun update(c: Config) = ClaudeUpdate(Claude({ _, _, _ -> "unexpected call" }, { false }, { "/src/$it" }), running).run(c)

    @Test fun skipsWhenTheHostRecordMatchesTheRunningVersion() {
        val d = dir("skip")
        record(d, running)
        assertEquals(NO_CHANGES, update(cfg(d, false)))
        val dry = update(cfg(d, true))
        assertTrue("up to date" in dry && running in dry, dry)
        removeDirAll(d)
    }

    @Test fun aLegacyInstallIsAnUpdate() {
        val d = dir("legacy")
        record(d, "0.0.1")
        val r = update(cfg(d, true))
        assertTrue(r.startsWith("~ plugin") && "claude plugin marketplace update rtok" in r, r)
        removeDirAll(d)
    }

    @Test fun nothingOnRecordInstallsFresh() {
        val d = dir("fresh")
        val r = update(cfg(d, true))
        assertTrue(r.startsWith("would install rtok@rtok from github v$running: "), r)
        assertTrue("claude plugin marketplace add pyrlyn/rtok" in r && "claude plugin install rtok@rtok" in r, r)
        removeDirAll(d)
    }

    @Test fun forceReinstallsEvenWhenUpToDate() {
        val d = dir("force")
        record(d, running)
        assertEquals(
            "would reinstall rtok@rtok from github v$running (forced): claude plugin uninstall rtok@rtok && claude plugin install rtok@rtok",
            update(cfg(d, true, force = true)),
        )
        removeDirAll(d)
    }

    @Test fun theInstalledCopysVersionFileIsReadFirst() {
        val d = dir("copy")
        val copy = "$d/plugins/cache/rtok/rtok/$running".also(::createDirAll)
        writeString("$copy/.rtok-plugin-version", """{"schema":1,"plugin":"claude","version":"0.0.9"}""")
        record(d, running)
        val r = update(cfg(d, true))
        assertTrue(r.startsWith("~ plugin rtok@rtok → "), r)
        writeString("$copy/.rtok-plugin-version", "{")
        val err = assertFailsWith<IllegalStateException> { update(cfg(d, true)) }
        assertTrue(".rtok-plugin-version" in err.message.orEmpty())
        assertTrue(update(cfg(d, true, force = true)).startsWith("would reinstall"))
        removeDirAll(d)
    }

    @Test fun aStaleMarketplaceReinstallsEvenWhenUpToDate() {
        val d = dir("stale")
        record(d, running, """{"source":"github","repo":"listepo/rtok"}""")
        val r = update(cfg(d, true))
        assertTrue(r.startsWith("would reinstall rtok@rtok from github v$running (stale marketplace): "), r)
        removeDirAll(d)
    }
}

class PluginVersionTest {
    @Test fun decideAndLocalVersionFollowSemverPrecedence() {
        val gh = Available("1.0.0", Source.Github)
        assertEquals(Decision.Install, PluginVersion.decide(null, gh, false))
        assertEquals(Decision.Update, PluginVersion.decide(Installed("0.9.0", Source.Github, false), gh, false))
        assertTrue(PluginVersion.decide(Installed("1.1.0", Source.Github, false), gh, false) is Decision.SkipOlder)
        assertTrue(PluginVersion.decide(Installed("1.0.0", Source.Local, false), gh, false) is Decision.Reinstall)
        assertEquals(Decision.Update, PluginVersion.decide(Installed("1.0.0+gaaa", Source.Github, false), Available("1.0.0+gbbb", Source.Github), false))
        assertEquals("0.10.0+gabc123.dirty", PluginVersion.localVersion("0.10.0", "v0.9.0-3-gabc123-dirty"))
        assertEquals("0.10.0+gabc123", PluginVersion.localVersion("0.10.0", "abc123"))
        assertFailsWith<IllegalArgumentException> { Source.parse("ftp") }
    }

    @Test fun theReceiptRoundTripsWithSortedHosts() {
        val d = join(tempDir(), "rtok-receipt-${kotlin.random.Random.nextLong().toULong()}").also(::createDirAll)
        val path = "$d/plugins.json"
        val e = ReceiptEntry(Source.Github, "v1.0.0", "rtok", "/x", "1.0.0", "2026-10-26T00:00:00Z")
        Receipt().upsert("zed", e).upsert("claude", e).write(path)
        val back = Receipt.read(path)
        assertEquals(listOf("claude", "zed"), back.rows.keys.sorted())
        assertEquals(e, back.get("claude"))
        assertEquals(null, back.delete("claude").get("claude"))
        removeDirAll(d)
    }
}
