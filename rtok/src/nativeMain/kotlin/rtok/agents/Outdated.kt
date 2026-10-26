// Port of src/agents/outdated.rs: `rtok agents outdated` / `rtok agents update --check` (T279.1) and `plugin_status`,
// the installed plugin's version and source on the `plugin` row of `agents list` and in its JSON rows (T382).
package rtok.agents

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.Col
import rtok.VERSION
import rtok.config.Config
import rtok.sys.isFile
import rtok.table
import semver.VersionKey

/** Host / variant selection: the same flags as `agents update`. */
data class OutdatedSelection(
    val hosts: List<String> = emptyList(),
    val cli: Boolean = false,
    val desktop: Boolean = false,
    val all: Boolean = false,
)

/** One outdated plugin row. */
data class OutdatedRow(
    val agent: String,
    val variant: String,
    val installed: String,
    val available: String,
    val source: String,
    val legacy: Boolean,
)

data class OutdatedReport(
    val rtok: String,
    val outdated: List<OutdatedRow>,
    val installed: Int,
)

/** A host variant's installed plugin; [unversioned]: no version recorded anywhere (the install predates T279). */
data class FoundPlugin(
    val got: Installed,
    val unversioned: Boolean,
)

/** The `plugin` row's note and the JSON `plugin_*` fields; [version]/[source] are null for a legacy install. */
data class PluginStatus(
    val version: String?,
    val source: String?,
    val note: String,
)

object Outdated {
    /** `--exit-code`'s status when at least one plugin is behind the running rtok. */
    const val EXIT_OUTDATED = 10
    const val NO_PLUGINS = "no rtok plugins installed"
    const val LEGACY_NOTE = "(legacy, no version)"

    private fun key(v: String) = VersionKey.parse(v)

    /** Precedence order: build metadata ignored; a non-SemVer side sorts first. */
    private fun cmp(
        a: String,
        b: String,
    ) = compareValues(key(a), key(b))

    fun isBehind(
        installed: String,
        target: String,
    ) = cmp(target, installed) > 0

    fun report(
        cfg: Config,
        sel: OutdatedSelection,
        target: String = VERSION,
        agent: (String) -> Agent? = Agents::agent,
    ): OutdatedReport {
        val receipt = Receipt.read(PluginVersion.receiptPath(cfg))
        val ids =
            sel.hosts.ifEmpty { Hosts.ALL.map { it.id } }.onEach { h ->
                if (Hosts.host(h) == null) throw AgentsFailure("unknown host: $h")
            }
        var installed = 0
        val rows =
            ids.flatMap { id ->
                val host = Hosts.host(id) ?: return@flatMap emptyList()
                val a = agent(id) ?: return@flatMap emptyList()
                host.variants.filter { Hosts.wants(it.kind, sel.cli, sel.desktop, sel.all) }.mapNotNull { v ->
                    val found = installedPlugin(a, host, v.kind, cfg, receipt) ?: return@mapNotNull null
                    installed++
                    row(id, v.kind, found, target)
                }
            }
        return OutdatedReport(target, rows, installed)
    }

    private fun row(
        agent: String,
        kind: Kind,
        found: FoundPlugin,
        target: String,
    ): OutdatedRow? {
        if (!isBehind(found.got.version, target)) return null
        return OutdatedRow(agent, kind.word, if (found.unversioned) "legacy" else found.got.version, target, found.got.source.toString(), found.unversioned)
    }

    /** The lookup shared by `agents outdated` and the `plugin` row: null when the variant has no rtok plugin. */
    fun installedPlugin(
        a: Agent,
        host: Host,
        kind: Kind,
        cfg: Config,
        receipt: Receipt,
    ): FoundPlugin? {
        if ("plugin" !in a.installed(cfg, kind) || host.support(kind, "plugin") is Support.No) return null
        val probe = PluginInstall.probe(a.id, cfg, kind, receipt) ?: InstallProbe("\u0000", null, Source.Github)
        val file = probe.versionFile.takeIf(::isFile)?.let(VersionFile::read)
        val got = PluginVersion.installed(file, receipt.get(a.id), probe.hostRecordVersion, probe.fallbackSource)
        val recorded = receipt.get(a.id) != null || probe.hostRecordVersion?.let(::key) != null
        return FoundPlugin(got, got.legacy && !recorded)
    }

    /** The installed plugin's version and source; null without a plugin and when the lookup fails (a listing never errors). */
    fun pluginStatus(
        a: Agent,
        kind: Kind,
        cfg: Config,
        target: String = VERSION,
    ): PluginStatus? {
        val receipt = runCatching { Receipt.read(PluginVersion.receiptPath(cfg)) }.getOrDefault(Receipt())
        val found = Hosts.host(a.id)?.let { host -> runCatching { installedPlugin(a, host, kind, cfg, receipt) }.getOrNull() }
        return found?.let { status(a.id, it, target) }
    }

    private fun status(
        host: String,
        found: FoundPlugin,
        target: String,
    ): PluginStatus {
        if (found.unversioned) return PluginStatus(null, null, LEGACY_NOTE)
        val (version, source) = found.got.version to found.got.source.toString()
        val c = cmp(version, target)
        val note =
            when {
                c == 0 -> "$version ($source)"
                c < 0 -> "$version ($source), rtok is $target — rtok agents update $host"
                else -> "$version ($source), rtok is $target"
            }
        return PluginStatus(version, source, note)
    }

    private val COLS = listOf(Col.left(AGENT_W), Col.left(INSTALLED_W), Col.left(AVAILABLE_W), Col.left(SOURCE_W))
    private const val AGENT_W = 5
    private const val INSTALLED_W = 8
    private const val AVAILABLE_W = 9
    private const val SOURCE_W = 6

    /** The human table, or the one-line verdict. */
    fun human(rep: OutdatedReport): String =
        when {
            rep.installed == 0 -> "$NO_PLUGINS\n"
            rep.outdated.isEmpty() -> "all rtok plugins are up to date (${rep.installed} installed, rtok ${rep.rtok})\n"
            else -> outdatedTable(rep.outdated)
        }

    private fun outdatedTable(rows: List<OutdatedRow>): String {
        val cells = listOf(listOf("agent", "installed", "available", "source")) + rows.map { listOf(it.agent, it.installed, it.available, it.source) }
        val hosts =
            rows
                .map { it.agent }
                .distinct()
                .sorted()
                .joinToString(",")
        return table(COLS, cells) + "\nrun: rtok agents update $hosts\n"
    }

    /** serde_json's compact form of the report, in Rust's field order. */
    fun json(rep: OutdatedReport): String =
        JsonObject(
            linkedMapOf(
                "rtok" to JsonPrimitive(rep.rtok),
                "outdated" to
                    JsonArray(
                        rep.outdated.map {
                            JsonObject(
                                linkedMapOf(
                                    "agent" to JsonPrimitive(it.agent),
                                    "variant" to JsonPrimitive(it.variant),
                                    "installed" to JsonPrimitive(it.installed),
                                    "available" to JsonPrimitive(it.available),
                                    "source" to JsonPrimitive(it.source),
                                    "legacy" to JsonPrimitive(it.legacy),
                                ),
                            )
                        },
                    ),
                "installed" to JsonPrimitive(rep.installed),
            ),
        ).toString()
}
