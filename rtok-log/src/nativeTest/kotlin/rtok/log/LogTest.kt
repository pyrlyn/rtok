// Port of crates/rtok-log/src/lib.rs tests.
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package rtok.log

import kotlinx.cinterop.*
import platform.posix.*
import kotlin.test.*

private fun readText(path: String): String {
    val f = fopen(path, "r") ?: error("no $path")
    val sb = StringBuilder()
    memScoped {
        val buf = allocArray<ByteVar>(4096)
        while (true) {
            val n = fread(buf, 1u, 4096u, f).toInt()
            if (n <= 0) break
            sb.append(buf.readBytes(n).decodeToString())
        }
    }
    fclose(f)
    return sb.toString()
}

private fun writeText(
    path: String,
    s: String,
) {
    val f = fopen(path, "w")!!
    fputs(s, f)
    fclose(f)
}

private fun exists(path: String) = access(path, F_OK) == 0

private fun mode(path: String) =
    memScoped {
        val st = alloc<stat>()
        stat(path, st.ptr)
        st.st_mode.toInt() and 0x1ff
    }

private fun tmp(name: String): String {
    val dir = "/tmp/rtok-log-$name-${getpid()}"
    system("rm -rf '$dir'")
    mkdirs(dir)
    return dir
}

private fun rmrf(dir: String) {
    system("rm -rf '$dir'")
}

private fun listDir(dir: String): List<String> {
    val d = opendir(dir)!!
    val out = mutableListOf<String>()
    while (true) {
        val e = readdir(d) ?: break
        val n = e.pointed.d_name.toKString()
        if (n != "." && n != "..") out += n
    }
    closedir(d)
    return out
}

private fun log(
    path: String,
    max: Long,
    files: Int,
) = FileLog(path, max, files, "debug")

class LogTest {
    @Test fun aFailedRotationKeepsTheIncomingLine() {
        val dir = tmp("failed-rotation")
        val path = "$dir/app.log"
        writeText(path, "old\n")
        mkdir(rotatedPath(path, 1), 0x1ed.convert())
        writeLine(log(path, 1, 1), "new")
        assertEquals("old\nnew\n", readText(path))
        rmrf(dir)
    }

    @Test fun aBusyRotationLockKeepsTheIncomingLine() {
        val dir = tmp("busy-rotation")
        val path = "$dir/app.log"
        writeText(path, "old\n")
        val held = openRotateLock(path)
        assertEquals(0, flock(held, LOCK_EX))
        writeLine(log(path, 1, 1), "new")
        assertEquals("old\nnew\n", readText(path))
        assertFalse(exists(rotatedPath(path, 1)))
        close(held)
        writeLine(log(path, 1, 1), "after")
        assertEquals("old\nnew\n", readText(rotatedPath(path, 1)))
        rmrf(dir)
    }

    @Test fun rotationLockIsPrivateAndRefusesSymlinks() {
        val dir = tmp("private-rotation")
        val path = "$dir/app.log"
        val lock = "$dir/app.log.rotate.lock"
        close(openRotateLock(path))
        assertEquals(0x180, mode(lock))
        unlink(lock)
        val target = "$dir/unrelated"
        writeText(target, "untouched")
        symlink(target, lock)
        assertFails { openRotateLock(path) }
        writeText(path, "old\n")
        writeLine(log(path, 1, 1), "new")
        assertEquals("old\nnew\n", readText(path))
        assertEquals("untouched", readText(target))
        unlink(lock)
        mkfifo(lock, 0x180.convert())
        assertFails { openRotateLock(path) }
        writeLine(log(path, 1, 1), "pipe")
        assertEquals("old\nnew\npipe\n", readText(path))
        rmrf(dir)
    }

    @Test fun aNewLogFileIsOwnerOnly() {
        val dir = tmp("mode")
        val path = "$dir/rtok.log"
        writeLine(log(path, 1L shl 20, 1), "x")
        assertEquals(0x180, mode(path))
        rmrf(dir)
    }

    @Test fun noFieldCanStartASecondLine() {
        val got = line(0, "info\n1970-01-01 00:00:00 error", "wasm\r", "p\nq", "m\nn")
        assertEquals("1970-01-01 00:00:00 info 1970-01-01 00:00:00 error wasm /p q: m n", got)
    }

    @Test fun aKnownEpochReadsAsADate() {
        assertEquals("1970-01-01 00:00:00", stamp(0))
        assertEquals("2026-09-09 15:04:05", stamp(1_788_966_245))
        assertEquals("2024-02-29 12:30:45", stamp(1_709_209_845))
        assertEquals("2100-03-01 00:00:00", stamp(4_107_542_400))
    }

    @Test fun theLevelIsAFloorAndAnUnknownLevelAlwaysPasses() {
        assertTrue(enabled("warn", "error") && enabled("warn", "warn"))
        assertTrue(!enabled("warn", "info") && !enabled("warn", "debug"))
        assertTrue(enabled("warn", "PANIC"))
    }

    @Test fun aRotationDecidedOnAStaleSizeIsDroppedUnderTheLock() {
        val dir = tmp("rotate-race")
        val path = "$dir/app.log"
        val l = log(path, 100, 3)
        writeText(rotatedPath(path, 1), "history\n")
        writeText(path, "fresh\n")
        rotateIfOver(l, 10)
        assertEquals("history\n", readText(rotatedPath(path, 1)))
        assertFalse(exists(rotatedPath(path, 2)))
        writeText(path, "x".repeat(99))
        rotateIfOver(l, 10)
        assertEquals("history\n", readText(rotatedPath(path, 2)))
        rmrf(dir)
    }

    @Test fun rotationKeepsFilesPlusTheCurrentOne() {
        val dir = tmp("rotate")
        val path = "$dir/app.log"
        val l = log(path, 200, 2)
        repeat(50) { append(l, "info", "test", "rotate", "line $it") }
        val names = listDir(dir)
        assertEquals(4, names.size, "$names")
        for (w in listOf("app.log", "app.log.1", "app.log.2", "app.log.rotate.lock")) assertTrue(w in names, "$names")
        val live = readText(path)
        assertTrue(live.contains("line 49"))
        assertTrue(live.length <= 200)
        rmrf(dir)
    }

    @Test fun aMessageWithNewlinesStaysOneLine() {
        val dir = tmp("newline")
        val path = "$dir/app.log"
        append(log(path, 1L shl 20, 1), "warn", "test", "multi", "first\nsecond")
        val t = readText(path)
        assertEquals(1, t.trimEnd('\n').lines().size)
        assertTrue(t.contains("first second"))
        rmrf(dir)
    }

    @Test fun errorsAreCopiedAndWarningsStayInTheMainFile() {
        val dir = tmp("split")
        val path = "$dir/app.log"
        val errs = errorPath(path)
        val l = log(path, 1L shl 20, 1)
        appendSplit(l, errs, "warn", "app", "boot", "careful")
        appendSplit(l, errs, "info", "app", "boot", "note")
        appendSplit(l, errs, "error", "app", "boot", "broken")
        val main = readText(path)
        val err = readText(errs)
        assertTrue(main.contains("careful") && main.contains("note") && main.contains("broken"))
        assertTrue(err.contains("broken") && !err.contains("careful") && !err.contains("note"))
        rmrf(dir)
    }

    @Test fun aLineBelowTheLevelTouchesNoDisk() {
        val dir = tmp("quiet")
        val path = "$dir/app.log"
        append(FileLog(path, 1L shl 20, 1, "error"), "debug", "test", "quiet", "nothing")
        assertFalse(exists(path))
        rmrf(dir)
    }
}
