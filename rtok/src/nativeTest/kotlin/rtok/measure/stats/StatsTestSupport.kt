// Shared fixtures of the src/measure/stats.rs test port: transcript lines and a collect over a fresh dir.
package rtok.measure.stats

import rtok.config.Config
import rtok.store.unixNow
import rtok.support.tmpDir
import rtok.sys.jsonQuote
import rtok.sys.writeString

internal const val SIXTY_DAYS = 86_400L * 60

internal fun q(s: String) = jsonQuote(s)

/** An assistant message calling [name] with [input] (raw JSON). */
internal fun toolCall(
    id: String,
    name: String,
    input: String,
) = """{"type":"assistant","message":{"id":"m$id","content":[{"type":"tool_use","id":"$id","name":"$name","input":$input}]}}"""

/** The result [body] of call [id]. */
internal fun toolResult(
    id: String,
    body: String,
) = """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"$id","content":${q(body)}}]}}"""

internal fun cutoff(window: Long = SIXTY_DAYS) = unixNow() - window

internal fun defaultReplay() = Replay.fromCfg(Config())

internal fun statsDir() = tmpDir("stats")

/** Write [lines] to `dir/name` and collect the dir. */
internal fun writeAndCollect(
    dir: String,
    name: String,
    lines: List<String>,
): Report {
    writeString("$dir/$name", lines.joinToString("") { "$it\n" })
    return collect(dir, cutoff(), "", defaultReplay())
}

/** An empty transcript prints no [label] line and has no [key] in `--json`. */
internal fun assertAbsentWhenEmpty(
    label: String,
    key: String,
) {
    val none = writeAndCollect(statsDir(), "empty.jsonl", emptyList())
    kotlin.test.assertFalse(label in none.toTable())
    kotlin.test.assertFalse(key in none.toJsonText())
}
