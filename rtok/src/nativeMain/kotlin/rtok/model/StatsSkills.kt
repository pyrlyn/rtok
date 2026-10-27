// Port of src/model/mod.rs `stats_skills` / `stats_skills_from` / `read_stats_skills` / `stats_page_text` and
// `skills_from` (T63.1, T227, T947): one transcript scan feeds both the Skills page (the doctor's skill listing joined
// to the scan's resident bytes and invocations) and the Stats page (`rtok stats --price`'s table plus `--cache`'s).
// The scan runs on a Background worker, never on the tick: the snapshot ships the last scan for 300 s, or "reading
// transcripts…" before the first one lands. `rtok stats` itself still goes through `StatsPage.report` uncached.
package rtok.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.config.Config
import rtok.doctor.SkillsAudit
import rtok.measure.stats.CacheHealth
import rtok.measure.stats.Report
import rtok.measure.stats.SkillRow
import rtok.measure.stats.SortedMap
import rtok.measure.stats.fixed
import rtok.measure.stats.toTable
import kotlin.time.Duration.Companion.seconds

/** What the scan hands the snapshot: skill rows, input tokens, whether a scan landed, and the Stats page text. */
data class StatsSkills(
    val skills: SortedMap<SkillRow>?,
    val usageInput: Long,
    val scanned: Boolean,
    val text: String?,
)

data class SkillPageRow(
    val name: String,
    val source: String,
    val descChars: Int,
    val bodyBytes: Long,
    val invocations: Long,
    val resident: Long,
    val lastInvoked: String,
    val never: Boolean,
) {
    fun toJson() =
        JsonObject(
            mapOf(
                "name" to JsonPrimitive(name),
                "source" to JsonPrimitive(source),
                "desc_chars" to JsonPrimitive(descChars),
                "body_bytes" to JsonPrimitive(bodyBytes),
                "invocations" to JsonPrimitive(invocations),
                "resident" to JsonPrimitive(resident),
                "last_invoked" to JsonPrimitive(lastInvoked),
                "never" to JsonPrimitive(never),
            ),
        )
}

/** The Skills page (T63.1, D23): one row per skill the host lists, hottest resident first. */
data class SkillsPage(
    val header: String,
    val rows: List<SkillPageRow>,
) {
    fun toJson() = JsonObject(mapOf("header" to JsonPrimitive(header), "rows" to JsonArray(rows.map { it.toJson() })))
}

/** research.md §10.2: 4 chars per token. */
private const val CHARS_PER_TOKEN = 4

object StatsSkillsPage {
    /** STATS_TTL (T947): a month of transcripts takes minutes to scan; skill counts and totals move slowly. */
    val TTL = 300.seconds

    const val READING = "reading transcripts…\n"

    private val cache = Background<Pair<String, StatsSkills>>("web-stats")

    /** `stats_skills`: the last scan for this `transcripts_dir` + `since`, never waiting on one. */
    fun get(cfg: Config): StatsSkills = from(cache, cfg.stats.transcriptsDir + cfg.stats.since) { read(cfg) }

    /** `stats_skills_from`: over any cache and scan, so a test can pass a scan that blocks. Another key's scan is dropped. */
    fun from(
        cache: Background<Pair<String, StatsSkills>>,
        key: String,
        read: () -> StatsSkills,
    ): StatsSkills {
        val landed = cache.get(TTL) { key to read() }
        if (landed != null && landed.first == key) return landed.second
        if (landed != null) cache.forget()
        return StatsSkills(null, 0, false, READING)
    }

    /** `read_stats_skills`: synchronous; the page always carries the cost (`[stats.prices]` is a local table). */
    fun read(cfg: Config): StatsSkills =
        runCatching { StatsPage.report(cfg.copy(stats = cfg.stats.copy(price = true))) }.fold(
            onSuccess = { StatsSkills(it.skills, it.usageInput, true, pageText(cfg, it)) },
            onFailure = { StatsSkills(null, 0, false, null) },
        )

    /** `stats_page_text` (T227): the `--price` table, then `--cache`'s below it. */
    fun pageText(
        cfg: Config,
        report: Report,
    ): String =
        buildString {
            append(report.toTable())
            runCatching { StatsPage.cacheHealth(cfg) }.getOrNull()?.let {
                append("\ncache health\n")
                append(CacheHealth.table(it))
            }
        }

    /** `skills_from`: the listing joined to the scan; with no listing row data, a scanned zero is "never". */
    fun skillsFrom(
        listing: SkillsAudit?,
        stats: Map<String, SkillRow>?,
        usageInput: Long,
        scanned: Boolean,
    ): SkillsPage {
        val rows =
            listing
                ?.rows
                .orEmpty()
                .map { r ->
                    val st = stats?.get(r.name)
                    val invocations = r.invocations ?: st?.count ?: 0
                    val never = r.invocations?.let { it == 0L } ?: (scanned && invocations == 0L)
                    SkillPageRow(r.name, r.source, r.descChars, r.bodyBytes, invocations, st?.resident ?: 0, if (never) "never" else "—", never)
                }.sortedWith(compareByDescending<SkillPageRow> { it.resident }.thenBy { it.name })
        val descBytes = listing?.descBytes ?: 0
        val resident = rows.sumOf { it.resident }
        val share = if (usageInput == 0L) 0.0 else 100.0 * (resident / CHARS_PER_TOKEN).toDouble() / usageInput.toDouble()
        return SkillsPage(
            "${rows.size} skills · $descBytes desc bytes ≈ ${descBytes / CHARS_PER_TOKEN} tok/req · $resident resident · ${fixed(share, 1)}% of input",
            rows,
        )
    }
}
