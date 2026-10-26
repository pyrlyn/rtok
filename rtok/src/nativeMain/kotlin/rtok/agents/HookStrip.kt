// Port of src/agents/claude/mod.rs `strip_ours_as` (T246.3).
package rtok.agents

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.agent.sdk.Apply
import rtok.agent.sdk.NO_CHANGES

object HookStrip {
    /**
     * Remove rtok's hooks under [hooks]; empty arrays go. One still as [HookInsert] writes it (a listed pair, exactly
     * `{type, command, <timeoutKey>}`) goes; one the user changed goes only as [StripReport.takesHook] decides. Null hooks
     * back when [hooks] is not an object (nothing to edit).
     */
    fun strip(
        form: HookForm,
        apply: Apply,
        path: String,
        hooks: JsonElement?,
        spec: HookSpec,
    ): Pair<JsonObject?, String> {
        val obj = hooks as? JsonObject ?: return null to NO_CHANGES
        val walk = Walk(form, apply, path, spec)
        val out =
            obj
                .mapValues { (event, v) -> (v as? JsonArray)?.let { arr -> JsonArray(arr.map { walk.entry(event, it) }.filter(::keepsHooks)) } ?: v }
                .filterValues { (it as? JsonArray)?.isEmpty() != true }
        return JsonObject(out) to StripReport.withKept(walk.kept, StripReport.removedReport(walk.removed))
    }

    private class Walk(
        val form: HookForm,
        val apply: Apply,
        val path: String,
        val spec: HookSpec,
    ) {
        var removed = 0
        val kept = mutableListOf<String>()

        fun entry(
            event: String,
            entry: JsonElement,
        ): JsonElement {
            val matcher = matcherOf(entry)
            val listed = (event to matcher) in spec.entries
            val inner = innerHooks(entry) ?: return entry
            return withInner(entry, inner.filterNot { h -> takes(event, matcher, listed, h) })
        }

        private fun takes(
            event: String,
            matcher: String,
            listed: Boolean,
            h: JsonElement,
        ): Boolean {
            val cmd = str(h, "command")?.takeIf { form.isOurs(it, event) } ?: return false
            val want = JsonObject(mapOf("type" to JsonPrimitive("command"), "command" to JsonPrimitive(cmd), spec.timeoutKey to JsonPrimitive(spec.timeout)))
            val take = StripReport.takesHook(apply, listed && h == want, { "hooks.$event${ClaudeHooks.show(matcher)} in $path" }, kept)
            if (take) removed++
            return take
        }
    }
}
