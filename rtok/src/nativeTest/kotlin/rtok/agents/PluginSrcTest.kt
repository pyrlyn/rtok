// Port of agents/mod.rs plugin_src_* / ketch_store_* / join_rel tests.
package rtok.agents

import rtok.sys.canonicalize
import rtok.sys.createDirAll
import rtok.sys.join
import rtok.sys.removeDirAll
import rtok.sys.symlink
import rtok.sys.tempDir
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginSrcTest {
    private val rel = "plugins/cursor"

    private fun tmp(name: String) = join(tempDir(), "rtok-plugin-src-$name-${kotlin.random.Random.nextLong().toULong()}").also(::createDirAll)

    private fun plugin(dir: String) = dir.also { createDirAll(it) }.also { writeString(join(it, "plugin.json"), "{}") }

    private fun exe(path: String) = path.also { rtok.sys.parent(it)?.let(::createDirAll) }.also { writeString(it, "") }

    @Test fun prefersBesideExeThenDevRootThenBesideForAClearError() {
        val root = tmp("beside")
        val plugins = plugin(join(root, "bin/plugins/cursor"))
        assertEquals(plugins, PluginSrc.resolve(rel, exe(join(root, "bin/rtok")), join(root, "missing"), "0.1.5"))
        val dev = plugin(join(root, "dev/plugins/cursor"))
        assertEquals(dev, PluginSrc.resolve(rel, exe(join(root, "lonely/rtok")), join(root, "dev"), "0.1.5"))
        assertEquals(join(root, "empty/plugins/cursor"), PluginSrc.resolve(rel, exe(join(root, "empty/rtok")), join(root, "empty"), "0.1.5"))
        removeDirAll(root)
    }

    @Test fun followsASymlinkedExe() {
        val root = tmp("link")
        val plugins = plugin(join(root, "lib/rtok/bin/plugins/cursor"))
        val real = exe(join(root, "lib/rtok/bin/rtok"))
        createDirAll(join(root, "prefix-bin"))
        symlink(real, join(root, "prefix-bin/rtok"))
        assertEquals(canonicalize(plugins), canonicalize(PluginSrc.resolve(rel, join(root, "prefix-bin/rtok"), null, "0.1.5")))
        removeDirAll(root)
    }

    @Test fun findsThePrefixShareDir() {
        val root = tmp("share")
        val plugins = plugin(join(root, "venv/share/rtok/plugins/cursor"))
        assertEquals(plugins, PluginSrc.resolve(rel, exe(join(root, "venv/bin/rtok")), null, "0.1.5"))
        removeDirAll(root)
    }

    @Test fun findsTheKetchStoreWhenBinHasNoPluginsAndStillPrefersBeside() {
        for (bin in listOf("bin", "Bin")) {
            val root = tmp("ketch-$bin")
            val fake = exe(join(root, "$bin/rtok"))
            val store = plugin(join(root, "store/rtok/v0.1.5/plugins/cursor"))
            assertEquals(store, PluginSrc.resolve(rel, fake, null, "0.1.5"), bin)
            val beside = plugin(join(root, "$bin/plugins/cursor"))
            assertEquals(beside, PluginSrc.resolve(rel, fake, null, "0.1.5"), bin)
            removeDirAll(root)
        }
    }

    @Test fun prefersTheMatchingStoreVersionThenTheNewest() {
        val root = tmp("ketch-ver")
        val fake = exe(join(root, "bin/rtok"))
        val older = plugin(join(root, "store/rtok/v0.1.4/plugins/cursor"))
        val newer = plugin(join(root, "store/rtok/v0.1.5/plugins/cursor"))
        assertEquals(older, PluginSrc.resolve(rel, fake, null, "0.1.4"))
        removeDirAll(join(root, "store/rtok/v0.1.4"))
        assertEquals(newer, PluginSrc.resolve(rel, fake, null, "0.1.4"))
        removeDirAll(root)
    }

    @Test fun ketchStoreTakesTheNewestVersionNotTheLexicographicLast() {
        val root = tmp("semver")
        for (v in listOf("v0.1.9", "v0.1.10", "v0.1.10-alpha.2", "junk")) plugin(join(root, "store/rtok/$v/plugins/cursor"))
        assertEquals(join(root, "store/rtok/v0.1.10/plugins/cursor"), PluginSrc.ketchStorePlugin(root, rel, "9.9.9"))
        assertNull(PluginSrc.ketchStorePlugin(root, "plugins/none", "9.9.9"))
        removeDirAll(root)
    }

    @Test fun versionKeyFollowsSemverPrecedence() {
        val order = listOf("0.1.9", "0.1.10-alpha", "0.1.10-alpha.2", "0.1.10-alpha.10", "0.1.10-beta", "0.1.10", "1.0.0")
        val keys = order.map { VersionKey.parse(it)!! }
        assertEquals(keys, keys.shuffled().sorted())
        assertNull(VersionKey.parse("current"))
        assertNull(VersionKey.parse("1.2"))
    }

    @Test fun joinRelSplitsSlashAndBackslash() {
        assertEquals("/tmp/root/plugins/cursor", PluginSrc.joinRel("/tmp/root", "plugins/cursor"))
        assertEquals("/tmp/root/plugins/cursor", PluginSrc.joinRel("/tmp/root", "plugins\\cursor"))
        assertTrue(PluginSrc.joinRel("/tmp/root", "").endsWith("root"))
    }
}
