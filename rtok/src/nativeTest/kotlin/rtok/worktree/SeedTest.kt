@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

// Port of src/worktree/seed.rs tests.
package rtok.worktree

import kotlinx.cinterop.convert
import platform.posix.chmod
import platform.posix.symlink
import rtok.support.tmpDir
import rtok.sys.createDirAll
import rtok.sys.exists
import rtok.sys.parent
import rtok.sys.readToString
import rtok.sys.setModified
import rtok.sys.symlinkMeta
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NANOS = 1_000_000_000L
private const val OLD = 1_000L
private const val NEW = 2_000L
private const val LOCKED = 0
private const val OPEN = 0x1ed

class SeedTest {
    private fun touch(
        path: String,
        secs: Long,
    ) {
        createDirAll(parent(path)!!)
        writeString(path, path)
        setModified(path, secs * NANOS)
    }

    @Test fun bareNamesMatchOneLevelDownAndOnlyFreePlacesCount() {
        val new = "${tmpDir("seed")}/new"
        createDirAll("$new/web/node_modules")
        createDirAll("$new/.hidden")
        val got = Seed.positions(new, listOf("target", "node_modules", "a/b", "../up", "/abs", ""))
        assertEquals(listOf("target", "web/target", "node_modules"), got)
    }

    @Test fun theSiblingThatBuiltLastIsTheSource() {
        val tmp = tmpDir("seed")
        val (old, recent, new) = Triple("$tmp/old", "$tmp/recent", "$tmp/new")
        touch("$old/target/debug/deps/a", OLD)
        touch("$old/target/debug/x", OLD)
        touch("$recent/target/debug/x", NEW)
        createDirAll(new)
        val siblings = listOf(old, new, recent)
        assertEquals(recent, Seed.freshest(siblings, new, "target"))
        assertNull(Seed.freshest(siblings, new, "node_modules"))
    }

    @Test fun runFillsFreeCachesKeepsMtimesAndCountsEveryFile() {
        val tmp = tmpDir("seed")
        val (main, new) = "$tmp/main" to "$tmp/new"
        touch("$main/target/debug/deps/libx.rlib", OLD)
        touch("$main/web/node_modules/p/index.js", OLD)
        createDirAll("$new/web")
        val seeded = Seed.run(listOf(main, new), new, listOf("target", "node_modules"))
        assertEquals(
            listOf("target" to 1L, "web/node_modules" to 1L),
            seeded.map { it.dir to it.files.cloned + it.files.copied },
        )
        assertTrue(seeded.all { it.source == main })
        // APFS clones every file.
        assertTrue(seeded.all { it.files.copied == 0L }, "$seeded")
        val rlib = "$new/target/debug/deps/libx.rlib"
        assertEquals(readToString("$main/target/debug/deps/libx.rlib"), readToString(rlib))
        assertEquals(OLD * NANOS, symlinkMeta(rlib).mtimeNanos)
        assertTrue(Seed.run(listOf(main, new), new, listOf("target")).isEmpty())
    }

    @Test fun aFailedSeedLeavesNoPartialCacheAndNoLinkIsASource() {
        val tmp = tmpDir("seed")
        val (main, new) = "$tmp/main" to "$tmp/new"
        touch("$main/target/a", OLD)
        touch("$main/target/locked/b", OLD)
        chmod("$main/target/locked", LOCKED.toUShort().convert())
        createDirAll(new)
        touch("$tmp/elsewhere/secret", OLD)
        symlink("$tmp/elsewhere", "$main/node_modules")
        val seeded = Seed.run(listOf(main, new), new, listOf("target", "node_modules"))
        chmod("$main/target/locked", OPEN.toUShort().convert())
        assertTrue(seeded.isEmpty(), "$seeded")
        assertFalse(exists("$new/target") || exists("$new/node_modules"))
    }
}
