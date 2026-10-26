// Port of src/log.rs `record`/`append`: the one funnel from a surface to the file log and the `logs` row.
package rtok

import rtok.config.Config
import rtok.log.FileLog
import rtok.store.Store
import rtok.store.insertLog

/** `[log]` as the rotating file the log library writes. */
fun Config.fileLog(): FileLog = FileLog(log.path, log.maxBytes.toLong(), log.files.toInt(), log.level)

/** Append one line to the file log; never fails upward (D1). */
fun appendLog(
    cfg: Config,
    level: String,
    source: String,
    name: String,
    message: String,
) = rtok.log.append(cfg.fileLog(), level, source, name, message)

/** The file line, then the `logs` row when `[log] to_db` and the level passes; each swallows its own errors. */
fun recordLog(
    cfg: Config,
    store: Store,
    session: String?,
    callId: Int?,
    level: String,
    source: String,
    name: String,
    message: String,
) {
    appendLog(cfg, level, source, name, message)
    if (cfg.log.toDb && rtok.log.enabled(cfg.log.level, level)) {
        runCatching { store.insertLog(level, source, name, message, session, callId, null) }
    }
}
