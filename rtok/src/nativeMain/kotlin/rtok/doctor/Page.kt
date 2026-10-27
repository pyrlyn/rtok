// Port of the probe half of src/doctor.rs: `page` (every probe: settings and host files are read, MCP servers are
// spawned and asked for their tools, proxy hops answer `/health` or do not), `mcp_servers`, `list_tools`,
// `plugin_hooks`, `openai_seed`, `proxy_chain`, `hostport`, `is_loop`, `next_upstream`, `detected_hosts`,
// `sync_block_present`, `checks`, `config_notes` and the graph alerts and health.
package rtok.doctor

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.agents.Agents
import rtok.agents.Claude
import rtok.agents.Hosts
import rtok.agents.Locate
import rtok.agents.McpDoctor
import rtok.agents.Modules
import rtok.config.Config
import rtok.config.Validate
import rtok.config.envUserHome
import rtok.config.envVar
import rtok.estimate
import rtok.http.HttpRequest
import rtok.http.HttpSettings
import rtok.http.ktor.KtorHttp
import rtok.json.StrictJson
import rtok.mcp.Roundtrip
import rtok.plugin.Runtime
import rtok.plugin.sdk.Class
import rtok.plugins.graph.Alert
import rtok.plugins.graph.Alerts
import rtok.plugins.graph.Score
import rtok.plugins.graph.Weak
import rtok.plugins.memory.Sync
import rtok.sys.cwd
import rtok.sys.readToString
import tomlaot.TomlDoc
import kotlin.time.Duration.Companion.milliseconds

/** One `mcpServers` entry; `args`/`env` matter: a bare `uvx`/`npx` command lists 0 tools. */
data class McpServer(
    val name: String,
    val cmd: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
)

@Suppress("TooManyFunctions")
internal object Page {
    private const val MIN_MCP_TIMEOUT_MS = 500L
    private const val MIN_PROBE_TIMEOUT_MS = 300L
    private const val MAX_HOPS = 4

    private fun readJson(path: String): JsonElement? = runCatching { StrictJson.parseOrNull(readToString(path)) }.getOrNull()

    private fun str(v: JsonElement?) = (v as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Every probe. [ownExe]: rtok's own binary, probed as the `rtok` MCP server (null under test). */
    @Suppress("LongMethod")
    fun page(
        cfg: Config,
        ownExe: String? = null,
    ): Report {
        val settings = readJson(cfg.doctor.settingsPath)
        // A plugin install carries its hooks outside `settings.json` (T173): count them too.
        val hooks = Advice.countHooks(settings) + pluginHooks(cfg)
        val claude = readJson(cfg.doctor.claudeJson)
        val mcpJson = readJson(cfg.doctor.mcpJson)
        val servers = mcpServers(claude, mcpJson) + listOfNotNull(ownExe?.let { McpServer("rtok", it, listOf("mcp")) })
        val mcpTimeout =
            cfg.doctor.mcpTimeoutMs
                .toLong()
                .coerceAtLeast(MIN_MCP_TIMEOUT_MS)
        val mcp = servers.map { s -> listTools(s, mcpTimeout, cfg).let { (n, t) -> ServerInfo(s.name, s.cmd, n, t) } }
        val timeout =
            cfg.doctor.probeTimeoutMs
                .toLong()
                .coerceAtLeast(MIN_PROBE_TIMEOUT_MS)
        val anthropic = Advice.anthropicBase(settings, envVar("ANTHROPIC_BASE_URL"))
        val toolSearch = Advice.resolveToolSearch(settings, envVar("ENABLE_TOOL_SEARCH"), anthropic != null)
        val off = toolSearch.likelyOff()
        val proxy = proxyChain(anthropic, timeout)
        val rtokEntry = listOfNotNull(claude, mcpJson).firstNotNullOfOrNull { Advice.pointer(it, "mcpServers", "rtok") }
        val overlaps =
            Advice.overlapLines(
                cfg.pluginEnabled("archive", true),
                cfg.pluginEnabled("memory", true) && cfg.plugins.memory.recallTokens > 0u,
                syncBlockPresent(),
                detectedHosts(cfg),
            ) + McpDoctor.doctorLines(cfg) +
                listOfNotNull(
                    Advice.alwaysLoadAdvice(rtokEntry, off, mcp.firstOrNull { it.name == "rtok" }),
                    Advice.hookClientAdvice(hooks.prefersClient, envVar("PATH")),
                )
        return Report(
            hooksTotal = hooks.total,
            hooksByEvent = hooks.byEvent,
            mcp = mcp,
            proxy = proxy,
            proxyOpenai = proxyChain(openaiSeed(cfg, settings), timeout),
            mcpToolSearchDisabled = off,
            mcpToolSearch = toolSearch,
            bashMaxOutputLength = envVar("BASH_MAX_OUTPUT_LENGTH"),
            autoCompactWindow = (settings as? JsonObject)?.get("autoCompactWindow")?.toString(),
            readShare = Usage.readShare(cfg),
            instructions = if (cfg.doctor.instructions) InstructionAudit.run(cfg, settings, claude, runCatching { cwd() }.getOrNull()) else null,
            skills =
                Usage.skillUsage(cfg).let { u ->
                    SkillsAuditProbe.audit(envUserHome() ?: cfg.home, runCatching { cwd() }.getOrDefault("."), u?.first, u?.second)
                },
            overlaps = overlaps,
            configNotes = cfg.loadedFrom?.let(Validate::pinnedNotes).orEmpty(),
            toolsRewriteAdvice =
                Advice.toolsRewriteAdvice(
                    off,
                    proxy,
                    cfg.proxy.port.toInt(),
                    mcp.sumOf { it.descTokens },
                    cfg.proxy.toolsRewrite.enabled,
                    cfg.doctor.toolsRewriteMinDescTokens.toInt(),
                ),
            agents =
                Hosts.ALL.flatMap { h ->
                    val agent = Agents.agent(h.id) ?: return@flatMap emptyList()
                    h.variants.map { v -> AgentModules(h.id, v.kind.word, Modules.rows(agent, v.kind, cfg)) }
                },
            problems = checks(cfg),
            graphAlerts = graphAlerts(cfg),
            graphHealth = graphHealth(cfg),
        )
    }

    /** Every config finding of this machine: hooks, then duplicate MCP entries. */
    fun checks(cfg: Config): List<Problem> {
        val (problems, plugins) = Hooks.checkWithPlugins(cfg, Probes.REAL)
        return problems + McpDupes.check(cfg, Probes.REAL, plugins)
    }

    private fun graphAlerts(cfg: Config): List<Alert> = if (!cfg.plugins.graph.alerts) emptyList() else runCatching { Runtime.open(cfg, "doctor").use(Alerts::all) }.getOrDefault(emptyList())

    private fun graphHealth(cfg: Config): List<Weak> = runCatching { Runtime.open(cfg, "doctor").use(Score::weak) }.getOrDefault(emptyList())

    /** Which of the hosts the overlap checks know this machine runs: an app found, not a leftover folder (T426). */
    private fun detectedHosts(cfg: Config): List<String> =
        listOf("claude", "opencode", "cursor").filter { id ->
            Hosts.host(id)?.variants?.any { Locate.present(cfg, id, it) } == true
        }

    private fun syncBlockPresent(): Boolean {
        val dir = runCatching { cwd() }.getOrNull() ?: return false
        return listOf("CLAUDE.md", "AGENTS.md").any { f -> runCatching { Sync.hasBlock(readToString("$dir/$f")) }.getOrDefault(false) }
    }

    /** Hooks carried by the installed Claude plugin (T173); fail open. */
    private fun pluginHooks(cfg: Config): HookCount {
        if (!Claude.pluginInstalled(cfg)) return HookCount()
        val root = readJson("${Claude.configDir(cfg)}/plugins/installed_plugins.json")
        return Advice.pluginInstallPaths(root, Claude.PLUGIN_ID).fold(HookCount()) { acc, p ->
            val obj = Advice.pointer(readJson("$p/hooks/hooks.json"), "hooks") as? JsonObject
            if (obj != null) acc + Advice.hooksObject(obj) else acc
        }
    }

    /** `~/.claude.json` first, then `.mcp.json`; the first entry of a name wins; sorted by name. */
    fun mcpServers(
        claude: JsonElement?,
        mcpJson: JsonElement?,
    ): List<McpServer> {
        val out = LinkedHashMap<String, McpServer>()
        for (src in listOfNotNull(claude, mcpJson)) {
            val map = Advice.pointer(src, "mcpServers") as? JsonObject ?: continue
            for ((name, spec) in map) {
                if (name in out) continue
                val o = spec as? JsonObject
                out[name] =
                    McpServer(
                        name = name,
                        cmd = str(o?.get("command")).orEmpty(),
                        args = (o?.get("args") as? JsonArray).orEmpty().mapNotNull(::str),
                        env = (o?.get("env") as? JsonObject).orEmpty().mapNotNull { (k, v) -> str(v)?.let { k to it } }.toMap(),
                    )
            }
        }
        return out.values.sortedBy { it.name }
    }

    @Suppress("ReturnCount")
    private fun listTools(
        s: McpServer,
        timeoutMs: Long,
        cfg: Config,
    ): Pair<Int, Int> {
        if (s.cmd.isEmpty()) return 0 to 0
        val replies = Roundtrip.run(s.cmd, s.args, s.env, null, timeoutMs.milliseconds).getOrNull() ?: return 0 to 0
        val tools = replies.firstNotNullOfOrNull { Advice.pointer(it, "result", "tools") } as? JsonArray ?: return 0 to 0
        return tools.size to tools.sumOf { estimate(str(Advice.pointer(it, "description")).orEmpty(), Class.Prose, cfg.estimator) }
    }

    /** `OPENAI_BASE_URL` from the shell, Claude settings, OpenCode's config, then Codex's `[model_providers.rtok]`. */
    private fun openaiSeed(
        cfg: Config,
        settings: JsonElement?,
    ): String? =
        envVar("OPENAI_BASE_URL")?.takeIf { it.isNotEmpty() }
            ?: str(Advice.pointer(settings, "env", "OPENAI_BASE_URL"))?.takeIf { it.isNotEmpty() }
            ?: str(Advice.pointer(readJson(cfg.setup.opencode.configPath), "env", "OPENAI_BASE_URL"))?.takeIf { it.isNotEmpty() }
            ?: runCatching {
                TomlDoc
                    .parse(readToString(cfg.setup.codex.configPath))
                    .table("model_providers.rtok")
                    ?.str("base_url")
            }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** The hops behind [seed], joined with `→`; at most five, a loop stops the walk. */
    fun proxyChain(
        seed: String?,
        timeoutMs: Long,
        upstream: (String, Long) -> String? = ::nextUpstream,
    ): String {
        val hops = mutableListOf<String>()
        var url = seed?.takeIf { it.isNotEmpty() }
        while (url != null && hops.size <= MAX_HOPS) {
            hops += hostport(url)
            url = upstream(url, timeoutMs)?.takeUnless { isLoop(hops, it) }
        }
        return hops.joinToString("→")
    }

    /** By `hostport`, not substring: hop `8788` is inside `http://127.0.0.1:18788`. */
    fun isLoop(
        hops: List<String>,
        next: String,
    ) = hostport(next) in hops

    fun hostport(url: String): String {
        val rest = url.substringAfter("://")
        return rest.substringBefore('/').removePrefix("127.0.0.1:")
    }

    @Suppress("ReturnCount")
    private fun nextUpstream(
        url: String,
        timeoutMs: Long,
    ): String? {
        val body = httpGet(url, "/health", timeoutMs) ?: return null
        val v = StrictJson.parseOrNull(body) ?: return null
        return str(Advice.pointer(v, "checks", "upstream", "url")) ?: str(Advice.pointer(v, "config", "anthropic_api_url"))
    }

    /** The body of a plain GET to a local rtok endpoint (host and port of [base], then [path]); null on any failure. */
    fun httpGet(
        base: String,
        path: String,
        timeoutMs: Long,
    ): String? {
        val hp = base.substringAfter("://").substringBefore('/')
        return runCatching {
            KtorHttp.FACTORY.create(HttpSettings(timeoutMs, timeoutMs, timeoutMs)).use { c ->
                runBlocking { c.get(HttpRequest("http://$hp$path")) }.text()
            }
        }.getOrNull()
    }
}
