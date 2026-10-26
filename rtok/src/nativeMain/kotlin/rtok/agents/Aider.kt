// Port of src/agents/aider/mod.rs: aider reads `openai-api-base` from `~/.aider.conf.yml` (`[setup.aider] config_path`).
// The YAML is edited line by line: rtok's value is set in place (indent and trailing comment kept) or appended, and
// remove drops only a line whose value is the proxy URL or a loopback URL on the proxy port. `--proxy` gates install.
package rtok.agents

import rtok.agent.sdk.NO_CHANGES
import rtok.agent.sdk.write
import rtok.config.Config
import rtok.sys.exists
import rtok.sys.readToString

object Aider : Agent {
    private const val KEY = "openai-api-base"

    override val id = "aider"

    private fun keyLine(line: String) = YamlKeyLine.parse(line, KEY)

    override fun files(
        cfg: Config,
        kind: Kind,
    ) = listOf(cfg.setup.aider.configPath)

    override fun installed(
        cfg: Config,
        kind: Kind,
    ): List<String> {
        val ours = read(cfg).lines().mapNotNull(::keyLine).any { isOurs(it.value, cfg) }
        return if (ours) listOf("proxy") else emptyList()
    }

    override fun apply(
        cfg: Config,
        kind: Kind,
        mode: Mode,
    ): List<String> {
        val remove = mode == Mode.Remove
        return listOf(if (remove || cfg.setup.proxy) run(cfg, remove) else NO_CHANGES)
    }

    fun run(
        cfg: Config,
        remove: Boolean,
    ): String {
        val raw = read(cfg)
        val (body, report) = if (remove) strip(raw, cfg) else insert(raw, ProxyUrl.openai(cfg))
        write(applyOf(cfg), cfg.setup.aider.configPath, body, report)
        return report
    }

    private fun read(cfg: Config) =
        cfg.setup.aider.configPath
            .let { if (exists(it)) readToString(it) else "" }

    private fun isOurs(
        value: String,
        cfg: Config,
    ) = value == ProxyUrl.openai(cfg) || ProxyUrl.loopbackPort(value) == cfg.proxy.port.toInt()

    private fun lines(raw: String) = if (raw.isEmpty()) emptyList() else raw.removeSuffix("\n").split('\n')

    private fun insert(
        raw: String,
        url: String,
    ): Pair<String, String> {
        val all = lines(raw)
        val keys = all.mapNotNull(::keyLine)
        val old = keys.lastOrNull { it.value != url }?.value
        return when {
            old != null -> replace(raw, all, url) to "+ $KEY: $url\nrevert: set $KEY to $old"
            keys.isNotEmpty() -> raw to NO_CHANGES
            else -> "$raw${if (raw.isNotEmpty() && !raw.endsWith("\n")) "\n" else ""}$KEY: $url\n" to "+ $KEY: $url\nrevert: remove $KEY"
        }
    }

    /** Every other value set to [url], each line keeping its indent and trailing comment. */
    private fun replace(
        raw: String,
        all: List<String>,
        url: String,
    ): String {
        val out = all.map { l -> keyLine(l)?.takeIf { it.value != url }?.let { k -> "${k.indent}$KEY: $url${k.comment?.let { " $it" }.orEmpty()}" } ?: l }
        return out.joinToString("\n") + if (raw.endsWith("\n")) "\n" else ""
    }

    private fun strip(
        raw: String,
        cfg: Config,
    ): Pair<String, String> {
        val all = lines(raw)
        val keep = all.filter { l -> keyLine(l)?.let { !isOurs(it.value, cfg) } ?: true }
        if (keep.size == all.size) return raw to NO_CHANGES
        return (if (keep.isEmpty()) "" else keep.joinToString("\n") + "\n") to "- $KEY"
    }
}
