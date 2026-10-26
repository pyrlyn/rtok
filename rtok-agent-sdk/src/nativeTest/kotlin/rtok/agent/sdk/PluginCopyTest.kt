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

class PluginCopyTest {
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
    fun pluginFromAVersionedStoreSurvivesTheStoreVersionBeingRemoved() {
        val dir = tmp("store-copy")
        val version = join(dir, "store/rtok/v1")
        val src = join(version, "plugins/demo")
        write(join(src, "plugin.json"), "{}")
        val dest = join(dir, "host/plugins/rtok")
        val link = demo(src, dest)
        assertTrue(link.run(yes, false).startsWith("+ plugin"))
        assertEquals(EntryKind.Dir, entryKind(dest), "a copy, not a link into the store")
        assertEquals(LinkHealth.Current, link.health())
        removeDirAll(version)
        assertEquals("{}", readToString(join(dest, "plugin.json")))
        assertTrue(link.ours(), "and the copy is still rtok's to remove")
        removeDirAll(dir)
    }

    @Test
    fun anExistingLinkIntoAVersionedStoreIsReplacedByACopy() {
        val dir = tmp("store-link")
        val src = join(dir, "store/rtok/v1/plugins/demo")
        write(join(src, "plugin.json"), "{}")
        createDirAll(join(dir, "host"))
        val dest = join(dir, "host/rtok")
        symlink(src, dest)
        val link = demo(src, dest)
        assertIs<LinkHealth.Stale>(link.health())
        assertTrue(link.run(yes, false).startsWith("+ plugin"))
        assertTrue(isFile(join(dest, "plugin.json")))
        assertEquals(EntryKind.Dir, entryKind(dest))
        assertEquals(NO_CHANGES, link.run(yes, false))
        removeDirAll(dir)
    }

    @Test
    fun pluginLinkRelinksAStaleOwnedCopy() {
        val dir = tmp("stale-copy")
        val src = join(dir, "plugins/demo")
        write(join(src, "plugin.json"), "v2")
        val dest = join(dir, "host/plugins/rtok")
        copyOwned(src, dest)
        writeString(join(dest, "plugin.json"), "v1")
        val link = demo(src, dest)
        assertTrue(link.ours(), "an owned copy with our marker is ours")
        assertFalse(link.upToDate(keepBytes), "stale content is not up to date")
        assertEquals("+ plugin plugins/demo → $dest", link.run(yes, false))
        assertEquals("v2", readToString(join(dest, "plugin.json")))
        removeDirAll(dir)
    }

    @Test
    fun aFixedOwnedCopyIsUpToDate() {
        val upperHooks: CopyFix = { rel, bytes -> if (rel == "hooks/hooks.json") bytes.decodeToString().uppercase().encodeToByteArray() else bytes }
        val dir = tmp("fixed-copy")
        val src = join(dir, "plugins/demo")
        write(join(src, "plugin.json"), "v1")
        write(join(src, "hooks/hooks.json"), "posix")
        val dest = join(dir, "host/plugins/rtok")
        copyOwned(src, dest, upperHooks)
        assertEquals("POSIX", readToString(join(dest, "hooks/hooks.json")))
        assertEquals("v1", readToString(join(dest, "plugin.json")))
        val link = demo(src, dest)
        assertTrue(link.ours())
        assertTrue(link.upToDate(upperHooks))
        assertFalse(link.upToDate(keepBytes), "unfixed source bytes differ")
        assertEquals(NO_CHANGES, link.run(yes, false, upperHooks))
        removeDirAll(dir)
    }

    @Test
    fun ownedCopyInstallRemoveIsIdempotent() {
        val dir = tmp("owned-copy")
        val src = join(dir, "plugins/demo")
        write(join(src, "plugin.json"), "{}")
        val dest = join(dir, "host/plugins/rtok")
        val link = demo(src, dest, "~/.demo/plugins")
        copyOwned(src, dest)
        assertTrue(isFile(join(dest, OWNED_MARKER)))
        assertTrue(isFile(join(dest, "plugin.json")))
        assertEquals(NO_CHANGES, link.run(yes, false))
        assertEquals("- plugin $dest", link.run(yes, true))
        assertFalse(exists(dest), "owned copy must be removed")
        assertEquals(NO_CHANGES, link.run(yes, true))
        removeDirAll(dir)
    }

    @Test
    fun removeWipesAMaterializedCopyOfOurTree() {
        val dir = tmp("materialized-copy")
        val src = join(dir, "plugins/demo")
        write(join(src, "plugin.json"), "{}")
        write(join(src, "hooks/pre.js"), "ours")
        val dest = join(dir, "host/plugins/rtok")
        write(join(dest, "plugin.json"), "{}")
        write(join(dest, "hooks/pre.js"), "ours")
        write(join(dest, "host-cache.bin"), "host wrote this")
        val link = demo(src, dest)
        assertTrue(link.ours(), "a byte-complete copy of our tree is ours")
        assertEquals("- plugin $dest", link.run(yes, true))
        assertFalse(isDir(dest), "the materialized copy must go")
        assertFalse(link.ours())
        assertEquals(NO_CHANGES, link.run(yes, true))
        removeDirAll(dir)
    }

    @Test
    fun aDirectoryThatDiffersByAByteIsNotOurs() {
        val dir = tmp("not-ours")
        val src = join(dir, "plugins/demo")
        write(join(src, "plugin.json"), "ours")
        val dest = join(dir, "host/plugins/rtok")
        write(join(dest, "plugin.json"), "someone else's")
        val link = demo(src, dest)
        assertTrue(link.linked(), "something is there")
        assertFalse(link.ours(), "but it is not ours")
        removeDirAll(dir)
    }
}
