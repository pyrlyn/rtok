package rtok.agent.sdk

import rtok.sys.createDirAll
import rtok.sys.eachOrdered
import rtok.sys.exists
import rtok.sys.isDir
import rtok.sys.isFile
import rtok.sys.join
import rtok.sys.listDir
import rtok.sys.pid
import rtok.sys.readToString
import rtok.sys.removeDirAll
import rtok.sys.removeFile
import rtok.sys.tempDir
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val WORKERS = 16

class PluginCacheTest {
    private fun tmp(name: String): String {
        val dir = join(tempDir(), "rtok-plugin-cache-$name-${pid()}")
        removeDirAll(dir)
        createDirAll(dir)
        return dir
    }

    private fun plugin(root: String): String {
        val src = join(root, "src")
        createDirAll(join(src, "hooks"))
        writeString(join(src, "plugin.json"), """{"name":"demo"}""")
        writeString(join(src, "hooks/run.sh"), "echo hi\n")
        return src
    }

    @Test fun putStoresOneCompressedArchivePerContent() {
        val t = tmp("put")
        val cache = PluginCache.under(join(t, "home"))
        val src = plugin(t)
        val a = cache.put(src)
        val b = cache.put(src)
        assertEquals(a, b)
        assertTrue(isFile(cache.archivePath(a)))
        assertEquals(listOf("$a.tar.gz"), listDir(join(t, "home/cache/plugins/archives")))
        writeString(join(src, "plugin.json"), """{"name":"demo2"}""")
        assertNotEquals(a, cache.put(src))
        removeDirAll(t)
    }

    @Test fun firstUseUnpacksAndLaterLoadsReuseTheCopy() {
        val t = tmp("reuse")
        val cache = PluginCache(join(t, "cache"))
        val hash = cache.put(plugin(t))
        val dir = cache.materialize(hash)
        assertEquals(cache.unpackedPath(hash), dir)
        assertEquals("echo hi\n", readToString(join(dir, "hooks/run.sh")))
        assertEquals(hash, readToString(join(dir, UNPACKED_MARKER)))
        // A file dropped into the reused copy survives: the second load does not unpack again.
        writeString(join(dir, "probe"), "x")
        assertEquals(dir, cache.materialize(hash))
        assertTrue(isFile(join(dir, "probe")))
        // ...but a deep check notices the change and restores the archived content.
        cache.materialize(hash, verify = true)
        assertTrue(!exists(join(dir, "probe")))
        removeDirAll(t)
    }

    @Test fun missingMarkerOrDirIsReunpacked() {
        val t = tmp("marker")
        val cache = PluginCache(join(t, "cache"))
        val hash = cache.put(plugin(t))
        val dir = cache.materialize(hash)
        removeFile(join(dir, UNPACKED_MARKER))
        cache.materialize(hash)
        assertTrue(isFile(join(dir, UNPACKED_MARKER)))
        removeDirAll(dir)
        cache.materialize(hash)
        assertTrue(isFile(join(dir, "plugin.json")))
        removeDirAll(t)
    }

    @Test fun corruptArchiveFailsAndIsDropped() {
        val t = tmp("corrupt")
        val cache = PluginCache(join(t, "cache"))
        val hash = cache.put(plugin(t))
        writeString(cache.archivePath(hash), "not a tarball")
        assertFailsWith<Exception> { cache.materialize(hash) }
        assertTrue(!isDir(cache.unpackedPath(hash)))
        assertTrue(listDir(join(t, "cache/unpacked")).none { it.startsWith(".tmp-") })
        removeDirAll(t)
    }

    @Test fun concurrentLoadsShareOneCompleteCopy() {
        val t = tmp("concurrent")
        val root = join(t, "cache")
        val src = plugin(t)
        val results = mutableListOf<String>()
        // Each worker is its own PluginCache, like separate rtok processes sharing the user cache.
        eachOrdered(List(WORKERS) { it }, { _ ->
            val dir = PluginCache(root).load(src)
            check(isFile(join(dir, UNPACKED_MARKER)) && isFile(join(dir, "hooks/run.sh"))) { "partial dir seen" }
            dir
        }) { _, dir -> results += dir }
        assertEquals(1, results.toSet().size)
        assertEquals(1, listDir(join(root, "archives")).size)
        assertEquals(1, listDir(join(root, "unpacked")).size)
        removeDirAll(t)
    }
}
