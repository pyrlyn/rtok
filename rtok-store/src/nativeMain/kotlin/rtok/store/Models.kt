// Port of crates/rtok-store/src/models.rs: the row structs Diesel loads with `as_select()`.
package rtok.store

import db.*

data class Host(
    val id: Int,
    val slug: String,
    val kind: String,
    val createdAt: Long,
)

data class Provider(
    val id: Int,
    val slug: String,
    val name: String,
    val createdAt: Long,
)

data class Model(
    val id: Int,
    val providerId: Int,
    val slug: String,
    val createdAt: Long,
)

data class Session(
    val id: String,
    val hostId: Int?,
    val project: String?,
    val cwd: String?,
    val source: String?,
    val startedAt: Long,
    val endedAt: Long?,
) {
    internal companion object {
        const val COLS = "sessions.id, sessions.host_id, sessions.project, sessions.cwd, sessions.source, sessions.started_at, sessions.ended_at"

        fun of(
            r: Row,
            o: Int = 0,
        ) = Session(r.string(o), r.intOrNull(o + 1), r.stringOrNull(o + 2), r.stringOrNull(o + 3), r.stringOrNull(o + 4), r.long(o + 5), r.longOrNull(o + 6))
    }
}

data class Call(
    val id: Int,
    val ts: Long,
    val sessionId: String,
    val hostId: Int?,
    val providerId: Int?,
    val modelId: Int?,
    val plugin: String?,
    val surface: String,
    val kind: String,
    val parentId: Int?,
    val name: String?,
    val ms: Double?,
    val ok: Int,
    val error: String?,
    val serviceTier: String?,
) {
    internal companion object {
        const val COLS = "calls.id, calls.ts, calls.session_id, calls.host_id, calls.provider_id, calls.model_id, calls.plugin, calls.surface, calls.kind, calls.parent_id, calls.name, calls.ms, calls.ok, calls.error, calls.service_tier"

        fun of(r: Row) =
            Call(
                r.int(0),
                r.long(1),
                r.string(2),
                r.intOrNull(3),
                r.intOrNull(4),
                r.intOrNull(5),
                r.stringOrNull(6),
                r.string(7),
                r.string(8),
                r.intOrNull(9),
                r.stringOrNull(10),
                r.doubleOrNull(11),
                r.int(12),
                r.stringOrNull(13),
                r.stringOrNull(14),
            )
    }
}

data class CallIo(
    val callId: Int,
    val requestBytes: Long,
    val responseBytes: Long,
    val requestSha256: String?,
    val responseSha256: String?,
    val requestJson: String?,
    val responseJson: String?,
    val requestArchive: String?,
    val responseArchive: String?,
) {
    internal companion object {
        const val COLS = "call_io.call_id, call_io.request_bytes, call_io.response_bytes, call_io.request_sha256, call_io.response_sha256, call_io.request_json, call_io.response_json, call_io.request_archive, call_io.response_archive"

        fun of(r: Row) = CallIo(r.int(0), r.long(1), r.long(2), r.stringOrNull(3), r.stringOrNull(4), r.stringOrNull(5), r.stringOrNull(6), r.stringOrNull(7), r.stringOrNull(8))
    }
}

data class TokenRow(
    val id: Int,
    val ts: Long,
    val callId: Int,
    val plugin: String?,
    val phase: String,
    val source: String,
    val nTokens: Long,
    val bytes: Long?,
    val input: Long?,
    val output: Long?,
    val cacheCreate: Long?,
    val cacheRead: Long?,
) {
    internal companion object {
        const val COLS = "id, ts, call_id, plugin, phase, source, tokens, bytes, input, output, cache_create, cache_read"

        fun of(r: Row) = TokenRow(r.int(0), r.long(1), r.int(2), r.stringOrNull(3), r.string(4), r.string(5), r.long(6), r.longOrNull(7), r.longOrNull(8), r.longOrNull(9), r.longOrNull(10), r.longOrNull(11))
    }
}

data class LogRow(
    val id: Int,
    val ts: Long,
    val level: String,
    val source: String,
    val name: String,
    val session: String?,
    val callId: Int?,
    val plugin: String?,
    val message: String,
    val fields: String?,
) {
    internal companion object {
        const val COLS = "id, ts, level, source, name, session, call_id, plugin, message, fields"

        fun of(r: Row) = LogRow(r.int(0), r.long(1), r.string(2), r.string(3), r.string(4), r.stringOrNull(5), r.intOrNull(6), r.stringOrNull(7), r.string(8), r.stringOrNull(9))
    }
}
