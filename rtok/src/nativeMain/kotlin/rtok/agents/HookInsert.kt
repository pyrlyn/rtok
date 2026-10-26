// Port of src/agents/claude/mod.rs `insert_ours_as`, `has_ours`, `prune_ours` (T242.1).
package rtok.agents

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.agent.sdk.NO_CHANGES

object HookInsert {
    /**
     * Add rtok's hook under `hooks.<event>[]` for each row not already ours, bring ours up to date in their slot (another
     * binary or timeout), and drop ours on a row no longer listed. Foreign hooks are never touched. A non-object
     * [hooks] becomes `{}`.
     */
    fun insert(
        form: HookForm,
        hooks: JsonElement?,
        spec: HookSpec,
        bin: String,
    ): Pair<JsonObject, String> {
        val changed = mutableListOf<String>()
        val obj = hooks as? JsonObject ?: JsonObject(emptyMap())
        val out = LinkedHashMap(if (spec.matcherless) obj else prune(form, obj, spec.entries, changed))
        for ((event, matcher) in spec.entries) {
            val want = form.command(bin, event)
            val arr = (out[event] as? JsonArray).orEmpty()
            val slot = Slot(form, event, want, spec)
            val updated = arr.map { if (hasOurs(form, it, event, matcher.takeUnless { spec.matcherless })) slot.refresh(it, matcher, changed) else it }
            out[event] = JsonArray(if (slot.found) updated else updated + fresh(matcher, want, spec).also { changed += "+ $event${ClaudeHooks.show(matcher)} $want" })
        }
        return JsonObject(out) to summary(changed)
    }

    private class Slot(
        val form: HookForm,
        val event: String,
        val want: String,
        val spec: HookSpec,
    ) {
        var found = false

        fun refresh(
            entry: JsonElement,
            matcher: String,
            changed: MutableList<String>,
        ): JsonElement =
            withInner(
                entry,
                innerHooks(entry).orEmpty().map { h ->
                    val ours = str(h, "command")?.let { form.isOurs(it, event) } == true
                    found = found || ours
                    val stale = ours && (str(h, "command") != want || (h as JsonObject)[spec.timeoutKey] != JsonPrimitive(spec.timeout))
                    if (!stale) return@map h
                    changed += "~ $event${ClaudeHooks.show(matcher)} $want"
                    JsonObject((h as JsonObject) + mapOf("command" to JsonPrimitive(want), spec.timeoutKey to JsonPrimitive(spec.timeout)))
                },
            )
    }

    private fun fresh(
        matcher: String,
        want: String,
        spec: HookSpec,
    ): JsonObject {
        val hook = JsonObject(mapOf("type" to JsonPrimitive("command"), "command" to JsonPrimitive(want), spec.timeoutKey to JsonPrimitive(spec.timeout)))
        val head = if (matcher.isEmpty()) emptyMap() else mapOf("matcher" to JsonPrimitive(matcher))
        return JsonObject(head + ("hooks" to JsonArray(listOf(hook))))
    }

    private fun hasOurs(
        form: HookForm,
        entry: JsonElement,
        event: String,
        matcher: String?,
    ) = (matcher == null || matcherOf(entry) == matcher) && innerHooks(entry).orEmpty().any { h -> str(h, "command")?.let { form.isOurs(it, event) } == true }

    private fun summary(changed: List<String>): String {
        if (changed.isEmpty()) return NO_CHANGES
        val counts = listOf('+' to "additions", '~' to "updates", '-' to "removals").map { (p, w) -> changed.count { it.startsWith(p) } to w }
        return changed.joinToString("\n") + "\n" + counts.filter { it.first > 0 }.joinToString(", ") { "${it.first} ${it.second}" }
    }

    /** Drop rtok hooks on a pair [entries] does not list; emptied entries and event arrays go; one `- …` line each. */
    private fun prune(
        form: HookForm,
        hooks: JsonObject,
        entries: List<Pair<String, String>>,
        removed: MutableList<String>,
    ): Map<String, JsonElement> =
        hooks.entries
            .mapNotNull { (event, v) ->
                val arr = v as? JsonArray
                val before = removed.size
                val kept = arr?.map { pruneEntry(form, event, it, entries, removed) }
                when {
                    arr == null || removed.size == before -> event to v
                    else -> kept!!.filter(::keepsHooks).takeIf { it.isNotEmpty() }?.let { event to JsonArray(it) }
                }
            }.toMap()

    private fun pruneEntry(
        form: HookForm,
        event: String,
        entry: JsonElement,
        entries: List<Pair<String, String>>,
        removed: MutableList<String>,
    ): JsonElement {
        val matcher = matcherOf(entry)
        val inner = innerHooks(entry)
        if (inner == null || (event to matcher) in entries) return entry
        return withInner(
            entry,
            inner.filter { h ->
                val c = str(h, "command")
                val ours = c != null && form.isOurs(c, event)
                if (ours) removed += "- $event${ClaudeHooks.show(matcher)} $c"
                !ours
            },
        )
    }
}
