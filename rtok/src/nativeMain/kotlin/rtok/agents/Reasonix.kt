// Port of src/agents/reasonix/mod.rs: Reasonix keeps each MCP server as one `[[plugins]]` table (`name`, `command`,
// `args`) in `<[setup.reasonix] dir>/config.toml`, keyed by `name`. The file goes through [TomlDoc], so comments,
// providers and foreign plugins survive; remove takes rtok's table only as far as rtok wrote it (T246).
package rtok.agents

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
    ) = applyMcpOnly(cfg, mode, ::register, ::unregister)

    private fun isRtok(t: TomlTable) = t.str("name") == NAME

    private fun tables(doc: TomlDoc): List<TomlTable> {
        check(doc.shape(AOT) in setOf(Shape.Absent, Shape.ArrayOfTables)) { "`$AOT` is not an array of tables" }
        return doc.arrayOfTables(AOT)
    }

    private fun entry(cmd: String) = JsonObject(StdioMcp.entry(cmd, HOST) + ("name" to JsonPrimitive(NAME)))

    fun register(cfg: Config): String {
        val path = configPath(cfg)
        val doc = HostToml.load(path)
        val cmd = rtokMcpCommand()
        tables(doc).firstOrNull(::isRtok)?.let { return refresh(cfg, path, doc, it, cmd) }
        doc.push(AOT, listOf("name" to TomlValue.Str(NAME), "command" to TomlValue.Str(cmd), "args" to HostToml.strings(StdioMcp.args(HOST))))
        val report = "plugins.rtok: ${StdioMcp.summary(cmd, HOST)}"
        write(applyOf(cfg), path, doc.toString(), report)
        return report
    }

    /**
     * Point rtok's table at [cmd] when only its command went stale (the binary moved). A table whose other keys differ
     * from what rtok writes is the user's edit and stays as it is.
     */
    private fun refresh(
        cfg: Config,
        path: String,
        doc: TomlDoc,
        have: TomlTable,
        cmd: String,
    ): String {
        val want = entry(cmd)
        val now = HostToml.toJson(have)
        val rest = JsonObject(now - "command") == JsonObject(want - "command")
        if (now == want || !rest || have.str("command")?.let(::isRtokBin) != true) return NO_CHANGES
        have["command"] = TomlValue.Str(cmd)
        val report = "plugins.rtok: ${StdioMcp.summary(cmd, HOST)}"
        write(applyOf(cfg), path, doc.toString(), report)
        return report
    }

    fun unregister(cfg: Config): String {
        val path = configPath(cfg)
        val doc = HostToml.load(path)
        val have = tables(doc).firstOrNull(::isRtok) ?: return NO_CHANGES
        val leave = judgeOwned(applyOf(cfg), "plugins.rtok in $path", HostToml.toJson(have), entry("rtok"), ::isRtokBin)
        if (leave == null) {
            doc.retain(AOT) { !isRtok(it) }
            write(applyOf(cfg), path, doc.toString(), "- plugins.rtok")
        }
        return leave ?: "- plugins.rtok"
    }
}
