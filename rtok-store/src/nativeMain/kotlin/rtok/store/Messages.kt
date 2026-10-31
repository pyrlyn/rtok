// Port of crates/rtok-store/src/messages.rs (T287): messages between agents and the user.
// `fromAgent = null` is the user at a terminal.
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package rtok.store

import db.*
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The cap on a cleaned body, in bytes of UTF-8. */
const val MAX_BODY = 4096

@Serializable
data class Message(
    val id: Int,
    @SerialName("from_agent") val fromAgent: String?,
    @SerialName("from_host") val fromHost: String?,
    @SerialName("to_agent") val toAgent: String,
    val body: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("delivered_at") val deliveredAt: Long?,
    @SerialName("read_at") val readAt: Long?,
    /** T441.13.1: canonical task id such as `A12`; omitted from JSON when absent. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val task: String? = null,
)

/** Control characters stripped (`\n`, `\t` kept); refused when empty or over [MAX_BODY], never truncated. */
fun cleanBody(text: String): String {
    val body = text.filter { !it.isISOControl() || it == '\n' || it == '\t' }
    if (body.isBlank()) throw StoreException("empty message")
    val len = body.encodeToByteArray().size
    if (len > MAX_BODY) throw StoreException("message is $len bytes; the cap is $MAX_BODY")
    return body
}

/** The 8-char display form of an rtok agent id (D34). */
fun shortAgentId(id: String): String = if (id.length >= 8) id.substring(0, 8) else id

private fun canonicalTask(task: String?): String? =
    task?.let {
        try {
            TaskId.parse(it.trim()).toString()
        } catch (e: IllegalArgumentException) {
            throw StoreException(e.message ?: "bad task id", e)
        }
    }

private enum class Only(
    val sql: String,
) {
    All(""),
    Unread(" AND messages.read_at IS NULL"),
    Undelivered(" AND messages.delivered_at IS NULL"),
}

private fun loadMessages(
    db: Db,
    to: String,
    only: Only,
    task: String?,
): List<Message> {
    val args = arrayListOf<Any?>(to)
    var sql =
        "SELECT messages.id, messages.from_agent, hosts.slug, messages.to_agent, messages.body, messages.created_at, messages.delivered_at, messages.read_at, messages.task " +
            "FROM messages LEFT JOIN agents ON agents.id = messages.from_agent LEFT JOIN hosts ON hosts.id = agents.host_id WHERE messages.to_agent = ?"
    if (task != null) {
        sql += " AND messages.task = ?"
        args += task
    }
    sql += only.sql + " ORDER BY messages.id ASC"
    return db.query(sql, *args.toTypedArray()) {
        Message(it.int(0), it.stringOrNull(1), it.stringOrNull(2), it.string(3), it.string(4), it.long(5), it.longOrNull(6), it.longOrNull(7), it.stringOrNull(8))
    }
}

fun Store.sendMessage(
    from: String?,
    to: String,
    text: String,
): Int = sendMessageOn(from, to, text, null)

/** Queue `text` for agent `to`, tagged with `task`; refused for an unknown or ended recipient. */
fun Store.sendMessageOn(
    from: String?,
    to: String,
    text: String,
    task: String?,
): Int {
    val body = cleanBody(text)
    val canonical = canonicalTask(task)
    return conn { db ->
        // A list, so "no such agent" (no row) and "live agent" (NULL ended_at) stay distinct.
        val endedAt =
            db.queryOne("SELECT ended_at FROM agents WHERE id = ?", to) { listOf(it.longOrNull(0)) }
                ?: throw StoreException("unknown agent $to")
        if (endedAt[0] != null) throw StoreException("agent ${shortAgentId(to)} has ended; it can no longer receive messages")
        db.queryOne("INSERT INTO messages (from_agent, to_agent, body, task) VALUES (?, ?, ?, ?) RETURNING id", from, to, body, canonical) { it.int(0) }!!
    }
}

fun Store.inbox(
    to: String,
    unreadOnly: Boolean,
    markRead: Boolean,
): List<Message> = inboxLimited(to, unreadOnly, markRead, null, null)

/** At most `limit` oldest messages (about `task`, when given); the read mark covers only what is returned. */
fun Store.inboxLimited(
    to: String,
    unreadOnly: Boolean,
    markRead: Boolean,
    limit: Int?,
    task: String?,
): List<Message> {
    val canonical = canonicalTask(task)
    return conn { db ->
        var rows = loadMessages(db, to, if (unreadOnly) Only.Unread else Only.All, canonical)
        if (limit != null) rows = rows.take(limit)
        if (markRead && rows.isNotEmpty()) {
            db.execute(
                "UPDATE messages SET read_at = COALESCE(read_at, unixepoch()), delivered_at = COALESCE(delivered_at, unixepoch()) " +
                    "WHERE id IN (${inList(rows.size)})",
                *rows.map { it.id }.toTypedArray(),
            )
        }
        rows
    }
}

/** T284: unread messages per agent; agents with none are absent. */
fun Store.unreadCounts(): Map<String, Long> =
    conn { db ->
        db.query("SELECT to_agent, count(*) FROM messages WHERE read_at IS NULL GROUP BY to_agent") { it.string(0) to it.long(1) }.toMap()
    }

/** T288: messages no hook has pushed yet, in send order; stamps nothing. */
fun Store.undelivered(to: String): List<Message> = conn { loadMessages(it, to, Only.Undelivered, null) }

/** T288: stamp `delivered_at` on exactly `ids` where unset; `read_at` untouched. */
fun Store.markDelivered(ids: List<Int>) {
    if (ids.isEmpty()) return
    conn { it.execute("UPDATE messages SET delivered_at = unixepoch() WHERE id IN (${inList(ids.size)}) AND delivered_at IS NULL", *ids.toTypedArray()) }
}
