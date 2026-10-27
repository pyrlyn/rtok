// Port of src/measure/transcript_cache.rs (T135): the per-transcript numbers `doctor` needs, cached by (path, size,
// mtime) so an unchanged JSONL is parsed once. The web/TUI snapshot loop hits the in-process map; `rtok doctor` runs
// reload it from a JSON file beside the store. A cache that cannot be read or written only costs a re-parse.
package rtok.measure

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import rtok.agent.sdk.writeAtomic
import rtok.sys.exists
import rtok.sys.fileStat
import rtok.sys.readToString
import kotlin.concurrent.AtomicReference
import kotlin.math.ceil

/** One transcript's totals, valid while [size] and [mtimeNs] match the file. */
@Serializable
data class FileAgg(
    val size: Long = 0,
    @SerialName("mtime_ns") val mtimeNs: Long = 0,
    /** `est_tokens` of every tool_result, per tool name. */
    @SerialName("tool_tokens") val toolTokens: Map<String, Long> = emptyMap(),
    /** `Skill` tool_uses per `input.skill` (T61.1). */
    val skills: Map<String, Long> = emptyMap(),
    /** T392: per skill, `[loads, body tokens]` of the loads that repeated one with no compaction between. */
    @SerialName("skill_repeats") val skillRepeats: Map<String, List<Long>> = emptyMap(),
    /** An entry written before a field existed lacks it; a version mismatch re-parses instead. */
    val version: Int = 0,
)

object TranscriptCache {
    const val AGG_VERSION = 2
    private const val CHARS_PER_TOKEN = 4.0

    private val JSON = Json { ignoreUnknownKeys = true }
    private val SERIALIZER = MapSerializer(String.serializer(), FileAgg.serializer())
    private val MEMORY = AtomicReference<MutableMap<String, FileAgg>?>(null)

    /** stats.rs `est_tokens`: bytes / 4, rounded up. */
    fun estTokens(bytes: Long): Long = if (bytes == 0L) 0 else ceil(bytes / CHARS_PER_TOKEN).toLong()

    /**
     * Aggregates for every transcript under [dir] modified after [cutoff] (unix seconds), in path order, through the
     * process cache seeded from [file]. The file is rewritten when anything was parsed.
     */
    fun scan(
        dir: String,
        cutoff: Long,
        file: String?,
    ): List<Pair<String, FileAgg>> {
        val cache = MEMORY.value ?: (file?.let(::load) ?: mutableMapOf()).also { MEMORY.value = it }
        val (out, parsed) = scanWith(cache, dir, cutoff)
        if (parsed > 0 && file != null) {
            // Deleted transcripts leave the file with it, so the cache never outgrows the dir.
            cache.keys.retainAll(::exists)
            runCatching { save(cache, file) }
        }
        return out
    }

    /** [scan] over an explicit cache; also returns the bytes it had to parse. */
    @Suppress("LoopWithTooManyJumpStatements")
    fun scanWith(
        cache: MutableMap<String, FileAgg>,
        dir: String,
        cutoff: Long,
    ): Pair<List<Pair<String, FileAgg>>, Long> {
        var parsed = 0L
        val out = mutableListOf<Pair<String, FileAgg>>()
        for (p in jsonlPaths(dir, cutoff).sorted()) {
            val (mtime, size) = fileStat(p) ?: continue
            val hit = cache[p]?.takeIf { it.size == size && it.mtimeNs == mtime && it.version == AGG_VERSION }
            val agg =
                hit ?: run {
                    val t = parsePath(p) ?: return@run null
                    parsed += size
                    aggregate(t, size, mtime).also { cache[p] = it }
                } ?: continue
            out += p to agg
        }
        return out to parsed
    }

    fun aggregate(
        t: Parsed,
        size: Long,
        mtimeNs: Long,
    ): FileAgg {
        val bodies = t.injected.associate { it.toolUseId to it.bytes }
        val window = HashMap<String, Int>()
        val names = HashMap<String, String>()
        val skills = LinkedHashMap<String, Long>()
        val repeats = LinkedHashMap<String, List<Long>>()
        for (u in t.toolUses) {
            names[u.id] = u.name
            val s = u.input.field("skill").str()
            if (u.name != "Skill" || s == null) continue
            skills[s] = (skills[s] ?: 0) + 1
            // The compaction count before a call names the context window it loaded into.
            val now = t.compactions.count { it <= u.turn }
            if (window.put(s, now) == now) {
                val e = repeats[s] ?: listOf(0L, 0L)
                repeats[s] = listOf(e[0] + 1, e[1] + estTokens(bodies[u.id] ?: 0))
            }
        }
        val tools = LinkedHashMap<String, Long>()
        for (r in t.toolResults) {
            val name = names[r.toolUseId] ?: "unknown"
            tools[name] = (tools[name] ?: 0) +
                estTokens(
                    r.content
                        .encodeToByteArray()
                        .size
                        .toLong(),
                )
        }
        return FileAgg(size, mtimeNs, tools.sorted(), skills.sorted(), repeats.sorted(), AGG_VERSION)
    }

    private fun <V> Map<String, V>.sorted(): Map<String, V> = entries.sortedBy { it.key }.associate { it.key to it.value }

    fun load(file: String): MutableMap<String, FileAgg> = runCatching { JSON.decodeFromString(SERIALIZER, readToString(file)).toMutableMap() }.getOrDefault(mutableMapOf())

    fun save(
        cache: Map<String, FileAgg>,
        file: String,
    ) {
        writeAtomic(file, JSON.encodeToString(SERIALIZER, cache.sorted()).encodeToByteArray())
    }
}
