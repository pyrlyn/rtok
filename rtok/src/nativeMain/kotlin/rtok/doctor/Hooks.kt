// Port of src/doctor/hooks.rs (the file half; the command half is HookCommand.kt) and `plugin_install_paths` of
// src/doctor.rs. Broken hooks (T331.1): a hook whose command leads nowhere. Every hook of Claude Code's settings files,
// its enabled plugins, and the JSON and TOML configs the other hosts' installers write is resolved without running it.
// A target that is not there is `broken-hook`; a script that exists but is not executable is `suspect-hook`; a command
// that cannot be judged statically is `unverified-hook`. Only the first class is ever offered for removal (T331.5).
// Nothing here edits a file, and nothing reaches the machine except through [Probes] (the plugin links excepted, read
// through the SDK's PluginLink as in Rust).
package rtok.doctor

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import rtok.agents.Agents
import rtok.agents.Antigravity
import rtok.agents.Claude
import rtok.agents.Cline
import rtok.agents.CommandCode
import rtok.agents.Cursor
import rtok.agents.HostPlugin
import rtok.agents.Hosts
import rtok.agents.Kilo
import rtok.agents.Mimo
import rtok.agents.Omp
import rtok.agents.OpenCode
import rtok.agents.Pi
import rtok.agents.Vscode
import rtok.agents.Zcode
import rtok.config.Config
import rtok.config.HOSTS
import rtok.config.Toml
import rtok.json.StrictJson
import rtok.mcp.config.Jsonc

/** One command hook of a config document. */
internal data class Entry(
    val event: String,
    val matcher: String?,
    val command: String,
    val key: String,
)

/** One hook entry as found, whether or not it is a problem: the input of the duplicate check. */
internal data class Seen(
    val agent: String,
    val source: String,
    val path: String,
    val event: String,
    val matcher: String?,
    val command: String,
    /** The command with variables expanded and quotes and spacing removed: two entries differing only there match. */
    val normal: String,
    val rank: Int,
    /** Whether `--fix` may edit the file the entry lives in: not a plugin's, and JSON. */
    val editable: Boolean,
)

@Suppress("TooManyFunctions")
object Hooks {
    /**
     * Which copy of a duplicate hook is kept, best first (T331): the one a plugin owns, so plugin updates keep working;
     * a project `settings.json`, which teammates share; the user's own file; then a `settings.local.json`.
     */
    internal const val RANK_PLUGIN = 0
    private const val RANK_PROJECT = 1
    private const val RANK_USER = 2
    private const val RANK_LOCAL = 3

    /** The hosts whose hooks live in a `config.toml`, each shape documented by the host (see [tomlEntries]). */
    private val TOML_HOSTS = setOf("kimi", "codewhale", "codex")

    private enum class Format { Json, Toml }

    /** What one pass over the config files collects. */
    private class Acc {
        val problems = ArrayList<Problem>()
        val seen = ArrayList<Seen>()
        val pluginDirs = ArrayList<Pair<String, String>>()
    }

    private fun withFileName(
        path: String,
        name: String,
    ) = path.substringBeforeLast('/', "").let { if (it.isEmpty() && !path.startsWith('/')) name else "$it/$name" }

    /** The Claude Code settings files in load order (user, user local, project, project local), with keep ranks. */
    private fun sources(
        cfg: Config,
        project: String?,
    ): List<Pair<String, Int>> {
        val user = cfg.doctor.settingsPath
        val out = mutableListOf(user to RANK_USER, withFileName(user, "settings.local.json") to RANK_LOCAL)
        if (project != null) {
            val dir = rtok.sys.join(project, ".claude")
            out += rtok.sys.join(dir, "settings.json") to RANK_PROJECT
            out += rtok.sys.join(dir, "settings.local.json") to RANK_LOCAL
        }
        // No file twice: the working directory may be `$HOME`.
        val seen = HashSet<String>()
        return out.filter { seen.add(it.first) }
    }

    private fun JsonElement?.str() = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    /** Object members in key order, as serde_json's sorted map yields them. */
    private fun JsonObject.sortedEntries() = entries.sortedBy { it.key }

    /**
     * Every command hook of a settings document: `hooks.<Event>[].{matcher, hooks[].command}`, plus the flat
     * `hooks.<Event>[].command` shape `doctor`'s hook count also accepts.
     */
    internal fun entries(doc: JsonElement): List<Entry> {
        val out = ArrayList<Entry>()
        val events = (doc as? JsonObject)?.get("hooks") as? JsonObject ?: return out
        for ((event, groups) in events.sortedEntries()) {
            (groups as? JsonArray ?: JsonArray(emptyList())).forEachIndexed { gi, group ->
                val matcher = (group as? JsonObject)?.get("matcher").str()

                fun push(
                    hook: JsonElement,
                    key: String,
                ) {
                    val o = hook as? JsonObject ?: return
                    val type = o["type"]
                    val isCommand = type == null || type !is JsonPrimitive || !type.isString || type.content == "command"
                    val command = o["command"].str()
                    if (isCommand && command != null) out += Entry(event, matcher, command, key)
                }
                when (val hooks = (group as? JsonObject)?.get("hooks")) {
                    is JsonArray -> hooks.forEachIndexed { hi, hook -> push(hook, "hooks.$event[$gi].hooks[$hi]") }
                    else -> push(group, "hooks.$event[$gi]")
                }
            }
        }
        return out
    }

    /** [check] limited to one host's findings (an id of `HOSTS`); null is all. */
    fun checkFor(
        cfg: Config,
        p: Probes,
        agent: String?,
    ) = check(cfg, p).filter { agent == null || it.agent == agent }

    /** [id] as a host id, or an error naming the valid ones. */
    fun hostId(id: String): Result<String> = if (id in HOSTS) Result.success(id) else Result.failure(IllegalArgumentException("unknown host `$id`; valid hosts: ${HOSTS.joinToString(", ")}"))

    /**
     * Check every hook of Claude Code's settings files and enabled plugins, and of the JSON and TOML config files the
     * other hosts' installers write. A missing file is skipped; one that cannot be read or parsed is reported and left.
     */
    fun check(
        cfg: Config,
        p: Probes,
    ) = checkWithPlugins(cfg, p).first

    /** [check], and the install directory of each enabled Claude plugin it found (one per plugin id). */
    fun checkWithPlugins(
        cfg: Config,
        p: Probes,
    ): Pair<List<Problem>, List<Pair<String, String>>> {
        val project = p.env.cwd().orEmpty()
        val claude = Scope(project, relativeOk = true)
        val other = claude.copy(relativeOk = false)
        val acc = Acc()
        val seen = HashSet<String>()
        val enabled = HashSet<String>()
        for ((source, rank) in sources(cfg, project.takeIf { it.isNotEmpty() })) {
            // The working directory may reach `$HOME` through a symlink; the file is still one file.
            if (!seen.add(p.fs.canonical(source))) continue
            scan(p, "claude", source, rank, Format.Json, claude, acc)?.let { enabled += enabledPlugins(it) }
        }
        for ((agent, source, format) in hostSources(cfg)) {
            if (seen.add(p.fs.canonical(source))) scan(p, agent, source, RANK_USER, format, other, acc)
        }
        plugins(cfg, p, enabled, claude, acc)
        hostPluginLinks(cfg, acc)
        acc.problems += Dupes.find(acc.seen)
        return acc.problems to acc.pluginDirs
    }

    /** The config files every host but Claude Code keeps hooks in, once each: JSON, and the TOML of [TOML_HOSTS]. */
    @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
    private fun hostSources(cfg: Config): List<Triple<String, String, Format>> {
        val out = ArrayList<Triple<String, String, Format>>()
        for (id in HOSTS.filter { it != "claude" }) {
            val agent = Agents.agent(id) ?: continue
            val host = Hosts.host(id) ?: continue
            for (v in host.variants) {
                for (path in agent.files(cfg, v.kind)) {
                    val name = path.substringAfterLast('/')
                    val ext = if ('.' in name) name.substringAfterLast('.') else null
                    val format =
                        when {
                            ext == "json" || ext == "jsonc" -> Format.Json
                            ext == "toml" && id in TOML_HOSTS -> Format.Toml
                            else -> continue
                        }
                    if (out.none { it.second == path }) out += Triple(id, path, format)
                }
            }
        }
        return out
    }

    /** `enabledPlugins` of a settings document: the ids switched on. */
    private fun enabledPlugins(doc: JsonElement): List<String> {
        val on = (doc as? JsonObject)?.get("enabledPlugins") as? JsonObject ?: return emptyList()
        return on.sortedEntries().filter { (it.value as? JsonPrimitive)?.booleanOrNull == true }.map { it.key }
    }

    /** doctor.rs `plugin_install_paths`: every `installPath` of plugin [id] in `installed_plugins.json`. */
    internal fun pluginInstallPaths(
        root: JsonElement,
        id: String,
    ): List<String> {
        val list = ((root as? JsonObject)?.get("plugins") as? JsonObject)?.get(id) as? JsonArray ?: return emptyList()
        return list.mapNotNull { (it as? JsonObject)?.get("installPath").str() }
    }

    /**
     * The hooks of every enabled Claude plugin, with `${CLAUDE_PLUGIN_ROOT}` resolved against its install directory. A
     * plugin that is enabled but whose directory is gone is reported as stale. These files belong to the plugin, so
     * nothing found in them is ever fixable.
     */
    private fun plugins(
        cfg: Config,
        p: Probes,
        enabled: Set<String>,
        claude: Scope,
        acc: Acc,
    ) {
        val index = rtok.sys.join(Claude.configDir(cfg), "plugins/installed_plugins.json")
        val root = runCatching { StrictJson.parse(p.fs.read(index)) }.getOrNull() ?: return
        val plugins = (root as? JsonObject)?.get("plugins") as? JsonObject ?: return
        for (id in plugins.keys.sorted().filter { it in enabled }) {
            pluginInstallPaths(root, id).forEachIndexed { i, dir ->
                if (p.fs.kind(dir) != PathKind.Dir) {
                    acc.problems +=
                        Problem(
                            "stale-plugin",
                            "claude",
                            index,
                            path = "plugins.$id[$i]",
                            detail = "`$id` is enabled but its install directory $dir is gone",
                        )
                    return@forEachIndexed
                }
                if (acc.pluginDirs.none { it.first == id }) acc.pluginDirs += id to dir
                val scope = claude.copy(pluginRoot = dir)
                scan(p, "claude", rtok.sys.join(dir, "hooks/hooks.json"), RANK_PLUGIN, Format.Json, scope, acc)
            }
        }
    }

    /** agents/plugin.rs `all`: every declared host plugin, by host id. */
    private fun hostPlugins(): List<Pair<String, HostPlugin>> =
        listOf(
            "antigravity" to Antigravity.PLUGIN,
            "commandcode" to CommandCode.PLUGIN,
            "cursor" to Cursor.PLUGIN,
            "kilo" to Kilo.PLUGIN,
            "mimo" to Mimo.PLUGIN,
            "omp" to Omp.PLUGIN,
            "opencode" to OpenCode.PLUGIN,
            "pi" to Pi.PLUGIN,
            "vscode" to Vscode.plugin(false),
            "vscode" to Vscode.plugin(true),
            "zcode" to Zcode.PLUGIN,
        ) + Cline.HOOKS.map { "cline" to it }

    /**
     * Each host plugin rtok installed by link: one whose target is gone loads nothing, and one into a versioned ketch
     * store dangles at the next upgrade (T925). Unix only, as the Kotlin port is.
     */
    private fun hostPluginLinks(
        cfg: Config,
        acc: Acc,
    ) {
        val seen = HashSet<String>()
        for ((agent, plugin) in hostPlugins()) {
            val dest = plugin.path(cfg)
            if (!seen.add(dest)) continue
            plugin.problem(cfg)?.let {
                acc.problems += Problem("stale-plugin", agent, dest, detail = "$it; `rtok agents install $agent` replaces it")
            }
        }
    }

    /** The document of [raw] as JSON, whichever the format, so one classification serves both. */
    private fun parse(
        raw: String,
        format: Format,
    ): JsonElement =
        when (format) {
            Format.Json -> Jsonc.parse(raw)
            Format.Toml -> Toml.parse(raw)
        }

    /**
     * The hooks of a TOML config. Kimi writes `[[hooks]]` tables and CodeWhale `[[hooks.hooks]]`, both
     * `{event, matcher?, command}`; Codex's `[[hooks.<Event>]]` with nested `[[hooks.<Event>.hooks]]` has the
     * settings.json shape and goes through [entries].
     */
    private fun tomlEntries(doc: JsonElement): List<Entry> {
        fun flat(
            list: JsonArray,
            prefix: String,
        ) = list.mapIndexedNotNull { i, h ->
            val o = h as? JsonObject ?: return@mapIndexedNotNull null
            val command = o["command"].str() ?: return@mapIndexedNotNull null
            Entry(o["event"].str().orEmpty(), o["matcher"].str()?.takeIf { it.isNotEmpty() }, command, "$prefix[$i]")
        }
        val hooks = (doc as? JsonObject)?.get("hooks")
        val nested = (hooks as? JsonObject)?.get("hooks") as? JsonArray
        return when {
            hooks is JsonArray -> flat(hooks, "hooks")
            nested != null -> flat(nested, "hooks.hooks")
            else -> entries(doc)
        }
    }

    /**
     * One config file: its hooks classified into [acc]. A plugin's file is not the user's to edit, and `--fix` edits
     * JSON only, so nothing in those is fixable. Returns the parsed document.
     */
    @Suppress("ReturnCount", "LongParameterList")
    private fun scan(
        p: Probes,
        agent: String,
        source: String,
        rank: Int,
        format: Format,
        scope: Scope,
        acc: Acc,
    ): JsonElement? {
        val editable = rank != RANK_PLUGIN && format == Format.Json
        val raw =
            try {
                p.fs.read(source)
            } catch (e: ReadError) {
                if (!e.notFound) acc.problems += unreadable(agent, source, e.message.orEmpty())
                return null
            }
        val doc =
            try {
                parse(raw, format)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                acc.problems += unreadable(agent, source, e.message.orEmpty())
                return null
            }
        val found = if (format == Format.Json) entries(doc) else tomlEntries(doc)
        for (e in found) {
            acc.seen += Seen(agent, source, e.key, e.event, e.matcher, e.command, HookCommand.normalize(e.command, p, scope), rank, editable)
            val (kind, detail, fixable) =
                when (val v = HookCommand.classify(e.command, p, scope)) {
                    Verdict.Ok -> continue
                    is Verdict.Broken -> Triple("broken-hook", v.why, editable)
                    is Verdict.Suspect -> Triple("suspect-hook", v.why, false)
                    is Verdict.Unverified -> Triple("unverified-hook", v.why, false)
                }
            acc.problems += Problem(kind, agent, source, e.key, e.event, e.matcher, e.command, detail, fixable)
        }
        return doc
    }

    private fun unreadable(
        agent: String,
        source: String,
        why: String,
    ) = Problem("unreadable-config", agent, source, detail = "cannot read $source: $why")

    private val HOOK_KINDS = setOf("broken-hook", "suspect-hook", "unverified-hook", "unreadable-config", "stale-plugin")
    private val TITLES =
        listOf(
            "broken-hook" to "broken",
            "suspect-hook" to "suspect",
            "unverified-hook" to "cannot verify",
            "stale-plugin" to "stale plugin",
            "unreadable-config" to "unreadable",
        )

    /** The `hooks check` lines of the doctor text: grouped by class, or "none found". */
    fun render(problems: List<Problem>): String {
        val hookProblems = problems.filter { it.kind in HOOK_KINDS }
        val out = StringBuilder(if (hookProblems.isEmpty()) "hooks check none found\n" else "hooks check\n")
        for ((kind, title) in TITLES) {
            for (p in hookProblems.filter { it.kind == kind }) {
                if (kind == "unreadable-config" || kind == "stale-plugin") {
                    out.append("  $title ${p.agent} ${p.detail}\n")
                    continue
                }
                val matcher = p.matcher?.let { "[$it]" }.orEmpty()
                val note = if (p.fixable) " (can be cleaned up)" else ""
                out.append("  $title ${p.agent} ${p.event}$matcher `${p.command}`: ${p.detail}$note\n    ${p.source} ${p.path}\n")
            }
        }
        out.append(Dupes.render(problems))
        return out.toString()
    }
}
