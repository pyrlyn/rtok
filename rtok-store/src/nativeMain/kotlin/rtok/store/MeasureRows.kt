// Port of crates/rtok-store/src/measure_rows.rs.
package rtok.store

import rtok.db.*
import rtok.plugin.sdk.Measurement

fun Store.insertMeasurement(
    session: String,
    m: Measurement,
) = insertMeasurementOnce(session, m, null)

fun Store.insertMeasurementOnce(
    session: String,
    m: Measurement,
    once: String?,
) = conn { insertMeasurementDb(it, session, m, once) }

fun Store.insertMeasurementsOnce(
    session: String,
    ms: List<Measurement>,
    once: String?,
) = conn { db -> db.transaction("IMMEDIATE") { ms.forEach { insertMeasurementDb(db, session, it, once) } } }

fun Store.measurementCount(plugin: String): Long = conn { it.longOf("SELECT count(*) FROM measurements WHERE plugin = ?", plugin)!! }

fun Store.listMeasurements(plugin: String): List<MeasRow> =
    conn { db ->
        db.query("SELECT kind, before_bytes, after_bytes, est_before, est_after, ref_id FROM measurements WHERE plugin = ? ORDER BY id ASC", plugin) {
            MeasRow(it.string(0), it.long(1), it.long(2), it.int(3), it.int(4), it.stringOrNull(5))
        }
    }

private fun total(
    r: Row,
    o: Int,
) = MeasurementTotal(r.string(o), r.string(o + 1), r.long(o + 2), r.longOrNull(o + 3) ?: 0, r.longOrNull(o + 4) ?: 0)

fun Store.measurementTotals(): List<MeasurementTotal> =
    conn { db ->
        db.query("SELECT plugin, kind, count(*), sum(est_before), sum(est_after) FROM measurements GROUP BY plugin, kind ORDER BY plugin, kind") { total(it, 0) }
    }

fun Store.measurementTotalsSince(since: Long): List<Pair<Long, MeasurementTotal>> =
    conn { db ->
        db.query("SELECT ts, plugin, kind, count(*), sum(est_before), sum(est_after) FROM measurements WHERE ts >= ? GROUP BY plugin, kind, ts ORDER BY ts", since) { it.long(0) to total(it, 1) }
    }

/** Tokens saved per host slug (null = unknown host) in `[since, until)`, sorted with null first. */
fun Store.measurementSavedByHost(
    since: Long,
    until: Long,
): List<Pair<String?, Long>> =
    conn { db ->
        val rows =
            db.query("SELECT session, sum(est_before), sum(est_after) FROM measurements WHERE ts >= ? AND ts < ? GROUP BY session", since, until) {
                Triple(it.string(0), it.longOrNull(1) ?: 0, it.longOrNull(2) ?: 0)
            }
        val hostOf = hostBySession(db)
        val by = HashMap<String?, Long>()
        for ((s, b, a) in rows) {
            val h = hostOf[s]
            by[h] = (by[h] ?: 0) + b - a
        }
        by.entries.sortedWith(compareBy({ it.key != null }, { it.key ?: "" })).map { it.key to it.value }
    }

fun Store.setMeasurementTs(
    session: String,
    ts: Long,
) = conn {
    it.execute("UPDATE measurements SET ts = ? WHERE session = ?", ts, session)
    Unit
}

fun Store.memoryNoteAggs(project: String?): List<MemoryNoteKindAgg> =
    conn { db ->
        val sql =
            "SELECT project, kind, SUM(CASE WHEN retired IS NULL THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN retired IS NULL AND pinned != 0 THEN 1 ELSE 0 END), SUM(CASE WHEN retired IS NOT NULL THEN 1 ELSE 0 END), " +
                "SUM(length(body)), min(ts), max(ts) FROM notes WHERE kind NOT LIKE 'checkpoint:%' AND kind NOT LIKE 'session:%'" +
                (if (project != null) " AND project = ?" else "") + " GROUP BY project, kind ORDER BY project, kind"
        val args: Array<Any?> = if (project != null) arrayOf(project) else emptyArray()
        db.query(sql, *args) {
            fun u(i: Int) = maxOf(it.longOrNull(i) ?: 0, 0).toULong()
            MemoryNoteKindAgg(it.stringOrNull(0), it.string(1), u(2), u(3), u(4), it.longOrNull(5) ?: 0, it.longOrNull(6) ?: 0, it.longOrNull(7) ?: 0)
        }
    }

/** `(recalls, bytes the recalls stood for, bytes injected)` since `sinceUnix`. */
fun Store.memoryRecallTotals(sinceUnix: Long): Triple<ULong, Long, Long> =
    conn { db ->
        db.queryOne("SELECT count(*), SUM(before_bytes), SUM(after_bytes) FROM measurements WHERE plugin = 'memory' AND kind = 'recall' AND ts >= ?", sinceUnix) {
            Triple(maxOf(it.long(0), 0).toULong(), it.longOrNull(1) ?: 0, it.longOrNull(2) ?: 0)
        }!!
    }

/** `(mem_search calls, mem_get calls)` over MCP since `sinceUnix`. */
fun Store.memoryMcpCalls(sinceUnix: Long): Pair<ULong, ULong> =
    conn { db ->
        db.queryOne(
            "SELECT SUM(CASE WHEN name = 'mem_search' THEN 1 ELSE 0 END), SUM(CASE WHEN name = 'mem_get' THEN 1 ELSE 0 END) FROM calls " +
                "WHERE plugin = 'memory' AND surface = 'mcp' AND kind = 'mcp_call' AND ts >= ?",
            sinceUnix,
        ) {
            maxOf(it.longOrNull(0) ?: 0, 0).toULong() to maxOf(it.longOrNull(1) ?: 0, 0).toULong()
        }!!
    }

fun Store.lastMeasurementRef(
    session: String,
    plugin: String,
    kind: String,
): String? =
    conn {
        it.queryOne("SELECT ref_id FROM measurements WHERE session = ? AND plugin = ? AND kind = ? ORDER BY id DESC LIMIT 1", session, plugin, kind) { r -> r.stringOrNull(0) }
    }
