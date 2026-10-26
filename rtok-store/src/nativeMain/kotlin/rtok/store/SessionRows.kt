// Port of crates/rtok-store/src/session_rows.rs.
package rtok.store

/** Insert a session, or fill in the fields a later sighting knows (`COALESCE(excluded, old)`). */
fun Store.upsertSession(id: String, hostId: Int?, project: String?, cwd: String?, source: String?) = conn { db ->
    db.execute("INSERT INTO sessions (id, host_id, project, cwd, source) VALUES (?, ?, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET " +
        "host_id = COALESCE(excluded.host_id, sessions.host_id), project = COALESCE(excluded.project, sessions.project), " +
        "cwd = COALESCE(excluded.cwd, sessions.cwd), source = COALESCE(excluded.source, sessions.source)", id, hostId, project, cwd, source)
    Unit
}

/** `(provider id, model id)`, both created on first sight. */
fun Store.upsertModel(providerSlug: String, modelSlug: String): Pair<Int, Int> = conn { db ->
    db.transaction("IMMEDIATE") {
        db.execute("INSERT OR IGNORE INTO providers (slug, name) VALUES (?, ?)", providerSlug, providerSlug)
        val p = db.longOf("SELECT id FROM providers WHERE slug = ?", providerSlug)?.toInt() ?: throw StoreException("provider")
        db.execute("INSERT OR IGNORE INTO models (provider_id, slug) VALUES (?, ?)", p, modelSlug)
        val m = db.longOf("SELECT id FROM models WHERE provider_id = ? AND slug = ?", p, modelSlug)?.toInt() ?: throw StoreException("model")
        p to m
    }
}

fun Store.hostId(slug: String): Int? = conn { it.longOf("SELECT id FROM hosts WHERE slug = ?", slug)?.toInt() }

fun Store.sessionRow(id: String): SessionRow? = conn { db ->
    db.queryOne("SELECT hosts.slug, sessions.project, sessions.cwd FROM sessions LEFT JOIN hosts ON hosts.id = sessions.host_id WHERE sessions.id = ?", id) {
        Triple(it.stringOrNull(0), it.stringOrNull(1), it.stringOrNull(2))
    }
}

/** Sessions with a cwd, most recently seen first (last call, else start). */
fun Store.sessionsByCwd(): List<SessionSeen> = conn { db ->
    val lastCall = db.query("SELECT session_id, max(ts) FROM calls GROUP BY session_id") { it.string(0) to it.longOrNull(1) }.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
    db.query("SELECT sessions.id, hosts.slug, sessions.cwd, sessions.started_at, sessions.ended_at FROM sessions LEFT JOIN hosts ON hosts.id = sessions.host_id WHERE sessions.cwd IS NOT NULL") {
        SessionSeen(it.string(0), it.stringOrNull(1), it.string(2), lastCall[it.string(0)] ?: it.long(3), it.longOrNull(4))
    }.sortedWith(compareByDescending<SessionSeen> { it.lastSeen }.thenBy { it.id })
}
