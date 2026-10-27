// Port of crates/rtok-store/src/lib.rs + handle.rs: one SQLite file — WAL mode, FTS5, migrations
// keyed by directory version (diesel-compatible `__diesel_schema_migrations`). Prints nothing.
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package rtok.store

import kotlinx.cinterop.*
import platform.posix.*
import rtok.db.*
import rtok.sys.*
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

class StoreException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** How long one connection waits on another process's lock (T178). Milliseconds. */
data class LockWait(
    val busyMs: Long,
    val attempts: Int,
    val migrateMs: Long,
) {
    companion object {
        val STEADY = LockWait(1000, 10, 30_000)
    }
}

data class StoreWarning(
    val what: String,
    val message: String,
)

data class RetentionJob(
    val dbPath: String,
    val retainCallsDays: UInt,
    val retainHookBodiesDays: UInt,
)

const val BATCH_CALL_KIND = "api_request:batch"
const val FLEX_SERVICE_TIER = "flex"
internal const val FORGOTTEN_CONTEXT = "\u0001forgotten"
internal const val HOOK_BODY_BATCH = 5_000L
private const val OPEN_RETRY_DELAY_MS = 100L

/** Unix seconds; same value `rtok_log::now` returns. */
fun unixNow(): Long = time(null)

fun hexSha256(bytes: ByteArray): String = Sha256.hex(bytes)

/** Run `op` again, up to `attempts` times with `pauseMs` between, while it fails on another process's lock. */
fun <T> retryLocked(
    attempts: Int,
    pauseMs: Long,
    op: () -> T,
): T {
    val n = max(attempts, 1)
    var tried = 1
    while (true) {
        try {
            return op()
        } catch (e: Throwable) {
            if (tried < n && isLocked(e)) {
                tried++
                usleep((pauseMs * 1000).toUInt())
            } else {
                throw e
            }
        }
    }
}

/** Every blank-separated token quoted, so FTS5 syntax is searched literally; null when no token. */
internal fun ftsPhraseQuery(query: String): String? =
    query
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .map { "\"" + it.replace("\"", "\"\"") + "\"" }
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" ")

internal fun keepsNoteVersions(kind: String) = !kind.startsWith("checkpoint:") && !kind.startsWith("session:")

fun retentionScore(
    pinned: Boolean,
    uses: Int,
    ageDays: Double,
    daysSinceUsed: Double?,
): Double {
    if (pinned) return 1.0
    val salience = 0.5 + min(uses * 0.02, 0.2)
    val temporal = exp(-0.01 * ageDays)
    val boost = if (daysSinceUsed != null && daysSinceUsed > 0.0) 0.3 / daysSinceUsed else 0.0
    return min(salience * temporal + boost, 1.0)
}

internal val FORGET_KEEPS = listOf("user", "decision")
internal const val RANK_WINDOW = 3u
internal const val PROJECT_BOOST = 1.3

fun rankBlend(
    rank: Int,
    retention: Double,
): Double = (0.4 + 0.6 * retention) / (8.0 + rank)

internal fun ageDays(
    now: Long,
    ts: Long,
): Double = max((now - ts).toDouble() / 86_400.0, 0.0)

/** A recursive mutex: one connection, one caller at a time (Rust `Mutex<SqliteConnection>`). */
internal class ConnLock {
    private val m = nativeHeap.alloc<pthread_mutex_t>()

    init {
        memScoped {
            val a = alloc<pthread_mutexattr_t>()
            pthread_mutexattr_init(a.ptr)
            pthread_mutexattr_settype(a.ptr, PTHREAD_MUTEX_RECURSIVE.convert())
            pthread_mutex_init(m.ptr, a.ptr)
            pthread_mutexattr_destroy(a.ptr)
        }
    }

    inline fun <T> with(body: () -> T): T {
        pthread_mutex_lock(m.ptr)
        try {
            return body()
        } finally {
            pthread_mutex_unlock(m.ptr)
        }
    }
}

class Store private constructor(
    internal val db: Db,
    private val wait: LockWait,
) : AutoCloseable {
    private val lock = ConnLock()

    /** `[core] store_raw` (T431): save request bodies verbatim instead of cleaned. */
    var storeRaw: Boolean = false
    internal var idfMemo: Any? = null

    internal fun <T> conn(body: (Db) -> T): T = lock.with { body(db) }

    override fun close() = db.close()

    companion object {
        private var longMigrate: ((String, () -> Int) -> Int)? = null

        /** Spinner for a migration whose wait is at least a second; the store itself prints nothing. */
        fun onLongMigrate(hook: (String, () -> Int) -> Int) {
            if (longMigrate == null) longMigrate = hook
        }

        /** Test tally of fresh connections (T203). */
        var openCount = 0

        fun open(path: String) = openWith(path, LockWait.STEADY)

        fun openWith(
            path: String,
            wait: LockWait,
        ): Store {
            parent(path)?.let { createPrivateDir(it) }
            val attempts = max(wait.attempts, 1)
            for (attempt in 0 until attempts) {
                try {
                    return connect(path, wait).also { restrictDbFiles(path) }
                } catch (e: Throwable) {
                    if (isLocked(e) && attempt + 1 < attempts) {
                        usleep((OPEN_RETRY_DELAY_MS * 1000).toUInt())
                        continue
                    }
                    throw StoreException("$path: ${e.message}", e)
                }
            }
            error("open: the retry loop always returns")
        }

        private fun connect(
            path: String,
            wait: LockWait,
        ): Store {
            val fresh = fileLen(path).let { it == null || it == 0L }
            val db = Db.open(path)
            try {
                db.exec("PRAGMA busy_timeout = ${wait.busyMs}")
                if (fresh) db.exec("PRAGMA auto_vacuum = INCREMENTAL")
                db.exec("PRAGMA journal_mode = WAL")
                db.exec("PRAGMA synchronous = NORMAL")
                return init(db, wait)
            } catch (e: Throwable) {
                db.close()
                throw e
            }
        }

        fun openInMemory(): Store = init(Db.open(":memory:"), LockWait.STEADY)

        private fun init(
            db: Db,
            wait: LockWait,
        ): Store {
            openCount++
            db.exec("PRAGMA foreign_keys = ON")
            val s = Store(db, wait)
            s.migrate()
            return s
        }

        /** Housekeeping at session start; warnings instead of errors. */
        fun housekeeping(job: RetentionJob): List<StoreWarning> {
            fun warn(
                what: String,
                e: Throwable,
            ) = StoreWarning(what, "$what skipped until next start: ${e.message}")
            val store =
                try {
                    open(job.dbPath)
                } catch (e: Throwable) {
                    return listOf(warn("retention", e))
                }
            store.use {
                try {
                    it.runRetention(job.retainCallsDays, job.retainHookBodiesDays)
                } catch (e: Throwable) {
                    return listOf(warn("retention", e))
                }
                return try {
                    it.convertToIncrementalVacuum()
                    emptyList()
                } catch (e: Throwable) {
                    listOf(warn("vacuum", e))
                }
            }
        }
    }

    internal fun setBusy(ms: Long) = db.exec("PRAGMA busy_timeout = $ms")

    internal val busyMs get() = wait.busyMs

    /** Apply pending migrations; returns how many ran. Idempotent. One `BEGIN EXCLUSIVE` for the apply. */
    fun migrate(): Int =
        conn { db ->
            Migrations.bridgeLegacy(db)
            if (!Migrations.hasPending(db)) return@conn 0
            setBusy(wait.migrateMs)
            val apply = {
                db.transaction("EXCLUSIVE") {
                    Migrations.bridgeLegacy(db)
                    Migrations.runPending(db)
                }
            }
            try {
                val hook = longMigrate
                if (wait.migrateMs < 1000 || hook == null) apply() else hook("migrating the store…", apply)
            } finally {
                setBusy(wait.busyMs)
            }
        }

    /** Run `f` with a 30 s busy timeout (maintenance steps outlive the steady bound). */
    internal fun <T> maintenance(f: (Db) -> T): T =
        conn { db ->
            setBusy(30_000)
            try {
                f(db)
            } finally {
                setBusy(wait.busyMs)
            }
        }
}

/** `dir` and missing parents, owner-only (0700). An existing directory keeps its mode. */
fun createPrivateDir(dir: String) {
    if (dir.isEmpty() || isDir(dir)) return
    parent(dir)?.let { if (!isDir(it)) createPrivateDir(it) }
    if (mkdir(dir, 0x1c0u) != 0 && !isDir(dir)) throw IoException(IoException.Kind.Other, "cannot create $dir")
}

/** Owner-only read/write for the database and its WAL/SHM siblings; best effort. */
private fun restrictDbFiles(path: String) {
    for (p in listOf(path, "$path-wal", "$path-shm")) {
        val m = fileMode(p) ?: continue
        if (m and 0x3f != 0) chmod(p, 0x180u)
    }
}

/** `write` with mode 0600: archived bodies are prompts and tool output. */
internal fun writePrivate(
    path: String,
    body: ByteArray,
) {
    val fd = open(path, O_WRONLY or O_CREAT or O_TRUNC, 0x180)
    if (fd < 0) throw IoException(IoException.Kind.Other, "cannot write $path")
    try {
        var off = 0
        body.usePinned { p ->
            while (off < body.size) {
                val n = write(fd, p.addressOf(off), (body.size - off).convert())
                if (n <= 0) throw IoException(IoException.Kind.Other, "short write $path")
                off += n.toInt()
            }
        }
    } finally {
        close(fd)
    }
}

private var archiveSeq = 0L

/** Write `body` to `dir/<sha>` atomically (temp + rename). Returns the path and whether this call created it. */
internal fun writeArchiveFile(
    dir: String,
    sha: String,
    body: ByteArray,
): Pair<String, Boolean> {
    createPrivateDir(dir)
    val path = "$dir/$sha"
    val created = !exists(path)
    val tmp = "$dir/.$sha.tmp-${pid()}-${archiveSeq++}"
    try {
        writePrivate(tmp, body)
        if (rename(tmp, path) != 0) throw IoException(IoException.Kind.Other, "rename $tmp")
    } catch (e: Throwable) {
        unlink(tmp)
        throw e
    }
    return path to created
}
