// Port of the `section!` structs in crates/rtok-config/src/lib.rs. Field names map to the TOML keys
// via snake_case (see Json in Layers.kt); every section rejects unknown keys and defaults every field,
// matching `#[serde(default, deny_unknown_fields)]`.
@file:Suppress("PropertyName")
package rtok.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

val HOSTS = listOf("claude", "cursor", "codex", "opencode", "kilo", "pi", "omp", "zcode", "kimi", "grok", "vscode",
    "copilot", "commandcode", "aider", "windsurf", "zed", "cline", "gemini", "codewhale", "mimo", "antigravity", "devin",
    "roo", "qwen", "codebuddy", "goose", "droid", "kiro", "junie", "amp", "warp", "continue", "crush", "openhands",
    "augment", "reasonix")
val TASK_ADAPTERS = listOf("disk", "github", "gitlab")
val TASK_LAYOUTS = listOf("auto", "files", "folders")
val CATALOGUE = listOf("measure" to true, "cmd" to true, "read" to true, "json_tree" to false, "archive" to true,
    "proxy" to true, "inject" to true, "guard" to true, "memory" to true, "graph" to true, "toon" to true,
    "compress" to true, "docs" to false)
val JUNK_EXTRA_KINDS = listOf("cache", "temp", "logs", "crash-dumps")

@Serializable data class Core(
    val enabled: Boolean = true,
    val dbPath: String = "~/.rtok/rtok.db",
    val archiveDir: String = "~/.rtok/archive",
    val logLevel: String? = null,
    val logFile: String? = null,
    val sessionEnv: String = "CLAUDE_SESSION_ID",
    val callIoInlineBytes: UInt = 65536u,
    val hookMaxInputBytes: UInt = rtok.hook.HOOK_MAX_INPUT_BYTES.toUInt(),
    val retainCallsDays: UInt = 30u,
    val retainHookBodiesDays: UInt = 3u,
    val storeRaw: Boolean = false,
    val logToDb: Boolean? = null,
    val injectBudgetTokens: UInt? = null,
)

@Serializable data class Log(
    val path: String = "~/.rtok/logs/rtok.log", val maxBytes: ULong = 1_048_576u, val files: UInt = 5u,
    val lines: ULong = 200u, val level: String = "info", val toDb: Boolean = true, val tspin: String = "auto",
)

@Serializable data class Hook(val host: String = "claude", val maxMs: ULong = 10u, val slowWarnS: ULong = 300u, val failOpen: Boolean = true)

@Serializable data class Agents(
    val enabled: Boolean = true, val idle: String = "30m", val pushBytes: UInt = 1024u,
    val usage: AgentsUsage = AgentsUsage(), val junk: AgentsJunk = AgentsJunk(),
)

@Serializable data class AgentsJunk(
    val keepLogsDays: UInt = 30u, val tempMinAgeHours: UInt = 24u, val staleSessionDays: UInt = 30u,
    val staleWorktreeDays: UInt = 14u, val crashDumpMinAgeDays: UInt = 7u,
    val exclude: List<String> = emptyList(), val extra: List<JunkExtra> = emptyList(),
)

@Serializable data class JunkExtra(val host: String = "", val kind: String = "", val path: String = "")

@Serializable data class AgentsUsage(
    val source: String = "logs", val hosts: List<String> = emptyList(), val since: String = "", val until: String = "",
    val period: String = "monthly", val by: String = "agent", val tz: String = "", val dirs: UsageDirs = UsageDirs(),
)

@Serializable data class UsageDirs(
    val opencode: List<String> = listOf("~/.local/share/opencode"),
    val kilo: List<String> = listOf("~/.local/share/kilo"),
    val copilot: List<String> = listOf("~/.copilot/session-state"),
    val gemini: List<String> = listOf("~/.gemini/tmp"),
    val droid: List<String> = listOf("~/.factory/sessions"),
    val pi: List<String> = listOf("~/.pi/agent/sessions"),
    val kimi: List<String> = listOf("~/.kimi-code/sessions"),
    val grok: List<String> = listOf("~/.grok/sessions"),
    val zcode: List<String> = listOf("~/.zcode"),
    val antigravity: List<String> = listOf("~/.gemini/antigravity"),
) {
    /** Default dirs follow the hosts' own env overrides (XDG_DATA_HOME, COPILOT_HOME, …) when absolute. */
    fun followEnv(get: (String) -> String?): UsageDirs {
        val base = UsageDirs()
        fun abs(k: String) = get(k)?.takeIf { it.startsWith("/") }
        fun moved(list: List<String>, was: List<String>, to: String?) = if (list == was && to != null) listOf(to) else list
        val xdg = abs("XDG_DATA_HOME")
        return copy(
            opencode = moved(opencode, base.opencode, xdg?.let { "$it/opencode" }),
            kilo = moved(kilo, base.kilo, xdg?.let { "$it/kilo" }),
            copilot = moved(copilot, base.copilot, abs("COPILOT_HOME")?.let { "$it/session-state" }),
            gemini = moved(gemini, base.gemini, abs("GEMINI_CLI_HOME")?.let { "$it/.gemini/tmp" }),
            pi = moved(pi, base.pi, abs("PI_CODING_AGENT_SESSION_DIR") ?: abs("PI_CODING_AGENT_DIR")?.let { "$it/sessions" }),
            kimi = moved(kimi, base.kimi, abs("KIMI_CODE_HOME")?.let { "$it/sessions" }),
            grok = moved(grok, base.grok, abs("GROK_HOME")?.let { "$it/sessions" }),
        )
    }
}

@Serializable data class Mcp(
    val tools: List<String> = emptyList(), val maxDescriptionTokens: UInt = 60u, val maxResultChars: UInt = 20000u,
    val http: String = "127.0.0.1:8791", val httpTools: List<String> = listOf("read", "search", "tree"),
    val token: String = "", val publicUrl: String = "",
)

@Serializable data class ToolsRewrite(
    val enabled: Boolean = false, val compactSchema: Boolean = false, val maxDescriptionTokens: UInt = 60u,
    val allow: List<String> = emptyList(), val deny: List<String> = emptyList(),
)

@Serializable data class Lanes(
    val enabled: Boolean = true, val bulk: LanePolicy = LanePolicy(), val embeddings: LanePolicy = LanePolicy(),
    val meta: LanePolicy = LanePolicy(), val internal: LanePolicy = LanePolicy(),
)

@Serializable data class LanePolicy(
    val compress: Boolean = false, val toon: Boolean = false, val toolsRewrite: Boolean = false,
    val contextManagement: Boolean = false, val semanticCache: Boolean = false, val flex: Boolean = false,
    val timeoutS: ULong = 0u, val upstream: String = "", val maxInFlight: UInt = 0u, val maxQueued: UInt = 8u,
)

@Serializable data class BatchPolicy(val parseResults: Boolean = false)

@Serializable data class FlexPolicy(
    val force: Boolean = false, @SerialName("on_429") val on429: String = "none", val retries: UInt = 3u, val backoffMs: ULong = 1000u,
)

@Serializable data class RoutingPolicy(val enabled: Boolean = false, val bulk: RouteRule = RouteRule(), val internal: RouteRule = RouteRule())

@Serializable data class RouteRule(val from: List<String> = emptyList(), val to: String = "", val provider: String = "", val gate: String = "")

@Serializable data class Proxy(
    val enabled: Boolean = true, val bind: String = "127.0.0.1", val port: UShort = 8790u, val mode: String = "passthrough",
    val upstream: String = "https://api.anthropic.com", val openaiUpstream: String = "https://api.openai.com",
    val geminiUpstream: String = "https://generativelanguage.googleapis.com", val timeoutS: ULong = 600u,
    val includeUsage: Boolean = true, val contextManagement: Boolean = false, val dryRun: Boolean = false,
    val toolsRewrite: ToolsRewrite = ToolsRewrite(), val lanes: Lanes = Lanes(), val batch: BatchPolicy = BatchPolicy(),
    val flex: FlexPolicy = FlexPolicy(), val routing: RoutingPolicy = RoutingPolicy(),
)

/** `[web]` — kept so config files stay interchangeable; the web UI itself is not ported. */
@Serializable data class Web(val host: String = "127.0.0.1", val port: UShort = 3333u)

/** `[tui]` — kept for file compatibility; the TUI itself is not ported. */
@Serializable data class Tui(val tab: String = "", val tickSecs: ULong = 2u)

@Serializable data class Ui(val emoji: Boolean = true, val color: Boolean = true)

@Serializable data class Demon(
    val services: List<String> = listOf("proxy"), val stateDir: String = "~/.rtok/demon", val backoffMs: ULong = 1000u,
    val maxBackoffMs: ULong = 30000u, val healthyMs: ULong = 10000u, val pollMs: ULong = 200u,
)

@Serializable data class ModelPrice(val input: Double = 0.0, val cacheWrite: Double = 0.0, val cacheRead: Double = 0.0, val output: Double = 0.0)

fun defaultStatsPrices(): Map<String, ModelPrice> {
    fun r(a: Double, b: Double, c: Double, d: Double) = ModelPrice(a, b, c, d)
    return sortedMap(
        "claude-sonnet-5" to r(2.0, 2.5, 0.2, 10.0), "claude-fable-5-1" to r(10.0, 12.5, 0.25, 50.0),
        "claude-opus-5-5" to r(4.0, 5.0, 0.2, 20.0), "claude-sonnet-5-5" to r(2.0, 2.5, 0.1, 10.0),
        "claude-haiku-4-5" to r(1.0, 1.25, 0.1, 5.0), "gpt-5" to r(1.25, 1.25, 0.125, 10.0),
        "gpt-5-mini" to r(0.25, 0.25, 0.025, 2.0),
        "claude-sonnet-5@batch" to r(1.0, 1.25, 0.1, 5.0), "claude-sonnet-5-5@batch" to r(1.0, 1.25, 0.05, 5.0),
        "claude-opus-5-5@batch" to r(2.0, 2.5, 0.1, 10.0), "claude-fable-5-1@batch" to r(5.0, 6.25, 0.125, 25.0),
        "claude-haiku-4-5@batch" to r(0.5, 0.625, 0.05, 2.5),
        "gpt-5@batch" to r(0.625, 0.625, 0.0625, 5.0), "gpt-5@flex" to r(0.625, 0.625, 0.0625, 5.0),
        "gpt-5-mini@batch" to r(0.125, 0.125, 0.0125, 1.0), "gpt-5-mini@flex" to r(0.125, 0.125, 0.0125, 1.0),
    )
}

@Serializable data class Stats(
    val since: String = "30d", val format: String = "table", val plugin: String = "",
    val transcriptsDir: String = "~/.claude/projects", val codexDir: String = "~/.codex/sessions",
    val calibrateSamples: UInt = 30u, val baseline: String = "", val price: Boolean = false,
    val prices: Map<String, ModelPrice> = defaultStatsPrices(),
)

@Serializable data class Report(val format: String = "md", val out: String = "", val since: String = "30d", val ai: Boolean = false, val budgetTokens: UInt = 8000u)

@Serializable data class Bench(
    val tasks: String = "bench/tasks.toml", val runs: UInt = 3u, val dryRun: Boolean = false, val timeoutS: ULong = 900u,
    val suite: String = "", val configs: Map<String, String> = sortedMap("a" to "bench/configs/legacy.json", "b" to "bench/configs/rtok.json"),
)

@Serializable data class Doctor(
    val settingsPath: String = "~/.claude/settings.json", val claudeJson: String = "~/.claude.json", val mcpJson: String = ".mcp.json",
    val probeTimeoutMs: ULong = 500u, val mcpTimeoutMs: ULong = 15000u, val instructionWarnTokens: UInt = 1000u,
    val instructions: Boolean = false, val toolsRewriteMinDescTokens: UInt = 2000u,
)

@Serializable data class SetupClaude(val settingsPath: String = "~/.claude/settings.json")
@Serializable data class SetupCursor(val hooksPath: String = "~/.cursor/hooks.json")
@Serializable data class SetupCodex(val configPath: String = "~/.codex/config.toml")
@Serializable data class SetupOpenCode(val configPath: String = "~/.config/opencode/opencode.json")
@Serializable data class SetupKilo(val configPath: String = "~/.config/kilo/kilo.json")
@Serializable data class SetupCline(val hooksPath: String = "~/Documents/Cline/Hooks", val mcpPath: String = "~/.cline/data/settings/cline_mcp_settings.json")
@Serializable data class SetupPi(val extensionsPath: String = "~/.pi/agent/extensions", val tools: Boolean = false)
@Serializable data class SetupOmp(val extensionsPath: String = "~/.omp/agent/extensions", val mcpPath: String = "~/.omp/agent/mcp.json")
@Serializable data class SetupZcode(val configPath: String = "~/.zcode/cli/config.json")
@Serializable data class SetupKimi(val configPath: String = "~/.kimi-code/config.toml")
@Serializable data class SetupGrok(val configPath: String = "~/.grok/config.toml")
@Serializable data class SetupCopilot(val dir: String = "~/.copilot")
@Serializable data class SetupCommandCode(val dir: String = "~/.commandcode")
@Serializable data class SetupVscode(val codeUserDir: String = "", val insidersUserDir: String = "")
@Serializable data class SetupAider(val configPath: String = "~/.aider.conf.yml")
@Serializable data class SetupWindsurf(val configPath: String = "~/.codeium/windsurf/mcp_config.json")
@Serializable data class SetupZed(val configPath: String = "~/.config/zed/settings.json")
@Serializable data class SetupGemini(val dir: String = "~/.gemini")
@Serializable data class SetupCodewhale(val dir: String = "~/.codewhale")
@Serializable data class SetupMimo(val configPath: String = "~/.config/mimocode/mimocode.json")
@Serializable data class SetupDevin(val configPath: String = "~/.config/devin/config.json")
@Serializable data class SetupRoo(val mcpPath: String = "")
@Serializable data class SetupJunie(val mcpPath: String = "")
@Serializable data class SetupQwen(val dir: String = "~/.qwen")
@Serializable data class SetupGoose(val configPath: String = "~/.config/goose/config.yaml")
@Serializable data class SetupContinue(val configPath: String = "~/.continue/config.yaml")
@Serializable data class SetupCodebuddy(val dir: String = "~/.codebuddy", val workbuddyDir: String = "~/.workbuddy")
@Serializable data class SetupDroid(val mcpPath: String = "~/.factory/mcp.json")
@Serializable data class SetupKiro(val mcpPath: String = "~/.kiro/settings/mcp.json")
@Serializable data class SetupAmp(val settingsPath: String = "~/.config/amp/settings.json")
@Serializable data class SetupWarp(val mcpPath: String = "~/.warp/.mcp.json")
@Serializable data class SetupCrush(val configPath: String = "~/.config/crush/crush.json")
@Serializable data class SetupOpenhands(val mcpPath: String = "~/.openhands/mcp.json")
@Serializable data class SetupAugment(val settingsPath: String = "~/.augment/settings.json")
@Serializable data class SetupReasonix(val dir: String = "~/.reasonix")
@Serializable data class SetupAntigravity(val pluginsPath: String = "~/.gemini/config/plugins", val cliPluginsPath: String = "~/.gemini/antigravity-cli/plugins")

@Serializable data class Setup(
    val dryRun: Boolean = false, val yes: Boolean = false, val backup: Boolean = true, val backupFiles: UInt = 5u,
    val hookTimeoutS: ULong = 5u, val modes: List<String> = emptyList(), val mcp: Boolean = true, val proxy: Boolean = false,
    val force: Boolean = false, val source: String? = null, val paths: Map<String, String> = emptyMap(),
    val claude: SetupClaude = SetupClaude(), val cursor: SetupCursor = SetupCursor(), val codex: SetupCodex = SetupCodex(),
    val opencode: SetupOpenCode = SetupOpenCode(), val kilo: SetupKilo = SetupKilo(), val pi: SetupPi = SetupPi(),
    val omp: SetupOmp = SetupOmp(), val zcode: SetupZcode = SetupZcode(), val kimi: SetupKimi = SetupKimi(),
    val grok: SetupGrok = SetupGrok(), val vscode: SetupVscode = SetupVscode(), val copilot: SetupCopilot = SetupCopilot(),
    val commandcode: SetupCommandCode = SetupCommandCode(), val aider: SetupAider = SetupAider(),
    val windsurf: SetupWindsurf = SetupWindsurf(), val zed: SetupZed = SetupZed(), val cline: SetupCline = SetupCline(),
    val gemini: SetupGemini = SetupGemini(), val codewhale: SetupCodewhale = SetupCodewhale(), val mimo: SetupMimo = SetupMimo(),
    val antigravity: SetupAntigravity = SetupAntigravity(), val devin: SetupDevin = SetupDevin(), val roo: SetupRoo = SetupRoo(),
    val junie: SetupJunie = SetupJunie(), val qwen: SetupQwen = SetupQwen(), val codebuddy: SetupCodebuddy = SetupCodebuddy(),
    val goose: SetupGoose = SetupGoose(), val droid: SetupDroid = SetupDroid(), val kiro: SetupKiro = SetupKiro(),
    val amp: SetupAmp = SetupAmp(), val warp: SetupWarp = SetupWarp(), val `continue`: SetupContinue = SetupContinue(),
    val crush: SetupCrush = SetupCrush(), val openhands: SetupOpenhands = SetupOpenhands(), val augment: SetupAugment = SetupAugment(),
    val reasonix: SetupReasonix = SetupReasonix(),
)

@Serializable data class Expand(val maxLines: UInt = 0u, val maxRate: Double = 0.05)
@Serializable data class Filter(val cmd: String = "")
@Serializable data class Worktree(
    val enabled: Boolean = true, val root: String = ".rtok/worktrees", val seed: Boolean = true,
    val seedDirs: List<String> = listOf("target", "node_modules"), val minFreeMib: UInt = 2048u,
)

@Serializable data class Tasks(
    val adapter: String = "disk", val prefix: String = "", val disk: TasksDisk = TasksDisk(), val github: TasksGithub = TasksGithub(),
    val gitlab: TasksGitlab = TasksGitlab(), val export: TasksExport = TasksExport(),
)
@Serializable data class TasksExport(val github: TasksExportGithub = TasksExportGithub(), val linear: TasksExportLinear = TasksExportLinear())
@Serializable data class TasksExportLinear(val team: String = "")
@Serializable data class TasksExportGithub(val repo: String = "")
@Serializable data class TasksDisk(val dir: String = "tasks", val layout: String = "auto")
@Serializable data class TasksGithub(val repo: String = "", val project: UInt = 0u)
@Serializable data class TasksGitlab(val url: String = "https://gitlab.com", val project: String = "")

data class Endpoint(val url: String, val headers: List<Pair<String, String>>)

@Serializable data class Otel(
    val endpoint: String = "", val headers: String = "", val serviceName: String = "rtok", val content: Boolean = true,
    val contentBytes: UInt = 65536u, val flushSecs: UInt = 5u,
) {
    /** Config value, else `OTEL_EXPORTER_OTLP_ENDPOINT` / `_HEADERS`; null when no endpoint. */
    fun resolveWith(env: (String) -> String?): Endpoint? {
        fun pick(key: String, v: String) = if (key.isBlank()) env(v) ?: "" else key
        val url = pick(endpoint, "OTEL_EXPORTER_OTLP_ENDPOINT").trim().trimEnd('/')
        if (url.isEmpty()) return null
        val hs = pick(headers, "OTEL_EXPORTER_OTLP_HEADERS").split(',').mapNotNull { kv ->
            val at = kv.indexOf('='); if (at < 0) null else kv.substring(0, at).trim() to kv.substring(at + 1).trim()
        }.filter { it.first.isNotEmpty() }
        return Endpoint(url, hs)
    }
}

@Serializable data class Plugins(
    val measure: Measure = Measure(), val cmd: Cmd = Cmd(), val read: Read = Read(), val jsonTree: JsonTree = JsonTree(),
    val archive: Archive = Archive(), val proxy: ProxyPlugin = ProxyPlugin(), val inject: Inject = Inject(), val guard: Guard = Guard(),
    val memory: Memory = Memory(), val graph: Graph = Graph(), val toon: Toon = Toon(), val compress: Compress = Compress(),
    val docs: Docs = Docs(), val wasm: Wasm = Wasm(),
)
@Serializable data class Measure(val enabled: Boolean = true)
@Serializable data class Cmd(
    val enabled: Boolean = true, val rewrite: Boolean = true, val shell: String = "", val rules: String = "~/.rtok/rules.toml",
    val rulesDir: String = "~/.rtok/rules.d", val trailer: Boolean = true, val trailerMinLines: UInt = 40u, val failTailLines: UInt = 80u,
    val neverWrap: List<String> = listOf("rtok", "sudo"),
    val interactiveStems: List<String> = listOf("python", "node", "psql", "sqlite3", "irb", "bash", "sh", "zsh", "docker", "kubectl"),
)
@Serializable data class Read(
    val enabled: Boolean = true, val defaultMode: String = "full", val maxChars: UInt = 20000u, val nativeMaxBytes: ULong = 32768u,
    val rangeMaxLines: UInt = 300u, val advice: Boolean = true, val allowPaths: List<String> = emptyList(), val searchMax: UInt = 50u,
    val searchMaxBytes: ULong = 1_048_576u, val treeDepth: UInt = 2u, val delta: Boolean = true, val deltaMaxRatio: Float = 0.6f,
    val languages: List<String> = emptyList(),
)
@Serializable data class Archive(
    val enabled: Boolean = true, val keepTurns: UInt = 4u, val minTokens: UInt = 1500u, val headLines: UInt = 8u, val tailLines: UInt = 4u,
    val tiers: Boolean = false, val liveBlobs: Boolean = false, val skills: Boolean = false,
)
@Serializable data class SemanticCache(
    val enabled: Boolean = false, val threshold: Float = 0.99f, val ttlS: ULong = 300u, val maxMessages: UInt = 1u,
    val requireEmptyTools: Boolean = true, val embedBackend: String = "hash", val cacheByModel: Boolean = true, val cacheByProvider: Boolean = true,
)
@Serializable data class ProxyPlugin(val enabled: Boolean = true, val semanticCache: SemanticCache = SemanticCache())
@Serializable data class Inject(val enabled: Boolean = true, val budgetTokens: UInt = 800u, val modesDir: String = "~/.rtok/modes", val modes: List<String> = emptyList())
@Serializable data class Guard(
    val enabled: Boolean = true, val windowTurns: UInt = 8u, val denyGrepGlob: Boolean = false, val grepSymbol: Boolean = false,
    val skills: Boolean = false, val skillMaxBytes: UInt = 8192u,
)
@Serializable data class MemoryEmbed(
    val enabled: Boolean = false, val provider: String = "local", val model: String = "all-MiniLM-L6-v2", val dimensions: UInt = 384u,
    val hybrid: Boolean = true, val url: String = "", val apiKeyEnv: String = "", val maxChars: UInt = 8000u,
)
@Serializable data class MemoryExtract(val enabled: Boolean = false, val command: List<String> = emptyList(), val maxNotes: UInt = 5u, val maxInputBytes: UInt = 60000u)
@Serializable data class Memory(
    val enabled: Boolean = true, val recallTitles: UInt = 5u, val recallTokens: UInt = 200u, val promptRecall: UInt = 5u,
    val checkpointTokens: UInt = 400u, val searchLimit: UInt = 5u, val syncTokens: UInt = 300u, val startupRecall: Boolean = true,
    val handoff: Boolean = true, val spawnBrief: Boolean = true, val spawnBriefTokens: UInt = 300u, val admit: Boolean = true,
    val admitMinChars: UInt = 5u, val admitSimilarity: UInt = 85u, val forgetAfterDays: UInt = 90u,
    val embed: MemoryEmbed = MemoryEmbed(), val extract: MemoryExtract = MemoryExtract(),
)
@Serializable data class Docs(val enabled: Boolean = false, val queryLimit: UInt = 5u, val snippetChars: UInt = 400u, val maxTokens: UInt = 800u)
@Serializable data class Graph(
    val enabled: Boolean = true, val maxTokens: UInt = 2000u, val impactTokens: UInt = 1500u, val mapTokens: UInt = 0u,
    val mapRank: String = "pagerank", val bodyLines: UInt = 40u, val autoIndex: Boolean = true, val autoAddProjects: Boolean = true,
    val backend: String = "tags", val lspTimeoutMs: UInt = 40_000u, val backendByLanguage: Map<String, String> = emptyMap(),
    val watch: String = "off", val autoLinkReferences: Boolean = true, val referenceDepth: UInt = 3u, val maxAutoProjects: UInt = 20u,
    val alerts: Boolean = true, val healthCheckIntervalS: UInt = 60u, val liveHeatWindowS: UInt = 300u, val liveMaxEventsPerS: UInt = 50u,
    val liveFeedRows: UInt = 200u, val exclude: List<String> = emptyList(), val include: List<String> = emptyList(),
    val extensions: Map<String, String> = emptyMap(),
)
@Serializable data class JsonTree(val enabled: Boolean = false)
@Serializable data class Toon(val enabled: Boolean = true, val minRows: UInt = 5u)
@Serializable data class Compress(val enabled: Boolean = true, val llm: CompressLlm = CompressLlm())
@Serializable data class CompressLlm(
    val enabled: Boolean = false, val url: String = "", val model: String = "claude-haiku-4-5", val maxInputBytes: ULong = 65536u,
    val maxOutputTokens: UInt = 512u, val maxSpans: UInt = 64u, val maxInFlight: UInt = 2u, val timeoutS: ULong = 60u, val code: Boolean = false,
)
@Serializable data class Wasm(val enabled: Boolean = false, val dir: String = "~/.rtok/plugins")

/** Key-sorted map (Rust BTreeMap order); Kotlin/Native has no sortedMapOf. */
fun <V> sortedMap(vararg pairs: Pair<String, V>): Map<String, V> = pairs.sortedBy { it.first }.toMap()
