// Port of src/agents/claude/mod.rs hook JSON walk: `HookForm`, `command`, `is_ours`, `command_for`, `is_ours_for`,
// `insert_ours[_as]`, `prune_ours`, `strip_ours[_as]`, `show`, and the `run` body (`edit_json` over `hooks`). Every host
// with Claude's `hooks.<event>[] = {matcher?, hooks: [{type, command, <timeout>}]}` layout shares it (Qwen, Devin,
// CodeBuddy, Codex, ...); only the command spelling ([HookForm]) and the [HookSpec] differ.
package rtok.agents

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.agent.sdk.Apply
import rtok.agent.sdk.KETCH_INSTALL

/** How a host spells rtok's hook command: [command] is written, [isOurs] recognises one already there. */
class HookForm(
    val command: (bin: String, event: String) -> String,
    val isOurs: (cmd: String, event: String) -> Boolean,
)

/** What one host installs: its `(event, matcher)` rows and how it spells the timeout (`timeout` s, `timeoutMs`). */
data class HookSpec(
    val entries: List<Pair<String, String>>,
    val timeoutKey: String,
    val timeout: Long,
)

object ClaudeHooks {
    private const val SESSION_NOTE =
        "{\"hookSpecificOutput\":{\"hookEventName\":\"SessionStart\",\"additionalContext\":" +
            "\"rtok is not installed; run $KETCH_INSTALL to enable it.\"}}"

    /** T174: a bare `rtok` resolves at hook-run time (PATH, ketch, fail open; one note on SessionStart). */
    fun command(
        bin: String,
        event: String,
    ): String = if (bin != "rtok") "$bin hook $event" else HookLine.hookResolver("hook $event", SESSION_NOTE.takeIf { event == "SessionStart" })

    /** Exactly `<rtok-bin> hook <event>`, or the resolver's `exec rtok hook <event>;` marker; a user's chain stays foreign. */
    fun isOurs(
        cmd: String,
        event: String,
    ): Boolean = oursBy(cmd, " hook $event")

    /** [command] for a host that names itself: `hook <event> --host <host>` (Devin T89, CodeBuddy T560.2). */
    fun commandFor(
        host: String,
        bin: String,
        event: String,
    ): String = if (bin != "rtok") "$bin hook $event --host $host" else HookLine.hookResolver("hook $event --host $host")

    /** [isOurs] for [commandFor]'s spelling. */
    fun isOursFor(
        host: String,
        cmd: String,
        event: String,
    ): Boolean = oursBy(cmd, " hook $event --host $host")

    private fun oursBy(
        cmd: String,
        suffix: String,
    ): Boolean = if (cmd.endsWith(suffix)) isRtokBin(HookLine.unquoteBin(cmd.removeSuffix(suffix))) else cmd.contains("exec rtok${suffix.trimEnd()};")

    /** Claude's own spelling, which Codex, Qwen and ZCode share. */
    val CLAUDE = HookForm(::command, ::isOurs)

    /** The spelling of a host that names itself. */
    fun formFor(host: String) = HookForm({ b, e -> commandFor(host, b, e) }, { c, e -> isOursFor(host, c, e) })

    /** ` <matcher>` for a report line; nothing for an empty matcher. */
    fun show(matcher: String) = if (matcher.isEmpty()) "" else " $matcher"

    /** The `run` body: install into or strip from `root.hooks`, returning the new root and its report. */
    fun edit(
        root: JsonObject,
        remove: Boolean,
        ctx: HookEdit,
    ): Pair<JsonObject, String> {
        val (hooks, report) =
            if (remove) {
                HookStrip.strip(ctx.form, ctx.apply, ctx.path, root["hooks"], ctx.spec)
            } else {
                HookInsert.insert(ctx.form, root["hooks"], ctx.spec, ctx.bin)
            }
        return (if (hooks == null) root else JsonObject(root + ("hooks" to hooks))) to report
    }
}

/** Everything one host's hook edit needs besides the document. */
data class HookEdit(
    val form: HookForm,
    val apply: Apply,
    val path: String,
    val spec: HookSpec,
    val bin: String = HookLine.rtokHookBin(),
)

internal fun str(
    e: JsonElement?,
    key: String,
): String? = ((e as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun matcherOf(entry: JsonElement) = str(entry, "matcher").orEmpty()

internal fun innerHooks(entry: JsonElement) = (entry as? JsonObject)?.get("hooks") as? JsonArray

internal fun withInner(
    entry: JsonElement,
    inner: List<JsonElement>,
) = JsonObject((entry as JsonObject) + ("hooks" to JsonArray(inner)))

/** An entry whose `hooks` array is present and empty goes; one with no array stays. */
internal fun keepsHooks(entry: JsonElement) = innerHooks(entry)?.isEmpty() != true
