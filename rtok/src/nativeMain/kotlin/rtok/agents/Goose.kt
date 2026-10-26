// Port of src/agents/goose/mod.rs: Goose's CLI and Desktop app share `config.yaml`, whose `extensions.<key>` are its
// MCP servers; a stdio one is `{name, type: stdio, cmd, args, enabled}`. Only the `rtok` block under `extensions:` is
// added, replaced or dropped (the header goes when it held nothing else); every other byte stays ([YamlMcp]). Hooks stay
// off until rtok has an adapter for Goose's payload. MCP only.
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

object Goose : Agent {
    const val HOST = "goose"
    private const val KEY = "extensions"
    private const val AT = "$KEY.rtok"

    /** Goose re-saves its own defaults into the entry (`envs`, `env_keys`, `timeout`, …); those are not an edit. */
    private val CORE = listOf("name", "type", "cmd", "args", "enabled")

    override val id = HOST

    fun configPath(cfg: Config) = cfg.setup.goose.configPath

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
                locate(lines)?.second?.let { block(lines, it) }?.let { runsBin(it, ::isRtokBin) } == true
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
                "cmd" to JsonPrimitive(cmd),
                "args" to JsonArray(StdioMcp.args(HOST).map(::JsonPrimitive)),
                "enabled" to JsonPrimitive(true),
            ),
        )

    /** The `rtok:` block, [indent] columns in. */
    fun render(
        indent: Int,
        cmd: String,
        eol: String,
    ) = YamlMcp.render(indent, eol, "rtok:", listOf("name: rtok", "type: stdio", "cmd: ${YamlText.scalar(cmd)}", "args: ${YamlMcp.flowList(StdioMcp.args(HOST))}", "enabled: true"))

    /** The block-style `extensions:` mapping and its `rtok` child's lines; a flow value on the key line is an error. */
    private fun locate(lines: List<String>): Pair<YamlText.Block, IntRange?>? {
        val b = YamlText.block(lines, KEY) ?: return null
        check(b.tail.isEmpty() || b.tail.startsWith("#")) { "`$KEY:` is not a block mapping (`${b.tail}`); add $AT by hand" }
        val start =
            (b.header + 1 until b.end).firstOrNull { i ->
                val t = YamlText.text(lines[i])
                YamlText.indentOf(lines[i]) == b.childIndent && listOf("rtok:", "\"rtok\":", "'rtok':").any { t.substring(b.childIndent).startsWith(it) }
            }
        val range =
            start?.let { s ->
                val stop = (s + 1 until b.end).firstOrNull { !YamlText.skippable(lines[it]) && YamlText.indentOf(lines[it]) <= b.childIndent } ?: b.end
                s..YamlText.lastContent(lines, s, stop)
            }
        return b to range
    }

    private fun block(
        lines: List<String>,
        r: IntRange,
    ): JsonElement {
        val v = (YamlMcp.parse(lines, r.first, r.last + 1) as? YamlValue.Map)?.get("rtok")
        check(v != null && v != YamlValue.Null) { "$AT is not readable YAML; remove it by hand" }
        return YamlMcp.toJson(v)
    }

    fun register(cfg: Config): String {
        val raw = YamlMcp.load(configPath(cfg), KEY, "a mapping") { it is YamlValue.Map }
        val cmd = rtokMcpCommand()
        val eol = YamlText.eolOf(raw)
        val lines = YamlText.lines(raw)
        val found = locate(lines)
        val r = found?.second
        val body =
            when {
                found == null -> YamlText.splice(lines, lines.size, lines.size, "$KEY:$eol${render(2, cmd, eol)}", eol)
                r == null -> YamlText.splice(lines, found.first.end, found.first.end, render(found.first.childIndent, cmd, eol), eol)
                YamlMcp.core(block(lines, r), CORE) == YamlMcp.core(entry(cmd), CORE) -> null
                else -> YamlText.splice(lines, r.first, r.last + 1, render(YamlText.indentOf(lines[r.first]), cmd, eol), eol)
            }
        return YamlMcp.register(applyOf(cfg), configPath(cfg), raw, body, "$AT: ${StdioMcp.summary(cmd, HOST)}")
    }

    fun unregister(cfg: Config): String {
        val raw = YamlMcp.load(configPath(cfg), KEY, "a mapping") { it is YamlValue.Map }
        val lines = YamlText.lines(raw)
        val (b, found) = locate(lines)?.takeIf { it.second != null } ?: return NO_CHANGES
        val r = found!!
        // The header goes with its last child, so the file reads as before rtok arrived.
        val alone = (b.header + 1 until b.end).filter { it !in r }.all { YamlText.text(lines[it]).isBlank() }
        val drop = if (alone) b.header until b.end else r
        return YamlMcp.remove(applyOf(cfg), configPath(cfg), AT, raw, YamlMcp.core(block(lines, r), CORE), entry("rtok"), drop)
    }
}
