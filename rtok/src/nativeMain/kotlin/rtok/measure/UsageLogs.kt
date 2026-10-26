// Port of src/measure/usage.rs: the agents' own session files as `rtok agents usage --source logs` rows (T358.2),
// one `UsageSlice` per request, read on the fly and never written to the store. Claude Code and Codex sit on the
// `jsonl`/`codex` parsers; the other documented hosts have a reader each (`usage/`, T358.3). A request with no
// usable timestamp is left out: it has no day to land on.
package rtok.measure

import kotlinx.serialization.Serializable
import rtok.config.Config
import rtok.store.UsageSlice
import rtok.sys.fileName
import rtok.sys.listDir
import rtok.sys.parent

/** A host whose session files exist but could not be read: named once, counted nowhere. */
@Serializable
data class Skipped(
    val host: String,
    val reason: String,
    val path: String,
)

/** Everything `--source logs` read, and the hosts it had to skip. */
class Logs(
    val slices: MutableList<UsageSlice> = ArrayList(),
    val skipped: MutableList<Skipped> = ArrayList(),
)

/** One host's requests and the first of its files that held none it could read. */
typealias HostSlices = Pair<List<UsageSlice>, String?>

/** One request's token legs, in the store's order. */
data class Legs(
    val input: Long,
    val cacheCreate: Long,
    val cacheRead: Long,
    val output: Long,
) {
    val total get() = input + cacheCreate + cacheRead + output
}

object UsageLogs {
    /** Why a host's files are named without being counted: they exist but no request came out. */
    const val UNKNOWN = "unknown format"
    const val DROID_UNSUPPORTED = "unsupported: the session settings fields are not documented"
    const val GROK_UNSUPPORTED = "unsupported: xAI documents `grok usage`, not the session files"
    const val ZCODE_UNSUPPORTED = "unsupported: the session records are not documented"
    const val ANTIGRAVITY_UNSUPPORTED = "unsupported: the local session data is not documented"

    /** Every host's requests stamped at or after [since]: Claude Code and Codex from `[stats]`, the rest from `[agents.usage.dirs]`. */
    fun read(
        cfg: Config,
        since: Long,
    ): Logs {
        val dirs = cfg.agents.usage.dirs
        val logs = Logs()

        fun add(
            host: String,
            reason: String,
            got: HostSlices,
        ) {
            logs.slices += got.first
            got.second?.let { logs.skipped += Skipped(host, reason, it) }
        }
        add("claude", UNKNOWN, claudeSlices(cfg.stats.transcriptsDir, since))
        add("codex", UNKNOWN, codexSlices(cfg.stats.codexDir, since))
        // Hosts whose format is not documented are named when they left files, never guessed at.
        for ((host, reason, list) in listOf(
            Triple("droid", DROID_UNSUPPORTED, dirs.droid),
            Triple("grok", GROK_UNSUPPORTED, dirs.grok),
            Triple("zcode", ZCODE_UNSUPPORTED, dirs.zcode),
            Triple("antigravity", ANTIGRAVITY_UNSUPPORTED, dirs.antigravity),
        )) {
            add(host, reason, emptyList<UsageSlice>() to list.firstOrNull { runCatching { listDir(it).isNotEmpty() }.getOrDefault(false) })
        }
        return logs
    }

    /** Claude Code transcripts under [dir]; a sub-agent transcript counts toward its parent's session. */
    internal fun claudeSlices(
        dir: String,
        since: Long,
    ): HostSlices {
        val out = ArrayList<UsageSlice>()
        var unreadable: String? = null
        for (p in jsonlPaths(dir, since).sorted()) {
            val parsed = parsePath(p)
            if (parsed == null || (parsed.lines > 0 && parsed.malformed == parsed.lines)) unreadable = unreadable ?: p
            parsed ?: continue
            val owner = if (isSubagent(p)) parent(p)?.let(::parent) else p
            val session = owner?.let(::fileName)?.substringBeforeLast('.') ?: ""
            parsed.usages.mapNotNullTo(out) { u ->
                slice("claude", "anthropic", session, u.model, u.ts, Legs(u.inputTokens, u.cacheCreationInputTokens, u.cacheReadInputTokens, u.outputTokens), since)
            }
        }
        return out to unreadable
    }

    /** Codex rollouts under [dir], requests stamped at or after [since]. */
    internal fun codexSlices(
        dir: String,
        since: Long,
    ): HostSlices {
        val (reqs, unreadable) = requests(dir, since)
        return reqs.mapNotNull { r -> slice("codex", "openai", r.session, r.model, r.ts, Legs(r.input, r.cacheCreate, r.cacheRead, r.output), since) } to unreadable
    }

    /** No timestamp, before [since], or no tokens yields nothing. */
    fun slice(
        host: String,
        api: String,
        session: String,
        model: String?,
        ts: Long,
        legs: Legs,
        since: Long,
    ): UsageSlice? =
        if (ts <= 0 || ts < since || legs.total == 0L) {
            null
        } else {
            UsageSlice(host, api, session, model, ts, legs.input, legs.cacheCreate, legs.cacheRead, legs.output)
        }
}
