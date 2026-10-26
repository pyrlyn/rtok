// Port of crates/rtok-store/src/call_log.rs: calls, tokens, usage, logs.
package rtok.store

fun Store.insertCall(sessionId: String, surface: String, kind: String, hostId: Int?, providerId: Int?, modelId: Int?, plugin: String?, name: String?): Int = conn { db ->
    db.queryOne("INSERT INTO calls (session_id, surface, kind, host_id, provider_id, model_id, plugin, name) VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
        sessionId, surface, kind, hostId, providerId, modelId, plugin, name) { it.int(0) }!!
}

fun Store.setCallParent(id: Int, parent: Int) = conn { it.execute("UPDATE calls SET parent_id = ? WHERE id = ?", parent, id); Unit }
fun Store.setCallServiceTier(id: Int, tier: String) = conn { it.execute("UPDATE calls SET service_tier = ? WHERE id = ?", tier, id); Unit }
fun Store.setCallMs(id: Int, ms: Double) = conn { it.execute("UPDATE calls SET ms = ? WHERE id = ?", ms, id); Unit }
fun Store.setCallKind(id: Int, kind: String) = conn { it.execute("UPDATE calls SET kind = ? WHERE id = ?", kind, id); Unit }
fun Store.setCallTs(callId: Int, ts: Long) = conn { it.execute("UPDATE calls SET ts = ? WHERE id = ?", ts, callId); Unit }
fun Store.countKind(kind: String): Long = conn { it.longOf("SELECT count(*) FROM calls WHERE kind = ?", kind)!! }
fun Store.callIdsOfKind(kind: String): List<Int> = conn { it.query("SELECT id FROM calls WHERE kind = ?", kind) { r -> r.int(0) } }

fun Store.callIoArchives(callId: Int): Pair<String?, String?> = conn { db ->
    db.queryOne("SELECT request_archive, response_archive FROM call_io WHERE call_id = ?", callId) { it.stringOrNull(0) to it.stringOrNull(1) } ?: (null to null)
}
fun Store.callIoShas(callId: Int): Pair<String?, String?> = conn { db ->
    db.queryOne("SELECT request_sha256, response_sha256 FROM call_io WHERE call_id = ?", callId) { it.stringOrNull(0) to it.stringOrNull(1) } ?: (null to null)
}

/** Call id → archive id (response first, then request, then a measurement's ref_id). Sorted by call id. */
fun Store.archiveRefIds(callIds: List<Int>): Map<Int, String> {
    val out = HashMap<Int, String>()
    if (callIds.isEmpty()) return out
    return conn { db ->
        val list = callIds.joinToString(",")
        db.query("SELECT call_id, request_archive, response_archive FROM call_io WHERE call_id IN ($list)") { Triple(it.int(0), it.stringOrNull(1), it.stringOrNull(2)) }
            .forEach { (id, req, res) -> (res ?: req)?.let { out[id] = it } }
        db.query("SELECT call_id, ref_id FROM measurements WHERE call_id IN ($list) AND ref_id IS NOT NULL") { it.intOrNull(0) to it.stringOrNull(1) }
            .forEach { (id, ref) -> if (id != null && ref != null && id !in out) out[id] = ref }
        out.entries.sortedBy { it.key }.associate { it.key to it.value }
    }
}

fun Store.tokenPhases(callId: Int): List<String> = conn { it.query("SELECT phase FROM tokens WHERE call_id = ?", callId) { r -> r.string(0) } }
fun Store.countCallIo(): Long = conn { it.longOf("SELECT count(*) FROM call_io")!! }
fun Store.countTokens(): Long = conn { it.longOf("SELECT count(*) FROM tokens")!! }
fun Store.countMeasurements(): Long = conn { it.longOf("SELECT count(*) FROM measurements")!! }
fun Store.countUsage(): Long = conn { it.longOf("SELECT count(*) FROM usage")!! }
fun Store.countCalls(): Long = conn { it.longOf("SELECT count(*) FROM calls")!! }

internal fun Store.callSession(callId: Int): String = conn { it.stringOf("SELECT session_id FROM calls WHERE id = ?", callId) } ?: throw StoreException("call $callId has no session")

fun Store.setStoreRaw(raw: Boolean) { storeRaw = raw }

fun Store.insertTokens(callId: Int, plugin: String?, phase: String, source: String, nTokens: Long) = conn {
    it.execute("INSERT INTO tokens (call_id, plugin, phase, source, tokens) VALUES (?, ?, ?, ?, ?)", callId, plugin, phase, source, nTokens); Unit
}

fun Store.insertUsage(session: String, model: String?, api: String, input: Long, cacheCreate: Long, cacheRead: Long, output: Long, callId: Int) = conn {
    it.execute("INSERT INTO usage (session, model, api, input, cache_create, cache_read, output, call_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        session, model, api, input, cacheCreate, cacheRead, output, callId); Unit
}

/** Insert-or-ignore each row (a `result_key` already seen is skipped); returns how many were written. */
fun Store.insertUsageRows(session: String, callId: Int, rows: List<UsageRowInput>): Int = conn { db ->
    db.transaction {
        rows.sumOf { r -> db.execute("INSERT OR IGNORE INTO usage (session, model, api, input, cache_create, cache_read, output, call_id, result_key) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            session, r.model, r.api, r.legs[0], r.legs[1], r.legs[2], r.legs[3], callId, r.key) }
    }
}

fun Store.insertProxyTurn(session: String, input: Long, cacheCreate: Long, cacheRead: Long, output: Long) {
    upsertSession(session, null, null, null, "proxy")
    val id = insertCall(session, "proxy", "api_request", null, null, null, null, "/v1/messages")
    insertUsage(session, "m", "anthropic", input, cacheCreate, cacheRead, output, id)
}

fun Store.insertUsageAt(session: String, host: String?, model: String, ts: Long, legs: LongArray) {
    val hostId = host?.let { hostId(it) }
    upsertSession(session, hostId, null, null, "proxy")
    val call = insertCall(session, "proxy", "api_request", null, null, null, null, "/v1/messages")
    insertUsage(session, model, "anthropic", legs[0], legs[1], legs[2], legs[3], call)
    conn { it.execute("UPDATE usage SET ts = ? WHERE call_id = ?", ts, call) }
}

fun Store.insertProviderTokens(callId: Int, total: Long, input: Long, cacheCreate: Long, cacheRead: Long, output: Long) = conn {
    it.execute("INSERT INTO tokens (call_id, phase, source, tokens, input, output, cache_create, cache_read) VALUES (?, 'after', 'provider', ?, ?, ?, ?, ?)",
        callId, total, input, output, cacheCreate, cacheRead); Unit
}

fun Store.usageSessions(): List<String> = conn { it.query("SELECT session FROM usage GROUP BY session ORDER BY min(ts), min(id)") { r -> r.string(0) } }

fun Store.usageRows(session: String): List<UsageRow> = conn { db ->
    db.query("SELECT session, model, api, input, cache_create, cache_read, output, call_id FROM usage WHERE session = ? ORDER BY ts DESC, id DESC", session) {
        UsageRow(it.string(0), it.stringOrNull(1), it.string(2), it.long(3), it.long(4), it.long(5), it.long(6), it.longOrNull(7))
    }
}

/** Cumulative context tokens re-sent (CTT) and the last `turns` per-turn context sizes, oldest first. */
fun Store.usageCtt(turns: Long): Pair<Long, List<Long>> = conn { db ->
    val ctt = db.longOf("SELECT COALESCE(SUM(ctx * (total - rn)), 0) FROM (\n" +
        "                SELECT input + cache_create + cache_read AS ctx,\n" +
        "                       COUNT(*) OVER (PARTITION BY session) AS total,\n" +
        "                       ROW_NUMBER() OVER (PARTITION BY session ORDER BY ts, id) AS rn\n" +
        "                FROM usage)") ?: 0
    val tail = db.query("SELECT u.input + u.cache_create + u.cache_read FROM usage u " +
        "JOIN (SELECT session, MIN(ts) AS first_ts, MIN(id) AS first_id FROM usage GROUP BY session) f ON f.session = u.session " +
        "ORDER BY f.first_ts DESC, f.first_id DESC, u.ts DESC, u.id DESC LIMIT ?", turns) { it.long(0) }.reversed()
    ctt to tail
}

fun Store.usageByApi(): List<ApiUsage> = conn { db ->
    db.query("SELECT api, SUM(input), SUM(cache_create), SUM(cache_read), SUM(output) FROM usage GROUP BY api ORDER BY api") {
        ApiUsage(it.string(0), it.longOrNull(1) ?: 0, it.longOrNull(2) ?: 0, it.longOrNull(3) ?: 0, it.longOrNull(4) ?: 0)
    }
}

fun Store.usageByLaneTier(): List<LaneUsage> = conn { db ->
    db.query("SELECT calls.kind, calls.service_tier, SUM(usage.input), SUM(usage.cache_create), SUM(usage.cache_read), SUM(usage.output) " +
        "FROM usage INNER JOIN calls ON calls.id = usage.call_id GROUP BY calls.kind, calls.service_tier ORDER BY calls.kind, calls.service_tier") {
        LaneUsage(it.string(0), it.stringOrNull(1), it.longOrNull(2) ?: 0, it.longOrNull(3) ?: 0, it.longOrNull(4) ?: 0, it.longOrNull(5) ?: 0)
    }
}

fun Store.usageByModel() = modelUsage(false)
fun Store.usageByModelTier() = modelUsage(true)
fun Store.usageByLaneModelTier() = laneModelUsage(true)

private fun Store.modelUsage(byTier: Boolean): List<ModelUsage> {
    val byModel = HashMap<String, ModelUsage>()
    for (row in laneModelUsage(byTier)) {
        val u = row.usage
        val e = byModel.getOrPut(u.model) { ModelUsage(u.model, 0, 0, 0, 0) }
        e.input += u.input; e.cacheCreate += u.cacheCreate; e.cacheRead += u.cacheRead; e.output += u.output
    }
    return byModel.values.sortedBy { it.model }
}

private fun Store.laneModelUsage(byTier: Boolean): List<LaneModelUsage> {
    val rows = conn { db ->
        db.query("SELECT usage.model, calls.kind, calls.service_tier, SUM(usage.input), SUM(usage.cache_create), SUM(usage.cache_read), SUM(usage.output) " +
            "FROM usage LEFT JOIN calls ON calls.id = usage.call_id GROUP BY usage.model, calls.kind, calls.service_tier") {
            listOf(it.stringOrNull(0), it.stringOrNull(1), it.stringOrNull(2)) to longArrayOf(it.longOrNull(3) ?: 0, it.longOrNull(4) ?: 0, it.longOrNull(5) ?: 0, it.longOrNull(6) ?: 0)
        }
    }
    val merged = HashMap<Pair<String?, String>, LaneModelUsage>()
    for ((k, legs) in rows) {
        val (m, kind, tier) = k
        var model = m ?: "unknown"
        if (byTier && kind == BATCH_CALL_KIND) model += "@batch" else if (byTier && tier == FLEX_SERVICE_TIER) model += "@flex"
        val e = merged.getOrPut(kind to model) { LaneModelUsage(kind, ModelUsage(model, 0, 0, 0, 0)) }
        e.usage.input += legs[0]; e.usage.cacheCreate += legs[1]; e.usage.cacheRead += legs[2]; e.usage.output += legs[3]
    }
    // BTreeMap<(Option<String>, String)> order: None first, then by kind, then model.
    return merged.entries.sortedWith(compareBy<Map.Entry<Pair<String?, String>, LaneModelUsage>>({ it.key.first != null }, { it.key.first ?: "" }, { it.key.second })).map { it.value }
}

fun Store.usageSlices(since: Long, until: Long): List<UsageSlice> = conn { db ->
    val rows = db.query("SELECT api, session, model, ts, SUM(input), SUM(cache_create), SUM(cache_read), SUM(output) FROM usage " +
        "WHERE ts >= ? AND ts < ? GROUP BY api, session, model, ts", since, until) {
        UsageSlice(null, it.string(0), it.string(1), it.stringOrNull(2), it.long(3), it.longOrNull(4) ?: 0, it.longOrNull(5) ?: 0, it.longOrNull(6) ?: 0, it.longOrNull(7) ?: 0)
    }
    val hostOf = hostBySession(db)
    rows.map { it.copy(host = hostOf[it.session]) }
}

fun Store.sessionTotals(since: Long) = recentSessionTotals(since, -1)

fun Store.recentSessionTotals(since: Long, limit: Long): List<SessionTotals> = conn { db ->
    db.query("""WITH tot AS (
                 SELECT session AS sid, SUM(input) AS input, SUM(cache_create) AS cache_create,
                        SUM(cache_read) AS cache_read, SUM(output) AS output
                 FROM usage GROUP BY session
             ),
             last_u AS (
                 SELECT session AS sid, api, model FROM usage
                 WHERE id IN (SELECT MAX(id) FROM usage GROUP BY session)
             ),
             act AS (
                 SELECT sid, MAX(ts) AS ts FROM (
                     SELECT session AS sid, ts FROM usage
                     UNION ALL
                     SELECT session_id AS sid, ts FROM calls
                 ) GROUP BY sid
             ),
             prov AS (
                 SELECT c.session_id AS sid, p.slug AS provider
                 FROM calls c JOIN providers p ON p.id = c.provider_id
                 WHERE c.id IN (SELECT MAX(id) FROM calls
                                WHERE provider_id IS NOT NULL GROUP BY session_id)
             )
             SELECT s.id, h.slug, s.project,
                    prov.provider, last_u.api, last_u.model,
                    COALESCE(tot.input, 0),
                    COALESCE(tot.cache_create, 0),
                    COALESCE(tot.cache_read, 0),
                    COALESCE(tot.output, 0),
                    s.started_at,
                    COALESCE(act.ts, s.started_at),
                    s.ended_at
             FROM sessions s
             LEFT JOIN hosts h ON h.id = s.host_id
             LEFT JOIN tot ON tot.sid = s.id
             LEFT JOIN last_u ON last_u.sid = s.id
             LEFT JOIN act ON act.sid = s.id
             LEFT JOIN prov ON prov.sid = s.id
             WHERE s.started_at >= ? ORDER BY s.started_at DESC, s.id LIMIT ?""", since, limit) {
        SessionTotals(it.string(0), it.stringOrNull(1), it.stringOrNull(2), it.stringOrNull(3), it.stringOrNull(4), it.stringOrNull(5),
            it.long(6), it.long(7), it.long(8), it.long(9), it.long(10), it.long(11), it.longOrNull(12))
    }
}

fun Store.recentCalls(limit: Long): List<CallRow> = conn { db ->
    db.query("""SELECT c.id, c.ts, c.session_id, c.surface,
                    c.kind, c.plugin, c.name,
                    c.parent_id, c.ms, c.ok, c.error,
                    h.slug, p.slug, m.slug,
                    u.api, u.input, u.cache_create,
                    u.cache_read, u.output
             FROM calls c
             LEFT JOIN hosts h ON h.id = c.host_id
             LEFT JOIN providers p ON p.id = c.provider_id
             LEFT JOIN models m ON m.id = c.model_id
             LEFT JOIN usage u ON u.call_id = c.id
                  AND u.id = (SELECT MAX(id) FROM usage WHERE call_id = c.id)
             ORDER BY c.id DESC
             LIMIT ?""", limit) {
        CallRow(it.int(0), it.long(1), it.string(2), it.string(3), it.string(4), it.stringOrNull(5), it.stringOrNull(6), it.intOrNull(7), it.doubleOrNull(8), it.int(9),
            it.stringOrNull(10), it.stringOrNull(11), it.stringOrNull(12), it.stringOrNull(13), it.stringOrNull(14), it.longOrNull(15), it.longOrNull(16), it.longOrNull(17), it.longOrNull(18))
    }
}

fun Store.modelSlugOfCall(callId: Int): String? = conn { it.stringOf("SELECT models.slug FROM calls INNER JOIN models ON models.id = calls.model_id WHERE calls.id = ?", callId) }

fun Store.insertLog(level: String, source: String, name: String, message: String, session: String?, callId: Int?, plugin: String?) = conn {
    it.execute("INSERT INTO logs (level, source, name, message, session, call_id, plugin) VALUES (?, ?, ?, ?, ?, ?, ?)", level, source, name, message, session, callId, plugin); Unit
}

fun Store.countLogs(level: String, name: String): Long = conn { it.longOf("SELECT count(*) FROM logs WHERE level = ? AND name = ?", level, name)!! }

/** `(calls.plugin, surface, kind, name, ok, tokens.plugin, phase, source, n_tokens)` for every MCP call. */
data class McpTokenRow(val callPlugin: String?, val surface: String, val kind: String, val name: String?, val ok: Int, val tokenPlugin: String?, val phase: String, val source: String, val nTokens: Long)
fun Store.mcpCallTokenRows(): List<McpTokenRow> = conn { db ->
    db.query("SELECT calls.plugin, calls.surface, calls.kind, calls.name, calls.ok, tokens.plugin, tokens.phase, tokens.source, tokens.tokens " +
        "FROM calls INNER JOIN tokens ON tokens.call_id = calls.id WHERE calls.kind = 'mcp_call' ORDER BY calls.name, tokens.phase, tokens.plugin") {
        McpTokenRow(it.stringOrNull(0), it.string(1), it.string(2), it.stringOrNull(3), it.int(4), it.stringOrNull(5), it.string(6), it.string(7), it.long(8))
    }
}
