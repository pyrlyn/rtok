// Port of src/bench/p28.rs (T385.9.3): `rtok bench --suite p28`, Gate P28, the LLM compressor against the lossless
// archive pointer. Each arm is a `claude --settings` file aimed at its own `rtok proxy`, and that proxy's
// `RTOK_HOME`, read after the runs for the compressor's `internal`-lane tokens, its summaries checked against the
// must-keep fixture, and `expand` recovery. Offline (no RTOK_BENCH_LIVE) every run is a zero row.
package rtok.bench

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import rtok.config.Config
import rtok.config.ModelPrice
import rtok.measure.rowCost
import rtok.measure.stats.fixed
import rtok.measure.stats.laneOfKind
import rtok.store.Store
import rtok.store.archiveSummaries
import rtok.store.getArchive
import rtok.store.usageByLaneModelTier
import rtok.sys.ProgressRunner
import rtok.sys.createDirAll
import rtok.sys.exists
import rtok.sys.pid
import rtok.sys.removeDirAll
import rtok.sys.tempDir
import rtok.sys.writeString

internal data class P28Arm(
    val id: String,
    val settings: String,
    val home: String,
)

internal data class P28Task(
    val id: String,
    val first: String,
    val question: String,
    val expect: Regex,
)

internal data class P28Body(
    val id: String,
    val text: String,
    val keep: List<String>,
)

internal class P28File(
    val arms: List<P28Arm>,
    val tasks: List<P28Task>,
    val bodies: List<P28Body>,
)

/** What one arm's proxy store holds after (or before) the runs. */
internal data class ArmStore(
    /** `internal`-lane tokens, all four legs. */
    val tokens: Long = 0,
    /** USD over the priced `internal`-lane models. */
    val cost: Double = 0.0,
    /** Some `internal`-lane model has no `[stats.prices]` row, so [cost] is a floor. */
    val partial: Boolean = false,
    val summaries: Int = 0,
    /** Summaries that keep every fixture span of the body they summarise. */
    val kept: Int = 0,
    /** Summaries whose original `expand` still returns with every fixture span. */
    val recovered: Int = 0,
)

/** Per task-arm (and per arm) totals. */
private class P28Acc {
    var usage = Usage()
    var pass = 0
    var n = 0

    fun add(o: P28Acc) {
        usage = Usage(usage.input + o.usage.input, usage.cache + o.usage.cache, usage.output + o.usage.output, usage.cost + o.usage.cost)
        pass += o.pass
        n += o.n
    }
}

private const val MIN_ARMS = 2

internal object P28Suite {
    /** `[bench] tasks` when it names `p28.toml`, else `p28.toml` beside it. */
    fun tomlPath(tasks: String): String = if (tasks.substringAfterLast('/') == "p28.toml") tasks else tasks.substringBeforeLast('/', "bench").ifEmpty { "bench" } + "/p28.toml"

    private fun resolve(
        dir: String,
        p: String,
    ) = if (p.startsWith("/")) p else "$dir/$p"

    /** The suite file with its paths resolved against its own directory, and the fixture bodies. */
    fun load(path: String): P28File {
        val root = readToml(path)
        val dir = path.substringBeforeLast('/', ".")
        val fixture = resolve(dir, root.need("fixture", path))
        val arms =
            root["arms"]?.jsonArray.orEmpty().map {
                val o = it.jsonObject
                P28Arm(o.need("id", path), resolve(dir, o.need("settings", path)), resolve(dir, o.need("home", path)))
            }
        val bodies =
            readToml(fixture)["body"]?.jsonArray.orEmpty().map {
                val o = it.jsonObject
                P28Body(o.need("id", fixture), o.need("text", fixture), o["keep"]?.jsonArray.orEmpty().map { k -> k.jsonObject.need("text", fixture) })
            }
        val tasks = root["tasks"]?.jsonArray.orEmpty().map { task(it.jsonObject, path, bodies) }
        // A shared id merges two arms' totals; a shared home mixes their compressor spend.
        val repeated = arms.withIndex().firstOrNull { (i, a) -> arms.take(i).any { it.id == a.id || it.home == a.home } }?.value
        listOfNotNull(
            "p28 suite needs ≥ $MIN_ARMS arms, got ${arms.size}".takeIf { arms.size < MIN_ARMS },
            repeated?.let { "arm ${it.id} repeats an earlier arm's id or home" },
            "p28 suite has no tasks".takeIf { tasks.isEmpty() },
        ).firstOrNull()?.let { throw BenchException(it) }
        return P28File(arms, tasks, bodies)
    }

    private fun task(
        o: kotlinx.serialization.json.JsonObject,
        path: String,
        bodies: List<P28Body>,
    ): P28Task {
        val id = o.need("id", path)
        val first = o.need("first", path)
        val b = bodies.firstOrNull { it.id == first }
        val re = runCatching { Regex(o.need("expect", path)) }
        val problem =
            when {
                b == null -> "task $id first $first is not a fixture body"

                re.isFailure -> "$id expect: ${re.exceptionOrNull()?.message}"

                // The answer must be a span the compressor is bound to keep, or the gate tests nothing.
                b.keep.none { re.getOrThrow().containsMatchIn(it) } -> "task $id expect matches no must-keep span of $first"

                else -> null
            }
        problem?.let { throw BenchException(it) }
        return P28Task(id, first, o.need("question", path), re.getOrThrow())
    }

    fun run(cfg: Config): String {
        val file = load(tomlPath(cfg.bench.tasks))
        val runs = cfg.bench.runs.toInt()
        if (cfg.bench.dryRun) {
            return buildString { for (t in file.tasks) for (arm in file.arms) for (n in 1..runs) append("${t.id} ${arm.id} $n\n") }
        }
        return table(file, cfg, runs)
    }

    private fun table(
        file: P28File,
        cfg: Config,
        runs: Int,
    ): String {
        val prices = cfg.stats.prices
        val before = file.arms.map { armStore(it.home, file.bodies, prices) }
        val s = StringBuilder("id arm in cache out usd pass\n")
        val by = file.arms.associate { it.id to P28Acc() }
        for (t in file.tasks) {
            val prompt = prompt(t, file.bodies)
            for (arm in file.arms) {
                val a = P28Acc()
                repeat(runs) {
                    val (usage, answer) = one(prompt, file.bodies, arm, cfg.bench.timeoutS.toLong())
                    a.add(P28Acc().also { it.usage = usage })
                    a.n += 1
                    if (t.expect.containsMatchIn(answer)) a.pass += 1
                }
                val n = maxOf(a.n, 1).toDouble()
                val u = a.usage
                s.append("${t.id} ${arm.id} ${fixed(u.input / n, 0)} ${fixed(u.cache / n, 0)} ${fixed(u.output / n, 0)} ${fixed(u.cost / n, COST_DECIMALS)} ${a.pass}/$runs\n")
                by.getValue(arm.id).add(a)
            }
        }
        s.append("\narm runs pass agent_usd compressor_tokens compressor_usd usd_per_pass summaries must_keep expand\n")
        file.arms.zip(before).forEach { (arm, was) -> s.append(armRow(arm, was, armStore(arm.home, file.bodies, prices), by.getValue(arm.id))) }
        return s.toString()
    }

    private fun armRow(
        arm: P28Arm,
        was: ArmStore,
        now: ArmStore,
        a: P28Acc,
    ): String {
        val compressor = now.cost - was.cost
        val perPass = if (a.pass == 0) "-" else fixed((a.usage.cost + compressor) / a.pass, COST_DECIMALS)
        val plus = if (now.partial) "+" else ""
        return "${arm.id} ${a.n} ${a.pass}/${a.n} ${fixed(a.usage.cost, COST_DECIMALS)} ${now.tokens - was.tokens} " +
            "${fixed(compressor, COST_DECIMALS)}$plus $perPass ${now.summaries} ${now.kept}/${now.summaries} ${now.recovered}/${now.summaries}\n"
    }

    /** Every body by file name, `first` first, so it is the oldest result when the question comes. */
    fun prompt(
        t: P28Task,
        bodies: List<P28Body>,
    ): String {
        val files = (listOf(t.first) + bodies.map { it.id }.filter { it != t.first }).joinToString(", ") { "$it.txt" }
        return "Read each of these files with the Read tool, one file per call, in this order: $files. " +
            "Then answer in one line: ${t.question} A shortened tool result names an id; " +
            "`rtok expand <id>` prints the original."
    }

    /** One `claude -p` run in a fresh directory holding the fixture bodies: usage and the final answer, or zeros and `""`. */
    private fun one(
        prompt: String,
        bodies: List<P28Body>,
        arm: P28Arm,
        timeoutS: Long,
    ): Pair<Usage, String> {
        val none = Usage() to ""
        val dir = "${tempDir()}/rtok-p28-${pid()}-${arm.id}"
        val ready =
            exists(arm.settings) && live() &&
                runCatching {
                    removeDirAll(dir)
                    createDirAll(dir)
                    bodies.forEach { writeString("$dir/${it.id}.txt", it.text) }
                }.isSuccess
        if (!ready) return none
        val cmd =
            ProgressRunner(
                "running claude -p…",
                "claude",
                listOf("-p", prompt, "--output-format", "json", "--settings", arm.settings, "--allowedTools", "Read,Bash(rtok expand:*)"),
            ).quiet().cwd(dir)
        val out = runBounded(cmd, maxOf(1, timeoutS) * MS)
        runCatching { removeDirAll(dir) }
        return out?.let { parseUsage(it) to parseJson(it).text("result") } ?: none
    }

    /** The arm proxy's store at `home/rtok.db`; all zeros when it does not exist yet (never created here). */
    fun armStore(
        home: String,
        bodies: List<P28Body>,
        prices: Map<String, ModelPrice>,
    ): ArmStore {
        val db = "$home/rtok.db"
        if (!exists(db)) return ArmStore()
        val store =
            try {
                Store.open(db)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                throw BenchException("$db: ${e.message}", e)
            }
        return store.use { tally(it, home, bodies, prices) }
    }

    private fun tally(
        store: Store,
        home: String,
        bodies: List<P28Body>,
        prices: Map<String, ModelPrice>,
    ): ArmStore {
        var r = ArmStore()
        for (row in store.usageByLaneModelTier()) {
            if (row.kind?.let(::laneOfKind) != "internal") continue
            val u = row.usage
            r = r.copy(tokens = r.tokens + u.input + u.cacheCreate + u.cacheRead + u.output)
            val p = prices[u.model]
            r = if (p == null) r.copy(partial = true) else r.copy(cost = r.cost + rowCost(u.input, u.cacheCreate, u.cacheRead, u.output, p).first)
        }
        for ((id, summary) in store.archiveSummaries()) {
            r = r.copy(summaries = r.summaries + 1)
            val original = store.getArchive(id, "$home/archive")?.decodeToString()
            // The archived Read output carries line numbers, so the body is found by its spans.
            val b = original?.let { o -> bodies.firstOrNull { b -> b.keep.all { o.contains(it) } } } ?: continue
            r = r.copy(recovered = r.recovered + 1, kept = r.kept + if (b.keep.all { summary.contains(it) }) 1 else 0)
        }
        return r
    }
}
