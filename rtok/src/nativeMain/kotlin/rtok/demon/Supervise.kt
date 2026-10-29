package rtok.demon

import rtok.fileLog
import rtok.log.append
import rtok.sys.StreamedChild
import rtok.sys.createDirAll
import rtok.sys.currentExe
import rtok.sys.exists
import kotlin.time.Clock
import kotlin.time.TimeSource

/**
 * The detached half (Rust `demon::supervise`): spawn the service, pipe its output into `<svc>.log` through the
 * rotating log sink, wait, spawn it again; runs until the stop marker. A second supervisor leaves at once.
 */
fun Lifecycle.supervise(service: Service) {
    createDirAll(store.dir)
    val lock = store.claim(service) ?: return
    lock.use {
        val exe = currentExe() ?: throw DemonException("cannot find the rtok binary")
        val stop = store.file(service, "stop")
        val log = cfg.fileLog().copy(path = store.file(service, "log"))
        val d = cfg.demon
        var st = State(service.id, rtok.sys.pid(), 0, nowSecs(), 0)
        var backoff = d.backoffMs.toLong()
        val sink = { err: Boolean, line: String -> append(log, if (err) "warn" else "info", "demon", service.id, line) }
        while (!exists(stop)) {
            val child = StreamedChild.spawn(exe, argv(service.args))
            st = st.copy(child = child.pid, since = nowSecs())
            store.write(st)
            val started = TimeSource.Monotonic.markNow()
            while (child.tryWait() == null || !child.drained) {
                if (exists(stop)) {
                    child.kill(sink)
                    store.remove(service, "json")
                    return
                }
                child.pump(d.pollMs.toInt(), sink)
            }
            val healthy = started.elapsedNow().inWholeMilliseconds >= d.healthyMs.toLong()
            val (wait, next) = nextBackoff(backoff, healthy, d.backoffMs.toLong(), d.maxBackoffMs.toLong())
            backoff = next
            st = st.copy(restarts = st.restarts + 1)
            store.write(st)
            OsProcs.pause(wait)
        }
        store.remove(service, "json")
    }
}

private fun nowSecs() = Clock.System.now().epochSeconds
