// The `--suite graph` half of src/bench.rs (T68.9): architecture questions with vs without the rtok graph MCP, on
// both arms `runs` times. Offline (no RTOK_BENCH_LIVE) every run is a zero row, so the table is the schedule.
package rtok.bench

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import rtok.config.Config
import rtok.measure.rowCost
import rtok.measure.stats.fixed
import rtok.sys.ProgressRunner
import kotlin.time.TimeSource

internal data class GraphRepo(
    val id: String,
    val path: String,
)

internal data class GraphQ(
    val id: String,
    val repo: String,
    val prompt: String,
    val expect: Regex,
)

internal class GraphFile(
    val repos: List<GraphRepo>,
    val questions: List<GraphQ>,
)

/** One graph run: tools, input, output, cache, wall ms, cost, resident tokens, and the answer text. */
internal data class GraphRun(
    val tools: Long = 0,
    val input: Long = 0,
    val output: Long = 0,
    val cache: Long = 0,
    val wall: Long = 0,
    val cost: Double = 0.0,
    val resident: Long = 0,
    val result: String = "",
) {
    operator fun plus(o: GraphRun) = GraphRun(tools + o.tools, input + o.input, output + o.output, cache + o.cache, wall + o.wall, cost + o.cost, resident + o.resident)

    /** The averaged metric columns over [n] runs. */
    fun cells(n: Double): String = listOf(tools, input, output, cache, wall).joinToString(" ") { fixed(it / n, 0) } + " ${fixed(cost / n, COST_DECIMALS)} ${fixed(resident / n, 0)}"
}

private val ARMS = listOf("mcp", "native")
private const val MIN_QUESTIONS = 10
private const val MIN_REPOS = 3

internal object GraphSuite {
    /** `[bench] tasks` when it names `graph.toml`, else `graph.toml` beside it. */
    fun tomlPath(tasks: String): String = if (tasks.substringAfterLast('/') == "graph.toml") tasks else tasks.substringBeforeLast('/', "bench").ifEmpty { "bench" } + "/graph.toml"

    fun load(path: String): GraphFile {
        val root = readToml(path)
        val repos = root["repos"]?.jsonArray.orEmpty().map { GraphRepo(it.jsonObject.need("id", path), it.jsonObject.need("path", path)) }
        val questions =
            root["questions"]?.jsonArray.orEmpty().map {
                val o = it.jsonObject
                val id = o.need("id", path)
                GraphQ(id, o.need("repo", path), o.need("prompt", path), expect(o.need("expect", path), id))
            }
        val unknown = questions.firstOrNull { q -> repos.none { it.id == q.repo } }
        listOfNotNull(
            "graph suite needs ≥ $MIN_QUESTIONS questions, got ${questions.size}".takeIf { questions.size < MIN_QUESTIONS },
            "graph suite needs ≥ $MIN_REPOS repos, got ${repos.size}".takeIf { repos.size < MIN_REPOS },
            unknown?.let { "question ${it.id} repo ${it.repo} is not in [[repos]]" },
        ).firstOrNull()?.let { throw BenchException(it) }
        return GraphFile(repos, questions)
    }

    private fun expect(
        pattern: String,
        id: String,
    ): Regex =
        try {
            Regex(pattern)
        } catch (
            @Suppress("SwallowedException") e: IllegalArgumentException,
        ) {
            throw BenchException("$id expect: ${e.message}")
        }

    fun run(cfg: Config): String {
        val file = load(tomlPath(cfg.bench.tasks))
        val runs = cfg.bench.runs.toInt()
        if (cfg.bench.dryRun) {
            return buildString { for (q in file.questions) for (arm in ARMS) for (n in 1..runs) append("${q.id} ${q.repo} $arm $n\n") }
        }
        return table(file, cfg, runs)
    }

    private fun table(
        file: GraphFile,
        cfg: Config,
        runs: Int,
    ): String {
        val s = StringBuilder("id repo arm tools in out cache wall_ms cost resident pass\n")
        val tot = Array(ARMS.size) { GraphRun() }
        val passTot = IntArray(ARMS.size)
        for (q in file.questions) {
            val repo = file.repos.first { it.id == q.repo }
            ARMS.forEachIndexed { i, arm ->
                var a = GraphRun()
                var pass = 0
                repeat(runs) {
                    val u = graphOne(q.prompt, repo.path, arm, cfg)
                    if (q.expect.containsMatchIn(u.result)) pass += 1
                    a += u
                }
                s.append("${q.id} ${q.repo} $arm ${a.cells(maxOf(runs, 1).toDouble())} $pass/$runs\n")
                tot[i] += a
                passTot[i] += pass
            }
        }
        val n = maxOf(file.questions.size * runs, 1).toDouble()
        ARMS.forEachIndexed { i, arm -> s.append("TOTAL * $arm ${tot[i].cells(n)} ${passTot[i]}/${fixed(n, 0)}\n") }
        return s.toString()
    }

    private fun graphOne(
        prompt: String,
        repo: String,
        arm: String,
        cfg: Config,
    ): GraphRun {
        if (!live()) return GraphRun()
        val start = TimeSource.Monotonic.markNow()
        val prefixed =
            if (arm == "mcp") {
                "Prefer rtok MCP graph tools (symbol, callers, impact, outline, explore). $prompt"
            } else {
                "Use only Read and Grep. Do not use MCP. $prompt"
            }
        val args = mutableListOf("-p", prefixed, "--output-format", "json")
        if (arm == "mcp") {
            val mcp = "${rtok.sys.tempDir()}/rtok-bench-mcp-${rtok.sys.pid()}.json"
            runCatching { rtok.sys.writeString(mcp, """{"mcpServers":{"rtok":{"command":"rtok","args":["mcp"]}}}""") }
            args += listOf("--mcp-config", mcp)
        } else {
            args += listOf("--allowedTools", "Read,Grep,Glob")
        }
        val cmd = ProgressRunner("running claude -p…", "claude", args).quiet().cwd(repo)
        return runBounded(cmd, maxOf(1, cfg.bench.timeoutS.toLong()) * MS)?.let { graphResult(parseJson(it), cfg, start) } ?: GraphRun()
    }

    private fun graphResult(
        v: kotlinx.serialization.json.JsonElement,
        cfg: Config,
        start: TimeSource.Monotonic.ValueTimeMark,
    ): GraphRun {
        val u = v.field("usage")
        val input = u.num("input_tokens")
        val cache = u.num("cache_read_input_tokens")
        val write = u.num("cache_creation_input_tokens")
        val output = u.num("output_tokens")
        val wall = v.num("duration_ms").takeIf { v.field("duration_ms") != null } ?: start.elapsedNow().inWholeMilliseconds
        val cost = cfg.stats.prices[v.text("model")]?.let { rowCost(input, write, cache, output, it).first } ?: 0.0
        return GraphRun(countToolUses(v), input, output, cache, wall, cost, input + cache, v.text("result"))
    }
}
