// Port of src/agents/junk_items.rs (T330.6): every concrete item `agents junk list` reports and `clear` would remove,
// with its size, when it was last used and why it is junk. `list` and the `clear` dry run print through the same
// lines; the row is the JSON shape too. `Row::from(&Planned)` comes with junk_clear.
package rtok.agents

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import rtok.config.Config
import rtok.config.envUserHome
import rtok.sys.ChangePreview
import rtok.sys.Zone
import rtok.sys.pathStartsWith

enum class JunkSort {
    /** Largest first. */
    Size,

    /** Longest unused first; an unknown time sorts last, as the recent item it is treated as. */
    LastUsed,
    Path,
    ;

    companion object {
        fun parse(name: String) =
            when (name) {
                "last-used" -> LastUsed
                "path" -> Path
                else -> Size
            }
    }
}

/** What a printed breakdown looks like: one run's flags plus the config it needs. */
data class JunkView(
    val shown: Int = JunkItems.DEFAULT_SHOWN,
    val sort: JunkSort = JunkSort.Size,
    val minSize: Long = 0,
    /** Raw byte counts instead of KB/MB/GB. */
    val exact: Boolean = false,
    /** Wrap each path in an OSC 8 link; only for a terminal. */
    val links: Boolean = false,
    /** `agents.junk.stale_session_days`, named in a session's reason. */
    val days: UInt = 30u,
    /** Unix seconds. */
    val now: Long = Junk.nowNanos() / NANOS,
    /** Paths under it print with `~`. */
    val home: String? = null,
) {
    fun size(n: Long): String = if (exact) n.toString() else ChangePreview.humanBytes(n)

    companion object {
        const val NANOS = 1_000_000_000L

        fun of(cfg: Config) = JunkView(days = cfg.agents.junk.staleSessionDays, home = envUserHome())
    }
}

/** One item of the breakdown; the `--json` shape is [json]. */
class JunkRow(
    val agent: String,
    val kind: String,
    val cls: String,
    val path: String,
    val sizeBytes: Long,
    /** Unix seconds; null when it could not be read, which counts as recent. */
    var lastUsed: Long?,
    var reason: String,
    val willClear: Boolean,
    val skipReason: String?,
    /** What a `clear --yes` did to it ("removed"), for the text. */
    val result: String,
    internal val evidence: String,
    /** Part of what the kind line sums, so the text lists it. */
    internal val counted: Boolean,
    internal var stamped: Boolean,
) {
    fun json(): JsonObject =
        buildJsonObject {
            put("kind", kind)
            put("class", cls)
            put("path", path)
            put("size_bytes", sizeBytes)
            put("last_used", lastUsed?.let(::JsonPrimitive) ?: JsonNull)
            put("reason", reason)
            put("will_clear", willClear)
            skipReason?.let { put("skip_reason", it) }
        }

    /** The last-used time and the reason, read only when a row is about to be shown (the walk costs a sizing). */
    fun stamp(view: JunkView) {
        if (stamped) return
        stamped = true
        lastUsed = newest(path)?.time?.let { it / JunkView.NANOS }
        if (reason.isEmpty()) reason = JunkItems.reason(kind, evidence, lastUsed, view)
    }

    companion object {
        fun ofItem(
            agent: String,
            i: JunkItem,
        ): JunkRow {
            val skip =
                i.kept ?: when (i.cls) {
                    "review" -> "review kind: add --include review"
                    "explicit" -> "explicit kind: name it with --kind ${i.kind}"
                    else -> null
                }
            return JunkRow(agent, i.kind, i.cls, i.path, i.bytes, null, "", skip == null, skip, "", i.evidence, i.counted, false)
        }

        /** rtok's own log and archive files: `scan` already says why each is junk. */
        fun ofOwn(o: JunkOutcome) = JunkRow("rtok", o.kind, "safe", o.path, o.bytes, null, o.note, true, null, "", JunkCache.RTOK_OWN, true, false)
    }
}

/** The rows of one kind under one agent, cut to what the text prints. */
class JunkGroup(
    val agent: String,
    val kind: String,
    internal val shown: List<JunkRow>,
    private val more: Int,
    private val moreBytes: Long,
) {
    fun lines(
        view: JunkView,
        indent: String,
    ): String {
        if (view.shown == 0) return ""
        return buildString {
            for (r in shown) {
                val tilde = JunkItems.tilde(r.path, view.home)
                val path = if (view.links) JunkList.folderLink(r.path, tilde) else tilde
                append("$indent$path  ${view.size(r.sizeBytes)}  last used ${JunkItems.whenUsed(r.lastUsed, view)}  ${r.reason}")
                when {
                    r.skipReason != null -> append("  (not cleared: ${r.skipReason})")
                    r.result.isNotEmpty() -> append("  (${r.result})")
                }
                append('\n')
            }
            if (more > 0) append("$indent+$more more (${view.size(moreBytes)})\n")
        }
    }
}

object JunkItems {
    /** How many items a kind shows before "+N more" (T330: 10 largest). */
    const val DEFAULT_SHOWN = 10
    val SORTS = listOf("size", "last-used", "path")
    private const val DAY = 86_400L
    private const val MINUTE = 60L
    private const val HOUR = 3_600L
    private const val TWO_DAYS = 172_800L
    private const val TWO_WEEKS = 1_209_600L
    private const val TWO_MONTHS = 5_184_000L
    private const val WEEK = 604_800L
    private const val MONTH = 2_592_000L

    /** `--items`: how many items each kind prints; `0` prints totals only. */
    fun shownArg(s: String): Int = if (s == "all") Int.MAX_VALUE else s.toIntOrNull()?.takeIf { it >= 0 } ?: throw IllegalArgumentException("not a count or `all`: $s")

    /** Why an item is junk, in the words of the T330 card. */
    fun reason(
        kind: String,
        evidence: String,
        lastUsed: Long?,
        view: JunkView,
    ): String {
        val days = lastUsed?.let { maxOf(view.now - it, 0) / DAY }
        val future = lastUsed != null && lastUsed > view.now
        val base =
            when {
                kind == "sessions" && days != null -> "not touched for $days days (threshold ${view.days})"
                kind == "crash-dumps" && days != null -> "crash dump $days days old"
                else -> "$kind ($evidence)"
            }
        return if (future) "$base, future timestamp" else base
    }

    /** Every item of the report, counted or not, with the reason `clear` leaves it. */
    fun rows(report: JunkReport): List<JunkRow> = report.own.map(JunkRow::ofOwn) + report.agents.flatMap { a -> a.items.map { JunkRow.ofItem(a.name, it) } }

    private fun order(
        rows: List<JunkRow>,
        view: JunkView,
    ): List<JunkRow> =
        when (view.sort) {
            JunkSort.Size -> {
                rows.sortedWith(compareByDescending<JunkRow> { it.sizeBytes }.thenBy { it.path })
            }

            JunkSort.Path -> {
                rows.sortedBy { it.path }
            }

            JunkSort.LastUsed -> {
                rows.forEach { it.stamp(view) }
                // Unknown after every known time.
                rows.sortedWith(compareBy<JunkRow> { it.lastUsed == null }.thenBy { it.lastUsed ?: 0 }.thenBy { it.path })
            }
        }

    /** The text groups in the order their first row came; only counted rows, what the kind line sums. */
    fun groups(
        rows: List<JunkRow>,
        view: JunkView,
    ): List<JunkGroup> {
        val by = LinkedHashMap<Pair<String, String>, MutableList<JunkRow>>()
        rows.filter { it.counted && it.sizeBytes >= view.minSize }.forEach { by.getOrPut(it.agent to it.kind) { ArrayList() } += it }
        return by.map { (key, list) ->
            val sorted = order(list, view)
            val cut = minOf(sorted.size, view.shown)
            val shown = sorted.subList(0, cut)
            val hidden = sorted.subList(cut, sorted.size)
            shown.forEach { it.stamp(view) }
            JunkGroup(key.first, key.second, shown, hidden.size, hidden.sumOf { it.sizeBytes })
        }
    }

    /** [report] as JSON with every item of each agent under `items`: the shape of [JunkRow]. */
    fun json(
        report: JunkReport,
        view: JunkView,
    ): JsonObject {
        val all = rows(report).filter { it.sizeBytes >= view.minSize }
        all.forEach { it.stamp(view) }
        val ordered = order(all, view)
        return buildJsonObject {
            put("agents", JsonArray(report.agents.map { a -> agentJson(a, JsonArray(ordered.filter { it.agent == a.name }.map(JunkRow::json))) }))
            put("total_bytes", report.totalBytes)
            put("freed_default_bytes", report.freedDefaultBytes)
            put("freed_review_bytes", report.freedReviewBytes)
        }
    }

    private fun agentJson(
        a: AgentJunk,
        items: JsonElement,
    ) = buildJsonObject {
        put("name", a.name)
        put("host", a.host)
        put("installed", a.installed)
        put("folders", JsonArray(a.folders.map(::folderJson)))
        put("total_bytes", a.totalBytes)
        put(
            "kinds",
            JsonArray(
                a.kinds.map { k ->
                    buildJsonObject {
                        put("kind", k.kind)
                        put("class", k.cls)
                        put("items", k.items)
                        put("size_bytes", k.sizeBytes)
                    }
                },
            ),
        )
        put("items", items)
        if (a.notes.isNotEmpty()) put("notes", JsonArray(a.notes.map(::JsonPrimitive)))
        put("freed_default_bytes", a.freedDefaultBytes)
        put("freed_review_bytes", a.freedReviewBytes)
    }

    private fun folderJson(f: Folder) =
        buildJsonObject {
            put("path", f.path)
            put("role", f.role)
            put("size_bytes", f.sizeBytes)
            put("documented", f.documented)
            if (f.sharedWith.isNotEmpty()) put("shared_with", JsonArray(f.sharedWith.map(::JsonPrimitive)))
            f.note?.let { put("note", it) }
        }

    /** What `plan` stores on a planned removal: the time and the reason the breakdown prints. */
    fun stampPlanned(
        kind: String,
        evidence: String,
        path: String,
        note: String,
        view: JunkView,
    ): Pair<Long?, String> {
        val last = newest(path)?.time?.let { it / JunkView.NANOS }
        return last to note.ifEmpty { reason(kind, evidence, last, view) }
    }

    internal fun tilde(
        path: String,
        home: String?,
    ): String =
        when {
            home == null || !pathStartsWith(path, home) -> path
            path.trimEnd('/') == home.trimEnd('/') -> "~"
            else -> "~/" + path.removePrefix(home.trimEnd('/')).trimStart('/')
        }

    /** `3 weeks ago (2026-09-09 14:02)`, in the local time zone. */
    internal fun whenUsed(
        last: Long?,
        view: JunkView,
    ): String {
        last ?: return "unknown"
        val age = maxOf(view.now - last, 0)

        fun unit(
            n: Long,
            name: String,
        ) = "$n $name${if (n == 1L) "" else "s"} ago"
        val rel =
            when {
                age < MINUTE -> "just now"
                age < HOUR -> unit(age / MINUTE, "minute")
                age < TWO_DAYS -> unit(age / HOUR, "hour")
                age < TWO_WEEKS -> unit(age / DAY, "day")
                age < TWO_MONTHS -> unit(age / WEEK, "week")
                else -> unit(age / MONTH, "month")
            }
        val abs = runCatching { Zone.system().minute(last) }.getOrDefault("")
        return "$rel ($abs)"
    }
}
