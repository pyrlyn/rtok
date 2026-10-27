package rtok.demon

/** Who a state file's pids may be signalled as (T597): a saved pid is only a number once its owner died. */
enum class Owner {
    /** A supervisor holds the service lock, so both pids are its own. */
    LIVE,

    /** No supervisor, but the state dates from this boot: its child may still be up. */
    ORPHAN,

    /** Nothing proves the pids still belong to us. */
    STALE,
}

/** The demon verbs over one [store], with [procs] for every signal and [say] for every line they print. */
class Demon(
    val store: Store,
    val procs: Procs = OsProcs,
    val say: (String) -> Unit = ::println,
) {
    fun ownership(
        service: Service,
        st: State,
    ): Owner {
        if (store.held(service)) return Owner.LIVE
        val boot = procs.bootTime()
        return if (boot != null && st.since >= boot) Owner.ORPHAN else Owner.STALE
    }

    /** True while the state file still says what [st] said. */
    fun unchanged(
        service: Service,
        st: State,
    ): Boolean = store.read(service)?.let { it.copy(service = st.service) == st } ?: false

    /** Drop the state file, unless a new supervisor has published its own since [st] was read. */
    fun retire(
        service: Service,
        st: State,
    ) {
        if (unchanged(service, st)) store.remove(service, "json")
    }

    /** TERM (or KILL when [force]) [pids], wait, and KILL the ones that stayed while ownership still holds. */
    fun bringDown(
        service: Service,
        st: State,
        pids: List<Int>,
        force: Boolean,
    ) {
        for (pid in pids) if (force) procs.kill(pid) else procs.term(pid)
        for (i in 0 until GRACE_POLLS) {
            if (pids.none(procs::alive)) break
            procs.pause(GRACE_STEP_MS)
        }
        pids
            .filter { procs.alive(it) && unchanged(service, st) && (it != st.supervisor || store.held(service)) }
            .forEach(procs::kill)
        retire(service, st)
    }

    /** The named services, else (for [runningFirst]) every one with a state file, else `[demon] services`. */
    fun targets(
        named: List<Service>,
        configured: List<String>,
        runningFirst: Boolean,
    ): List<Service> {
        val running = if (runningFirst) Service.entries.filter { store.read(it) != null } else emptyList()
        return named.ifEmpty { running }.ifEmpty { configured.map(Service::parse) }
    }

    /** Ask each supervisor to go away, then make sure it did; [force] only skips the grace period. */
    fun stop(
        services: List<Service>,
        force: Boolean,
    ) {
        for (service in services) stopOne(service, force)
    }

    private fun stopOne(
        service: Service,
        force: Boolean,
    ) {
        val st = store.read(service) ?: return say("$service not running")
        val owner = ownership(service, st)
        if (owner == Owner.STALE) {
            say(staleLine(service, st))
            return retire(service, st)
        }
        if (owner == Owner.LIVE) rtok.sys.writeString(store.file(service, "stop"), "")
        bringDown(service, st, signalable(st, owner), force)
        say("$service stopped")
    }

    /** Keep what [stop] stopped down: retire a late respawn and return once no state file comes back (4 s). */
    fun quiesce(services: List<Service>) {
        var waited = 0L
        while (true) {
            var rogue = false
            for (service in services) {
                val st = store.read(service) ?: continue
                rogue = true
                val pids = signalable(st, ownership(service, st))
                if (pids.isNotEmpty()) {
                    say("$service: respawned during the replace window, retiring pid ${st.child}")
                    pids.forEach(procs::kill)
                }
                store.remove(service, "json")
            }
            if (!rogue) return
            if (waited > QUIESCE_MS) throw DemonException("demon surfaces did not stay down for the replace")
            procs.pause(GRACE_STEP_MS)
            waited += GRACE_STEP_MS
        }
    }

    companion object {
        const val GRACE_POLLS = 40
        const val GRACE_STEP_MS = 50L
        const val QUIESCE_MS = 4000L

        fun staleLine(
            service: Service,
            st: State,
        ) = "$service: stale state from before this boot, not signalling pid ${st.child}"
    }
}

/** The pids [owner] allows signalling; positive only, since 0 or a negative pid reaches a process group. */
fun signalable(
    st: State,
    owner: Owner,
): List<Int> =
    when (owner) {
        Owner.LIVE -> listOf(st.supervisor, st.child)
        Owner.ORPHAN -> listOf(st.child)
        Owner.STALE -> emptyList()
    }.filter { it > 0 }

/** `(wait now, wait after the next fast crash)`: a healthy run resets to backoff, a crash loop doubles to the cap. */
fun nextBackoff(
    cur: Long,
    healthy: Boolean,
    backoffMs: Long,
    maxBackoffMs: Long,
): Pair<Long, Long> {
    val wait = if (healthy) backoffMs else cur
    return wait to minOf(if (wait > Long.MAX_VALUE / 2) Long.MAX_VALUE else wait * 2, maxBackoffMs)
}
