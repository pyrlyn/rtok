// Port of src/agents/claude/mod.rs `plugin_update` and its helpers (`installed_version_file`, `update_source`,
// `record_install`, `marketplace_target`, `catalog_dir`, `reinstall`, `update_in_place`) and plugin_install.rs
// `claude_install`: `agents update` compares the installed `rtok@rtok` with what the chosen source offers (T279) and
// skips, updates in place, or reinstalls, keeping rtok's receipt in step.
package rtok.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import rtok.VERSION
import rtok.agent.sdk.NO_CHANGES
import rtok.config.Config
import rtok.json.StrictJson
import rtok.sys.canonicalize
import rtok.sys.exists
import rtok.sys.isDir
import rtok.sys.isFile
import rtok.sys.readToString

class ClaudeUpdate(
    private val claude: Claude,
    private val running: String = VERSION,
) {
    private val id = Claude.PLUGIN_ID

    fun run(cfg: Config): String {
        val receiptPath = PluginVersion.receiptPath(cfg)
        val receipt = Receipt.read(receiptPath)
        val present = Claude.pluginInstalled(cfg)
        val installed =
            if (present) PluginVersion.installed(installedVersionFile(cfg), receipt.get(HOST), claudeInstall(cfg)?.first, Source.Github) else null
        val source = cfg.setup.source?.let(Source::parse) ?: installed?.source ?: Source.Github
        val dry = applyOf(cfg).dryRun
        val refreshed =
            source == Source.Marketplace && present && !dry && claude.onPath(cfg) && claude.cli(cfg, listOf("plugin", "marketplace", "update", "rtok")) == null
        val available = PluginVersion.available(source, claude.plugins.of(Claude.PLUGIN_SRC), catalogDir(cfg), running)
        val target = target(source)
        val stale = present && Claude.marketplaceState(cfg, target) == MarketplaceState.Stale
        val decision =
            when (val d = PluginVersion.decide(installed, available, cfg.setup.force)) {
                is Decision.Reinstall -> d
                else if stale -> Decision.Reinstall("stale marketplace")
                Decision.Update if source == Source.Local -> Decision.Reinstall("new local build")
                else -> d
            }
        return act(cfg, decision, source, available, target, refreshed)
    }

    private fun act(
        cfg: Config,
        decision: Decision,
        source: Source,
        available: Available,
        target: String,
        refreshed: Boolean,
    ): String {
        val dry = applyOf(cfg).dryRun
        return when (decision) {
            is Decision.Skip -> if (dry) "plugin $id ${available.version} up to date ($source)" else NO_CHANGES
            is Decision.SkipOlder -> "plugin $id: ${decision.warning}"
            Decision.Update -> updateInPlace(cfg, source, available, refreshed)
            is Decision.Reinstall, Decision.Install -> if (dry) wouldInstall(cfg, decision, source, available, target) else reinstall(cfg, source, available)
        }
    }

    private fun wouldInstall(
        cfg: Config,
        decision: Decision,
        source: Source,
        available: Available,
        target: String,
    ): String {
        val (verb, why) = if (decision is Decision.Reinstall) "reinstall" to " (${decision.reason})" else "install" to ""
        val steps = Claude.pluginSteps(cfg, false, target, true).orEmpty()
        return "would $verb $id from $source v${available.version}$why: ${Claude.shownSteps(steps)}"
    }

    private fun target(source: Source) = if (source == Source.Local) claude.plugins.of(Claude.PLUGIN_SRC) else Claude.MARKETPLACE_REPO

    private fun catalogDir(cfg: Config) = "${Claude.configDir(cfg)}/plugins/marketplaces/rtok/${Claude.PLUGIN_SRC}"

    private fun isLocalTree(dir: String) = (canonicalize(dir) ?: dir) == claude.plugins.of(Claude.PLUGIN_SRC).let { canonicalize(it) ?: it }

    /** The copy Claude runs' version file; null for none, a legacy copy or the local tree; a broken file throws unless `--force`. */
    private fun installedVersionFile(cfg: Config): VersionFile? {
        val dir = claudeInstall(cfg)?.second?.takeUnless(::isLocalTree)
        val path = dir?.let { "$it/${PluginVersion.FILE}" }?.takeIf(::isFile) ?: return null
        return runCatching { VersionFile.read(path) }.getOrElse { if (cfg.setup.force) null else throw it }
    }

    private fun reinstall(
        cfg: Config,
        source: Source,
        available: Available,
    ): String {
        val line = claude.pluginAgainst(cfg, false, target(source), force = true)
        val path = PluginVersion.receiptPath(cfg)
        when {
            line.startsWith('+') -> record(cfg, source, available)
            Claude.REINSTALL_FAILED in line -> Receipt.read(path).delete(HOST).write(path)
        }
        return line
    }

    private fun updateInPlace(
        cfg: Config,
        source: Source,
        available: Available,
        refreshed: Boolean,
    ): String {
        val steps = UPDATE_STEPS.drop(if (refreshed) 1 else 0)
        val shown = Claude.shownSteps(steps)
        val err = if (applyOf(cfg).dryRun || !claude.onPath(cfg)) null else steps.firstNotNullOfOrNull { claude.cli(cfg, it) }
        return when {
            applyOf(cfg).dryRun -> "~ plugin $id → ${available.version} ($shown)"
            !claude.onPath(cfg) -> Claude.claudeMissing(shown)
            err == null -> "~ plugin $id updated to ${available.version}".also { record(cfg, source, available) }
            else -> "${reinstall(cfg, source, available)} (update failed: $err)"
        }
    }

    /** Write the receipt row (and, for a local build, the version file into Claude's copy). */
    private fun record(
        cfg: Config,
        source: Source,
        available: Available,
    ) {
        val copy = claudeInstall(cfg)?.second
        val local = source == Source.Local
        val own = copy?.takeIf { isDir(it) && !isLocalTree(it) }
        if (local && own != null) VersionFile(plugin = HOST, version = available.version, source = source).write("$own/${PluginVersion.FILE}")
        val path = PluginVersion.receiptPath(cfg)
        val entry =
            ReceiptEntry(
                source = source,
                reference = if (local) target(source) else "v${available.version}",
                marketplace = "rtok".takeUnless { local },
                path = copy ?: "${Claude.configDir(cfg)}/$PLUGIN_CACHE",
                version = available.version,
                installedAt = PluginVersion.nowIso(),
            )
        Receipt.read(path).upsert(HOST, entry).write(path)
    }

    companion object {
        private const val HOST = "claude"
        const val PLUGIN_CACHE = "plugins/cache/rtok/rtok"
        private val UPDATE_STEPS = listOf(listOf("plugin", "marketplace", "update", "rtok"), listOf("plugin", "update", Claude.PLUGIN_ID))

        /** Claude's record of `rtok@rtok`: its `version` string and the dir it runs it from (`installPath`, else the cache). */
        fun claudeInstall(cfg: Config): Pair<String?, String>? {
            val dir = Claude.configDir(cfg)
            val path = "$dir/plugins/installed_plugins.json"
            val root = runCatching { StrictJson.parse(readToString(path)) as JsonObject }.getOrNull()?.takeIf { exists(path) }
            val entry = ((root?.get("plugins") as? JsonObject)?.get(Claude.PLUGIN_ID) as? JsonArray)?.firstOrNull() ?: return null
            val version = str(entry, "version")
            return (str(entry, "installPath") ?: version?.let { "$dir/$PLUGIN_CACHE/$it" })?.let { version to it }
        }
    }
}
