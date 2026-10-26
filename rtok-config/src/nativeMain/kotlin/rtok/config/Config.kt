// Port of the `Config` struct and impl in crates/rtok-config/src/lib.rs.
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.serialization.ExperimentalSerializationApi::class)
package rtok.config

import kotlinx.cinterop.toKString
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.*
import platform.posix.getenv
import rtok.hook.expandWith
import rtok.hook.homeDirFrom
import rtok.hook.userHomeFrom
import rtok.sys.*

/** The one JSON codec for config trees: snake_case keys, unknown keys rejected, defaults filled. */
val ConfigJson = Json {
    namingStrategy = JsonNamingStrategy.SnakeCase
    encodeDefaults = true
    explicitNulls = false
    isLenient = false
    ignoreUnknownKeys = false
    allowSpecialFloatingPointValues = true
}

data class LoadNote(val event: String, val message: String, val log: String)

fun envVar(k: String): String? = getenv(k)?.toKString()

@Serializable
data class Config(
    val core: Core = Core(), val estimator: Estimator = Estimator(), val log: Log = Log(), val hook: Hook = Hook(),
    val agents: Agents = Agents(), val mcp: Mcp = Mcp(), val proxy: Proxy = Proxy(), val web: Web = Web(), val tui: Tui = Tui(),
    val ui: Ui = Ui(), val dashboard: Web? = null, val demon: Demon = Demon(), val stats: Stats = Stats(), val report: Report = Report(),
    val bench: Bench = Bench(), val doctor: Doctor = Doctor(), val setup: Setup = Setup(), val expand: Expand = Expand(),
    val filter: Filter = Filter(), val worktree: Worktree = Worktree(), val tasks: Tasks = Tasks(), val otel: Otel = Otel(),
    val plugins: Plugins = Plugins(),
) {
    /** Directory the config was loaded from; not part of the file or of equality. */
    @Transient var home: String = ""
    @Transient var loadedFrom: String? = null
    @Transient var notes: MutableList<LoadNote> = mutableListOf()
    @Transient var pluginReceiptPath: String? = null
    @Transient var hookClientPid: UInt? = null

    fun toJson(): JsonObject = ConfigJson.encodeToJsonElement(serializer(), this).jsonObject

    fun pluginEnabled(id: String, defaultOn: Boolean): Boolean = with(plugins) {
        when (id) {
            "measure" -> measure.enabled; "cmd" -> cmd.enabled; "read" -> read.enabled; "json_tree" -> jsonTree.enabled
            "archive" -> archive.enabled; "proxy" -> proxy.enabled; "inject" -> inject.enabled; "guard" -> guard.enabled
            "memory" -> memory.enabled; "graph" -> graph.enabled; "toon" -> toon.enabled; "compress" -> compress.enabled
            "docs" -> docs.enabled; else -> defaultOn
        }
    }

    fun withPluginEnabled(id: String, on: Boolean): Config = keep(copy(plugins = with(plugins) {
        when (id) {
            "measure" -> copy(measure = measure.copy(enabled = on)); "cmd" -> copy(cmd = cmd.copy(enabled = on))
            "read" -> copy(read = read.copy(enabled = on)); "json_tree" -> copy(jsonTree = jsonTree.copy(enabled = on))
            "archive" -> copy(archive = archive.copy(enabled = on)); "proxy" -> copy(proxy = proxy.copy(enabled = on))
            "inject" -> copy(inject = inject.copy(enabled = on)); "guard" -> copy(guard = guard.copy(enabled = on))
            "memory" -> copy(memory = memory.copy(enabled = on)); "graph" -> copy(graph = graph.copy(enabled = on))
            "toon" -> copy(toon = toon.copy(enabled = on)); "compress" -> copy(compress = compress.copy(enabled = on))
            "docs" -> copy(docs = docs.copy(enabled = on)); else -> this
        }
    }))

    /** Carries the non-file fields over to a modified copy. */
    internal fun keep(c: Config): Config = c.also {
        it.home = home; it.loadedFrom = loadedFrom; it.notes = notes; it.pluginReceiptPath = pluginReceiptPath; it.hookClientPid = hookClientPid
    }

    /** Every path-valued key, dotted (`setup.paths.<k>`, `plugins.read.allow_paths[i]`, …), with its value. */
    fun pathFields(): List<Pair<String, String>> {
        val root = toJson(); val out = ArrayList<Pair<String, String>>()
        for (k in PATH_KEYS) (leaf(root, k) as? JsonPrimitive)?.let { out += k to it.content }
        for (m in PATH_MAPS) (leaf(root, m) as? JsonObject)?.forEach { (k, v) -> out += "$m.$k" to v.jsonPrimitive.content }
        for (a in PATH_ARRAYS) (leaf(root, a) as? JsonArray)?.forEachIndexed { i, v -> out += "$a[$i]" to v.jsonPrimitive.content }
        return out
    }

    /** Rewrites every path field through [f] (Rust `path_fields_mut`). */
    fun mapPaths(f: (String) -> String): Config {
        var root = toJson()
        for (k in PATH_KEYS) root = updateLeaf(root, k) { (it as? JsonPrimitive)?.let { p -> JsonPrimitive(f(p.content)) } ?: it }
        for (m in PATH_MAPS) root = updateLeaf(root, m) { o -> (o as? JsonObject)?.let { JsonObject(it.mapValues { (_, v) -> JsonPrimitive(f(v.jsonPrimitive.content)) }) } ?: o }
        for (a in PATH_ARRAYS) root = updateLeaf(root, a) { o -> (o as? JsonArray)?.let { JsonArray(it.map { v -> JsonPrimitive(f(v.jsonPrimitive.content)) }) } ?: o }
        return keep(decodeConfig(root))
    }

    fun rebasePaths(dir: String): Config = mapPaths { expandWith(it, dir, dir) }

    /** Legacy fold, legacy notes, usage-dir env follow, `~` expansion (Rust `finish`). */
    fun finish(home: String, env: (String) -> String? = ::envVar): Config {
        var c = applyLegacyFold(this)
        val notes = ArrayList<String>()
        c.core.injectBudgetTokens?.let { b -> if (c.plugins.inject.budgetTokens == b) notes += "core.inject_budget_tokens is now plugins.inject.budget_tokens (using $b)" }
        c.dashboard?.let { w -> if (c.web == w) notes += "[dashboard] is now [web] (using it)" }
        c.core.logFile?.let { p -> if (c.log.path == p) notes += "core.log_file is now log.path (using $p)" }
        c.core.logLevel?.let { l -> if (c.log.level == l) notes += "core.log_level is now log.level (using $l)" }
        c.core.logToDb?.let { t -> if (c.log.toDb == t) notes += "core.log_to_db is now log.to_db (using $t)" }
        c = keep(c.copy(core = c.core.copy(injectBudgetTokens = null, logFile = null, logLevel = null, logToDb = null), dashboard = null,
            agents = c.agents.copy(usage = c.agents.usage.copy(dirs = c.agents.usage.dirs.followEnv(env)))))
        val userHome = userHomeFrom(env("HOME"), env("USERPROFILE"))
        c = c.mapPaths { expandWith(it, home, userHome) }
        c.home = home
        c.notes = (this.notes + notes.map { LoadNote("legacy", it, it) }).toMutableList()
        return c
    }

    companion object {
        val PATH_KEYS = listOf("core.db_path", "core.archive_dir", "log.path", "demon.state_dir", "stats.transcripts_dir",
            "stats.codex_dir", "report.out", "bench.tasks", "doctor.settings_path", "doctor.claude_json", "doctor.mcp_json",
            "setup.claude.settings_path", "setup.cursor.hooks_path", "setup.codex.config_path", "setup.opencode.config_path",
            "setup.kilo.config_path", "setup.pi.extensions_path", "setup.omp.extensions_path", "setup.omp.mcp_path",
            "setup.zcode.config_path", "setup.kimi.config_path", "setup.grok.config_path", "setup.copilot.dir",
            "setup.commandcode.dir", "setup.vscode.code_user_dir", "setup.vscode.insiders_user_dir", "setup.aider.config_path",
            "setup.windsurf.config_path", "setup.zed.config_path", "setup.cline.hooks_path", "setup.cline.mcp_path",
            "setup.gemini.dir", "setup.qwen.dir", "setup.goose.config_path", "setup.codebuddy.dir", "setup.codebuddy.workbuddy_dir",
            "setup.droid.mcp_path", "setup.kiro.mcp_path", "setup.junie.mcp_path", "setup.amp.settings_path", "setup.warp.mcp_path",
            "setup.crush.config_path", "setup.openhands.mcp_path", "setup.augment.settings_path", "setup.reasonix.dir",
            "setup.codewhale.dir", "setup.mimo.config_path", "setup.antigravity.plugins_path", "setup.antigravity.cli_plugins_path",
            "setup.devin.config_path", "plugins.cmd.rules", "plugins.cmd.rules_dir", "plugins.inject.modes_dir", "plugins.wasm.dir",
            "worktree.root", "setup.continue.config_path")
        val PATH_MAPS = listOf("bench.configs", "setup.paths")
        val PATH_ARRAYS = listOf("plugins.read.allow_paths") +
            listOf("opencode", "kilo", "copilot", "gemini", "droid", "pi", "kimi", "grok", "zcode", "antigravity").map { "agents.usage.dirs.$it" }

        /** `$RTOK_HOME` or `$HOME/.rtok`; always absolute. */
        fun homeDir(): String = homeDirAbsolute(envVar("RTOK_HOME"), envUserHome(), { cwd() }, { "${tempDir()}/.rtok" })
        fun pathFor(home: String) = join(home, "config.toml")
        fun userPath(home: String, configFile: String?) = configFile ?: envVar("RTOK_CONFIG") ?: pathFor(home)

        fun load(): Config = loadWith(null, null)
        fun loadFrom(home: String): Config { if (!exists(pathFor(home))) init(home, false); return Layers.load(home, pathFor(home), null) }
        fun loadWith(configFile: String?, flags: JsonObject?): Config {
            val home = homeDir(); ensureUserFile(home, configFile); return Layers.load(home, configFile, flags)
        }
        fun loadLenient(configFile: String?, flags: JsonObject?): Config = try { loadWith(configFile, flags) } catch (e: Exception) {
            val home = homeDir()
            Config().finish(home).also {
                it.notes += LoadNote("load", "config ignored (${e.message}); using defaults", "ignored (${e.message}); using defaults")
                Layers.notify(it)
            }
        }
        fun ensureUserFile(home: String, configFile: String?) {
            if (configFile != null || envVar("RTOK_CONFIG") != null) return
            if (!exists(pathFor(home))) init(home, false)
        }
        fun init(home: String, force: Boolean): String = initMaybe(home, null, force, false).first
        /** Writes the reference `default.toml`; returns (path, diff preview). */
        fun initMaybe(home: String, configFile: String?, force: Boolean, dryRun: Boolean): Pair<String, String> {
            val path = userPath(home, configFile)
            if (exists(path) && !force) throw ConfigException("$path exists; pass --force to overwrite")
            val before = runCatching { readToString(path) }.getOrDefault("")
            val diff = Validate.fileDiff(path, before, DEFAULT_TOML)
            if (!dryRun) writeFile(path, DEFAULT_TOML)
            return path to diff
        }
    }
}

/** Atomic write (temp + rename), owner-only, parent dirs created. */
fun writeFile(path: String, body: String) {
    parent(path)?.let { createDirAll(it) }
    val tmp = "$path.tmp-${pid()}"
    writeString(tmp, body)
    if (platform.posix.rename(tmp, path) != 0) { platform.posix.unlink(tmp); throw ConfigException("cannot write $path") }
}

fun decodeConfig(root: JsonObject): Config = try { rawDecode(root) } catch (e: ConfigException) {
    val key = failingKey(root, Config().toJson())
    if (key == null || (e.message ?: "").contains("\"$key\"")) throw e
    throw ConfigException("${(e.message ?: "invalid config").lineSequence().first()} for key \"$key\"")
}

private fun rawDecode(root: JsonObject): Config = try { ConfigJson.decodeFromJsonElement(Config.serializer(), checkUnsigned(root, Config().toJson())) }
    catch (e: kotlinx.serialization.SerializationException) { throw ConfigException(e.message ?: "invalid config") }
    catch (e: IllegalArgumentException) { throw ConfigException(e.message ?: "invalid config") }

/** kotlinx reports paths relative to the nested decoder; find the full dotted key by decoding one leaf at a time. */
private fun failingKey(root: JsonObject, defaults: JsonObject): String? {
    fun leaves(o: JsonObject, d: JsonElement?, at: List<String>, out: MutableList<List<String>>) {
        for ((k, v) in o) { val dd = (d as? JsonObject)?.get(k); val p = at + k
            if (v is JsonObject && dd is JsonObject) leaves(v, dd, p, out) else out += p }
    }
    val all = ArrayList<List<String>>(); leaves(root, defaults, emptyList(), all)
    for (p in all) {
        var v: JsonElement = p.fold(root as JsonElement) { acc, k -> (acc as JsonObject)[k]!! }
        for (k in p.reversed()) v = JsonObject(mapOf(k to v))
        if (runCatching { rawDecode(deepMerge(defaults, v as JsonObject)) }.isFailure) return p.joinToString(".")
    }
    return null
}

/** kotlinx wraps negative numbers into unsigned fields silently; Rust/serde rejects them. */
private fun checkUnsigned(v: JsonObject, defaults: JsonObject, at: String = ""): JsonObject {
    for ((k, x) in v) {
        val d = defaults[k]; val key = if (at.isEmpty()) k else "$at.$k"
        if (x is JsonObject && d is JsonObject) checkUnsigned(x, d, key)
        if (x is JsonPrimitive && !x.isString && d is JsonPrimitive && !d.isString && d.longOrNull != null && (d.longOrNull ?: 0) >= 0 && (x.longOrNull ?: 0) < 0)
            throw ConfigException("invalid value: integer `${x.content}`, expected an unsigned integer for key \"$key\"")
    }
    return v
}

internal fun applyLegacyFold(cfg: Config): Config {
    val d = Config(); var c = cfg
    c.core.injectBudgetTokens?.let { if (c.plugins.inject.budgetTokens == d.plugins.inject.budgetTokens) c = c.copy(plugins = c.plugins.copy(inject = c.plugins.inject.copy(budgetTokens = it))) }
    c.dashboard?.let { if (c.web.host == d.web.host && it.host != d.web.host) c = c.copy(web = c.web.copy(host = it.host)) }
    c.dashboard?.let { if (c.web.port == d.web.port && it.port != d.web.port) c = c.copy(web = c.web.copy(port = it.port)) }
    c.core.logFile?.let { if (c.log.path == d.log.path) c = c.copy(log = c.log.copy(path = it)) }
    c.core.logLevel?.let { if (c.log.level == d.log.level) c = c.copy(log = c.log.copy(level = it)) }
    c.core.logToDb?.let { if (c.log.toDb == d.log.toDb) c = c.copy(log = c.log.copy(toDb = it)) }
    return cfg.keep(c)
}

fun envUserHome(): String? = userHomeFrom(envVar("HOME"), envVar("USERPROFILE"))

fun homeDirAbsolute(rtokHome: String?, userHome: String?, cwd: () -> String?, fallback: () -> String): String {
    val explicit = !rtokHome.isNullOrEmpty() || userHome != null
    val home = homeDirFrom(rtokHome, userHome)
    if (home.startsWith("/")) return home
    if (!explicit) return fallback()
    return cwd()?.let { join(it, home) } ?: home
}

// ---- JSON tree helpers shared by layers and validate ----

fun leaf(root: JsonObject, dotted: String): JsonElement? {
    var cur: JsonElement = root
    for (p in dotted.split('.')) cur = (cur as? JsonObject)?.get(p) ?: return null
    return cur
}

fun updateLeaf(root: JsonObject, dotted: String, f: (JsonElement) -> JsonElement): JsonObject {
    val parts = dotted.split('.')
    fun go(o: JsonObject, i: Int): JsonObject {
        val cur = o[parts[i]] ?: return o
        val next = if (i == parts.size - 1) f(cur) else (cur as? JsonObject)?.let { go(it, i + 1) } ?: cur
        return JsonObject(o + (parts[i] to next))
    }
    return go(root, 0)
}

fun insertDotted(root: JsonObject, dotted: String, value: JsonElement): JsonObject {
    val head = dotted.substringBefore('.')
    if (!dotted.contains('.')) return JsonObject(root + (head to value))
    val child = root[head] as? JsonObject ?: JsonObject(emptyMap())
    return JsonObject(root + (head to insertDotted(child, dotted.substringAfter('.'), value)))
}
