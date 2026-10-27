// Port of src/bench.rs (T9.1, T68.9): `rtok bench` A/B harness. The default suite runs six tasks on every
// `[bench.configs]` arm `runs` times; `--dry-run` prints the schedule. Live spend is gated on RTOK_BENCH_LIVE.
package rtok.bench

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import rtok.config.Config
import rtok.measure.stats.fixed
import rtok.sys.ProgressRunner

/** A bench that cannot run (bad suite, task file or count). */
class BenchException(
    message: String,
) : Exception(message)

internal data class Task(
    val id: String,
    val prompt: String,
    val check: String,
)

/** Per-arm totals of the default suite. */
internal class Acc {
    var input = 0L
    var cache = 0L
    var output = 0L
    var cost = 0.0
    var pass = 0
    var n = 0

    fun add(u: Usage) {
        input += u.input
        cache += u.cache
        output += u.output
        cost += u.cost
        n += 1
    }
}

private const val TASKS = 6
private const val NAME_W = 8
private const val COL_W = 10
private const val PASS_W = 3

/** Decimals of a cost column (Rust `{:.4}`). */
internal const val COST_DECIMALS = 4

object Bench {
    /** The report `rtok bench` prints for [cfg]. */
    fun run(cfg: Config): String =
        when (cfg.bench.suite) {
            "graph" -> GraphSuite.run(cfg)
            "p28" -> P28Suite.run(cfg)
            "" -> defaultSuite(cfg)
            else -> throw BenchException("unknown bench suite \"${cfg.bench.suite}\"")
        }

    private fun defaultSuite(cfg: Config): String {
        val tasks = loadTasks(cfg.bench.tasks)
        if (tasks.size != TASKS) throw BenchException("expected $TASKS tasks, got ${tasks.size}")
        return if (cfg.bench.dryRun) dryList(tasks, cfg) else table(tasks, cfg)
    }

    internal fun loadTasks(path: String): List<Task> {
        val root = readToml(path)
        return root["tasks"]?.jsonArray.orEmpty().map {
            val o = it.jsonObject
            Task(o.need("id", path), o.need("prompt", path), o.need("check", path))
        }
    }

    private fun dryList(
        tasks: List<Task>,
        cfg: Config,
    ) = buildString {
        for (t in tasks) for (name in cfg.bench.configs.keys) for (n in 1..cfg.bench.runs.toInt()) append("${t.id} $name $n\n")
    }

    private fun table(
        tasks: List<Task>,
        cfg: Config,
    ): String {
        val by = mutableMapOf<String, Acc>()
        for (t in tasks) {
            for ((name, settings) in cfg.bench.configs) {
                val acc = by.getOrPut(name, ::Acc)
                repeat(cfg.bench.runs.toInt()) { acc.add(one(t.prompt, settings, cfg.bench.timeoutS.toLong())) }
                if (checkPasses(t)) acc.pass += 1
            }
        }
        writeResults(by, tasks.size, cfg)
        val s = StringBuilder("${"config".padEnd(NAME_W)} ")
        listOf("input", "cache", "output", "cost").forEach { s.append(it.padStart(COL_W)).append(' ') }
        s.append("pass".padStart(PASS_W * 2 + 2)).append('\n')
        for ((name, a) in by.entries.sortedBy { it.key }.map { it.key to it.value }) {
            val n = maxOf(a.n, 1).toDouble()
            s.append(name.padEnd(NAME_W)).append(' ')
            for (v in listOf(a.input, a.cache, a.output)) s.append(fixed(v / n, 0).padStart(COL_W)).append(' ')
            s.append(fixed(a.cost / n, COST_DECIMALS).padStart(COL_W)).append(' ')
            s
                .append("${a.pass}".padStart(PASS_W))
                .append('/')
                .append("${tasks.size}".padEnd(PASS_W))
                .append('\n')
        }
        return s.toString()
    }

    /** Whether the task's `check` command exits 0; its output is kept, not shown. */
    private fun checkPasses(t: Task): Boolean =
        runCatching {
            ProgressRunner("checking ${t.id}…", "sh", listOf("-c", t.check))
                .quiet()
                .runCapped(MAX_OUTPUT)
                .first.exitCode == 0
        }.getOrDefault(false)

    /** `<tasks dir>/results/<arm>.json`, one per arm. */
    private fun writeResults(
        by: Map<String, Acc>,
        tasks: Int,
        cfg: Config,
    ) {
        val dir =
            cfg.bench.tasks
                .substringBeforeLast('/', "bench")
                .ifEmpty { "/" } + "/results"
        runCatching { rtok.sys.createDirAll(dir) }
        for ((name, a) in by) {
            val n = maxOf(a.n, 1).toDouble()
            val v =
                buildJsonObject {
                    put("config", name)
                    put("runs", a.n)
                    put("mean_input", a.input / n)
                    put("mean_cache", a.cache / n)
                    put("mean_output", a.output / n)
                    put("mean_cost_usd", a.cost / n)
                    put("pass", a.pass)
                    put("tasks", tasks)
                    put("live", live())
                }
            runCatching { rtok.sys.writeString("$dir/$name.json", PRETTY.encodeToString(JsonObject.serializer(), v) + "\n") }
        }
    }
}

internal val PRETTY = kotlinx.serialization.json.Json { prettyPrint = true }

/** The TOML file at [path] as JSON; the error names the file. */
internal fun readToml(path: String): JsonObject =
    try {
        rtok.config.Toml.parse(rtok.sys.readToString(path))
    } catch (
        @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
    ) {
        throw BenchException("$path: ${e.message}")
    }

internal fun JsonObject.need(
    k: String,
    path: String,
): String = (get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw BenchException("$path: missing field `$k`")
