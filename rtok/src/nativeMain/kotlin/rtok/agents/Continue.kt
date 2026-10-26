// Port of src/agents/continue/mod.rs: the Continue IDE extensions and the `cn` CLI share `~/.continue/config.yaml`,
// whose `mcpServers` is a list of `{name, type, command, args}` items. Only the item named `rtok` is added, replaced
// or dropped (the header goes with its last item); every other byte stays ([YamlMcp]). MCP only.
package rtok.agents

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.agent.sdk.NO_CHANGES
import rtok.agent.sdk.runsBin
import rtok.config.Config
import yamlaot.YamlText
import yamlaot.YamlValue

object Continue : Agent {
    const val HOST = "continue"
    private const val KEY = "mcpServers"
    private const val AT = "$KEY.rtok"
    private val CORE = listOf("name", "type", "command", "args")

    override val id = HOST

    fun configPath(cfg: Config) = cfg.setup.`continue`.configPath

    override fun files(
        cfg: Config,
        kind: Kind,
    ) = listOf(configPath(cfg))

    override fun installed(
        cfg: Config,
        kind: Kind,
    ): List<String> {
        val ours =
            runCatching {
                val lines = YamlText.lines(YamlMcp.read(configPath(cfg)))
                locate(lines)?.rtok?.let { item(lines, it) }?.let { runsBin(it, ::isRtokBin) } == true
            }.getOrDefault(false)
        return if (ours) listOf("mcp") else emptyList()
    }

    override fun apply(
        cfg: Config,
        kind: Kind,
        mode: Mode,
    ) = applyMcpOnly(cfg, mode, ::register, ::unregister)

    private fun entry(cmd: String): JsonObject =
        JsonObject(
            mapOf(
                "name" to JsonPrimitive("rtok"),
                "type" to JsonPrimitive("stdio"),
                "command" to JsonPrimitive(cmd),
                "args" to JsonArray(StdioMcp.args(HOST).map(::JsonPrimitive)),
            ),
        )

    /** The `- name: rtok` item, [indent] columns in. */
    fun render(
        indent: Int,
        cmd: String,
        eol: String,
    ) = YamlMcp.render(indent, eol, "- name: rtok", listOf("type: stdio", "command: ${YamlText.scalar(cmd)}", "args: ${YamlMcp.flowList(StdioMcp.args(HOST))}"))

    /** `name`, `version` and `schema` are required, so a file rtok creates is one Continue accepts. */
    private fun fresh(
        cmd: String,
        eol: String,
    ) = "name: Local Config${eol}version: 1.0.0${eol}schema: v1$eol$KEY:$eol${render(2, cmd, eol)}"

    private class Found(
        val block: YamlText.Block,
        val emptyFlow: Boolean,
        val items: List<IntRange>,
        val rtok: IntRange?,
    )

    /** The block-style `mcpServers:` list by its lines; any other flow value on the key line is an error. */
    private fun locate(lines: List<String>): Found? {
        val b = YamlText.block(lines, KEY) ?: return null
        val emptyFlow = b.tail == "[]"
        check(emptyFlow || b.tail.isEmpty() || b.tail.startsWith("#")) { "`$KEY:` is not a block list (`${b.tail}`); add the rtok item by hand" }
        val starts =
            (b.header + 1 until b.end).filter { i ->
                val t = YamlText.text(lines[i])
                YamlText.indentOf(lines[i]) == b.childIndent && t.substring(b.childIndent).let { it == "-" || it.startsWith("- ") }
            }
        val items = starts.mapIndexed { n, s -> s..YamlText.lastContent(lines, s, starts.getOrElse(n + 1) { b.end }) }
        val rtok = items.firstOrNull { r -> (item(lines, r) as? JsonObject)?.get("name") == JsonPrimitive("rtok") }
        return Found(b, emptyFlow, items, rtok)
    }

    /** One list item as JSON; null when it is not a mapping. */
    private fun item(
        lines: List<String>,
        r: IntRange,
    ): JsonElement? = ((YamlMcp.parse(lines, r.first, r.last + 1) as? YamlValue.Seq)?.items?.firstOrNull() as? YamlValue.Map)?.let(YamlMcp::toJson)

    fun register(cfg: Config): String {
        val raw = YamlMcp.load(configPath(cfg), KEY, "a list") { it is YamlValue.Seq }
        val cmd = rtokMcpCommand()
        val eol = YamlText.eolOf(raw)
        val lines = YamlText.lines(raw)
        val f = if (raw.isBlank()) null else locate(lines)
        val rtok = f?.rtok
        val body =
            when {
                raw.isBlank() -> fresh(cmd, eol)
                f == null -> YamlText.splice(lines, lines.size, lines.size, "$KEY:$eol${render(2, cmd, eol)}", eol)
                rtok != null -> if (YamlMcp.core(item(lines, rtok), CORE) == YamlMcp.core(entry(cmd), CORE)) null else YamlText.splice(lines, rtok.first, rtok.last + 1, render(f.block.childIndent, cmd, eol), eol)
                f.emptyFlow -> YamlText.splice(lines, f.block.header, f.block.header + 1, "$KEY:$eol${render(2, cmd, eol)}", eol)
                else -> YamlText.splice(lines, f.block.end, f.block.end, render(f.block.childIndent, cmd, eol), eol)
            }
        return YamlMcp.register(applyOf(cfg), configPath(cfg), raw, body, "$AT: ${StdioMcp.summary(cmd, HOST)}")
    }

    fun unregister(cfg: Config): String {
        val raw = YamlMcp.load(configPath(cfg), KEY, "a list") { it is YamlValue.Seq }
        val lines = YamlText.lines(raw)
        val f = locate(lines)?.takeIf { it.rtok != null } ?: return NO_CHANGES
        val r = f.rtok!!
        // The header goes with its last item, so the file reads as before rtok arrived.
        val drop = if (f.items.size == 1) f.block.header until f.block.end else r
        return YamlMcp.remove(applyOf(cfg), configPath(cfg), AT, raw, YamlMcp.core(item(lines, r), CORE), entry("rtok"), drop)
    }
}
