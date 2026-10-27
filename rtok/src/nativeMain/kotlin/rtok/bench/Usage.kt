// The `claude -p` side of `rtok bench` (src/bench.rs `one`, `graph_one`, `run_bounded`, `parse_usage`): every live
// run is gated on RTOK_BENCH_LIVE, bounded by `[bench] timeout_s`, and reads only the JSON result it prints.
package rtok.bench

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import rtok.sys.ProgressRunner

/** One default-suite run: tokens in, cache (read + write), out, and the reported cost. */
data class Usage(
    val input: Long = 0,
    val cache: Long = 0,
    val output: Long = 0,
    val cost: Double = 0.0,
)

/** Most stdout kept from one run; a run's JSON result is a few KiB, so a flood is a runaway. */
const val MAX_OUTPUT: Long = 8L shl 20

internal fun live(): Boolean = rtok.config.envVar("RTOK_BENCH_LIVE") != null

/** Run [cmd] and collect its stdout, killed once [timeoutMs] passed; null when it failed, exited badly or timed out. */
fun runBounded(
    cmd: ProgressRunner,
    timeoutMs: Long,
): ByteArray? {
    val done = runCatching { cmd.timeout(timeoutMs).runCapped(MAX_OUTPUT).first }.getOrNull() ?: return null
    return done.stdout.takeIf { done.exitCode == 0 }
}

internal fun parseJson(bytes: ByteArray): JsonElement = runCatching { Json.parseToJsonElement(bytes.decodeToString()) }.getOrDefault(JsonNull)

internal fun JsonElement.field(k: String): JsonElement? = (this as? JsonObject)?.get(k)

internal fun JsonElement?.num(k: String): Long = (this?.field(k) as? JsonPrimitive)?.longOrNull?.coerceAtLeast(0) ?: 0

/** `(input, cache, output, cost)` out of a `claude -p --output-format json` result. */
fun parseUsage(bytes: ByteArray): Usage {
    val v = parseJson(bytes)
    val u = v.field("usage")
    return Usage(
        u.num("input_tokens"),
        u.num("cache_read_input_tokens") + u.num("cache_creation_input_tokens"),
        u.num("output_tokens"),
        (v.field("total_cost_usd") as? JsonPrimitive)?.doubleOrNull ?: 0.0,
    )
}

/** Every `"type": "tool_use"` object anywhere in [v]. */
fun countToolUses(v: JsonElement): Long =
    when (v) {
        is JsonObject -> (if ((v["type"] as? JsonPrimitive)?.contentOrNull == "tool_use") 1L else 0L) + v.values.sumOf(::countToolUses)
        is JsonArray -> v.sumOf(::countToolUses)
        else -> 0
    }

/** One default-suite run with [settings]; zeros when not live, the settings file is missing, or the run fails. */
internal fun one(
    prompt: String,
    settings: String,
    timeoutS: Long,
): Usage {
    if (!rtok.sys.exists(settings) || !live()) return Usage()
    val cmd = ProgressRunner("running claude -p…", "claude", listOf("-p", prompt, "--output-format", "json", "--settings", settings)).quiet()
    return runBounded(cmd, maxOf(1, timeoutS) * MS)?.let(::parseUsage) ?: Usage()
}

internal const val MS = 1000L

/** The string field [k] of [v], or "". */
internal fun JsonElement.text(k: String): String = (field(k) as? JsonPrimitive)?.takeIf { it.isString }?.jsonPrimitive?.content ?: ""
