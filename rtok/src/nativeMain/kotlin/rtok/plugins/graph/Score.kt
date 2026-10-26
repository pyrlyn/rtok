// Port of src/plugins/graph/health/score.rs (T329.19): a 0 to 100 health score per project from what the
// health check and the capability record already know. Nothing here probes or spawns.
package rtok.plugins.graph

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import rtok.plugin.Runtime
import rtok.plugin.sdk.Ctx
import rtok.store.Project
import rtok.store.project
import rtok.store.projectLinks
import rtok.store.projects
import kotlin.math.roundToInt
import kotlin.time.TimeSource

@Serializable enum class Level {
    @SerialName("good")
    Good,

    @SerialName("warn")
    Warn,

    @SerialName("bad")
    Bad,

    @SerialName("indexing")
    Indexing,

    @SerialName("missing")
    Missing,
}

@Serializable enum class Component {
    @SerialName("freshness")
    Freshness,

    @SerialName("backend")
    Backend,

    @SerialName("links")
    Links,
}

@Serializable data class Reason(
    val component: Component,
    val text: String,
    val fix: String,
)

@Serializable data class Components(
    val freshness: Double,
    val backend: Double,
    val links: Double,
)

@Serializable data class HealthScore(
    val score: Int?,
    val level: Level,
    val components: Components,
    val reasons: List<Reason> = emptyList(),
)

@Serializable data class Weak(
    val project: String,
    val root: String,
    val score: Int,
    val reasons: List<Reason>,
)

object Score {
    const val NOTICE_BELOW = 80
    private const val WARN_BELOW = 50
    private const val PENDING_LIMIT = 0.20
    private const val STALE_S = 24 * 3600L
    private const val TAGS_FALLBACK = 0.6
    private const val TEXT_FALLBACK = 0.3
    const val MISSING_SERVER_FIX = "install the server; it is picked up within one health-check interval, or restart"

    private fun two(x: Double) = (x.coerceIn(0.0, 1.0) * 100.0).roundHalfAway() / 100.0

    private fun Double.roundHalfAway(): Double = if (this < 0) -kotlin.math.floor(-this + 0.5) else kotlin.math.floor(this + 0.5)

    fun missing(p: Project) =
        HealthScore(
            0,
            Level.Missing,
            Components(0.0, 0.0, 0.0),
            listOf(
                Reason(Component.Freshness, "missing: the project directory does not exist", "restore the directory, or `rtok graph projects remove ${p.id}`"),
            ),
        )

    internal fun freshness(
        st: GraphStatus,
        now: Long,
    ): Pair<Double, Reason?> {
        val pending = st.pending.size
        if (pending == 0) return 1.0 to null
        val share = pending.toDouble() / maxOf(st.files, pending.toLong()).toDouble()
        val age = st.indexedAt?.let { now - it } ?: STALE_S
        val value = minOf(1.0 - share / PENDING_LIMIT, 1.0 - age.toDouble() / STALE_S)
        val pct = (share * 100.0).roundHalfAway().toLong()
        return value.coerceIn(0.0, 1.0) to Reason(Component.Freshness, "$pending files pending ($pct%)", "run `rtok graph index ${st.root}`")
    }

    private fun backend(
        cx: Ctx,
        p: Project,
        st: GraphStatus,
    ): Pair<Double, Reason?> {
        val config = GraphCore.backendName(cx, p.root)
        if (config != "auto" && config != "lsp") return 1.0 to null
        val rec = Capability.mirrored(cx, p.root)?.takeIf { it.config == config } ?: return 1.0 to null
        if (rec.backend == Chosen.Lsp || !rec.server) return 1.0 to null
        val (value, using) =
            when {
                st.rows > 0 -> TAGS_FALLBACK to "using tree-sitter"
                config == "auto" -> TEXT_FALLBACK to "using text search"
                else -> 0.0 to "no backend answers"
            }
        val cause = (rec.reason ?: "language server stopped answering").removePrefix("lsp: ")
        val fix = if (rec.down) "the health check retries the server on a backoff; or restart" else MISSING_SERVER_FIX
        return value to Reason(Component.Backend, "$cause, $using", fix)
    }

    private fun links(
        rt: Runtime,
        cx: Ctx,
        p: Project,
    ): Pair<Double, List<Reason>> {
        val why = ArrayList<Reason>()
        var total = 0
        var good = 0
        for (l in runCatching { rt.store.projectLinks() }.getOrDefault(emptyList()).filter { it.from == p.id }) {
            val to = runCatching { rt.store.project(l.to) }.getOrNull() ?: continue
            total++
            val name = to.displayName()
            val down = to.missing() || Alerts.mirrored(cx, to.root).any { it.kind == AlertKind.Missing || it.kind == AlertKind.Unreachable }
            val indexed = runCatching { cx.symbolIndexedAt(Index.canon(to.root)) }.getOrNull() != null
            when {
                down -> why += Reason(Component.Links, "link to $name broken: the project is missing or unreachable", "restore it, or `rtok graph projects unlink ${to.id} --from ${p.id}`")
                !indexed -> why += Reason(Component.Links, "link to $name not indexed", "run `rtok graph index ${to.root}`")
                else -> good++
            }
        }
        for (a in Alerts.mirrored(cx, p.root).filter { it.kind == AlertKind.LinkBroken }) {
            total += a.detail.split("; ").size
            why += Reason(Component.Links, a.detail, "fix the path in the manifest, or remove the reference")
        }
        return (if (total == 0) 1.0 else good.toDouble() / total) to why
    }

    fun levelOf(score: Int) =
        when {
            score >= NOTICE_BELOW -> Level.Good
            score >= WARN_BELOW -> Level.Warn
            else -> Level.Bad
        }

    fun of(
        rt: Runtime,
        p: Project,
        st: GraphStatus,
    ): HealthScore {
        if (p.missing()) return missing(p)
        val cx = Ctx(rt)
        val (fresh, freshWhy) = freshness(st, Capability.now())
        val (back, backWhy) = backend(cx, p, st)
        val (link, linkWhy) = links(rt, cx, p)
        val total = (100.0 * (0.4 * fresh + 0.3 * back + 0.3 * link)).roundHalfAway().toInt()
        val comps = Components(two(fresh), two(back), two(link))
        if (st.indexedAt == null && st.rows == 0L) {
            return HealthScore(null, Level.Indexing, comps, listOf(Reason(Component.Freshness, "indexing: the first index has not finished", "run `rtok graph index ${st.root}`")))
        }
        return HealthScore(total, levelOf(total), comps, listOfNotNull(freshWhy, backWhy) + linkWhy)
    }

    private class Cached(
        val at: TimeSource.Monotonic.ValueTimeMark,
        val score: HealthScore,
    )

    private val cache = HashMap<String, Cached>()
    private val lock = rtok.sys.Mutex()

    /** [of] without a status at hand, recomputed at most once a second per project. Fails open. */
    fun cached(
        rt: Runtime,
        p: Project,
    ): HealthScore? {
        if (p.missing()) return missing(p)
        lock.hold { cache[p.root]?.takeIf { it.at.elapsedNow().inWholeMilliseconds < 1000 }?.let { return it.score } }
        val st = runCatching { Status.collect(Ctx(rt), p.root) }.getOrNull() ?: return null
        val s = of(rt, p, st)
        lock.hold { cache[p.root] = Cached(TimeSource.Monotonic.markNow(), s) }
        return s
    }

    fun lowest(scores: Iterable<HealthScore>): Int? = scores.mapNotNull { it.score }.minOrNull()

    fun scopeLowest(
        rt: Runtime,
        reached: List<Reached>,
        own: HealthScore,
    ): Int? = lowest(reached.drop(1).mapNotNull { cached(rt, it.project) } + own)

    /** The line that heads a graph answer when the scope scores under [NOTICE_BELOW]; nothing said twice. */
    fun note(
        rt: Runtime,
        reached: List<Reached>,
        alerting: Set<String>,
    ): String? {
        val pick =
            reached
                .filter { it.project.root !in alerting }
                .mapNotNull { r ->
                    val s = cached(rt, r.project) ?: return@mapNotNull null
                    val score = s.score?.takeIf { it < NOTICE_BELOW } ?: return@mapNotNull null
                    val why = s.reasons.filter { it.component != Component.Links || alerting.isEmpty() }.map { it.text }
                    if (why.isEmpty()) null else Triple(r.project.displayName(), score, why)
                }.minByOrNull { it.second } ?: return null
        return "notice: graph health ${pick.second} for ${pick.first}: ${pick.third.joinToString("; ")}; results may be incomplete\n"
    }

    /** Every registered project under [NOTICE_BELOW], weakest first. */
    fun weak(rt: Runtime): List<Weak> =
        runCatching { rt.store.projects() }
            .getOrDefault(emptyList())
            .mapNotNull { p ->
                val s = cached(rt, p) ?: return@mapNotNull null
                Weak(p.displayName(), p.root, s.score?.takeIf { it < NOTICE_BELOW } ?: return@mapNotNull null, s.reasons)
            }.sortedWith(compareBy<Weak> { it.score }.thenBy { it.root })

    fun texts(weak: List<Weak>): List<String> = weak.map { w -> "${w.project} ${w.score}/100: ${w.reasons.joinToString("; ") { "${it.text} (fix: ${it.fix})" }}" }
}
