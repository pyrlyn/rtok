// Port of src/agents/mod.rs + locate.rs lookup tests (expand_spec, under, find_bin, probe_installed, variant_for,
// app_version).
package rtok.agents

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.chmod
import rtok.config.Config
import rtok.config.envVar
import rtok.sys.createDirAll
import rtok.sys.tempDir
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

private val EXEC_MODE: UShort = "755".toUShort(radix = 8)

private fun scratch(name: String): String = "${tempDir()}/rtok-locate-$name-${kotlin.random.Random.nextLong().toULong()}".also(::createDirAll)

@OptIn(ExperimentalForeignApi::class)
private fun script(
    dir: String,
    name: String,
    body: String,
): String = "$dir/$name".also { writeString(it, "#!/bin/sh\n$body") }.also { chmod(it, EXEC_MODE) }

class HostBinsTest {
    @Test fun expandSpecResolvesHomeAndEnvVars() {
        assertEquals("${McpPaths.home()}/Apps/x", HostBins.expandSpec("~/Apps/x"))
        assertEquals("${envVar("PATH")}/Claude/claude.exe", HostBins.expandSpec("\$PATH/Claude/claude.exe"))
        assertEquals("\$RTOK_NO_SUCH_VAR/app", HostBins.expandSpec("\$RTOK_NO_SUCH_VAR/app"))
        assertEquals("/Applications/Claude.app", HostBins.expandSpec("/Applications/Claude.app"))
    }

    @Test fun underReRootsAPathOutsideHome() {
        val home = "/h/test-home"
        assertEquals("/h/test-home/Applications/Cursor.app", HostBins.under(home, "/Applications/Cursor.app"))
        assertEquals("/h/test-home/.grok/bin/grok", HostBins.under(home, "/h/test-home/.grok/bin/grok"))
        assertEquals("/h/test-home/\$LOCALAPPDATA/app", HostBins.under(home, "\$LOCALAPPDATA/app"))
    }

    @Test fun findBinSkipsDirsOutsideTheLimit() {
        val root = scratch("find-bin")
        val inside = "$root/bin".also(::createDirAll)
        val outside = "$root/other".also(::createDirAll)
        writeString("$inside/claude", "")
        writeString("$outside/claude", "")
        val path = "$outside:$inside"
        assertEquals("$inside/claude", HostBins.findBin("claude", path, inside))
        assertEquals("$outside/claude", HostBins.findBin("claude", path))
        assertNull(HostBins.findBin("claude", path, "$inside/missing"))
        assertNull(HostBins.findBin("claude", null))
    }

    @Test fun probeInstalledIsTheExistingPath() {
        val dir = scratch("probe")
        writeString("$dir/grok", "")
        assertEquals("$dir/grok", HostBins.probeInstalled("$dir/grok"))
        assertNull(HostBins.probeInstalled("$dir/missing"))
    }
}

class LocateTest {
    private val cursor = Hosts.host("cursor")!!

    @Test fun variantForReadsTheNameWithoutADisk() {
        val claude = Hosts.host("claude")!!
        assertEquals(Kind.Desktop, Locate.variantFor(claude, "/Applications/Claude.app")?.kind)
        assertEquals(Kind.Cli, Locate.variantFor(claude, "/usr/local/bin/claude")?.kind)
        assertEquals(Kind.Cli, Locate.variantFor(claude, "/x/unknown-name")?.kind)
        assertTrue(Locate.variantFor(cursor, "/nowhere/Foo.app") != null)
    }

    @Test fun aMissingAppIsNotPresentAndHasNoVersion() {
        val ghost = Variant(Kind.Cli, "ghost", listOf("rtok-no-such-bin-t426"), listOf("/nonexistent/Ghost.app"))
        assertNull(Locate.locate(Config(), "ghost", ghost))
        assertEquals("-", AppVersion.of(Config(), "ghost", ghost))
    }

    @Test fun keyNamesTheSetupPath() = assertEquals("setup.paths.claude", Locate.key("claude"))
}

class AppVersionTest {
    @Test fun looksLikeAVersionTellsANumberFromWrapperNoise() {
        assertTrue(AppVersion.looksLikeAVersion("0.1.0 (fake copilot)"))
        assertTrue(AppVersion.looksLikeAVersion("git version 2.43.0"))
        assertTrue(!AppVersion.looksLikeAVersion("Package extraction took 1234ms"))
        assertTrue(!AppVersion.looksLikeAVersion("some cli"))
        assertTrue(!AppVersion.looksLikeAVersion(""))
    }

    /** T168: npm wrappers print noise ahead of the real version line. */
    @Test fun appVersionSkipsWrapperNoiseAheadOfTheRealVersion() {
        val bin = script(scratch("noise"), "rtok-test-noisy", "echo 'Package extraction took 1234ms'\necho '0.1.0 (fake copilot)'\n")
        assertEquals("0.1.0 (fake copilot)", AppVersion.of(Config(), "copilot", Variant(Kind.Cli, "noisy", listOf(bin), emptyList())))
    }

    /** T280: a `--version` that never answers, with a helper holding its stdout, costs one probe limit. */
    @Test fun appVersionGivesUpOnACliThatNeverAnswers() {
        val bin = script(scratch("hang"), "rtok-test-hang", "sleep 60 &\nsleep 60\n")
        val start = TimeSource.Monotonic.markNow()
        assertEquals("-", AppVersion.of(Config(), "cursor", Variant(Kind.Cli, "hang", listOf(bin), emptyList())))
        val ms = start.elapsedNow().inWholeMilliseconds
        assertTrue(ms < AppVersion.PROBE_LIMIT_MS + SLACK_MS, "$ms ms")
    }

    private companion object {
        const val SLACK_MS = 2_000L
    }
}
