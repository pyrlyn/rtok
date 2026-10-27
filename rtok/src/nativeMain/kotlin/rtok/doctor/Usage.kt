// Port of the transcript readers of src/doctor.rs: `read_share` (T50.4), `skill_usage` (T61.1, T392) and
// `transcripts` (the T135 cache beside the store).
package rtok.doctor

import rtok.config.Config
import rtok.config.parseSinceFrom
import rtok.measure.FileAgg
import rtok.measure.TranscriptCache
import rtok.measure.isSubagent
import rtok.sys.parent

internal object Usage {
    private const val SKILL_WINDOW_SECS = 30L * 86_400

    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    private fun now(): Long = platform.posix.time(null)

    private fun transcripts(
        cfg: Config,
        cutoff: Long,
    ): List<Pair<String, FileAgg>> {
        val file = "${parent(cfg.core.dbPath) ?: "."}/transcripts-cache.json"
        return TranscriptCache.scan(cfg.stats.transcriptsDir, cutoff, file)
    }

    /** `(grep + glob) / (read + grep + glob)` over transcript tokens, sub-agents left out; null on no data or error. */
    fun readShare(cfg: Config): ReadShare? {
        val since = runCatching { parseSinceFrom(cfg.stats.since, "stats.since") }.getOrNull() ?: return null
        val files = transcripts(cfg, (now() - since).coerceAtLeast(0)).filter { !isSubagent(it.first) }

        fun tok(name: String) = files.sumOf { it.second.toolTokens[name] ?: 0 }
        val (read, grep, glob) = Triple(tok("Read"), tok("Grep"), tok("Glob"))
        val denom = read + grep + glob
        return if (denom == 0L) null else ReadShare(read, grep, glob, (grep + glob).toDouble() / denom)
    }

    /** Invocations per skill over the last 30 days, and `(loads, tokens)` of its repeat loads. */
    fun skillUsage(cfg: Config): Pair<Map<String, Long>, Map<String, Pair<Long, Long>>>? =
        runCatching {
            val calls = LinkedHashMap<String, Long>()
            val repeats = LinkedHashMap<String, Pair<Long, Long>>()
            for ((_, agg) in transcripts(cfg, now() - SKILL_WINDOW_SECS)) {
                for ((s, n) in agg.skills) calls[s] = (calls[s] ?: 0) + n
                for ((s, v) in agg.skillRepeats) {
                    val e = repeats[s] ?: (0L to 0L)
                    repeats[s] = (e.first + v.getOrElse(0) { 0 }) to (e.second + v.getOrElse(1) { 0 })
                }
            }
            calls to repeats
        }.getOrNull()
}
