// Port of src/plugins/read/hook.rs (T4.6): PreToolUse(Read) advice — deny a native Read of a large file not just edited.
package rtok.plugins.read

import kotlinx.serialization.json.*
import rtok.HostPaths
import rtok.plugin.sdk.*
import rtok.sys.canonicalize
import rtok.sys.extension
import rtok.sys.fileLen
import rtok.config.Hook as HookCfg

object ReadHook {
    /** Tail of the first deny; `measure::stats` matches it in a transcript to find a denied Read (T383). */
    const val REASON = "before Edit run native Read(limit=1) — it satisfies the edit gate"

    /** Head of the post-edit deny; matched like [REASON]. */
    const val DELTA_REASON = "file changed since last read; use "

    /** The MCP server name hosts register rtok under (rtok-mcp `registry::RTOK.name`). */
    private const val SERVER = "rtok"

    /** The edit window: the last 5 finished tool calls (PostToolUse rows, T202). */
    const val WINDOW_TOOL_CALLS = 5

    /** Extensions the host's native Read renders (vision, PDF pages) and MCP `read` cannot. */
    private val NATIVE_MEDIA = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "tif", "tiff", "heic", "avif", "pdf")

    /** T384: the exact tool name per host, and the line that loads it where the host defers MCP schemas. */
    private class ReadTool(
        val name: String,
        val hint: String,
    ) {
        companion object {
            fun of(host: String): ReadTool {
                val (name, defers) =
                    when (host) {
                        "claude" -> "mcp__${SERVER}__read" to true
                        "gemini" -> "mcp_${SERVER}_read" to false
                        else -> "$SERVER read" to false
                    }
                return ReadTool(name, if (defers) " (ToolSearch select:$name loads it)" else "")
            }
        }
    }

    fun preTool(
        ev: PreToolUse,
        cx: Ctx,
    ): PreToolDecision? {
        val cfg = readCfg(cx)
        if (!cfg.advice || ev.toolName != "Read") return null
        val path = pathArg(ev.toolInput) ?: return null
        val len = fileLen(path) ?: return null
        if (len.toULong() <= cfg.nativeMaxBytes) return null
        // T930: a deny naming a tool that cannot serve the file only costs a turn.
        if (rtokCannotServe(path, cfg.allowPaths)) return null
        // T127/T383: a ranged native Read passes; a zero limit is unranged for the host too.
        val limit = ((ev.toolInput as? JsonObject)?.get("limit") as? JsonPrimitive)?.longOrNull
        if (limit != null && limit in 1..cfg.rangeMaxLines.toLong()) return null
        if (recentlyEdited(cx, path)) {
            // T929: with a sub-agent in the session the id may name a body this window never saw.
            if (cfg.delta && !cx.sharedSession()) {
                lastReadId(cx, path)?.let { id ->
                    val t = tool(cx)
                    return deny(cx, "$DELTA_REASON${t.name}(mode=diff) vs ${id.take(8)}${t.hint}")
                }
            }
            return null
        }
        val t = tool(cx)
        return deny(cx, "use ${t.name}${t.hint}; $REASON")
    }

    /** A media file, or a path the MCP `read` guard refuses (the hook never enables the extra worktree roots). */
    private fun rtokCannotServe(
        path: String,
        allowPaths: List<String>,
    ): Boolean {
        if (extension(path)?.lowercase() in NATIVE_MEDIA) return true
        val cwd = runCatching { Roots.cwd() }.getOrNull() ?: return false
        return runCatching { resolve(cwd, path, allowPaths) }.isFailure
    }

    private fun tool(cx: Ctx) = ReadTool.of(cx.config("hook", HookCfg.serializer(), HookCfg()).host)

    /** Every deny is a cost row, never a saving (T383); a store error never turns a deny into a hook failure. */
    private fun deny(
        cx: Ctx,
        reason: String,
    ): PreToolDecision {
        runCatching { cx.record(Measurement("read", "deny", 0u, reason.encodeToByteArray().size.toULong(), 0u, cx.estimate(reason, Class.Code))) }
        return PreToolDecision.Deny(reason)
    }

    /** Archive id of the last MCP `read` of this path (`mode=full`), if still cached. */
    private fun lastReadId(
        cx: Ctx,
        path: String,
    ): String? {
        val abs = canonicalize(path) ?: return null
        return runCatching { cx.getReadCache(ReadCache.key(abs, "full", null)) }.getOrNull()?.first
    }

    /** A PostToolUse(Edit|Write) of [path] in the window; a store error or an elided body fails open (true). */
    private fun recentlyEdited(
        cx: Ctx,
        path: String,
    ): Boolean {
        val bodies = runCatching { cx.recentHookInputsForEvent("PostToolUse", WINDOW_TOOL_CALLS.toLong()) }.getOrElse { return true }
        for (b in bodies) {
            if (b.isEmpty()) return true
            val v = runCatching { Json.parseToJsonElement(b) }.getOrNull() as? JsonObject ?: continue
            if (editsPath(v, path)) return true
        }
        return false
    }

    private fun editsPath(
        v: JsonObject,
        path: String,
    ): Boolean {
        val tool = (v["tool_name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (tool != "Edit" && tool != "Write") return false
        return HostPaths.sameSpelling(v["tool_input"]?.let(::pathArg).orEmpty(), path)
    }
}
