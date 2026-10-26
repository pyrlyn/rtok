// Port of src/plugins/read/mod.rs: MCP `read` / `search` / `tree` with modes, size caps and re-read dedup.
package rtok.plugins.read

import kotlinx.serialization.json.*
import rtok.Expand
import rtok.HostPaths
import rtok.config.HostFs
import rtok.config.ReadFs
import rtok.config.Read as ReadCfg
import rtok.plugin.identicalResult
import rtok.plugin.sdk.*
import rtok.sys.*

/** A read the caller got wrong: outside the roots, a missing file, a refused credential file. */
class ReadException(message: String) : Exception(message)

object ReadPlugin : Plugin {
    override fun manifest() = Manifest("read", listOf(Surface.Mcp, Surface.Hook), true)
    override fun dashboardPage() = DashboardPage("Read", "Outline, map, and search instead of dumping full files into context.", true)
    override fun preTool(ev: PreToolUse, cx: Ctx) = ReadHook.preTool(ev, cx)
    override fun postTool(ev: PostToolUse, cx: Ctx): String? { ReadCache.invalidate(ev, cx); return null }
    override fun mcpTools() = listOf(
        ToolDef("read", "Read a file; mode full|lines|map|signatures|diff|stripped|density:X; range a-b for full|lines|stripped. From a worktree, pass absolute paths.",
            schema("""{"type":"object","properties":{"path":{"type":"string"},"mode":{"type":"string"},"range":{"type":"string"}},"required":["path"]}""")),
        ToolDef("search", "Regex search files; path:line: snippet, max hits. From a worktree, pass absolute paths.",
            schema("""{"type":"object","properties":{"pattern":{"type":"string"},"query":{"type":"string","description":"alias of pattern"},"path":{"type":"string"},"max":{"type":"integer"}},"anyOf":[{"required":["pattern"]},{"required":["query"]}]}""")),
        ToolDef("tree", "Compact directory listing with sizes; depth cap. From a worktree, pass absolute paths.",
            schema("""{"type":"object","properties":{"path":{"type":"string"},"depth":{"type":"integer"}}}""")),
    )
    private fun schema(s: String) = Json.parseToJsonElement(s)
}

internal fun readCfg(cx: Ctx) = cx.pluginConfig("read", ReadCfg.serializer(), ReadCfg())

fun read(cx: Ctx, path: String, mode: String, range: String?): String = readWith(cx, HostFs, Roots.cwd(), path, mode, range)

/** The file a Read/Edit/Write input names: Claude Code's `file_path`, or Copilot's `path`. */
fun pathArg(input: JsonElement): String? {
    val o = input as? JsonObject ?: return null
    return ((o["file_path"] ?: o["path"]) as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/** `read` over an arbitrary [ReadFs] (host disk or a test Vfs). */
fun readWith(cx: Ctx, fs: ReadFs, cwd: String, path: String, mode0: String, range: String?): String {
    val cfg = readCfg(cx)
    val abs = resolveWith(fs, cwd, path, cfg.allowPaths)
    // T353: the bare io error never says which file; name it, without the `(os error N)` tail.
    val raw = try { fs.readToString(abs) } catch (e: IoException) {
        val hint = if (e.kind == IoException.Kind.NotFound) Roots.movedHint(cwd, abs) else ""
        val msg = when { e.kind == IoException.Kind.NotFound -> "No such file or directory"; "is a directory" in (e.message ?: "") -> "Is a directory"; else -> e.message ?: "read failed" }
        throw ReadException("$msg: $path$hint")
    }
    val m = mode0.ifEmpty { cfg.defaultMode }
    val forceDelta = m == "diff"
    val mode = if (forceDelta) "full" else m
    // T50.3: comments-stripped when a grammar exists; unknown language / parse fail → `full`.
    val strippedSrc = if (mode == "stripped") stripped(abs, raw) else null
    val density = parseDensity(mode)?.getOrElse { throw ReadException(it.message ?: "density") }
    val body = when {
        density != null -> renderDensity(raw, density)
        mode == "map" || mode == "signatures" -> render(abs, raw, mode)
        else -> {
            // Same grammar as `expand --lines`; a malformed range is an error, not the whole file.
            val lines = (strippedSrc ?: raw).rustLines()
            val (a, b) = if (range != null) Expand.parseRange(range, lines.size) else 1 to lines.size
            Expand.sliceLines(lines, a, b).withIndex().joinToString("\n") { (i, l) -> "${a + i}:$l" }
        }
    }
    val key = ReadCache.key(abs, mode, range)
    // T122: a ranged read is keyed on the bytes it returns, never the whole file.
    val payload = (if (mode == "map" || mode == "signatures" || density != null || strippedSrc != null || range != null) body else raw).encodeToByteArray()
    // T929: once a sub-agent shares the session no read is answered with a pointer, a delta or a remembered body.
    if (!cx.sharedSession()) {
        ReadCache.hit(cx, key, abs, payload, forceDelta)?.let { return it }
        identicalResult(cx, "read", payload, null)?.let { runCatching { ReadCache.remember(cx, key, payload) }; return it }
        runCatching { ReadCache.remember(cx, key, payload) }
    }
    // T300: outline modes measure raw file vs returned text, on this fresh-render path only.
    val renderKind = when {
        mode == "map" -> "map"; mode == "signatures" -> "signatures"; density != null -> "density"; strippedSrc != null -> "stripped"; else -> null
    }
    // T535: a full or ranged view has no render row, so the cap is its only saving.
    val out = if (renderKind != null) cap(cx, body) else capRecording(cx, body, "read_cap")
    val rawBytes = raw.encodeToByteArray().size.toULong(); val outBytes = out.encodeToByteArray().size.toULong()
    if (renderKind != null && outBytes < rawBytes) runCatching {
        cx.record(Measurement("read", renderKind, rawBytes, outBytes, cx.estimate(raw, Class.Code), cx.estimate(out, Class.Code)))
    }
    return out
}

fun resolve(cwd: String, path: String, extra: List<String>): String = resolveWith(HostFs, cwd, path, extra)

/** Resolve + allow-check, following symlinks through [ReadFs.canonicalize]. */
fun resolveWith(fs: ReadFs, cwd: String, path: String, extra: List<String>): String {
    // Canonical roots first; relative paths join onto the canonical cwd so a missing file shares its spelling.
    val roots = (listOf(cwd) + extra).map { fs.canonicalize(it) ?: it }
    val abs = if (path.startsWith("/")) HostPaths.normalizeFollowing(fs, "/", path) else HostPaths.normalizeFollowing(fs, roots[0], path)
    val check = fs.canonicalize(abs) ?: abs
    if (Roots.secretsDenied() && (Roots.isSecret(abs) || Roots.isSecret(check)))
        throw ReadException("refused: $path looks like a credential file and this server is reachable over HTTP")
    if (roots.any { under(check, it) }) return abs
    // T351 (`rtok mcp` only): sibling worktrees and client roots, after the cheap checks failed.
    if (Roots.dynamic(cwd).any { under(check, fs.canonicalize(it) ?: it) }) return abs
    throw ReadException("path outside cwd: $path (outside this project and its worktrees; read it with the host's own file tool, or add its directory to [plugins.read] allow_paths)")
}

/** An empty root grants nothing (`starts_with("")` is true for every path). */
private fun under(path: String, root: String) = root.isNotEmpty() && pathStartsWith(path, root)

/** T263: refuse to walk `/` or the home directory; such a walk times out instead of answering. */
fun walkRootOk(root: String) { if (isUnwalkableRoot(root, homeDir())) throw rtok.noProjectRoot(root) }

/** Cap at `plugins.read.max_chars`, archiving an oversized text so the cut carries its 8-char id (T908). */
private fun capImpl(cx: Ctx, text: String, kind: String?): String {
    val max = readCfg(cx).maxChars.toInt()
    if (text.scalarCount() <= max) return text
    val id = cx.putArchive(text.encodeToByteArray())
    val out = Expand.cut(text, "\n… archived ${rtok.shortId(id)} …\n", max)
    if (kind != null) runCatching {
        cx.record(Measurement("read", kind, text.encodeToByteArray().size.toULong(), out.encodeToByteArray().size.toULong(),
            cx.estimate(text, Class.Code), cx.estimate(out, Class.Code), id))
    }
    return out
}

fun cap(cx: Ctx, text: String) = capImpl(cx, text, null)

/** [cap], plus a [kind] row when it actually truncated (`search`/`tree` have no other honest before). */
fun capRecording(cx: Ctx, text: String, kind: String) = capImpl(cx, text, kind)
