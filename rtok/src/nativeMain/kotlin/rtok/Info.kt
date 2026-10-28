// Port of src/info.rs — `rtok info` (plan T43): version, effective paths and disk usage in one place.
// `doctor` reports the host chain and `config show` every key, but neither answers "what does rtok use and how much
// disk does it take". Everything here is fail-open: a missing file or an unreadable store renders as `-`.
package rtok

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import rtok.config.CATALOGUE
import rtok.config.Config
import rtok.measure.stats.SerdeJson
import rtok.store.Store
import rtok.store.callsAfter
import rtok.store.listMeasurements
import rtok.store.sessionTotals
import rtok.store.usageRows
import rtok.store.usageSessions
import rtok.sys.ChangePreview.humanBytes
import rtok.sys.EntryKind

/** One file on disk: its path and bytes, or null bytes when it is missing. */
@Serializable
data class FileInfo(
    val path: String,
    val bytes: Long?,
)

/** A directory on disk: its path, file count and summed bytes. */
@Serializable
data class DirInfo(
    val path: String,
    val files: Long,
    val bytes: Long,
)

/** The regular log: its bytes plus the line and error-line counts (errors are lines whose level is `error`). */
@Serializable
data class LogInfo(
    val path: String,
    val bytes: Long,
    val lines: Long,
    val errors: Long,
)

/** The proxy server: its configured shape plus whether it answers. */
@Serializable
data class ProxyInfo(
    val enabled: Boolean,
    val mode: String,
    val bind: String,
    val port: Int,
    val upstream: String,
    /** `disabled` when `[proxy] enabled` is false, else `up`/`down` from one `/health` probe (the client `doctor` uses). */
    val status: String,
)

/** OpenTelemetry export: off until an endpoint resolves, else its URL. Headers are never printed: they carry ingestion keys. */
@Serializable
data class OtelInfo(
    val enabled: Boolean,
    val endpoint: String,
)

/** Row counts from the store. */
@Serializable
data class StoreInfo(
    val calls: Long,
    val measurements: Long,
    val usage: Long,
    val sessions: Long,
)

/** Everything `rtok info` prints, as data; byte counts stay numbers here and [toText] renders them human-readable. */
@Serializable
data class Info(
    val version: String,
    val binary: FileInfo,
    val home: String,
    val config: FileInfo,
    val db: FileInfo,
    @SerialName("db_wal") val dbWal: FileInfo,
    @SerialName("db_shm") val dbShm: FileInfo,
    val archive: DirInfo,
    val log: LogInfo,
    val proxy: ProxyInfo,
    val otel: OtelInfo,
    val store: StoreInfo?,
    /** Sum of the known files above (config, db + wal + shm, archive, log). */
    @SerialName("total_bytes") val totalBytes: Long,
) {
    /** One `key value` line per row, `-` for missing. */
    fun toText(): String =
        buildString {
            append("rtok $version\n")
            append("binary ${show(binary)}\n")
            append("home $home\n")
            append("config ${show(config)}\n")
            append("db ${show(db)} (wal ${dbWal.bytes?.let(::humanBytes) ?: "-"}; shm ${dbShm.bytes?.let(::humanBytes) ?: "-"})\n")
            append("archive ${archive.path} (${archive.files} files, ${humanBytes(archive.bytes)})\n")
            append("log ${log.path} (${humanBytes(log.bytes)}, ${log.lines} lines, ${log.errors} errors)\n")
            append("proxy ${proxy.mode} ${proxy.bind}:${proxy.port} (${proxy.status}) upstream ${proxy.upstream}\n")
            append(if (otel.enabled) "otel ${otel.endpoint}\n" else "otel off\n")
            append(store?.let { "store calls ${it.calls}, measurements ${it.measurements}, usage ${it.usage}, sessions ${it.sessions}\n" } ?: "store - (unreadable)\n")
            append("disk ${humanBytes(totalBytes)}\n")
        }

    /** `serde_json::to_string_pretty`. */
    fun toJson(): String = SerdeJson.pretty(Json.encodeToJsonElement(serializer(), this))

    companion object {
        private const val MIN_PROBE_MS = 100uL

        private fun show(file: FileInfo) = "${file.path} (${file.bytes?.let(::humanBytes) ?: "-"})"

        private fun fileOf(path: String) = FileInfo(path, rtok.sys.fileLen(path))

        /**
         * Recursive file count and bytes; symlinks are not followed, unreadable entries are skipped — a diagnostic
         * must not fail on permissions.
         */
        private fun dirUsage(path: String): Pair<Long, Long> {
            var files = 0L
            var bytes = 0L
            val stack = ArrayDeque(listOf(path))
            while (stack.isNotEmpty()) {
                val dir = stack.removeLast()
                for (name in rtok.sys.listDirOrEmpty(dir)) {
                    val p = rtok.sys.join(dir, name)
                    when (rtok.sys.entryKind(p)) {
                        EntryKind.Dir -> {
                            stack += p
                        }

                        EntryKind.File -> {
                            files++
                            bytes += rtok.sys.fileLen(p) ?: 0
                        }

                        else -> {}
                    }
                }
            }
            return files to bytes
        }

        /** Lines and error-lines: the level is the third whitespace token (`2026-09-09 15:04:05 error t/n: …`). */
        internal fun countErrors(lines: List<String>): Pair<Long, Long> = lines.size.toLong() to lines.count { l -> l.split(' ', '\t').filter { it.isNotEmpty() }.getOrNull(2) == "error" }.toLong()

        private fun logInfo(cfg: Config): LogInfo {
            val path = cfg.fileLog().path
            // Live file plus rotated siblings, matched by name so no rotation format is duplicated: `rtok.log`, `rtok.log.1`, …
            val name = path.substringAfterLast('/')
            val dir = path.substringBeforeLast('/', "")
            var bytes = rtok.sys.fileLen(path) ?: 0
            if (dir.isNotEmpty() && name.isNotEmpty()) {
                for (n in rtok.sys.listDirOrEmpty(dir)) {
                    if (n == name || !n.startsWith("$name.")) continue
                    bytes += rtok.sys.fileLen(rtok.sys.join(dir, n)) ?: 0
                }
            }
            // One bounded read: rotation caps the total at `max_bytes × (files + 1)`.
            val (lines, errors) = countErrors(logTail(cfg, Int.MAX_VALUE))
            return LogInfo(path, bytes, lines, errors)
        }

        private fun proxyInfo(cfg: Config): ProxyInfo {
            val p = cfg.proxy
            val status =
                if (!p.enabled) {
                    "disabled"
                } else {
                    // `0.0.0.0` never answers a connect; the loopback does on its behalf.
                    val host = if (p.bind == "0.0.0.0") "127.0.0.1" else p.bind
                    val timeout =
                        cfg.doctor.probeTimeoutMs
                            .coerceAtLeast(MIN_PROBE_MS)
                            .toLong()
                    if (rtok.doctor.Page.httpGet("http://$host:${p.port}", "/health", timeout) != null) "up" else "down"
                }
            return ProxyInfo(p.enabled, p.mode, p.bind, p.port.toInt(), p.upstream, status)
        }

        private fun storeInfo(cfg: Config): StoreInfo? =
            runCatching {
                Store.open(cfg.core.dbPath).use { store -> counts(store) }
            }.getOrNull()

        private fun counts(store: Store): StoreInfo {
            val calls = store.callsAfter(0, Long.MAX_VALUE).size.toLong()
            val measurements = CATALOGUE.sumOf { (id, _) -> store.listMeasurements(id).size.toLong() }
            var usage = 0L
            var sessions = 0L
            for (s in store.usageSessions()) {
                sessions++
                usage += store.usageRows(s).size
            }
            return StoreInfo(calls, measurements, usage, maxOf(sessions, store.sessionTotals(0).size.toLong()))
        }

        /** Every row. The DB is stat'ed before the store opens for its counts: opening creates it, and a missing DB still prints `-`. */
        fun collect(
            cfg: Config,
            configFile: String?,
        ): Info {
            val exe = rtok.sys.currentExe()
            val binary = FileInfo(exe ?: "-", exe?.let { rtok.sys.fileLen(it) })
            val config = fileOf(Config.userPath(cfg.home, configFile))
            val db = fileOf(cfg.core.dbPath)
            val dbWal = fileOf("${cfg.core.dbPath}-wal")
            val dbShm = fileOf("${cfg.core.dbPath}-shm")
            val (archiveFiles, archiveBytes) = dirUsage(cfg.core.archiveDir)
            val archive = DirInfo(cfg.core.archiveDir, archiveFiles, archiveBytes)
            val log = logInfo(cfg)
            val proxy = proxyInfo(cfg)
            val endpoint =
                rtok.otel
                    .endpoint(cfg)
                    ?.url
                    .orEmpty()
            val otel = OtelInfo(endpoint.isNotEmpty(), endpoint)
            val store = storeInfo(cfg)
            val total = listOf(config, db, dbWal, dbShm).sumOf { it.bytes ?: 0 } + archive.bytes + log.bytes
            return Info(VERSION, binary, cfg.home, config, db, dbWal, dbShm, archive, log, proxy, otel, store, total)
        }
    }
}
