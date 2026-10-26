// `rtok mcp ping` process side (mcp/ping.rs `run`, `named_hosts`, `select`, `check`, `run_headless`, `probe`,
// `capture`, `render_text`, `row_json`): prove each selected host's rtok MCP server answers, through the host's own
// headless CLI where it has one, else by spawning the server its config names.
package rtok.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import rtok.agents.Agent
import rtok.agents.Agents
import rtok.agents.DiskFs
import rtok.agents.Hosts
import rtok.agents.Kind
import rtok.agents.Locate
import rtok.config.Config
import rtok.sys.ProgressRunner
import kotlin.time.Duration

data class PingOpts(
    val agent: String?,
    val cli: Boolean,
    val desktop: Boolean,
    val timeout: Duration,
    val json: Boolean,
)

/** One variant to check. */
data class PingTarget(
    val agent: Agent,
    val kind: Kind,
    val display: String,
)

/** What one variant's check concluded; [mode] is `chat` (headless CLI) or `server-only`. */
data class PingReport(
    val agent: String,
    val variant: String,
    val display: String,
    val mode: String,
    val reply: String,
    val ok: Boolean,
    val reason: String?,
)

class PingRun(
    /** Runs a headless CLI: its stdout, or null when it timed out; throws when it cannot start. */
    private val capture: (String, List<String>, Duration) -> String? = ::captureCli,
    /** Spawns a configured server and calls `ping`. */
    private val probe: (Launch, String, Duration) -> Result<String> = ::probeServer,
) {
    /** The exit code (0 only when every selected variant answered) and what to print on stdout / stderr. */
    fun run(
        cfg: Config,
        opts: PingOpts,
    ): Triple<Int, String, String> {
        val named = namedHosts(opts.agent)
        val targets = select(cfg, named, opts.cli, opts.desktop)
        if (targets.isEmpty()) {
            val msg = if (named != null) "no variant selected; pass --cli or --desktop for a variant this host has" else "no host has rtok MCP installed"
            return Triple(1, "", "$msg\n")
        }
        val reports = targets.map { check(cfg, it, opts.timeout) }
        val text = if (opts.json) reports.joinToString("") { "${rowJson(it)}\n" } else renderText(reports)
        return Triple(if (reports.all { it.ok }) 0 else 1, text, "")
    }

    fun check(
        cfg: Config,
        target: PingTarget,
        timeout: Duration,
    ): PingReport {
        val expect = Ping.pingText(target.display)
        // A plugin can serve MCP with no config entry (Claude Code); the headless chat still proves it.
        val headless = Ping.HEADLESS.firstOrNull { it.host == target.agent.id }?.takeIf { target.kind == Kind.Cli }
        if (headless != null) {
            return runHeadless(headless, target, expect, timeout).fold({ report(target, "chat", it, null) }, { report(target, "chat", "", it.message) })
        }
        return Ping
            .launchFromRead(DiskFs::read, target.agent.files(cfg, target.kind), target.agent.id)
            ?.let { launch ->
                probe(launch, target.display, timeout).fold(
                    { if (it.trim() == expect) report(target, SERVER_ONLY, expect, null) else report(target, SERVER_ONLY, "", mismatch(it, expect)) },
                    { report(target, SERVER_ONLY, "", it.message) },
                )
            } ?: report(target, SERVER_ONLY, "", "no rtok MCP entry in the host config; run `rtok agents install ${target.agent.id}`")
    }

    private fun runHeadless(
        spec: Headless,
        target: PingTarget,
        expect: String,
        timeout: Duration,
    ): Result<String> {
        val prompt = Ping.promptFor(target.display)
        val args = spec.argv.map { if (it == "{prompt}") prompt else it }
        // `cursor-agent` and `agent` are the same CLI; whichever starts first answers.
        val bins = if (spec.bin == "cursor-agent") listOf("cursor-agent", "agent") else listOf(spec.bin)
        var last: Result<String?> = Result.failure(PingFailure("failed to start"))
        for (bin in bins) {
            last = runCatching { capture(bin, args, timeout) }
            if (last.isSuccess) break
        }
        return last.fold(
            { stdout ->
                val reply = stdout?.trim()
                when (reply) {
                    null -> Result.failure(PingFailure("timed out after ${timeout.inWholeSeconds}s"))
                    expect -> Result.success(reply)
                    else -> Result.failure(PingFailure(mismatch(reply, expect)))
                }
            },
            { Result.failure(PingFailure("failed to start: ${it.message}")) },
        )
    }

    private fun mismatch(
        reply: String,
        expect: String,
    ) = "reply was ${quote(reply)}, expected ${quote(expect)}"

    companion object {
        const val SERVER_ONLY = "server-only"

        /** `--agent a,b`: every name must be a known host; null checks every host. */
        fun namedHosts(agent: String?): List<String>? {
            if (agent == null) return null
            val names = agent.split(',').map(String::trim).filter(String::isNotEmpty)
            require(names.isNotEmpty()) { "no host given" }
            names.firstOrNull { Hosts.host(it) == null }?.let { throw IllegalArgumentException("unknown host: $it") }
            return names
        }

        /** Named hosts' wanted variants; with no names, every present variant whose `installed` holds `mcp`. */
        fun select(
            cfg: Config,
            named: List<String>?,
            cli: Boolean,
            desktop: Boolean,
            present: (String, rtok.agents.Variant) -> Boolean = { id, v -> Locate.present(cfg, id, v) },
        ): List<PingTarget> =
            (named ?: Hosts.ALL.map { it.id }).flatMap { id ->
                val agent = Agents.agent(id)
                val host = Hosts.host(id)
                if (agent == null || host == null) {
                    emptyList()
                } else {
                    host.variants
                        .filter { Hosts.wants(it.kind, cli, desktop, false) }
                        .filter { v -> named != null || (present(id, v) && "mcp" in agent.installed(cfg, v.kind)) }
                        .map { PingTarget(agent, it.kind, it.name) }
                }
            }

        fun renderText(reports: List<PingReport>): String =
            buildString {
                val many = reports.size > 1
                for (r in reports) {
                    if (r.mode == SERVER_ONLY) {
                        append("Paste into ${r.display}:\n${Ping.promptFor(r.display)}\n\n")
                        if (r.ok) {
                            append("Checked the configured server, not whether ${r.display} loaded it: ${r.reply}\n")
                            continue
                        }
                    }
                    when {
                        !r.ok -> append("${r.display}: ${r.reason ?: "failed"}\n")
                        many || r.mode == SERVER_ONLY -> append("${r.display}: ${r.reply}\n")
                        else -> append("${r.reply}\n")
                    }
                }
            }

        fun rowJson(r: PingReport): JsonObject =
            buildJsonObject {
                put("agent", r.agent)
                put("variant", r.variant)
                put("mode", r.mode)
                put("reply", r.reply)
                put("ok", r.ok)
                put("reason", r.reason?.let(::JsonPrimitive) ?: JsonNull)
            }

        private fun report(
            t: PingTarget,
            mode: String,
            reply: String,
            reason: String?,
        ) = PingReport(t.agent.id, t.kind.word, t.display, mode, reply, reason == null, reason)

        /** Rust `{:?}` of a string: quoted, with `"` and `\` escaped. */
        private fun quote(s: String) = JsonPrimitive(s).toString()

        /** Spawn the configured server, `initialize`, `tools/list`, `tools/call ping`. */
        fun probeServer(
            launch: Launch,
            agent: String,
            timeout: Duration,
        ): Result<String> {
            val call =
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", Roundtrip.CALL_ID)
                    put("method", "tools/call")
                    put(
                        "params",
                        buildJsonObject {
                            put("name", "ping")
                            put("arguments", buildJsonObject { put("agent", agent) })
                        },
                    )
                }
            return Roundtrip.run(launch.command, launch.args, launch.env.toMap(), call.toString(), timeout).mapCatching { pingReply(it, timeout) }
        }

        /** The ping tool's text out of the replies; fails when the tool is not listed, absent or an error. */
        fun pingReply(
            values: List<JsonElement>,
            timeout: Duration,
        ): String {
            val listed = values.any { v -> tools(v).any { (it as? JsonObject)?.get("name")?.let(::str) == "ping" } }
            val reply = values.firstOrNull(Roundtrip::isCallReply) as? JsonObject
            val result = reply?.get("result") as? JsonObject
            val text = ((result?.get("content") as? JsonArray)?.firstOrNull() as? JsonObject)?.get("text")?.let(::str)
            val failure =
                when {
                    !listed -> "the ping tool is missing"
                    reply == null -> "timed out after ${timeout.inWholeSeconds}s"
                    (result?.get("isError") as? JsonPrimitive)?.content == "true" -> text ?: "tool error"
                    else -> null
                }
            return if (failure == null) text.orEmpty().trim() else throw PingFailure(failure)
        }

        private fun tools(v: JsonElement) = (((v as? JsonObject)?.get("result") as? JsonObject)?.get("tools") as? JsonArray).orEmpty()

        private fun str(e: JsonElement) = (e as? JsonPrimitive)?.takeIf { it.isString }?.content

        /** A headless CLI's stdout, null once killed at [timeout]; a CLI that cannot start throws. */
        fun captureCli(
            bin: String,
            args: List<String>,
            timeout: Duration,
        ): String? {
            val runner = ProgressRunner("asking $bin…", bin, args).timeout(timeout.inWholeMilliseconds)
            return try {
                runner
                    .runCapped(CAPTURE_CAP)
                    .first.stdout
                    .decodeToString()
            } catch (e: rtok.sys.IoException) {
                if (e.message?.contains("timed out") == true) null else throw e
            }
        }

        private const val CAPTURE_CAP = 1L shl 20
    }
}

class PingFailure(
    message: String,
) : Exception(message)
