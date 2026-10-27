// Port of web/src/pages/text.ts (hosts, config) and the state helpers of Hosts.tsx and Config.tsx: the
// Snapshot fields that are plain CLI text on the wire. A line a parser does not understand is kept (`other`).
package rtok.web.ui

fun lines(t: String) = t.split("\n")

/** One host variant block of `rtok agents list` (text.ts `HostBlock`). */
data class HostBlock(
    val kind: String,
    val name: String,
    val note: String,
    val app: String? = null,
    val config: List<String> = emptyList(),
    val skip: String? = null,
    val modules: List<Pair<String, String>> = emptyList(),
)

/** text.ts `HostsView`. */
data class HostsView(
    val probing: Boolean,
    val blocks: List<HostBlock>,
    val other: List<String>,
)

private val HOST_HEAD = Regex("""^(CLI|Desktop): (.+?)(?: — (.+))?$""")
private val HOST_ROW = Regex("""^ {2}(?:[✓✗−] )?(\S+)\s+(.*)$""")

private fun HostBlock.with(
    key: String,
    value: String,
): HostBlock =
    when (key) {
        "app" -> copy(app = value.takeIf { it != "-" })
        "config" -> copy(config = value.split(", "))
        "skip" -> copy(skip = value)
        else -> copy(modules = modules + (key to value))
    }

/** text.ts `parseHosts`; the trailing `junk` section belongs to the junk card and is skipped. */
fun parseHosts(text: String): HostsView {
    if (text.startsWith("probing hosts")) return HostsView(true, emptyList(), emptyList())
    val blocks = ArrayList<HostBlock>()
    val other = ArrayList<String>()
    val body = lines(text).filter { it.isNotBlank() }.takeWhile { it != "junk" }
    for (l in body) {
        val h = HOST_HEAD.find(l)
        val m = HOST_ROW.find(l)
        when {
            h != null -> blocks += h.groupValues.let { g -> HostBlock(g[1], g[2], g.last()) }
            m == null || blocks.isEmpty() -> other += l
            else -> blocks[blocks.lastIndex] = blocks.last().with(m.groupValues[1], m.groupValues[2])
        }
    }
    return HostsView(false, blocks, other)
}

/** A pill: tone, label and the rest of the row (Hosts.tsx `moduleState`). */
data class Tagged(
    val tone: String,
    val label: String,
    val rest: String = "",
)

private val MODULE_PREFIXES = listOf("installed" to "ok", "not installed" to "warn", "not supported" to "muted")

/** Hosts.tsx `moduleState`: a plugin on another version than rtok is `outdated`, or `newer` when nothing needs updating. */
fun moduleState(value: String): Tagged {
    val rest = if (value.startsWith("installed")) value.removePrefix("installed").trim() else ""
    if (", rtok is " in rest) {
        return if (" — rtok agents update " in rest) Tagged("warn", "outdated", rest) else Tagged("ok", "newer", rest)
    }
    val hit = MODULE_PREFIXES.firstOrNull { (prefix, _) -> value.startsWith(prefix) }
    return hit?.let { (prefix, tone) -> Tagged(tone, prefix, value.removePrefix(prefix).trim()) } ?: Tagged("muted", value)
}

/** Hosts.tsx `hostNote`. */
fun hostNote(note: String): Tagged =
    when (note) {
        "not found" -> Tagged("muted", "not found")
        "not installed" -> Tagged("warn", "not installed")
        else -> Tagged("ok", note.ifEmpty { "present" })
    }

val CONFIG_SOURCES = listOf("default", "user", "project", "env", "flag")

/** One `key = value (source)` row of `rtok config show --sources`. */
data class ConfigEntry(
    val key: String,
    val value: String,
    val source: String,
)

private val CONFIG_ROW = Regex("""^(\S+) = (.*) \((\w+)\)$""")

/** text.ts `parseConfig`. */
fun parseConfig(text: String): List<ConfigEntry> = lines(text).mapNotNull { l -> CONFIG_ROW.find(l)?.destructured?.let { (k, v, s) -> ConfigEntry(k, v, s) } }

/** text.ts `matchesConfig`. */
fun matchesConfig(
    e: ConfigEntry,
    source: String,
    query: String,
): Boolean {
    val q = query.trim().lowercase()
    return (source == "all" || e.source == source) && (q.isEmpty() || q in "${e.key} ${e.value}".lowercase())
}

/** text.ts `groupConfig`: by table, in first-seen order. */
fun groupConfig(rows: List<ConfigEntry>): List<Pair<String, List<ConfigEntry>>> = rows.groupBy { it.key.substringBefore('.') }.toList()

/** Config.tsx `sourceTone`: process-only layers (env, flag) read as a warning. */
fun sourceTone(s: String): String =
    when (s) {
        "default" -> "muted"
        "env", "flag" -> "warn"
        else -> "info"
    }
