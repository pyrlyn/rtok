// Port of src/hooks/mod.rs — the hook surface `rtok hook <event>`: read the host's stdin, adapt it
// to Claude's shape, run every enabled plugin for the event, and write the host's stdout. Fail open:
// any error prints `{}` (D1).
package rtok.hooks

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.config.Config
import rtok.config.envVar
import rtok.db.isLocked
import rtok.plugin.Runtime
import rtok.plugin.fitBudget
import rtok.plugin.sdk.Class
import rtok.plugin.sdk.Ctx
import rtok.plugin.sdk.Injection
import rtok.plugin.sdk.PreToolDecision
import rtok.plugin.sdk.SessionStart
import rtok.plugins.Registry
import rtok.store.LockWait
import rtok.store.Origin
import rtok.store.autoAddProject
import rtok.store.endAgent
import rtok.store.endSession
import rtok.store.insertHookCallIo
import rtok.store.kvGet
import rtok.store.kvSet
import rtok.store.latestTaskClaim
import rtok.store.registerAgent
import rtok.store.setAgentAncestors
import rtok.store.setCallMs
import rtok.sys.eprint
import rtok.sys.rustLines
import kotlin.time.TimeSource

/** T178: one 5 ms lock wait, migrations included, then fail open — half the 10 ms hook budget. */
val HOOK_LOCK_WAIT = LockWait(busyMs = 5, attempts = 1, migrateMs = 5)

/** How many process ancestors are stored on an agent row (Rust `agents::link::ANCESTORS`). */
const val AGENT_ANCESTORS = 3

internal const val DEFERRED_ENV = "RTOK_HOOK_DEFERRED"

/**
 * Hook work that belongs to modules ported in later phases (worktree, otel export, MCP result
 * wrapping). Each default does nothing, which is the Rust behaviour when that step fails; the
 * owning module replaces it when it lands. Tracked in port-plan.md.
 */
object HookLinks {
    var completePendingWorktree: (rt: Runtime, cwd: String, agent: String) -> Unit = { _, _, _ -> }
    var unlockEndedWorktree: (cwd: String, agent: String) -> Unit = { _, _ -> }
    var spawnOtelExport: (rt: Runtime) -> Unit = {}
    /** MCP `shorten_result` for a foreign tool's result under Cursor; true when it shortened [result] in place. */
    var shortenMcpResult: (rt: Runtime, server: String, tool: String, result: Array<JsonElement>) -> Boolean = { _, _, _, _ -> false }
}

/**
 * Fail-open entry. [readStdin] reads at most `limit` bytes. With `[hook] fail_open = false`
 * (debugging only) errors are thrown instead of answering `{}`.
 */
fun run(event: String, readStdin: (limit: Long) -> ByteArray, cfg: Config): ByteArray {
    val max = cfg.core.hookMaxInputBytes.toLong()
    if (!cfg.hook.failOpen) {
        val buf = readStdin(max + 1)
        check(buf.size <= max) { "rtok hook: stdin over core.hook_max_input_bytes ($max bytes, debugging only)" }
        return dispatchOwnedStrict(buf, event, cfg)
    }
    fun failOpen(msg: String): ByteArray {
        eprint("rtok: hook $event $msg\n"); rtok.appendLog(cfg, "error", "hook", event, msg); return EMPTY_OUT
    }
    val buf = try { readStdin(max + 1) } catch (e: Exception) { return failOpen("stdin read failed: ${e.message}; failing open") }
    if (buf.size > max) return failOpen("stdin over core.hook_max_input_bytes ($max bytes, got ${buf.size}); failing open")
    return try { dispatchOwnedStrict(buf, event, cfg) } catch (e: HookError) { EMPTY_OUT } catch (e: Throwable) {
        failOpen("panicked: ${e.message ?: e::class.simpleName}; failing open")
    }
}

private val EMPTY_OUT get() = "{}".encodeToByteArray()

/** A dispatch error that is answered with `{}` silently (Rust `dispatch_owned` maps `Err` to `{}`). */
class HookError(message: String) : Exception(message)

/** Session id: stdin first, then `$<core.session_env>`, else `unknown`. An empty key disables the env fallback. */
internal fun resolveSession(stdinSession: String, sessionEnvKey: String, env: (String) -> String?): String {
    if (stdinSession.isNotEmpty()) return stdinSession
    if (sessionEnvKey.isNotEmpty()) env(sessionEnvKey)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    return "unknown"
}

internal fun dispatchOwnedStrict(stdin: ByteArray, event: String, cfg: Config, env: (String) -> String? = ::envVar): ByteArray {
    val input = try { HookInput.parse(stdin.decodeToString()) } catch (e: Exception) { throw HookError("hook $event: bad stdin: ${e.message}") }
    val host = cfg.hook.host
    // Grok Build sends its own envelope to every hook it runs, so GROK_HOOK_EVENT wins over --host (T98).
    val grok = host == "grok" || env("GROK_HOOK_EVENT") != null
    val h = if (grok) "grok" else host
    when (h) {
        "grok" -> input.adaptGrok(event)
        "cursor" -> input.adaptCursor(event)
        "copilot" -> input.adaptCopilot(event)
        "gemini" -> input.adaptGemini(event)
        "codewhale" -> input.adaptCodewhale(event)
        "cline" -> input.adaptCline(event)
        "devin" -> input.adaptDevin(event, env("DEVIN_PROJECT_DIR"))
        "commandcode" -> input.adaptCommandcode(event, env("COMMANDCODE_PROJECT_DIR"))
        else -> if (input.hookEventName.isEmpty()) input.hookEventName = event
    }
    // The call row keeps an adapted host's input in Claude's field names (T262.5); Claude's stays byte-identical.
    val adapted = h in setOf("grok", "copilot", "cursor", "gemini", "codewhale", "cline", "devin")
    val stored = if (adapted) input.encode().encodeToByteArray() else stdin
    val session = resolveSession(input.sessionId, cfg.core.sessionEnv, env)
    val wait = if (env(DEFERRED_ENV) != null) LockWait.STEADY else HOOK_LOCK_WAIT
    val cx = try { Runtime.open(cfg, session, wait) } catch (e: Exception) {
        if (isLocked(e)) deferSessionEnd(cfg, input.hookEventName, stored, env)
        throw HookError("hook $event: store open: ${e.message}")
    }
    return cx.use {
        cx.cwd = input.cwd
        // Plugin hooks plus leftover settings-file hooks deliver one call twice (T245).
        cx.once = input.toolUseId?.takeIf { it.isNotEmpty() }?.let { "${input.hookEventName}:$it" }
        val out = dispatch(stored, input, cx)
        fun parsed() = runCatching { HookOutput.parse(out.decodeToString()) }.getOrDefault(HookOutput())
        when (h) {
            "copilot" -> copilotOutput(parsed())
            "cursor" -> cursorOutput(parsed())
            "gemini" -> geminiOutput(parsed(), input.hookEventName)
            "codewhale" -> codewhaleOutput(parsed(), input.prompt)
            "cline" -> clineOutput(parsed())
            "codebuddy" -> codebuddyOutput(parsed())
            "codex" -> codexOutput(parsed())
            else -> out
        }
    }
}

/** The sub-agent key (host `agent_id`), or null for the main window; blank counts as absent. */
internal fun agentParentKey(input: HookInput): String? = input.agentId?.takeIf { it.isNotEmpty() }

/** "Bash: cargo nextest run" — tool name plus the first 60 chars of its main argument, 120 total (D34). */
internal fun agentActivity(input: HookInput): String? {
    val tool = input.toolName ?: return null
    val arg = (input.toolInput as? JsonObject)?.let(::toolMainArgument)
    return firstChars(if (arg != null) "$tool: ${firstChars(arg, 60)}" else tool, 120)
}

private fun toolMainArgument(input: JsonObject): String? =
    listOf("command", "file_path", "path", "pattern", "query", "url").firstNotNullOfOrNull { k ->
        (input[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

/** First [n] Unicode scalar values of [s] (Rust `chars().take(n)`). */
internal fun firstChars(s: String, n: Int): String {
    var count = 0; var i = 0
    while (i < s.length && count < n) { i += if (s[i].isHighSurrogate() && i + 1 < s.length) 2 else 1; count++ }
    return s.substring(0, i)
}

internal fun agentIdInjection(agent: String?): Injection? = agent?.let { id ->
    Injection("agent_id", "rtok agent id: ${firstChars(id, 8)} (full: $id). Use it with rtok's agent_* and worktree_* MCP tools; agent_inbox reads messages sent to you.", 10u)
}

/** T442: `task <id> <title>` for the newest claim this agent holds in the checkout `cx.cwd` names. */
internal fun claimInjection(cx: Runtime, agent: String?): Injection? {
    agent ?: return null
    val project = rtok.projectKey(cx.cwd ?: return null) ?: return null
    val row = runCatching { cx.store.latestTaskClaim(project, agent) }.getOrNull() ?: return null
    val title = row.title.split('\n', '\r').first().trim()
    return Injection("task_claim", "task ${row.taskId} $title".trimEnd(), 8u)
}

/** T283: append `export RTOK_AGENT_ID=<id>` to `$CLAUDE_ENV_FILE` once; every failure is silent. */
internal fun writeAgentEnvFile(agent: String?, env: (String) -> String? = ::envVar) {
    val id = agent ?: return
    val path = env("CLAUDE_ENV_FILE")?.takeIf { it.isNotEmpty() } ?: return
    val line = "export RTOK_AGENT_ID=$id"
    val existing = runCatching { rtok.sys.readToString(path) }.getOrNull()
    if (existing != null && existing.lines().any { it == line }) return
    runCatching { rtok.sys.appendString(path, "$line\n") }
}

/** A note when `[hook] max_ms` is non-zero and the event ran over it. */
internal fun slowNote(ms: Double, maxMs: ULong, event: String): String? =
    if (maxMs > 0u && ms > maxMs.toDouble()) "hook $event slow: ${fmt1(ms)} ms over max_ms $maxMs ms" else null

internal fun slowKey(event: String) = "hook:slow:$event"

/**
 * T969: folds one slow call into the tally `"<last warning secs> <calls> <worst ms>"`; returns the
 * next tally and, when a warning is due, its `(calls, worst ms)`. Garbage counts as never warned.
 */
internal fun slowTally(prev: String?, now: Long, ms: Double, every: ULong): Pair<String, Pair<Long, Double>?> {
    val it = (prev ?: "").split(' ')
    val since = it.getOrNull(0)?.toULongOrNull()?.toLong()
    val calls = (it.getOrNull(1)?.toULongOrNull()?.toLong() ?: 0) + 1
    val worst = maxOf(it.getOrNull(2)?.toDoubleOrNull() ?: 0.0, ms)
    return if (since == null || every == 0uL || maxOf(0, now - since) >= every.toLong()) "$now 0 0" to (calls to worst)
    else "$since $calls ${fmt1(worst)}" to null
}

/** `{:.1}` formatting. */
internal fun fmt1(v: Double): String { val r = kotlin.math.round(v * 10) / 10; val s = r.toString(); return if ('.' in s) s else "$s.0" }

/** T170 / T969: at most one `warn` row per event per `[hook] slow_warn_s`. */
internal fun noteSlow(cx: Runtime, event: String, ms: Double) {
    val maxMs = cx.config.hook.maxMs
    val note = slowNote(ms, maxMs, event) ?: return
    val key = slowKey(event)
    val prev = runCatching { cx.store.kvGet(key) }.getOrElse { return }
    val (next, due) = slowTally(prev, rtok.log.now(), ms, cx.config.hook.slowWarnS)
    runCatching { cx.store.kvSet(key, next) }
    val (calls, worst) = due ?: return
    val text = if (calls > 1) "hook $event slow: $calls calls over max_ms $maxMs ms since the last warning, worst ${fmt1(worst)} ms" else note
    eprint("rtok: $text\n")
    cx.log("warn", "hook", event, text)
}

/** T204: a throwing plugin is logged at `error` under its id and dropped; the rest run (D1). */
private inline fun <T> guarded(cx: Runtime, plugin: String, event: String, block: () -> T): T? = try { block() } catch (e: Exception) {
    cx.log("error", "plugin", plugin, "$event panicked: ${e.message ?: e::class.simpleName}"); null
}

/** Runs the event's plugins against an open runtime and returns Claude-shaped stdout bytes. */
fun dispatch(stdin: ByteArray, input: HookInput, cx: Runtime): ByteArray {
    val start = TimeSource.Monotonic.markNow()
    val registry = Registry.new(cx.config)
    val event = input.hookEventName
    val agent = if (!cx.config.agents.enabled) null else cx.hostId?.let { hostId ->
        runCatching { cx.store.registerAgent(hostId, cx.session, agentParentKey(input), cx.cwd, agentActivity(input)) }.getOrNull()?.also { id ->
            cx.config.hookClientPid?.toInt()?.let { pid -> runCatching { cx.store.setAgentAncestors(id, rtok.sys.processAncestors(pid, AGENT_ANCESTORS)) } }
        }
    }
    val parent = try { cx.recordCall("hook", "hook", event) } catch (e: Exception) {
        if (isLocked(e)) {
            val msg = "skipped: store locked: ${e.message}"
            eprint("rtok: hook $event $msg\n"); rtok.appendLog(cx.config, "error", "hook", event, msg)
            deferSessionEnd(cx.config, event, stdin)
            return EMPTY_OUT
        }
        rtok.appendLog(cx.config, "error", "hook", event, "record_call failed: ${e.message}"); null
    }
    forgetAfterCompaction(input, cx)
    val out = when (event) {
        "PreToolUse" -> preTool(input, cx, registry)
        "PostToolUse" -> postTool(input, cx, registry, agent)
        "AfterMCPExecution" -> HookOutput()
        "SessionStart" -> {
            writeAgentEnvFile(agent)
            val cwd = cx.cwd
            if (cx.config.plugins.graph.autoAddProjects && cwd != null) runCatching { cx.store.autoAddProject(cwd, Origin.Session, null) }
            if (agent != null && cwd != null) runCatching { HookLinks.completePendingWorktree(cx, cwd, agent) }
            cx.deferMeasurements()
            injectEvent(input, cx, registry, agent).also { cx.flushMeasurements() }
        }
        "PostCompact" -> if (cx.config.hook.host == "claude" && "compact_summary" in input.extra) HookOutput() else injectEvent(input, cx, registry, agent)
        "UserPromptSubmit", "SubagentStart" -> injectEvent(input, cx, registry, agent)
        "PreCompact" -> {
            input.preCompact()?.let { ev -> for (p in registry.enabled()) guarded(cx, p.manifest().id, "PreCompact") { p.preCompact(ev, Ctx(cx)) } }
            val extra = rtok.plugins.memory.compactContext(Ctx(cx))
            if (extra != null) HookOutput(hookSpecificOutput = HookSpecificOutput("PreCompact", additionalContext = extra.text)) else HookOutput()
        }
        "SessionEnd" -> {
            runCatching { rtok.plugins.checkpoint.saveSession(input.transcriptPath ?: "", Ctx(cx)) }
            val now = rtok.log.now()
            if (agent != null && runCatching { cx.store.endAgent(agent, now) }.isSuccess) cx.cwd?.let { cwd -> runCatching { HookLinks.unlockEndedWorktree(cwd, agent) } }
            runCatching { cx.store.endSession(cx.session, now) }.exceptionOrNull()?.let { if (isLocked(it)) deferSessionEnd(cx.config, "SessionEnd", stdin) }
            HookOutput()
        }
        else -> HookOutput()
    }
    val bytes = out.encode().encodeToByteArray()
    val ms = start.elapsedNow().inWholeNanoseconds / 1e6
    noteSlow(cx, event, ms)
    if (parent != null) {
        runCatching { cx.store.setCallMs(parent, ms) }
        runCatching { cx.store.insertHookCallIo(parent, stdin, bytes, cx.config.core.callIoInlineBytes.toInt()) }
    }
    if (event == "Stop" || event == "SessionEnd") runCatching { HookLinks.spawnOtelExport(cx) }
    return bytes
}

/** T929: a compaction drops what the window saw, so the re-read cache and archive pointers go too. */
private fun forgetAfterCompaction(input: HookInput, cx: Runtime) {
    val compacting = input.hookEventName == "PreCompact" || input.hookEventName == "PostCompact" || input.sessionStart()?.source == "compact"
    if (compacting) runCatching { cx.forgetSeen(agentParentKey(input)) }
}

/**
 * T(D1): a SessionEnd that met a locked store is re-run detached with a steady lock wait, so the
 * session still ends. Runs at most once (`RTOK_HOOK_DEFERRED` marks the re-run).
 */
internal fun deferSessionEnd(cfg: Config, event: String, stdin: ByteArray, env: (String) -> String? = ::envVar) {
    if (event != "SessionEnd" || env(DEFERRED_ENV) != null) return
    val exe = rtok.sys.currentExe() ?: return
    val extra = buildMap { put(DEFERRED_ENV, "1"); if (cfg.home.isNotEmpty()) put("RTOK_HOME", cfg.home) }
    rtok.sys.spawnDetached(exe, listOf("hook", "SessionEnd"), extra, stdin)
}

private fun preTool(input: HookInput, cx: Runtime, registry: Registry): HookOutput {
    val ev = input.preTool() ?: return HookOutput()
    var rewrite: PreToolDecision.Rewrite? = null
    for (p in registry.enabled()) {
        when (val got = guarded(cx, p.manifest().id, "PreToolUse") { p.preTool(ev, Ctx(cx, input.agentId)) }) {
            is PreToolDecision.Deny -> return HookOutput(hookSpecificOutput = HookSpecificOutput("PreToolUse", permissionDecision = "deny", permissionDecisionReason = got.reason))
            is PreToolDecision.Rewrite -> rewrite = got
            null -> {}
        }
    }
    return rewrite?.let { HookOutput(hookSpecificOutput = HookSpecificOutput("PreToolUse", updatedInput = it.input, permissionDecisionReason = it.reason)) } ?: HookOutput()
}

private fun postTool(input: HookInput, cx: Runtime, registry: Registry, agent: String?): HookOutput {
    val ev = input.postTool() ?: return HookOutput()
    val parts = mutableListOf<String>()
    for (p in registry.enabled()) guarded(cx, p.manifest().id, "PostToolUse") { p.postTool(ev, Ctx(cx, input.agentId)) }?.let { parts += it }
    val push = Push.pending(cx, agent)
    if (push != null) parts.add(0, push.text)
    val text = capBudget(cx, parts.joinToString("\n"))
    push?.settle(cx, text)
    val updated = cursorMcpOutput(input, cx)
    if (text.isEmpty() && updated == null) return HookOutput()
    return HookOutput(hookSpecificOutput = HookSpecificOutput("PostToolUse", additionalContext = text.ifEmpty { null }, updatedMcpToolOutput = updated))
}

/** True for rtok's own MCP server (never shortened again). */
internal fun isRtokMcp(input: HookInput): Boolean {
    if (input.mcpServerName()?.equals("rtok", ignoreCase = true) == true) return true
    val raw = input.toolName ?: ""
    return raw.removePrefix("MCP:") == "expand"
}

/** The MCP result object inside a Cursor tool response: `{content: [...]}` or `{result: {content: [...]}}`. */
internal fun mcpResult(response: JsonElement): JsonElement? {
    val o = response as? JsonObject ?: return null
    if (o["content"] is kotlinx.serialization.json.JsonArray) return o
    val r = o["result"] as? JsonObject
    return if (r?.get("content") is kotlinx.serialization.json.JsonArray) r else null
}

private fun cursorMcpOutput(input: HookInput, cx: Runtime): JsonElement? {
    if (cx.config.hook.host != "cursor" || isRtokMcp(input)) return null
    val result = arrayOf(mcpResult(input.toolResponse ?: return null) ?: return null)
    val server = input.mcpServerName() ?: "mcp"
    val tool = (input.toolName ?: "tool").removePrefix("MCP:")
    return if (HookLinks.shortenMcpResult(cx, server, tool, result)) result[0] else null
}

private fun injectEvent(input: HookInput, cx: Runtime, registry: Registry, agent: String?): HookOutput {
    val event = input.hookEventName
    val inj = mutableListOf<Injection>()
    for (p in registry.enabled()) guarded(cx, p.manifest().id, event) {
        val ss = input.sessionStart(); val ps = input.promptSubmit(); val sa = input.subagentStart()
        when {
            ss != null -> p.sessionStart(ss, Ctx(cx))
            ps != null -> p.promptSubmit(ps, Ctx(cx))
            event == "PostCompact" -> p.sessionStart(SessionStart("compact"), Ctx(cx))
            sa != null -> p.subagentStart(sa, Ctx(cx))
            else -> null
        }
    }?.let { inj += it }
    if (event == "SessionStart" || event == "SubagentStart") agentIdInjection(agent)?.let { inj += it }
    if (event == "SessionStart" || event == "PostCompact") claimInjection(cx, agent)?.let { inj += it }
    val push = if (event == "UserPromptSubmit") Push.pending(cx, agent) else null
    push?.let { inj += it.injection() }
    if (inj.isEmpty()) return HookOutput()
    val text = rtok.plugins.inject.apply(Ctx(cx), inj)
    push?.settle(cx, text)
    if (text.isEmpty()) return HookOutput()
    return HookOutput(hookSpecificOutput = HookSpecificOutput(event, additionalContext = text))
}

/** Caps PostToolUse context at `[plugins.inject] budget_tokens`; the rest goes to the archive behind a marker. */
internal fun capBudget(cx: Runtime, text: String): String {
    val budget = cx.config.plugins.inject.budgetTokens
    fun est(s: String) = cx.estimate(s, Class.Prose)
    if (est(text) <= budget) return text
    val lines = text.rustLines()
    var keep = 0
    while (keep < lines.size && est(lines.subList(0, keep + 1).joinToString("\n")) <= budget) keep++
    if (keep == lines.size) return lines.joinToString("\n")
    fun markerFor(dropped: String, id: String): String {
        val e = est(dropped)
        return if (id.isEmpty()) "dropped:post_tool:$e" else "dropped:post_tool:$e · expand: rtok expand ${rtok.shortId(id)}"
    }
    val placeholder = "0".repeat(rtok.MIN_PREFIX)
    while (keep > 0) {
        val dropped = lines.subList(keep, lines.size).joinToString("\n")
        if (est(lines.subList(0, keep).joinToString("\n") + "\n" + markerFor(dropped, placeholder)) <= budget) break
        keep--
    }
    val dropped = lines.subList(keep, lines.size).joinToString("\n")
    val id = runCatching { cx.putArchive(dropped.encodeToByteArray()) }.getOrDefault("")
    val marker = markerFor(dropped, id)
    if (keep > 0) return lines.subList(0, keep).joinToString("\n") + "\n" + marker
    val m = est("\n$marker")
    val room = if (budget > m) budget - m else 0u
    val prefix = fitBudget(Ctx(cx), lines[0], Class.Prose, room)
    if (prefix.isNotEmpty()) return "$prefix\n$marker"
    return if (est(marker) <= budget) marker else ""
}

/** What the resident process (`rtok hook --serve`) runs per request, already chdir'ed into its cwd. */
fun residentRun(req: rtok.hook.Request): ByteArray {
    // This process's env came from whichever call started it; the payload names the session.
    val cfg = Config.loadLenient(null, rtok.config.Layers.hookHostFlag(req.host.ifEmpty { null })).withoutSessionEnv().also { it.hookClientPid = req.pid }
    return run(req.event, { limit -> if (req.stdin.size > limit) req.stdin.copyOf(limit.toInt()) else req.stdin }, cfg)
}
