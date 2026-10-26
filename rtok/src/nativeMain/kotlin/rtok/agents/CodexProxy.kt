// Port of src/agents/codex/mod.rs `register_proxy`, `insert_proxy`, `strip_proxy`, `replaced_provider` (T11.5, T307):
// `model_provider = "rtok"` plus `[model_providers.rtok] {name, base_url}` pointing Codex at the proxy. The value rtok
// replaced is kept as a `# rtok: was "<value>"` comment on that line, so remove puts it back with no state outside
// Codex's own config.
package rtok.agents

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.agent.sdk.Apply
import rtok.agent.sdk.NO_CHANGES
import rtok.agent.sdk.keepEdited
import rtok.agent.sdk.write
import rtok.config.Config
import tomlaot.Shape
import tomlaot.TomlDoc
import tomlaot.TomlValue
import tomlaot.comment

object CodexProxy {
    private const val NAME = "rtok"
    private const val KEY = "model_provider"
    private const val TABLE = "model_providers.$NAME"
    private const val WAS = "# rtok: was "

    /** Point (or stop pointing) Codex's `model_provider` at this proxy. */
    fun run(
        cfg: Config,
        remove: Boolean,
    ): String {
        val path = cfg.setup.codex.configPath
        val doc = HostToml.load(path)
        val url = ProxyUrl.openai(cfg)
        val report = if (remove) strip(applyOf(cfg), path, doc, url) else insert(doc, url)
        write(applyOf(cfg), path, doc.toString(), report)
        return report
    }

    private fun entry(url: String) = JsonObject(mapOf("name" to JsonPrimitive(NAME), "base_url" to JsonPrimitive(url)))

    private fun insert(
        doc: TomlDoc,
        url: String,
    ): String {
        check(doc.shape("model_providers") in setOf(Shape.Absent, Shape.Table)) { "model_providers is not a table" }
        val old = doc.root.str(KEY)
        val ours = doc.table(TABLE)?.let(HostToml::toJson) == entry(url)
        if (old == NAME && ours) return NO_CHANGES
        // A re-install keeps the value the first install replaced.
        val was = if (old == NAME) replaced(doc) else old
        doc.root.set(KEY, TomlValue.Str(NAME), was?.let { WAS + TomlValue.quote(it) })
        doc.removeTable(TABLE)
        val t = doc.ensureTable(TABLE)
        t["name"] = TomlValue.Str(NAME)
        t["base_url"] = TomlValue.Str(url)
        val revert = was?.let { "revert: set $KEY to $it" } ?: "revert: remove [$TABLE]"
        return "+ [$TABLE]\nbase_url = \"$url\"\n$revert"
    }

    /** The value rtok replaced, read back from its [WAS] comment. */
    private fun replaced(doc: TomlDoc): String? {
        val text =
            doc.root
                .comment(KEY)
                ?.removePrefix(WAS)
                ?.takeIf { it != doc.root.comment(KEY) } ?: return null
        return (TomlValue.parse(text) as? TomlValue.Str)?.value
    }

    /**
     * Undo [insert] only as far as rtok wrote it (T307): a table the user changed stays (with `model_provider`) unless
     * [keepEdited] says remove; a replaced `model_provider` comes back, one rtok added goes.
     */
    private fun strip(
        apply: Apply,
        path: String,
        doc: TomlDoc,
        url: String,
    ): String {
        val have = doc.table(TABLE)?.let(HostToml::toJson)
        if (have != null && have != entry(url)) keepEdited(apply, "$TABLE in $path")?.let { return it }
        val key = doc.root.str(KEY) == NAME
        val restored = if (key) replaced(doc) else null
        when {
            restored != null -> doc.root.set(KEY, TomlValue.Str(restored), null)
            key -> doc.root.remove(KEY)
        }
        val table = doc.removeTable(TABLE)
        return when {
            restored != null -> "- [$TABLE]\nrestore $KEY = ${TomlValue.quote(restored)}"
            key || table -> "- [$TABLE]"
            else -> NO_CHANGES
        }
    }
}
