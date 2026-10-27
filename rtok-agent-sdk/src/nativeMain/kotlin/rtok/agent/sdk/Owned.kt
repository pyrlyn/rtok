// Port of crates/rtok-agent-sdk/src/lib.rs (ownership + questions subset): `parse_strict_json`,
// `judge_owned`, `keep_edited`, `runs_bin`, `without_default_type`, `confirmed`/`accepted`/`answer`.
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package rtok.agent.sdk

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import platform.posix.isatty
import rtok.json.StrictJson

/** Says whether a string names the rtok binary (the binary's own check knows `rtok.exe` and its running path). */
typealias IsBin = (String) -> Boolean

/** A file rtok refuses to rewrite because it is not strict JSON. */
class StrictJsonException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Strict JSON at [path] (named in the error); an empty document is `{}`. */
fun parseStrictJson(
    path: String,
    raw: String,
): JsonElement {
    if (raw.isBlank()) return JsonObject(emptyMap())
    return try {
        StrictJson.parse(raw)
    } catch (e: SerializationException) {
        throw StrictJsonException(
            "$path: not strict JSON: ${e.message?.lineSequence()?.firstOrNull()}. rtok refuses to rewrite this file " +
                "(a rewrite would delete its comments); add the entry by hand and re-run.",
            e,
        )
    }
}

/** True when [v] (or anything nested in it) is a string naming the rtok binary. */
fun runsBin(
    v: JsonElement,
    isBin: IsBin,
): Boolean =
    when (v) {
        is JsonPrimitive -> v.isString && isBin(v.content)
        is JsonArray -> v.any { runsBin(it, isBin) }
        is JsonObject -> v.values.any { runsBin(it, isBin) }
    }

/** [v] without a top-level `"type": "stdio"` (Claude.app drops it on re-save, T265). */
fun withoutDefaultType(v: JsonElement): JsonElement {
    if (v !is JsonObject) return v
    val type = v["type"] as? JsonPrimitive
    return if (type?.isString == true && type.content == "stdio") JsonObject(v - "type") else v
}

/**
 * The ownership check every removal of an entry named `rtok` runs (T246): not rtok's →
 * `leave … (not rtok's; remove by hand)`; rtok's but edited → [keepEdited]; unchanged → `null` (remove it).
 */
fun judgeOwned(
    apply: Apply,
    at: String,
    have: JsonElement,
    ours: JsonElement,
    isBin: IsBin,
): String? {
    if (!runsBin(have, isBin)) return "leave $at (not rtok's; remove by hand)"
    val same = rtokAsOne(withoutDefaultType(have), isBin) == rtokAsOne(withoutDefaultType(ours), isBin)
    return if (same) null else keepEdited(apply, at)
}

/** For an rtok entry the user changed: `null` removes it (`--yes` or a yes), else the report that keeps it. */
fun keepEdited(
    apply: Apply,
    at: String,
): String? {
    if (apply.dryRun && !apply.yes) return "? $at (changed by you; remove asks)"
    return if (confirmed(apply, "remove $at? you changed it")) null else "leave $at (changed by you; remove by hand)"
}

/** A `[y/N]` question: `--yes` accepts, no terminal is a no. */
fun confirmed(
    apply: Apply,
    question: String,
): Boolean = ask(apply, question, false)

/** A `[Y/n]` question: `--yes` accepts, no terminal is a no. */
fun accepted(
    apply: Apply,
    question: String,
): Boolean = ask(apply, question, true)

/** Enter takes [default]; y/yes accept; anything else declines. */
fun answer(
    line: String,
    default: Boolean,
): Boolean =
    when (val a = line.trim().lowercase()) {
        "" -> default
        else -> a == "y" || a == "yes"
    }

private fun ask(
    apply: Apply,
    question: String,
    default: Boolean,
): Boolean =
    when {
        apply.yes -> {
            true
        }

        isatty(0) != 1 -> {
            false
        }

        else -> {
            print("? $question ${if (default) "[Y/n]" else "[y/N]"} ")
            val line = readlnOrNull()
            println()
            line != null && answer(line, default)
        }
    }

/** The `--host <id>` pair after `mcp` dropped: entries before and after T283.2 are both rtok's. */
private fun withoutHostArg(argv: List<JsonElement>): List<JsonElement> {
    val out = ArrayList<JsonElement>(argv.size)
    var afterMcp = false
    var skip = false
    for (x in argv) {
        val s = (x as? JsonPrimitive)?.takeIf { it.isString }?.content
        when {
            skip -> {
                skip = false
            }

            afterMcp && s == "--host" -> {
                skip = true
            }

            else -> {
                afterMcp = s == "mcp"
                out += x
            }
        }
    }
    return out
}

/** [v] with every string naming the rtok binary folded to one placeholder. */
private fun rtokAsOne(
    v: JsonElement,
    isBin: IsBin,
): JsonElement =
    when (v) {
        is JsonPrimitive -> if (v.isString && isBin(v.content)) JsonNull else v
        is JsonArray -> JsonArray(withoutHostArg(v).map { rtokAsOne(it, isBin) })
        is JsonObject -> JsonObject(v.mapValues { rtokAsOne(it.value, isBin) })
    }
