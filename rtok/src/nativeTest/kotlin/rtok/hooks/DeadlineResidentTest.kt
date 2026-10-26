// Ports of src/hooks/deadline.rs and resident.rs tests (+ a request round trip over the socket).
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class, kotlin.native.concurrent.ObsoleteWorkersApi::class)
package rtok.hooks

import kotlinx.cinterop.*
import platform.posix.*
import rtok.VERSION
import rtok.hook.*
import rtok.sys.*
import kotlin.concurrent.atomics.AtomicInt
import kotlin.native.concurrent.*
import kotlin.test.*

private fun waitFor(ms: Long, ok: () -> Boolean): Boolean { var t = 0L; while (t < ms) { if (ok()) return true; usleep(5000u); t += 5 }; return ok() }

class DeadlineTest {
    @Test fun theWatchdogFiresOnceTheLimitPasses() { val n = AtomicInt(0); arm(20) { n.fetchAndAdd(1) }; assertTrue(waitFor(5000) { n.load() == 1 }) }
    @Test fun aFinishedHookDisarmsTheWatchdog() { val n = AtomicInt(0); arm(50) { n.fetchAndAdd(1) }.close(); usleep(300_000u); assertEquals(0, n.load()) }
    @Test fun onlyTheFirstClaimWins() { val g = Gate(); assertTrue(g.claim()); assertFalse(g.claim()) }
}

class ResidentTest {
    @Test fun bindPrivateLandsAtSocketModeWithoutLeftovers() {
        val dir = "${tempDir()}/rtok-hs-${pid()}"; removeDirAll(dir); createDirAll(dir); val ep = "$dir/hook.sock"
        val fd = assertNotNull(Resident.bindPrivate(ep))
        val mode = memScoped { val st = alloc<stat>(); stat(ep, st.ptr); st.st_mode.toInt() and 0x1ff }
        val names = listDir(dir); close(fd); removeDirAll(dir)
        assertEquals(SOCKET_MODE, mode); assertEquals(listOf("hook.sock"), names)
    }
    @Test fun answersOwnFingerprintRefusesOthersAndFlagsStaleVersions() {
        val dir = "${tempDir()}/rtok-hr-${pid()}"; removeDirAll(dir); createDirAll(dir); val ep = "$dir/hook.sock"
        // answer() chdirs into the request cwd (a resident server does); restore it so later tests keep a live cwd.
        val prevCwd = cwd()
        val lfd = assertNotNull(Resident.bindPrivate(ep))
        Resident.runner = { r -> "out:${r.event}".encodeToByteArray() }
        val w = Worker.start()
        fun call(req: Request): Pair<Result<ByteArray?>?, Boolean> {
            val f = w.execute(TransferMode.SAFE, { lfd }) { l -> val c = accept(l, null, null); try { Resident.answer(c, 42uL) } finally { close(c) } }
            val c = assertNotNull(connect(ep)); val got = exchange(c, req.encode()); close(c)
            return got to f.result
        }
        val (ok, s1) = call(Request(VERSION, 42uL, "PreToolUse", "", dir, ByteArray(0)))
        assertEquals("out:PreToolUse", ok!!.getOrNull()!!.decodeToString()); assertFalse(s1)
        val (refused, s2) = call(Request(VERSION, 7uL, "PreToolUse", "", dir, ByteArray(0))); assertNull(refused!!.getOrNull()); assertFalse(s2)
        val (_, s3) = call(Request("0.0.0", 42uL, "PreToolUse", "", dir, ByteArray(0))); assertTrue(s3)
        w.requestTermination().result; close(lfd); Resident.runner = null; setCwd(prevCwd); removeDirAll(dir)
    }
}
