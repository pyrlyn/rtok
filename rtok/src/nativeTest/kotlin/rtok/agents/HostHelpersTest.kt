// Port of agents/mod.rs `shell_quote_bin_*` tests, plus own tests for the shared hook/report/offer helpers.
package rtok.agents

import rtok.agent.sdk.Apply
import rtok.agent.sdk.KETCH_INSTALL
import rtok.agent.sdk.NO_CHANGES
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

private fun cfg(
    dry: Boolean = false,
    yes: Boolean = false,
) = Config().let { it.copy(setup = it.setup.copy(dryRun = dry, yes = yes)) }

class HookLineTest {
    private val h = HookLine

    @Test fun shellQuoteBinQuotesPathsWithSpaces() {
        assertEquals("rtok", h.shellQuoteBin("rtok"))
        val spaced = "C:\\Users\\Ivan Tuhai\\.ketch\\bin\\rtok.exe"
        val quoted = h.shellQuoteBin(spaced)
        assertTrue(quoted.startsWith('"') && quoted.endsWith('"'), quoted)
        assertEquals(spaced, h.unquoteBin(quoted))
        assertTrue(isRtokBin(h.unquoteBin(quoted)))
    }

    @Test fun shellQuoteBinEscapesEmbeddedQuotesForCmd() {
        assertEquals("\"C:\\Path With Spaces\\rtok\"\"x\"\".exe\"", h.shellQuoteBin("C:\\Path With Spaces\\rtok\"x\".exe"))
    }

    @Test fun unquoteBinStripsOneMatchingLayer() {
        assertEquals("rtok", h.unquoteBin("  'rtok' "))
        assertEquals("\"rtok", h.unquoteBin("\"rtok"))
        assertEquals("\"rtok'", h.unquoteBin("\"rtok'"))
        assertEquals("", h.unquoteBin("\"\""))
        assertEquals("rtok", h.rtokHookBin())
    }

    @Test fun hookResolverTriesPathThenKetchThenFailsOpen() {
        assertEquals(
            "command -v rtok >/dev/null 2>&1 && exec rtok hook x; " +
                "[ -x \"\$HOME/.ketch/bin/rtok\" ] && exec \"\$HOME/.ketch/bin/rtok\" hook x; true; exit 0",
            h.hookResolver("hook x"),
        )
        assertTrue(h.hookResolver("hook x", "{\"a\":1}").endsWith("true && printf '%s' '{\"a\":1}'; exit 0"))
    }
}

class StripReportTest {
    private val h = StripReport

    @Test fun takesHookKeepsAnEditedOneWithoutYes() {
        val kept = mutableListOf<String>()
        assertTrue(h.takesHook(Apply(), true, { error("not asked") }, kept))
        assertFalse(h.takesHook(Apply(dryRun = true), false, { "hooks.x" }, kept))
        assertTrue(h.takesHook(Apply(yes = true), false, { "hooks.y" }, kept))
        assertEquals(listOf("? hooks.x (changed by you; remove asks)"), kept)
    }

    @Test fun removedAndKeptReports() {
        assertEquals(NO_CHANGES, h.removedReport(0))
        assertEquals("2 removed", h.removedReport(2))
        assertEquals(NO_CHANGES, h.withKept(emptyList(), NO_CHANGES))
        assertEquals("leave a", h.withKept(listOf("leave a"), NO_CHANGES))
        assertEquals("leave a\n1 removed", h.withKept(listOf("leave a"), "1 removed"))
    }
}

class HostCliTest {
    private val h = HostCli

    @Test fun cliLabelNamesTheVerbOnly() {
        assertEquals("running gemini extensions link…", h.cliLabel("gemini", listOf("extensions", "link", "/tmp/x")))
    }

    @Test fun runCliOfAnUnknownHostIsNotFound() {
        assertEquals("nohost: not found", h.runCli(Config(), "nohost", listOf("x")))
    }
}

class PluginOffersTest {
    private val h = PluginOffers

    @Test fun manifestNamesReadsTheTopLevelName() {
        val dir = join(tempDir(), "rtok-manifest-${kotlin.random.Random.nextLong().toULong()}").also(::createDirAll)
        assertFalse(h.manifestNames(dir, "m.json", "rtok"))
        writeString(join(dir, "m.json"), "{\"name\":\"rtok\"}")
        assertTrue(h.manifestNames(dir, "m.json", "rtok"))
        assertFalse(h.manifestNames(dir, "m.json", "other"))
        writeString(join(dir, "m.json"), "not json")
        assertFalse(h.manifestNames(dir, "m.json", "rtok"))
        removeDirAll(dir)
    }

    private val offer = PluginOffer("gemini", "rtok", "plugins/gemini", listOf("extensions", "link"), listOf("extensions", "uninstall"))
    private val src = PluginSource { "/src/$it" }

    @Test fun offerPluginGatesOnYesAndTheMarker() {
        val never: CliCall = { error("not called") }
        assertEquals(NO_CHANGES, h.offerPlugin(cfg(), false, false, offer, src, never))
        assertEquals(NO_CHANGES, h.offerPlugin(cfg(yes = true), false, true, offer, src, never))
        assertEquals(NO_CHANGES, h.offerPlugin(cfg(yes = true), true, false, offer, src, never))
        assertEquals(
            "offer plugins/gemini → gemini extensions link /src/plugins/gemini $KETCH_INSTALL",
            h.offerPlugin(cfg(dry = true, yes = true), false, false, offer, src, never),
        )
        assertEquals("- plugin rtok (gemini extensions uninstall rtok)", h.offerPlugin(cfg(dry = true), true, true, offer, src, never))
    }

    @Test fun offerPluginRunsTheVerbAndFoldsAFailure() {
        val seen = mutableListOf<List<String>>()
        assertEquals(
            "+ plugin plugins/gemini → rtok",
            h.offerPlugin(cfg(yes = true), false, false, offer, src) {
                seen += it
                null
            },
        )
        assertEquals(
            "- plugin rtok",
            h.offerPlugin(cfg(), true, true, offer, src) {
                seen += it
                null
            },
        )
        assertEquals(listOf(listOf("extensions", "link", "/src/plugins/gemini"), listOf("extensions", "uninstall", "rtok")), seen)
        assertEquals(
            "offer plugins/gemini → gemini extensions link /src/plugins/gemini (gemini failed: boom)",
            h.offerPlugin(cfg(yes = true), false, false, offer, src) { "boom" },
        )
    }

    @Test fun printOfferNeverRuns() {
        assertEquals(NO_CHANGES, h.printOffer(cfg(), false, false, "plugins/kimi", "/plugins install x", "keep"))
        assertEquals("offer plugins/kimi → /plugins install x $KETCH_INSTALL", h.printOffer(cfg(yes = true), false, false, "plugins/kimi", "/plugins install x", "keep"))
        assertEquals("keep", h.printOffer(cfg(), true, true, "plugins/kimi", "i", "keep"))
        assertEquals(NO_CHANGES, h.printOffer(cfg(), true, false, "plugins/kimi", "i", "keep"))
    }
}

class ProgressRunnerEnvTest {
    @Test fun progressRunnerEnvReachesTheChild() {
        val out =
            rtok.sys
                .ProgressRunner("", "/bin/sh", listOf("-c", "printf %s \"\$RTOK_T_ENV\""))
                .quiet()
                .env("RTOK_T_ENV", "v1")
                .runCapped(1024)
                .first
        assertEquals("v1", out.stdout.decodeToString())
        assertNull(out.exitCode.takeIf { it != 0 })
    }
}
