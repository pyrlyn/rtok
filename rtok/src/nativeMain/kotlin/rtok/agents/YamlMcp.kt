// Shared by the hosts whose MCP entry lives in a YAML file the user and the host both edit (Continue, Goose): the file
// is read with `:yaml-aot` to refuse an unreadable document and to judge rtok's entry, and only rtok's lines are
// swapped (`YamlText`), so every other byte stays. Port of the reading half of agents/yaml_text.rs and the
// `serde_saphyr` reads in continue/goose.
package rtok.agents

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.agent.sdk.Apply
import rtok.agent.sdk.NO_CHANGES
import rtok.agent.sdk.judgeOwned
import rtok.agent.sdk.write
import rtok.sys.exists
import rtok.sys.readToString
import yamlaot.Yaml
import yamlaot.YamlAotException
import yamlaot.YamlText
import yamlaot.YamlValue

object YamlMcp {
    /** The file's text; absent is empty, unreadable is an error. */
    fun read(path: String): String = if (exists(path)) readToString(path) else ""

    /** The whole document is readable and its top-level [key] is absent, null, or of the shape [ok] accepts. */
    fun validate(
        raw: String,
        path: String,
        key: String,
        what: String,
        ok: (YamlValue) -> Boolean,
    ) {
        if (raw.isBlank()) return
        val root =
            try {
                Yaml.parse(raw)
            } catch (e: YamlAotException) {
                throw IllegalStateException("$path is not valid YAML; fix it first (${e.message})", e)
            }
        val v = (root as? YamlValue.Map)?.get(key) ?: YamlValue.Null
        check(v == YamlValue.Null || ok(v)) { "$path: `$key` is not $what" }
    }

    /** [read] then [validate]: the text of a file whose top-level [key] is of the shape [ok] accepts. */
    fun load(
        path: String,
        key: String,
        what: String,
        ok: (YamlValue) -> Boolean,
    ): String = read(path).also { validate(it, path, key, what, ok) }

    /** [lines] `[from, to)` read as one value, or null when they do not parse. */
    fun parse(
        lines: List<String>,
        from: Int,
        to: Int,
    ): YamlValue? = runCatching { Yaml.parse(lines.subList(from, to).joinToString("")) }.getOrNull()

    fun toJson(v: YamlValue?): JsonElement =
        when (v) {
            null, YamlValue.Null -> JsonNull
            is YamlValue.Bool -> JsonPrimitive(v.value)
            is YamlValue.Int -> JsonPrimitive(v.value)
            is YamlValue.Float -> v.text.toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(v.text)
            is YamlValue.Str -> JsonPrimitive(v.value)
            is YamlValue.Seq -> JsonArray(v.items.map(::toJson))
            is YamlValue.Map -> JsonObject(v.entries.mapValues { toJson(it.value) })
        }

    /** Only the [keys] that decide ownership; the host's or user's other keys are not an edit. */
    fun core(
        v: JsonElement?,
        keys: List<String>,
    ): JsonObject = JsonObject((v as? JsonObject).orEmpty().filterKeys { it in keys })

    /** One block, [indent] columns in: [head] then each of [fields] two deeper, every line ended by [eol]. */
    fun render(
        indent: Int,
        eol: String,
        head: String,
        fields: List<String>,
    ): String = (listOf(" ".repeat(indent) + head) + fields.map { " ".repeat(indent + 2) + it }).joinToString("") { it + eol }

    /** `[a, b]` with each item as a YAML scalar. */
    fun flowList(items: List<String>) = items.joinToString(", ", "[", "]") { YamlText.scalar(it) }

    /**
     * Drop `[from, to)` only as far as rtok wrote it: [judgeOwned] over [have] against [ours]; [drop] is the range that
     * actually goes (the header too when it held nothing else).
     */
    fun remove(
        apply: Apply,
        path: String,
        at: String,
        raw: String,
        have: JsonElement,
        ours: JsonElement,
        drop: IntRange,
    ): String {
        judgeOwned(apply, "$at in $path", have, ours, ::isRtokBin)?.let { return it }
        val lines = YamlText.lines(raw)
        val report = "- $at"
        write(apply, path, YamlText.splice(lines, drop.first, drop.last + 1, "", YamlText.eolOf(raw)), report)
        return report
    }

    /** Write [body] with the `<at>: <summary>` report, or no change when it equals [raw]. */
    fun register(
        apply: Apply,
        path: String,
        raw: String,
        body: String?,
        report: String,
    ): String {
        if (body == null || body == raw) return NO_CHANGES
        write(apply, path, body, report)
        return report
    }
}
