// Port of src/agents/junk_sessions.rs: the `sessions` junk kind (T330.5.2, D36): old sessions, class `explicit` (only
// `--kind sessions` clears them), judged by time alone. Only Claude Code documents the whole session unit
// (research.md §22.1): `projects/<project>/<session>.jsonl`, `<session>/`, `file-history/<session>/`. The host's memory
// never matches a session. Nothing here deletes.
package rtok.agents

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.json.StrictJson
import rtok.sys.isDir
import rtok.sys.isFile
import rtok.sys.listDirOrEmpty
import rtok.sys.readToString
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

object JunkSessions {
    /** A session written to in this window may be open in its agent, whatever the threshold says. */
    val IN_USE: Duration = 10.minutes

    const val UNKNOWN = "last used unknown: not cleared"
    const val FUTURE = "future timestamp: counted as just touched"
    const val IN_USE_REASON = "skipped: in use (modified in the last 10 minutes)"

    private const val UUID_LEN = 36
    private val DASHES = setOf(8, 13, 18, 23)

    /** Claude Code names a session by a UUID. */
    internal fun isSessionId(s: String): Boolean =
        s.length == UUID_LEN &&
            s.withIndex().all { (i, c) -> if (i in DASHES) c == '-' else c.isAsciiHexDigit() }

    private fun Char.isAsciiHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}

private fun dirPart(p: String): String? = p.takeIf { isDir(it) && !isSymlink(it) }

/**
 * Why a session with these [parts] stays, or null when it is old. One answer for all parts, so a session goes whole
 * or not at all. The reason names no age: `list` groups the sessions that share a reason.
 */
private fun stays(
    parts: List<String>,
    days: Int,
    now: Long,
): String? {
    // A part that cannot be dated says nothing about the session's age.
    val times = parts.map { newest(it)?.time }
    val age = times.filterNotNull().maxOrNull()?.let { now - it }
    return when {
        null in times -> JunkSessions.UNKNOWN
        age == null -> null
        age < 0 -> JunkSessions.FUTURE
        age < JunkSessions.IN_USE.inWholeNanoseconds -> JunkSessions.IN_USE_REASON
        age <= days.days.inWholeNanoseconds -> "touched within the $days-day threshold"
        else -> null
    }
}

/**
 * Claude Code's sessions: per session its transcript, its `<session>/` folder and its `file-history/<session>/` restore
 * points, each an item of its own with the same answer. [days] is `[agents.junk] stale_session_days`.
 */
fun sessionItems(
    roots: JunkRoots,
    days: Int,
    cx: JunkCtx,
    limit: Duration,
): List<JunkItem> {
    val claude = roots.resolve("{claude}")
    val items = ArrayList<JunkItem>()
    val projects = listDirOrEmpty("$claude/projects").map { "$claude/projects/$it" }
    for (project in projects.filter { isDir(it) && !isSymlink(it) }) {
        for (name in listDirOrEmpty(project).filter { isFile("$project/$it") && !isSymlink("$project/$it") }) {
            val id = name.removeSuffix(".jsonl").takeIf { name.endsWith(".jsonl") && JunkSessions.isSessionId(it) } ?: continue
            val transcript = "$project/$name"
            val parts = listOfNotNull(transcript, dirPart("$project/$id"), dirPart("$claude/file-history/$id"))
            val kept = stays(parts, days, cx.policy.now)
            for (p in parts) items += junkItem("sessions", p, JunkCache.SECTION_22, kept, limit).copy(cls = "explicit")
        }
    }
    return items
}

/**
 * What the host prunes by itself, shown next to rtok's threshold: Claude Code's `cleanupPeriodDays` (default 30) and
 * Gemini CLI's `general.sessionRetention.maxAge` (default 30d), both from the host's `settings.json` (§22.1).
 */
fun retention(
    roots: JunkRoots,
    host: String,
): String? {
    val (file, key, default) =
        when (host) {
            "claude" -> Triple(roots.resolve("{claude}/settings.json"), "cleanupPeriodDays", "30")
            "gemini" -> Triple(roots.resolve("{home}/.gemini/settings.json"), "general.sessionRetention.maxAge", "30d")
            else -> return null
        }
    // A missing or unreadable file is the host's default, not an error.
    val json = runCatching { StrictJson.parseOrNull(readToString(file)) }.getOrNull()
    val set = key.split('.').fold(json as JsonElement?) { v, k -> (v as? JsonObject)?.get(k) }
    val text =
        when {
            set is JsonPrimitive && set.isString -> "$key = ${set.content}"
            set != null -> "$key = $set"
            else -> "$key = $default (default)"
        }
    return "host retention: $text"
}
