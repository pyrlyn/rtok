// Port of crates/rtok-agent-sdk/src/lib.rs (JSON host files): `read_json`, `write_json`, `edit_json`,
// `register_server`, `unregister_server`, `unregister_owned`.
package rtok.agent.sdk

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import rtok.sys.exists
import rtok.sys.readToString

object HostJson {
    /** Two-space pretty JSON, the shape host files carry (serde `to_string_pretty`). */
    @OptIn(ExperimentalSerializationApi::class)
    val pretty =
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }

    /** The JSON object at [path]; `{}` when absent or empty; a non-object root reads as `{}` too. */
    fun read(path: String): JsonObject {
        if (!exists(path)) return JsonObject(emptyMap())
        return parseStrictJson(path, readToString(path)) as? JsonObject ?: JsonObject(emptyMap())
    }

    /** [write] for a JSON document: pretty-printed with the trailing newline host files carry. */
    fun write(
        apply: Apply,
        path: String,
        root: JsonElement,
        report: String,
    ) {
        if (!apply.writes(report)) return
        write(apply, path, pretty.encodeToString(JsonElement.serializer(), root) + "\n", report)
    }

    /** Read [path], let [edit] return the new root and its report, write it when the report says so. */
    fun edit(
        apply: Apply,
        path: String,
        edit: (JsonObject) -> Pair<JsonObject, String>,
    ): String {
        val (root, report) = edit(read(path))
        write(apply, path, root, report)
        return report
    }

    /** `<key>.<name> = entry` (dotted [key] walks nested objects, created as needed); [summary] follows the key in the report. */
    fun registerServer(
        apply: Apply,
        path: String,
        key: String,
        name: String,
        entry: JsonObject,
        summary: String,
    ): String =
        edit(apply, path) { root ->
            val keys = key.split('.')
            val have = keys.fold<String, JsonElement?>(root) { o, k -> (o as? JsonObject)?.get(k) }.let { (it as? JsonObject)?.get(name) }
            if (have != null && withoutDefaultType(have) == withoutDefaultType(entry)) {
                root to NO_CHANGES
            } else {
                setIn(root, keys, name, entry) to "$key.$name: $summary"
            }
        }

    /** Drop `<key>.<name>` only as far as rtok wrote it ([ours]); the ownership call is [judgeOwned]. */
    fun unregisterOwned(
        apply: Apply,
        path: String,
        key: String,
        name: String,
        ours: JsonElement,
        isBin: IsBin,
    ): String {
        val have = key.split('.').fold<String, JsonElement?>(read(path)) { o, k -> (o as? JsonObject)?.get(k) }.let { (it as? JsonObject)?.get(name) }
        return when {
            have == null -> NO_CHANGES
            else -> judgeOwned(apply, "$key.$name in $path", have, ours, isBin) ?: unregisterServer(apply, path, key, name)
        }
    }

    /** Remove `<key>.<name>` by name alone; the last level goes too when it ends up empty. */
    private fun unregisterServer(
        apply: Apply,
        path: String,
        key: String,
        name: String,
    ): String =
        edit(apply, path) { root ->
            val keys = key.split('.')
            val servers = keys.fold<String, JsonElement?>(root) { o, k -> (o as? JsonObject)?.get(k) } as? JsonObject
            if (servers == null || name !in servers) {
                root to NO_CHANGES
            } else {
                removeIn(root, keys, name) to "- $key.$name"
            }
        }

    private fun setIn(
        node: JsonObject,
        keys: List<String>,
        name: String,
        entry: JsonElement,
    ): JsonObject {
        if (keys.isEmpty()) return JsonObject(node + (name to entry))
        val child = node[keys[0]] as? JsonObject ?: JsonObject(emptyMap())
        return JsonObject(node + (keys[0] to setIn(child, keys.drop(1), name, entry)))
    }

    private fun removeIn(
        node: JsonObject,
        keys: List<String>,
        name: String,
    ): JsonObject {
        val head = keys[0]
        val child = node.getValue(head) as JsonObject
        val next = if (keys.size == 1) JsonObject(child - name) else removeIn(child, keys.drop(1), name)
        return JsonObject(if (keys.size == 1 && next.isEmpty()) node - head else node + (head to next))
    }
}
