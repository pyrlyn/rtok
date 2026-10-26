package rtok.agent.sdk

import rtok.sys.EntryKind
import rtok.sys.createDirAll
import rtok.sys.entryKind
import rtok.sys.exists
import rtok.sys.isDir
import rtok.sys.isFile
import rtok.sys.join
import rtok.sys.pid
import rtok.sys.readLink
import rtok.sys.readToString
import rtok.sys.removeDirAll
import rtok.sys.symlink
import rtok.sys.tempDir
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PluginLinkTest {
    private val yes = Apply(yes = true)

    private fun tmp(name: String): String {
        val dir = join(tempDir(), "rtok-agent-sdk-$name-${pid()}")
        removeDirAll(dir)
        createDirAll(dir)
        return dir
    }

    private fun demo(
        src: String,
        dest: String,
        label: String? = null,
    ) = PluginLink("plugins/demo", src, dest, label, "demo")

    private fun write(
        path: String,
        text: String,
    ) {
        rtok.sys.parent(path)?.let(::createDirAll)
        writeString(path, text)
    }

    @Test
    fun pluginLinkReplacesADanglingLink() {
        val dir = tmp("dangling")
        val src = join(dir, "plugins/demo")
        createDirAll(src)
        createDirAll(join(dir, "host"))
        val dest = join(dir, "host/rtok")
        symlink(join(dir, "gone"), dest)
        val link = demo(src, dest)
        assertTrue(link.run(yes, false).startsWith("+ plugin"))
        assertTrue(exists(dest), "the link reaches the source again")
        assertEquals(NO_CHANGES, link.run(yes, false))
        removeDirAll(dir)
    }

    @Test
    fun pluginLinkRelinksAStaleVersionTarget() {
        val dir = tmp("stale-version")
        val old = join(dir, "store/v1/plugins/demo")
        val new = join(dir, "store/v2/plugins/demo")
        createDirAll(old)
        createDirAll(new)
        createDirAll(join(dir, "host"))
        val dest = join(dir, "host/rtok")
        symlink(old, dest)
        val link = demo(new, dest)
        assertFalse(link.upToDate(keepBytes), "an old version is not up to date")
        assertEquals("+ plugin plugins/demo → $dest", link.run(yes, false))
        assertEquals(new, readLink(dest))
        assertEquals(NO_CHANGES, link.run(yes, false))
        removeDirAll(dir)
    }

    @Test
    fun aVersionedKetchStorePathIsRecognised() {
        val cases =
            listOf(
                "/h/.ketch/store/rtok/v0.17.0-alpha.2/plugins/cursor" to true,
                "/h/.ketch/store/rtok/v0.15.1" to true,
                "/h/.ketch/store/rtok/current/plugins/cursor" to false,
                "/h/.ketch/store/v/plugins" to false,
                "/h/src/rtok/plugins/cursor" to false,
                "/h/store/rtok" to false,
            )
        for ((path, want) in cases) assertEquals(want, inVersionedStore(path), path)
    }

    @Test
    fun healthNamesADanglingLinkAndLeavesForeignAndAbsentAlone() {
        val dir = tmp("health")
        val src = join(dir, "plugins/demo")
        createDirAll(src)
        write(join(dir, "host/foreign/own.txt"), "x")
        assertEquals(LinkHealth.Absent, demo(src, join(dir, "host/none")).health())
        assertEquals(LinkHealth.Foreign, demo(src, join(dir, "host/foreign")).health())
        val dest = join(dir, "host/rtok")
        val gone = join(dir, "store/rtok/v0.15.1/plugins/demo")
        symlink(gone, dest)
        assertEquals(LinkHealth.Dangling(gone), demo(src, dest).health())
        removeDirAll(dir)
    }

    @Test
    fun pluginLinkNeverInstallsOverAForeignDirectory() {
        val dir = tmp("foreign-install")
        val src = join(dir, "plugins/demo")
        write(join(src, "plugin.json"), "ours")
        val dest = join(dir, "host/plugins/rtok")
        write(join(dest, "mine.txt"), "keep")
        val report = demo(src, dest, "~/.demo/plugins").run(yes, false)
        assertTrue(report.startsWith("offer "), report)
        assertTrue(exists(join(dest, "mine.txt")))
        assertFalse(exists(join(dest, "plugin.json")))
        removeDirAll(dir)
    }

    @Test
    fun pluginLinkOffersThenLinksThenUnlinks() {
        val dir = tmp("link")
        val src = join(dir, "plugins/demo")
        write(join(src, "plugin.json"), "v1")
        val link = demo(src, join(dir, "host/plugins/rtok"), "~/.demo/plugins")
        val dry = link.run(Apply(dryRun = true), false)
        assertEquals("offer plugins/demo → ~/.demo/plugins (${link.dest}) $KETCH_INSTALL", dry)
        assertFalse(link.linked(), "a dry run must not link")
        val bare = demo(src, join(dir, "other/plugins/rtok"))
        assertEquals("offer plugins/demo → ${bare.dest} ($src) $KETCH_INSTALL", bare.run(Apply(dryRun = true), false))
        // No terminal, no --yes: the offer declines itself and still says how to accept.
        assertEquals("offer plugins/demo → ~/.demo/plugins (accept with --yes) $KETCH_INSTALL", link.run(Apply(), false))
        assertFalse(link.linked())
        assertEquals("+ plugin plugins/demo → ${link.dest} ~/.demo/plugins", link.run(yes, false))
        assertTrue(link.linked())
        assertEquals(NO_CHANGES, link.run(yes, false))
        assertTrue(link.run(yes, true).startsWith("- plugin"))
        assertFalse(link.linked())
        assertEquals(NO_CHANGES, link.run(yes, true))
        removeDirAll(dir)
    }

    @Test
    fun removeLeavesAForeignDirectoryWithoutMarkerAlone() {
        val dir = tmp("foreign")
        val dest = join(dir, "host/plugins/rtok")
        write(join(dest, "mine.txt"), "keep")
        for (label in listOf("~/.demo/plugins", null)) {
            val report = demo(join(dir, "plugins/demo"), dest, label).run(yes, true)
            assertTrue(report.startsWith("leave "), report)
            assertTrue(exists(join(dest, "mine.txt")), "contents must stay")
        }
        removeDirAll(dir)
    }
}
