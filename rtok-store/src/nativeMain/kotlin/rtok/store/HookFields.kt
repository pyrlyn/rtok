// Port of crates/rtok-store/src/hook_fields.rs (T433): per-session hook fields stored once in
// `hook_sessions`, the rest of the body in `call_io`; read back whole.
package rtok.store

import db.*
import kotlinx.serialization.json.*

internal object HookFields {
    private val SESSION_KEYS = listOf("session_id", "transcript_path", "cwd", "scratchpad_dir", "permission_mode", "effort", "agent_id", "agent_type")

    /** `(event body, session fields JSON)`, both cleaned; null for a non-object or a body without session fields. */
    fun split(stdin: ByteArray): Pair<ByteArray, String>? {
        val obj = stdin.decodeUtf8OrNull()?.let { SerdeJson.parse(it) } as? JsonObject ?: return null
        val fields = JsonObject(SESSION_KEYS.mapNotNull { k -> obj[k]?.let { k to it } }.toMap())
        if (fields.isEmpty()) return null
        val event = JsonObject(obj.filterKeys { it !in SESSION_KEYS })
        return SerdeJson.encode(Sanitize.strings(event, Sanitize::text).first).encodeToByteArray() to
            SerdeJson.encode(Sanitize.strings(fields, Sanitize::text).first)
    }

    /** Splice `fields` back into `body` (both JSON objects); `body` unchanged otherwise. */
    fun rebuild(
        body: String,
        fields: String?,
    ): String {
        fun inner(s: String) = if (s.startsWith("{") && s.endsWith("}") && s.length >= 2) s.substring(1, s.length - 1) else null
        val f = fields?.let(::inner) ?: return body
        val b = inner(body) ?: return body
        val sep = if (f.isEmpty() || b.isEmpty()) "" else ","
        return "{$f$sep$b}"
    }

    fun upsert(
        db: Db,
        fields: String,
    ): Long {
        db.execute("INSERT INTO hook_sessions (fields) VALUES (?) ON CONFLICT (fields) DO NOTHING", fields)
        return db.longOf("SELECT id FROM hook_sessions WHERE fields = ?", fields)!!
    }

    fun dropOrphans(db: Db): Int = db.execute("DELETE FROM hook_sessions WHERE id NOT IN (SELECT hook_session_id FROM call_io WHERE hook_session_id IS NOT NULL)")
}

fun Store.hookSessionCount(): Long = conn { it.longOf("SELECT count(*) FROM hook_sessions")!! }
