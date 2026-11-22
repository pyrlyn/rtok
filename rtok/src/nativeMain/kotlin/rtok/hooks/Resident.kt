// Port of src/hooks/resident.rs — `rtok hook --serve`, the optional resident hook process.
// The `rtok-hook` client sends each hook call over a Unix socket; calls run one at a time in the
// client's cwd. It refuses a client whose environment fingerprint differs, and exits when its home
// goes or a client of another version calls. Nothing depends on it (fail open).
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package rtok.hooks

import kotlinx.cinterop.*
import platform.posix.*
import rtok.VERSION
import rtok.hook.*
import rtok.sys.*

/** The resident's socket is owner-only (`rw-------`). */
const val SOCKET_MODE: Int = 0x180 // 0o600

object Resident {
    /** What `rtok hook <event> [--host]` prints for a request, run in its cwd; set by the dispatcher. */
    var runner: ((Request) -> ByteArray?)? = null

    /** Serve until the home goes away or a client of another version calls. Returns at once when
     *  another resident holds the home or the environment gives it no endpoint. */
    fun serve() {
        // A client that gave up at its deadline must fail the reply's write, not kill the resident with it.
        signal(SIGPIPE, SIG_IGN)
        val env = environ().toMap()
        val home = home { env[it] } ?: return
        val ep = endpoint(home) ?: return
        createDirAll(home)
        val lockPath = "$home/hook.lock"
        val lockFd = tryLockExclusive(lockPath) ?: return
        try {
            listen(ep, lockPath, fingerprint(environ()))
        } finally {
            close(lockFd)
        }
    }

    private fun listen(
        ep: String,
        lock: String,
        fp: ULong,
    ) {
        unlink(ep)
        val fd = bindPrivate(ep) ?: return
        try {
            memScoped {
                val pfd = alloc<pollfd>()
                pfd.fd = fd
                pfd.events = POLLIN.toShort()
                while (true) {
                    val r = poll(pfd.ptr, 1u, 1000)
                    if (r > 0) {
                        val c = accept(fd, null, null)
                        if (c >= 0) {
                            val stale =
                                try {
                                    answer(c, fp)
                                } finally {
                                    close(c)
                                }
                            if (stale) break
                        }
                    }
                    if (!exists(lock) || !exists(ep)) break
                }
            }
        } finally {
            close(fd)
            unlink(ep)
        }
    }

    /** Read one request, answer it. Returns true when the caller is another version (this binary is stale). */
    internal fun answer(
        c: Int,
        fp: ULong,
        readTimeoutMs: Long = DEADLINE_MS,
    ): Boolean {
        // Calls are served one at a time: a client that connects and then stalls must not hold up every other hook.
        receiveTimeout(c, readTimeoutMs)
        val req = runCatching { Request.decode(readFrame { readExact(c, it) }) }.getOrNull()
        val stale = req != null && req.version != VERSION
        val out = if (req != null && !stale && req.fingerprint == fp) run(req) else null
        writeAll(c, encodeResponse(out))
        return stale
    }

    private fun receiveTimeout(
        fd: Int,
        ms: Long,
    ) {
        memScoped {
            val tv = alloc<timeval>()
            tv.tv_sec = (ms / MS_PER_SECOND).convert()
            tv.tv_usec = (ms % MS_PER_SECOND * US_PER_MS).convert()
            setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())
        }
    }

    private const val MS_PER_SECOND = 1000L
    private const val US_PER_MS = 1000L

    private fun run(req: Request): ByteArray? {
        if (chdir(req.cwd) != 0) return null
        return runCatching { runner?.invoke(req) }.getOrNull()
    }

    /** Bind with no window at the umask mode: bind inside a fresh 0700 dir, chmod, rename into place;
     *  falls back to bind-then-chmod when the detour path is too long. */
    fun bindPrivate(ep: String): Int? {
        val parent = rtok.sys.parent(ep) ?: "."
        val dir = "$parent/.hs${pid()}"
        // A crashed run's leftover; if it cannot go, the mkdir below fails and the caller gets null as before.
        runCatching { removeDirAll(dir) }
        if (mkdir(dir, 0x1c0u) != 0) return null // 0o700
        val tmp = "$dir/s"
        var fd = bindUnix(tmp)
        if (fd != null && (chmod(tmp, SOCKET_MODE.convert()) != 0 || rename(tmp, ep) != 0)) {
            close(fd)
            fd = null
        }
        unlink(tmp)
        rmdir(dir)
        if (fd == null) {
            fd = bindUnix(ep) ?: return null
            chmod(ep, SOCKET_MODE.convert())
        }
        return fd
    }
}
