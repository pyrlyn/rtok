// Port of src/plugins/guard/mod.rs — deny duplicate Read/Bash when a prior archive id exists (plan T2.6).
// `grep_symbol` (T369) answers a symbol-shaped Grep from the graph index (GrepSymbol.kt).
package rtok.plugins.guard

import kotlinx.serialization.json.*
import rtok.canonicalToolName
import rtok.cmdStem
import rtok.json.StrictJson
import rtok.plugin.sdk.*
import rtok.shortId
import rtok.skipWord
import rtok.stripWrap
import rtok.sys.Shlex
import rtok.sys.components
import rtok.sys.joinComponents
import rtok.config.Guard as GuardCfg
import rtok.config.Read as ReadCfg

/**
 * T201: above this, `postTool`'s sha256 + synchronous archive write would blow the hook's ≤ 10 ms
 * budget. Skipping the archive just leaves the key uncached (lossless by omission).
 */
internal const val ARCHIVE_CAP_BYTES = 256 * 1024

internal fun guardCfg(cx: Ctx) = cx.pluginConfig("guard", GuardCfg.serializer(), GuardCfg())

object GuardPlugin : Plugin {
    override fun manifest() = Manifest("guard", listOf(Surface.Hook, Surface.Cli), true)

    override fun dashboardPage() = DashboardPage("Guard", "Deny duplicate reads and commands inside a sliding window.", true)

    override fun preTool(
        ev: PreToolUse,
        cx: Ctx,
    ): PreToolDecision? {
        if (ev.toolName == "Skill") return digest(ev, cx)
        // T369: answer a symbol-shaped Grep before `nativeRedirect` would only point elsewhere.
        if (ev.toolName == "Grep") GrepSymbol.answer(ev, cx)?.let { return it }
        nativeRedirect(ev.toolName, cx)?.let { return it }
        val key = cacheKey(ev.toolName, ev.toolInput, cx.agentId(), cx.cwd()) ?: return null
        val (id0, ts) = runCatching { cx.getReadCache(key) }.getOrNull() ?: return null
        val id = id0 ?: return null
        val n = runCatching { cx.callsSince(ts) }.getOrDefault(0L)
        if (n > guardCfg(cx).windowTurns.toLong()) return null
        val reason = "duplicate; rtok expand ${shortId(id)}"
        // Never deny without a retrievable original; metadata only (T55.16).
        val avoided = runCatching { cx.archiveSize(id) }.getOrNull() ?: return null
        if (avoided == 0uL) return null
        val est = (avoided / 4uL).coerceAtLeast(1uL).toUInt()
        runCatching { cx.record(Measurement("guard", "guard", avoided, 0uL, est, 0u, id)) }
        return PreToolDecision.Deny(reason)
    }

    // T392: loaded bodies are gone after a compaction.
    override fun preCompact(
        ev: PreCompact,
        cx: Ctx,
    ) = forgetLoads(cx)

    override fun sessionStart(
        ev: SessionStart,
        cx: Ctx,
    ): Injection? {
        if (ev.source == "compact") forgetLoads(cx)
        return null
    }

    override fun postTool(
        ev: PostToolUse,
        cx: Ctx,
    ): String? {
        if (ev.toolName == "Skill") return noteLoad(ev, cx)
        val key = cacheKey(ev.toolName, ev.toolInput, cx.agentId(), cx.cwd())
        if (key != null) {
            // A kept `cd` hop moves the shell for every later call.
            if (ev.toolName == "Bash" && str(ev.toolInput, "command")?.let(::hasCdHop) == true) runCatching { cx.clearReadCache("bash") }
            val body = payload(ev.toolResponse)
            if (body.size <= ARCHIVE_CAP_BYTES) {
                val id = runCatching { cx.putArchive(body) }.getOrNull() ?: return null
                runCatching { cx.putReadCache(key, id, id) }
            } else {
                runCatching { cx.clearReadCache(key) }
            }
        } else if (ev.toolName in setOf("Bash", "Edit", "Write")) {
            // The guard owns its keys (T55.8).
            runCatching { cx.clearReadCache("bash") }
            val mutated = fileArg(ev.toolInput)?.let { pathIdentity(it, cx.cwd()) }
            when {
                ev.toolName == "Bash" -> runCatching { cx.clearReadCache("read") }
                mutated != null -> runCatching { cx.clearReadCache("read\t$mutated") }
                else -> runCatching { cx.clearReadCache("read") }
            }
        }
        return null
    }
}

/** `rtok guard check` — same allow/deny `preTool` returns, as a JSON line. */
fun check(
    tool: String,
    rawInput: String,
    host: Capabilities,
): String {
    val name = canonicalToolName(tool)
    var input: JsonElement = runCatching { StrictJson.parse(rawInput) }.getOrDefault(JsonNull)
    if (input is JsonObject && "filePath" in input) {
        val m = LinkedHashMap(input)
        val fp = m.remove("filePath")!!
        if ("file_path" !in m) m["file_path"] = fp
        input = JsonObject(m)
    }
    val d = GuardPlugin.preTool(PreToolUse(name, input), Ctx(host))
    return if (d is PreToolDecision.Deny && d.reason.isNotEmpty()) {
        buildJsonObject {
            put("allow", false)
            put("reason", d.reason)
        }.toString()
    } else {
        buildJsonObject { put("allow", true) }.toString()
    }
}

/** T50.4: opt-in deny of native Grep/Glob pointing at MCP search/tree; silent while `read` is off. */
private fun nativeRedirect(
    tool: String,
    cx: Ctx,
): PreToolDecision? {
    val target =
        when (tool) {
            "Grep" -> "search"
            "Glob" -> "tree"
            else -> return null
        }
    if (!guardCfg(cx).denyGrepGlob) return null
    if (!cx.pluginConfig("read", ReadCfg.serializer(), ReadCfg()).enabled) return null
    runCatching { cx.record(Measurement("guard", "native_deny", 0uL, 0uL, 0u, 0u)) }
    return PreToolDecision.Deny("native $tool is disabled here; use rtok $target (MCP) instead")
}

private fun str(
    v: JsonElement,
    k: String,
): String? = ((v as? JsonObject)?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun fileArg(input: JsonElement): String? {
    val o = input as? JsonObject ?: return null
    val v = o["file_path"] ?: o["path"] ?: return null
    return (v as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/**
 * One spelling for a file (T556): relative resolved against the hook cwd, `.` and repeated
 * separators dropped. Lexical only. Null when blank, relative without a cwd, or holding `..`.
 */
internal fun pathIdentity(
    p: String,
    cwd: String?,
): String? {
    if (p.isBlank()) return null
    val joined = if (p.startsWith("/")) p else "${cwd?.takeIf { it.isNotEmpty() } ?: return null}/$p"
    if (!joined.startsWith("/")) return null
    val c = components(joined)
    if (c.any { it == ".." }) return null
    return joinComponents(c)
}

internal fun cacheKey(
    tool: String,
    input: JsonElement,
    agent: String?,
    cwd: String?,
): String? {
    val key =
        when (tool) {
            "Read" -> {
                val p = pathIdentity(fileArg(input) ?: return null, cwd) ?: return null
                // T322: a slice is a different body; the slice is a suffix so the prefix clear reaches it.
                val o = input as? JsonObject
                val slice =
                    listOf("offset", "limit", "pages").map { f ->
                        when (val v = o?.get(f)) {
                            null, JsonNull -> ""
                            is JsonPrimitive -> if (v.isString) v.content else v.toString()
                            else -> v.toString()
                        }
                    }
                if (slice.all { it.isEmpty() }) "read\t$p" else "read\t$p\t${slice.joinToString(":")}"
            }

            "Bash" -> {
                val c = normCmd(str(input, "command") ?: return null) ?: return null
                if (relativeHop(c)) return null
                if (!readOnly(c)) return null
                when (val d = cwd?.takeIf { it.isNotEmpty() }) {
                    null -> "bash\t$c"
                    else -> "bash\t$d\t$c"
                }
            }

            else -> {
                return null
            }
        }
    // T129: a context window is (session_id, agent_id).
    return if (!agent.isNullOrEmpty()) "$key\t$agent" else key
}

private val WS = Regex("\\s+")

private fun words(s: String) = s.split(WS).filter { it.isNotEmpty() }

/** T57.1: keyed only when every segment's stem is read-only and no writer marker is present. */
internal fun readOnly(cmd: String): Boolean =
    !cmd.contains("$(") && !cmd.contains('`') &&
        segments(afterCdPrefix(cmd)).map { it.trim() }.filter { it.isNotEmpty() }.all(::segmentReadOnly)

/** Splits at every unquoted `|`, `;` and `&`. */
private fun segments(s: String): List<String> {
    val out = ArrayList<String>()
    var start = 0
    var quote: Char? = null
    for ((i, b) in s.withIndex()) {
        val q = quote
        when {
            q != null -> {
                if (b == q) quote = null
            }

            b == '\'' || b == '"' -> {
                quote = b
            }

            b == '|' || b == ';' || b == '&' -> {
                out += s.substring(start, i)
                start = i + 1
            }
        }
    }
    out += s.substring(start)
    return out
}

private fun segmentReadOnly(seg: String) = stemReadOnly(seg) && !writerMarker(seg)

private val LISTING = setOf("-a", "-r", "-v", "-vv", "--all", "--remotes", "--verbose", "--list", "--show-current")

private fun stemReadOnly(seg: String): Boolean {
    val w = words(seg)
    return when (cmdStem(w.firstOrNull() ?: "")) {
        "ls", "cat", "head", "tail", "grep", "rg", "find", "tree", "wc", "jq" -> {
            true
        }

        // T557: a script can write (`w`) or run (`e`) from inside its quotes.
        "sed" -> {
            Shlex.split(seg)?.let { it.isNotEmpty() && sedReadOnly(it.drop(1)) } == true
        }

        // T322: an awk program can write from inside its quotes.
        "awk" -> {
            !seg.contains("system(") && !seg.contains('>') && !seg.contains('|')
        }

        "git" -> {
            when (w.getOrNull(1)) {
                // T557: `--output=<file>` and its abbreviations write a file.
                "status", "log", "diff", "show", "rev-parse" -> {
                    Shlex.split(seg)?.none { a ->
                        val name = a.substringBefore('=')
                        name.length >= 3 && "--output".startsWith(name)
                    } == true
                }

                "branch" -> {
                    w.drop(2).all { it in LISTING }
                }

                else -> {
                    false
                }
            }
        }

        "cargo" -> {
            w.getOrNull(1) == "metadata"
        }

        else -> {
            false
        }
    }
}

/** Writer markers take the mutating path and clear `bash` keys (fail-open vs false deny). */
private fun writerMarker(seg: String): Boolean {
    val toks = words(seg)
    val stem = cmdStem(toks.firstOrNull() ?: "")
    return toks.any(::isRedirect) || hasUnquotedRedirect(seg) ||
        (
            stem == "find" && (
                Shlex.split(seg)?.any { t ->
                    t == "-delete" || t == "-fls" || listOf("-exec", "-ok", "-fprint").any { t.startsWith(it) }
                } ?: true
            )
        ) ||
        (stem == "tail" && toks.any { it == "-f" || it == "--follow" || it.startsWith("--follow=") })
}

private fun isRedirect(t0: String): Boolean {
    val t = t0.trimStart { it in '0'..'9' }
    return t.startsWith('>') || t.startsWith("&>")
}

/** A `>`/`>>` glued to a word (`echo x>file`); quote-aware. */
private fun hasUnquotedRedirect(seg: String): Boolean {
    var quote: Char? = null
    for (b in seg) {
        val q = quote
        when {
            q != null -> if (b == q) quote = null
            b == '\'' || b == '"' -> quote = b
            b == '>' -> return true
        }
    }
    return false
}

/** Normalized Bash key body: whitespace collapsed, the `rtok run --` wrap stripped, every leading `cd … &&` hop kept. */
private fun normCmd(s: String): String? {
    val (hops, rest) = splitCd(unwrapFlat(s) ?: return null) ?: return null
    return hops.joinToString("") { "cd $it && " } + rest
}

private fun splitCd(s: String): Pair<List<String>, String>? {
    val hops = ArrayList<String>()
    var t = s
    while (true) {
        val (d, rest) = stripCdHop(t) ?: break
        hops += d
        t = unwrapFlat(rest) ?: return null
    }
    return hops to t
}

/** The wrapper is removed before the inner command is flattened. */
private fun unwrapFlat(s: String): String? {
    val f = flatten(s) ?: return null
    return flatten(stripWrap(f))
}

/** Whether any leading `cd` hop is relative (absolute: rooted, `~…`, or a Windows drive). */
private fun relativeHop(cmd: String): Boolean {
    val (hops, _) = splitCd(cmd) ?: return true
    return hops.any { t0 ->
        val t =
            when {
                t0.length >= 2 && t0.startsWith('\'') && t0.endsWith('\'') -> t0.substring(1, t0.length - 1)
                t0.length >= 2 && t0.startsWith('"') && t0.endsWith('"') -> t0.substring(1, t0.length - 1)
                else -> t0
            }
        val drive = t.length >= 3 && t[0].code < 128 && t[0].isLetter() && t[1] == ':' && (t[2] == '\\' || t[2] == '/')
        !(t.startsWith('/') || t.startsWith('~') || drive)
    }
}

private fun hasCdHop(command: String) = unwrapFlat(command)?.let { stripCdHop(it) != null } == true

/** One `cd <dir> &&` prefix: the target word (quotes kept) and the remainder after `&&`. */
internal fun stripCdHop(s: String): Pair<String, String>? {
    if (!s.startsWith("cd ")) return null
    val after = s.substring(3)
    val rest = skipWord(after) ?: return null
    val target = after.substring(0, after.length - rest.length).trimEnd()
    if (target.isEmpty()) return null
    if (!rest.startsWith("&&")) return null
    return target to rest.substring(2).trimStart()
}

private fun afterCdPrefix(s0: String): String {
    var s = s0
    while (true) {
        if (!s.startsWith("cd ")) return s
        val r = skipWord(s.substring(3)) ?: return s
        if (!r.startsWith("&&")) return s
        s = r.substring(2).trimStart()
    }
}

/**
 * Collapses whitespace runs to one space and turns each unquoted newline into `;` (T322). Whitespace
 * inside quotes or behind a backslash stays (T555). Null for an unterminated quote.
 */
private fun flatten(s: String): String? {
    val out = StringBuilder(s.length)
    var quote: Char? = null
    var gap = false
    var i = 0
    while (i < s.length) {
        val c = s[i++]
        val q = quote
        when {
            q == '\'' -> {
                out.append(c)
                if (c == '\'') quote = null
            }

            q != null -> {
                out.append(c)
                if (c == '\\') {
                    if (i >= s.length) return null
                    out.append(s[i++])
                } else if (c == q) {
                    quote = null
                }
            }

            c == '\n' || c == '\r' -> {
                if (out.isNotEmpty()) out.append(' ')
                out.append(';')
                gap = true
            }

            c.isWhitespace() -> {
                gap = true
            }

            else -> {
                if (gap && out.isNotEmpty()) out.append(' ')
                gap = false
                out.append(c)
                when (c) {
                    '\'', '"' -> quote = c
                    '\\' -> if (i < s.length) out.append(s[i++])
                }
            }
        }
    }
    return if (quote == null) out.toString() else null
}

private fun payload(v: JsonElement): ByteArray {
    if (v is JsonPrimitive && v.isString) return v.content.encodeToByteArray()
    for (k in listOf("stdout", "content")) str(v, k)?.let { return it.encodeToByteArray() }
    return v.toString().encodeToByteArray()
}
