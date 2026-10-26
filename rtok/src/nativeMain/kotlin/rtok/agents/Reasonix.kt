// Port of src/agents/reasonix/mod.rs: Reasonix keeps each MCP server as one `[[plugins]]` table (`name`, `command`,
// `args`) in `<[setup.reasonix] dir>/config.toml`, keyed by `name`. The file goes through [TomlDoc], so comments,
// providers and foreign plugins survive; remove takes rtok's table only as far as rtok wrote it (T246).
package rtok.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.agent.sdk.NO_CHANGES
import rtok.agent.sdk.judgeOwned
import rtok.agent.sdk.write
import rtok.config.Config
import tomlaot.Shape
import tomlaot.TomlDoc
import tomlaot.TomlTable
import tomlaot.TomlValue

object Reasonix : Agent {
    const val HOST = "reasonix"
    private const val NAME = "rtok"
    private const val AOT = "plugins"

    override val id = HOST

    fun configPath(cfg: Config) = join(cfg.setup.reasonix.dir, "config.toml")

    override fun files(
        cfg: Config,
        kind: Kind,
    ) = listOf(configPath(cfg))

    override fun installed(
        cfg: Config,
        kind: Kind,
    ): List<String> {
        val has = runCatching { tables(HostToml.load(configPath(cfg))).any(::isRtok) }.getOrDefault(false)
        return if (has) listOf("mcp") else emptyList()
    }

    override fun apply(
        cfg: Config,
        kind: Kind,
        mode: Mode,
    ): List<String> =
        listOf(
            when {
                mode == Mode.Remove -> unregister(cfg)
                cfg.setup.mcp -> register(cfg)
                else -> NO_CHANGES
            },
        )

    private fun isRtok(t: TomlTable) = t.str("name") == NAME

    private fun tables(doc: TomlDoc): List<TomlTable> {
        check(doc.shape(AOT) in setOf(Shape.Absent, Shape.ArrayOfTables)) { "`$AOT` is not an array of tables" }
        return doc.arrayOfTables(AOT)
    }

    private fun entry(cmd: String) = JsonObject(StdioMcp.entry(cmd, HOST) + ("name" to JsonPrimitive(NAME)))

    fun register(cfg: Config): String {
        val path = configPath(cfg)
        val doc = HostToml.load(path)
        if (tables(doc).any(::isRtok)) return NO_CHANGES
        val cmd = rtokMcpCommand()
        val args = StdioMcp.args(HOST).joinToString(", ", "[", "]") { TomlValue.quote(it) }
        doc.push(AOT, listOf("name" to TomlValue.Str(NAME), "command" to TomlValue.Str(cmd), "args" to TomlValue.Raw(args)))
        val report = "plugins.rtok: ${StdioMcp.summary(cmd, HOST)}"
        write(applyOf(cfg), path, doc.toString(), report)
        return report
    }

    fun unregister(cfg: Config): String {
        val path = configPath(cfg)
        val doc = HostToml.load(path)
        val have = tables(doc).firstOrNull(::isRtok) ?: return NO_CHANGES
        val leave = judgeOwned(applyOf(cfg), "plugins.rtok in $path", toJson(have), entry("rtok"), ::isRtokBin)
        if (leave == null) {
            doc.retain(AOT) { !isRtok(it) }
            write(applyOf(cfg), path, doc.toString(), "- plugins.rtok")
        }
        return leave ?: "- plugins.rtok"
    }

    /** The table as JSON for the ownership check: scalars as they are, raw values (arrays) parsed when they are JSON. */
    private fun toJson(t: TomlTable): JsonObject =
        JsonObject(
            t.keys().associateWith { k ->
                when (val v = t[k]) {
                    is TomlValue.Str -> JsonPrimitive(v.value)
                    is TomlValue.Int -> JsonPrimitive(v.value)
                    is TomlValue.Bool -> JsonPrimitive(v.value)
                    is TomlValue.Raw -> runCatching { Json.parseToJsonElement(v.text) }.getOrDefault(JsonPrimitive(v.text)) as JsonElement
                    null -> JsonPrimitive(null as String?)
                }
            },
        )
}
