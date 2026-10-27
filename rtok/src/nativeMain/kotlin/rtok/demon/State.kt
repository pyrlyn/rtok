@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package rtok.demon

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.posix.LOCK_EX
import platform.posix.LOCK_NB
import platform.posix.O_CLOEXEC
import platform.posix.O_CREAT
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.open
import rtok.agent.sdk.writeAtomic
import rtok.sys.exists
import rtok.sys.readToString
import rtok.sys.removeFile

/** What `status` reads; the supervisor is the only writer. */
@Serializable
data class State(
    val service: String,
    val supervisor: Int,
    val child: Int,
    /** Unix seconds the current child started. */
    val since: Long,
    val restarts: Int,
)

/** Where one demon keeps its files: `<stateDir>/<service>.<ext>`. */
class Store(
    val dir: String,
) {
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    fun file(
        service: Service,
        ext: String,
    ) = "$dir/$service.$ext"

    fun read(service: Service): State? = runCatching { json.decodeFromString<State>(readToString(file(service, "json"))) }.getOrNull()

    /** Atomic swap, never truncate-then-write: readers poll this file while the supervisor rewrites it. */
    fun write(st: State) = writeAtomic(file(Service.parse(st.service), "json"), json.encodeToString(State.serializer(), st).encodeToByteArray())

    fun remove(
        service: Service,
        ext: String,
    ) {
        if (exists(file(service, ext))) runCatching { removeFile(file(service, ext)) }
    }

    /**
     * An exclusive non-blocking flock on `<service>.lock`: the held lock, or null while another supervisor holds it.
     * Opened close-on-exec, so a spawned service never inherits the lock and cannot keep it after its supervisor.
     */
    fun claim(service: Service): Claim? {
        val path = file(service, "lock")
        val fd = open(path, O_CREAT or O_WRONLY or O_CLOEXEC, 0x1a4)
        if (fd < 0) throw DemonException("cannot open $path")
        if (rtok.sys.fileLock(fd, LOCK_EX or LOCK_NB) != 0) {
            close(fd)
            return null
        }
        return Claim(fd)
    }
}

/** A held service lock; [close] drops it. */
class Claim(
    private val fd: Int,
) : AutoCloseable {
    override fun close() {
        close(fd)
    }
}

/** True when the lock is held elsewhere; a probe that wins drops at once. */
fun Store.held(service: Service): Boolean {
    val probe = claim(service) ?: return true
    probe.close()
    return false
}
