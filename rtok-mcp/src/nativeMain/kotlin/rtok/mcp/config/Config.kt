// Port of crates/rtok-mcp/src/config.rs: read, write and remove one MCP entry in a host's config file, whatever
// shape the host uses (strict JSON, JSONC with comments kept, TOML with formatting kept). Schema-less: only
// `keyPath.<server.name>` is read or written; every other key stays as it was.
package rtok.mcp.config

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import rtok.agent.sdk.parseStrictJson
import rtok.agent.sdk.withoutDefaultType
import rtok.config.Toml
import rtok.config.TomlException

/** The disk (or an in-memory stand-in) every config read and write goes through. */
interface McpFs {
    /** Text at [path], or `null` if it does not exist. */
    fun read(path: String): String?

    /** [read] for a caller about to edit: throws when the file exists but cannot be read (T652). */
    fun tryRead(path: String): String? = read(path)

    /** Overwrite (or create) [path]. */
    fun write(
        path: String,
        text: String,
    )

    /** Best-effort copy of [path] before it changes; a no-op only suits a throwaway Fs. */
    fun backup(path: String) = Unit
}

/** A config that is unreadable, not of its format, or has a non-object at the key path. */
class McpConfigException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Whether a write changed the file. */
enum class Written { Changed, Unchanged }

object McpConfig {
    /** The entry at `keyPath.<name>`, or `null` if the file, the table or the name is absent. */
    fun readEntry(
        fs: McpFs,
        spec: McpSpec,
    ): JsonElement? = readServers(fs, spec)?.get(spec.server.name)

    /** Every server in the table at `keyPath`, or `null` if the file or table is absent. */
    fun readServers(
        fs: McpFs,
        spec: McpSpec,
    ): JsonObject? {
        val text = fs.tryRead(spec.configPath) ?: return null
        val root = parse(spec, text)
        return spec.keyPath.fold<String, JsonElement?>(root) { node, k -> (node as? JsonObject)?.get(k) } as? JsonObject
    }

    /** Write [entry] at `keyPath.<name>`; `Unchanged` when it already holds it (a type-only difference counts as same, T265). */
    fun writeEntry(
        fs: McpFs,
        spec: McpSpec,
        entry: JsonObject,
    ): Written {
        val have = readEntry(fs, spec)
        if (have != null && withoutDefaultType(have) == withoutDefaultType(entry)) return Written.Unchanged
        edit(fs, spec, entry)
        return Written.Changed
    }

    /** Remove the entry; `Unchanged` when there was nothing to remove. */
    fun removeEntry(
        fs: McpFs,
        spec: McpSpec,
    ): Written {
        if (readEntry(fs, spec) == null) return Written.Unchanged
        edit(fs, spec, null)
        return Written.Changed
    }

    private fun parse(
        spec: McpSpec,
        text: String,
    ): JsonElement {
        val path = spec.configPath
        return when (spec.format) {
            Format.Json -> {
                parseStrictJson(path, text)
            }

            Format.Jsonc -> {
                if (text.isBlank()) {
                    JsonObject(emptyMap())
                } else {
                    runCatching { Jsonc.parse(text) }.getOrElse { throw McpConfigException("$path: ${it.message}", it) }
                }
            }

            Format.Toml -> {
                try {
                    Toml.parse(text)
                } catch (e: TomlException) {
                    throw McpConfigException("$path: not valid TOML: ${e.message}", e)
                }
            }
        }
    }

    private fun edit(
        fs: McpFs,
        spec: McpSpec,
        entry: JsonObject?,
    ) {
        val old = fs.tryRead(spec.configPath)
        val name = spec.server.name
        val body =
            when (spec.format) {
                Format.Json -> {
                    writeJson(spec, old, entry)
                }

                Format.Jsonc -> {
                    JsoncEdit.set(spec.configPath, old.orEmpty(), spec.keyPath, name, entry)
                }

                Format.Toml -> {
                    val root = old?.let { parse(spec, it) }
                    spec.keyPath
                        .runningFold<String, JsonElement?>(root) { n, k -> (n as? JsonObject)?.get(k) }
                        .drop(1)
                        .zip(spec.keyPath)
                        .firstOrNull { (n, _) -> n != null && n !is JsonObject }
                        ?.let { (_, k) -> throw McpConfigException("${spec.configPath}: $k is not a table") }
                    TomlEdit.set(spec.configPath, old.orEmpty(), spec.keyPath, name, entry)
                }
            }
        fs.backup(spec.configPath)
        fs.write(spec.configPath, body)
    }

    internal val pretty = rtok.agent.sdk.HostJson.pretty

    private fun writeJson(
        spec: McpSpec,
        old: String?,
        entry: JsonObject?,
    ): String {
        val root = old?.let { parseStrictJson(spec.configPath, it) } ?: JsonObject(emptyMap())
        val next =
            setAt(root, spec.keyPath, spec.server.name, entry)
                ?: throw McpConfigException("${spec.configPath}: the root or ${spec.keyPath.joinToString(".")} is not an object")
        return pretty.encodeToString(JsonElement.serializer(), next) + "\n"
    }

    /**
     * [node] with `name` set (or removed) under [keyPath]; creates intermediate objects for a write and drops ones a
     * remove empties. `null` when a node on the way is not an object: the user's value is never replaced.
     */
    private fun setAt(
        node: JsonElement,
        keyPath: List<String>,
        name: String,
        entry: JsonObject?,
    ): JsonObject? {
        val obj = node as? JsonObject
        val head = keyPath.firstOrNull()
        return when {
            obj == null -> {
                null
            }

            head == null -> {
                JsonObject(if (entry != null) obj + (name to entry) else obj - name)
            }

            entry == null && head !in obj -> {
                obj
            }

            else -> {
                setAt(obj[head] ?: JsonObject(emptyMap()), keyPath.drop(1), name, entry)?.let { child ->
                    JsonObject(if (entry == null && child.isEmpty()) obj - head else obj + (head to child))
                }
            }
        }
    }
}
