// Port of src/otel/otlp.rs: the OTLP/HTTP JSON shapes rtok emits (traces, logs, cumulative sums).
@file:Suppress("MagicNumber", "TooManyFunctions")

package rtok.otel

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import rtok.sys.Sha256

/** One attribute value; the OTLP `AnyValue` subset rtok emits. */
sealed interface AttrValue {
    data class Str(
        val v: String,
    ) : AttrValue

    data class Int(
        val v: Long,
    ) : AttrValue

    data class Bool(
        val v: Boolean,
    ) : AttrValue

    data class Double(
        val v: kotlin.Double,
    ) : AttrValue
}

typealias Attr = Pair<String, AttrValue>

fun s(
    key: String,
    v: String,
): Attr = key to AttrValue.Str(v)

fun i(
    key: String,
    v: Long,
): Attr = key to AttrValue.Int(v)

fun b(
    key: String,
    v: Boolean,
): Attr = key to AttrValue.Bool(v)

/** `SpanKind` as the spec numbers it. */
enum class Kind(
    val code: Int,
) {
    Internal(1),
    Server(2),
    Client(3),
}

sealed interface Status {
    data object Unset : Status

    data object Ok : Status

    data class Error(
        val message: String,
    ) : Status
}

data class Event(
    val name: String,
    val timeNs: Long,
    val attrs: List<Attr>,
)

data class Span(
    val traceId: String,
    val spanId: String,
    val parent: String?,
    val name: String,
    val kind: Kind,
    val startNs: Long,
    val endNs: Long,
    val attrs: List<Attr>,
    val events: List<Event>,
    val status: Status,
)

data class LogRecord(
    val timeNs: Long,
    /** OTLP `SeverityNumber`: 1 trace, 5 debug, 9 info, 13 warn, 17 error. */
    val severity: Int,
    val severityText: String,
    val body: String,
    val attrs: List<Attr>,
    val traceId: String?,
    val spanId: String?,
)

/** A cumulative integer sum: one data point per attribute set. */
data class Sum(
    val name: String,
    val unit: String,
    val description: String,
    /** `false` for a sum a point of which can go down between flushes (`rtok.tokens.saved`). */
    val monotonic: Boolean,
    val startNs: Long,
    val timeNs: Long,
    val points: List<Pair<List<Attr>, Long>>,
)

data class Resource(
    val attrs: List<Attr> = emptyList(),
)

private const val NS = 1_000_000_000L
private const val TRACE_BYTES = 16
private const val SPAN_BYTES = 8

fun secondsToNs(ts: Long): Long =
    if (ts <= 0) {
        0
    } else if (ts > Long.MAX_VALUE / NS) {
        Long.MAX_VALUE
    } else {
        ts * NS
    }

private fun hex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

/** 16 bytes of `sha256("rtok:session:" + id)`: one trace per session, stable across flushes. */
fun traceId(session: String): String = hex(Sha256.digest("rtok:session:$session".encodeToByteArray()).copyOf(TRACE_BYTES))

/** 8 bytes of `sha256("rtok:" + kind + ":" + id)`; `kind` keeps `calls` and `sessions` apart. */
fun spanId(
    kind: String,
    id: String,
): String = hex(Sha256.digest("rtok:$kind:$id".encodeToByteArray()).copyOf(SPAN_BYTES))

private fun value(v: AttrValue): JsonElement =
    when (v) {
        is AttrValue.Str -> buildJsonObject { put("stringValue", v.v) }
        is AttrValue.Int -> buildJsonObject { put("intValue", v.v.toString()) }
        is AttrValue.Bool -> buildJsonObject { put("boolValue", v.v) }
        is AttrValue.Double -> buildJsonObject { put("doubleValue", v.v) }
    }

private fun attrs(a: List<Attr>): JsonArray =
    buildJsonArray {
        a.forEach { (k, v) ->
            add(
                buildJsonObject {
                    put("key", k)
                    put("value", value(v))
                },
            )
        }
    }

private fun scope(): JsonObject =
    buildJsonObject {
        put("name", "rtok")
        put("version", rtok.VERSION)
    }

private fun span(sp: Span): JsonObject =
    buildJsonObject {
        put("traceId", sp.traceId)
        put("spanId", sp.spanId)
        put("name", sp.name)
        put("kind", sp.kind.code)
        put("startTimeUnixNano", sp.startNs.toString())
        put("endTimeUnixNano", sp.endNs.toString())
        put("attributes", attrs(sp.attrs))
        put(
            "events",
            JsonArray(
                sp.events.map { e ->
                    buildJsonObject {
                        put("name", e.name)
                        put("timeUnixNano", e.timeNs.toString())
                        put("attributes", attrs(e.attrs))
                    }
                },
            ),
        )
        put(
            "status",
            when (val st = sp.status) {
                Status.Unset -> {
                    JsonObject(emptyMap())
                }

                Status.Ok -> {
                    buildJsonObject { put("code", 1) }
                }

                is Status.Error -> {
                    buildJsonObject {
                        put("code", 2)
                        put("message", st.message)
                    }
                }
            },
        )
        sp.parent?.let { put("parentSpanId", it) }
    }

private fun envelope(
    outer: String,
    inner: String,
    listKey: String,
    res: Resource,
    items: List<JsonElement>,
): JsonObject =
    buildJsonObject {
        put(
            outer,
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("resource", buildJsonObject { put("attributes", attrs(res.attrs)) })
                        put(
                            inner,
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("scope", scope())
                                        put(listKey, JsonArray(items))
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
    }

/** Body for `POST /v1/traces`. */
fun traces(
    res: Resource,
    spans: List<Span>,
): JsonObject = envelope("resourceSpans", "scopeSpans", "spans", res, spans.map(::span))

/** Body for `POST /v1/logs`. */
fun logs(
    res: Resource,
    records: List<LogRecord>,
): JsonObject =
    envelope(
        "resourceLogs",
        "scopeLogs",
        "logRecords",
        res,
        records.map { r ->
            buildJsonObject {
                put("timeUnixNano", r.timeNs.toString())
                put("severityNumber", r.severity)
                put("severityText", r.severityText)
                put("body", buildJsonObject { put("stringValue", r.body) })
                put("attributes", attrs(r.attrs))
                r.traceId?.let { put("traceId", it) }
                r.spanId?.let { put("spanId", it) }
            }
        },
    )

/** Body for `POST /v1/metrics`: cumulative (`aggregationTemporality` 2) sums. */
fun metrics(
    res: Resource,
    sums: List<Sum>,
): JsonObject =
    envelope(
        "resourceMetrics",
        "scopeMetrics",
        "metrics",
        res,
        sums.map { m ->
            buildJsonObject {
                put("name", m.name)
                put("unit", m.unit)
                put("description", m.description)
                put(
                    "sum",
                    buildJsonObject {
                        put("aggregationTemporality", 2)
                        put("isMonotonic", m.monotonic)
                        put(
                            "dataPoints",
                            JsonArray(
                                m.points.map { (a, n) ->
                                    buildJsonObject {
                                        put("attributes", attrs(a))
                                        put("startTimeUnixNano", m.startNs.toString())
                                        put("timeUnixNano", m.timeNs.toString())
                                        put("asInt", n.toString())
                                    }
                                },
                            ),
                        )
                    },
                )
            }
        },
    )
