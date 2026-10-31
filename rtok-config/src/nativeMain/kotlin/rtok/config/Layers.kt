// Port of crates/rtok-config/src/layers.rs — config layering (figment replacement):
// default < user file < <checkout>/.rtok.toml (restricted keys dropped) < legacy fold < .env < RTOK_* env < flags.
// Each layer keeps its name so `config show --sources` can say where a value came from.
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package rtok.config

import kotlinx.cinterop.*
import kotlinx.serialization.json.*
import rtok.sys.*

/** One named provider's data. */
class Layer(
    val name: String,
    val data: JsonObject,
)

/** Ordered layers; later layers override earlier ones key by key (tables merge, arrays replace). */
class Fig(
    val layers: List<Layer>,
    val error: ConfigException? = null,
) {
    fun merge(l: Layer) = Fig(layers + l, error)

    fun withError(e: ConfigException) = Fig(layers, error ?: e)

    val merged: JsonObject by lazy { layers.fold(JsonObject(emptyMap())) { acc, l -> deepMerge(acc, l.data) } }

    fun findValue(dotted: String): JsonElement? = leaf(merged, dotted)

    /** Name of the last layer that set [dotted]. */
    fun findMetadata(dotted: String): String? = layers.lastOrNull { leaf(it.data, dotted) != null }?.name

    fun extract(): Config {
        error?.let { throw it }
        return decodeConfig(merged)
    }
}

fun deepMerge(
    a: JsonObject,
    b: JsonObject,
): JsonObject {
    val out = LinkedHashMap(a)
    for ((k, v) in b) {
        val old = out[k]
        out[k] = if (old is JsonObject && v is JsonObject) deepMerge(old, v) else v
    }
    return JsonObject(out)
}

object Layers {
    private var onLoad: ((Config) -> Unit)? = null
    private var onEarly: ((String) -> Unit)? = null

    fun onLoad(hook: (Config) -> Unit) {
        onLoad = hook
    }

    fun onEarlyWarning(hook: (String) -> Unit) {
        onEarly = hook
    }

    internal fun notify(cfg: Config) {
        onLoad?.invoke(cfg)
    }

    fun hookHostFlag(host: String?): JsonObject? = host?.let { buildJsonObject { put("hook", buildJsonObject { put("host", it) }) } }

    private val PROJECT_RESTRICTED =
        listOf(
            "core",
            "otel",
            "proxy",
            "mcp",
            "web",
            "dashboard",
            "log",
            "demon",
            "agents",
            "setup",
            "doctor",
            "stats",
            "report",
            "bench",
            "plugins.wasm",
            "plugins.memory",
            "plugins.compress",
            "plugins.graph",
            "plugins.docs",
            "plugins.cmd.shell",
            "plugins.cmd.rules",
            "plugins.cmd.rules_dir",
            "plugins.read.allow_paths",
            "plugins.inject.modes_dir",
            "plugins.guard",
            "filter",
            "worktree",
            "tasks.gitlab.url",
        )

    /** Keys a project `.rtok.toml` / project `.env` may not set (destinations, commands, paths). */
    fun projectRestricted(key: String) = PROJECT_RESTRICTED.any { key == it || (key.startsWith(it) && key[it.length] == '.') }

    private fun ignoredNote(
        key: String,
        path: String,
    ) = "$path: ignored project setting $key; set it in user config, process environment or explicit --config"

    private fun filterProject(
        dict: JsonObject,
        prefix: String,
        rejected: MutableList<String>,
    ): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for ((name, value) in dict) {
            val key = if (prefix.isEmpty()) name else "$prefix.$name"
            if (value is JsonObject) {
                val nested = filterProject(value, key, rejected)
                if (nested.isNotEmpty()) out[name] = nested
                continue
            }
            if (!projectRestricted(key)) out[name] = value else rejected += key
        }
        return JsonObject(out)
    }

    private fun markIgnored(
        fig: Fig,
        keys: List<String>,
    ): Fig {
        var fallback = JsonObject(emptyMap())
        for (k in keys) fig.findValue(k)?.let { fallback = insertDotted(fallback, k, it) }
        return if (fallback.isEmpty()) fig else fig.merge(Layer("ignored (project)", fallback))
    }

    private val LEGACY_KEYS = listOf("core.inject_budget_tokens", "core.log_file", "core.log_level", "core.log_to_db", "dashboard")

    private fun legacyFold(base: Fig): Layer {
        if (LEGACY_KEYS.none { base.findValue(it).let { v -> v != null && v !is JsonNull } }) return Layer("legacy", JsonObject(emptyMap()))
        val before = base.extract()
        val after = applyLegacyFold(before)
        val b = before.toJson()
        val a = after.toJson()
        var out = JsonObject(emptyMap())
        for ((dotted, _) in envLeafTable.values) {
            val av = leaf(a, dotted)
            if (leaf(b, dotted) != av && av != null) out = insertDotted(out, dotted, av)
        }
        return Layer("legacy", out)
    }

    /** RTOK_<KEY> suffix → (dotted key, is array), derived from the default config's leaves. */
    val envLeafTable: Map<String, Pair<String, Boolean>> by lazy {
        val t = HashMap<String, Pair<String, Boolean>>()

        fun walk(
            d: JsonObject,
            prefix: String,
        ) {
            for ((k, v) in d) {
                val dotted = if (prefix.isEmpty()) k else "$prefix.$k"
                if (v is JsonObject) walk(v, dotted) else t[dotted.uppercase().replace('.', '_')] = dotted to (v is JsonArray)
            }
        }
        walk(Config().toJson(), "")
        t.entries.sortedBy { it.key }.associate { it.key to it.value }
    }

    internal val LEGACY_ALIASES = mapOf("UPSTREAM" to "proxy.upstream", "OPENAI_UPSTREAM" to "proxy.openai_upstream")

    /** figment `Value::from_str`: bool, integer, float, quoted string, `[a, b]` array, else the raw string. */
    fun parseEnvValue(raw: String): JsonElement {
        val t = raw.trim()
        when (t) {
            "true" -> return JsonPrimitive(true)
            "false" -> return JsonPrimitive(false)
        }
        t.toLongOrNull()?.let { return JsonPrimitive(it) }
        if (t.isNotEmpty() && t.any { it.isDigit() } && Regex("""[+-]?\d*\.?\d+([eE][+-]?\d+)?""").matches(t)) t.toDoubleOrNull()?.let { return JsonPrimitive(it) }
        if (t.length >= 2 && t.startsWith('"') && t.endsWith('"')) return JsonPrimitive(t.substring(1, t.length - 1))
        if (t.startsWith("[") && t.endsWith("]")) {
            return JsonArray(
                t
                    .substring(1, t.length - 1)
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map(::parseEnvValue),
            )
        }
        return JsonPrimitive(raw)
    }

    fun resolve(
        suffix: String,
        raw: String,
    ): Pair<String, JsonElement>? {
        val (dotted, isArray) = LEGACY_ALIASES[suffix]?.let { it to false } ?: envLeafTable[suffix] ?: return null
        val v =
            if (isArray) {
                JsonArray(
                    raw
                        .split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .map { JsonPrimitive(it) },
                )
            } else {
                parseEnvValue(raw)
            }
        return dotted to v
    }

    class RtokEnv(
        val name: String,
        val vars: List<Pair<String, String>>,
        val warnings: List<String> = emptyList(),
        val rejected: List<String> = emptyList(),
    ) {
        fun layer(): Layer {
            var d = JsonObject(emptyMap())
            for ((suffix, raw) in vars) resolve(suffix, raw)?.let { (k, v) -> d = insertDotted(d, k, v) }
            return Layer(name, d)
        }

        companion object {
            fun fromPairs(
                pairs: List<Pair<String, String>>,
                name: String = "env",
            ) = RtokEnv(name, pairs)

            /** `RTOK_<KEY>` for every known leaf key and legacy alias (looked up by name; Rust scans the whole env). */
            fun fromProcess(): RtokEnv = RtokEnv("env", (envLeafTable.keys + LEGACY_ALIASES.keys).mapNotNull { k -> envVar("RTOK_$k")?.let { k to it } })

            fun fromDotenv(
                home: String,
                cwd: String?,
            ): RtokEnv = readDotenv(home, cwd).let { RtokEnv("dotenv", it.pairs, it.warnings, it.rejected) }
        }
    }

    class DotenvFile(
        val pairs: List<Pair<String, String>>,
        val warnings: List<String>,
        val rejected: List<String>,
    )

    private fun findUp(
        start: String,
        name: String,
    ): String? {
        var dir: String? = start
        while (dir != null) {
            if (isFile(join(dir, name))) return dir
            dir = parent(dir)
        }
        return null
    }

    fun readDotenv(
        home: String,
        cwd: String?,
    ): DotenvFile {
        val files = listOfNotNull(cwd?.let { findUp(it, ".env") }?.let { join(it, ".env") }) + join(home, ".env")
        val pairs = ArrayList<Pair<String, String>>()
        val warnings = ArrayList<String>()
        val rejected = ArrayList<String>()
        for (path in files) {
            val text =
                try {
                    readToString(path)
                } catch (e: IoException) {
                    if (e.kind == IoException.Kind.NotFound) continue
                    warnings += "$path: ${e.message}"
                    continue
                }
            val (items, bad) = Dotenv.parse(text)
            bad?.let { warnings += "$path: $it" }
            for ((k, v) in items) {
                val suffix = k.removePrefix("RTOK_").takeIf { k.startsWith("RTOK_") }?.uppercase() ?: continue
                if (path != join(home, ".env")) {
                    val key = resolve(suffix, v)?.first
                    if (key != null && projectRestricted(key)) {
                        warnings += ignoredNote(key, path)
                        rejected += key
                        continue
                    }
                }
                if (pairs.none { it.first == suffix }) pairs += suffix to v
            }
        }
        return DotenvFile(pairs, warnings, rejected)
    }

    fun figment(
        home: String,
        configFile: String?,
        flags: JsonObject?,
    ): Fig {
        val cwd = runCatching { cwd() }.getOrNull()
        return assemble(home, configFile, flags, RtokEnv.fromProcess(), cwd, RtokEnv.fromDotenv(home, cwd), ArrayList())
    }

    private fun tomlLayer(
        name: String,
        path: String,
    ): Pair<Layer?, ConfigException?> {
        val text =
            try {
                readToString(path)
            } catch (e: IoException) {
                return null to null
            } // a missing file is an empty layer
        return try {
            Layer(name, Toml.parse(text)) to null
        } catch (e: TomlException) {
            null to ConfigException("${e.message} in $path")
        }
    }

    fun assemble(
        home: String,
        configFile: String?,
        flags: JsonObject?,
        env: RtokEnv,
        cwd: String?,
        dotenv: RtokEnv,
        warnings: MutableList<String>,
    ): Fig {
        val userPath = Config.userPath(home, configFile)
        var fig = Fig(listOf(Layer("default", Config().toJson())))
        tomlLayer("user", userPath).let { (l, e) ->
            l?.let { fig = fig.merge(it) }
            e?.let { fig = fig.withError(it) }
        }
        val projectRejected = ArrayList<String>()
        cwd?.let { ProjectRoot.find(it) }?.let { root ->
            val path = join(root.checkout, ".rtok.toml")
            val (l, e) = tomlLayer("project", path)
            l?.let {
                fig = fig.merge(Layer("project", filterProject(it.data, "", projectRejected)))
                warnings += projectRejected.map { k -> ignoredNote(k, path) }
            }
            e?.let { fig = fig.withError(it) }
        }
        if (fig.error == null) fig = fig.merge(legacyFold(fig))
        fig = markIgnored(fig, projectRejected).merge(dotenv.layer())
        fig = markIgnored(fig, dotenv.rejected).merge(env.layer())
        flags?.let { fig = fig.merge(Layer("flag", it)) }
        return fig
    }

    fun proxyFlags(
        port: UShort?,
        dryRun: Boolean,
        upstream: String?,
        mode: String?,
    ): JsonObject? {
        val p =
            buildJsonObject {
                port?.let { put("port", it.toLong()) }
                if (dryRun) put("dry_run", true)
                upstream?.let { put("upstream", it) }
                mode?.let { put("mode", it) }
            }
        return if (p.isEmpty()) null else buildJsonObject { put("proxy", p) }
    }

    /** `rtok doctor --instructions`: `[doctor] instructions = true`. */
    fun doctorFlags(instructions: Boolean): JsonObject? = if (instructions) buildJsonObject { put("doctor", buildJsonObject { put("instructions", true) }) } else null

    fun tuiFlags(
        tab: String?,
        tickSecs: ULong?,
    ): JsonObject? {
        val t =
            buildJsonObject {
                tab?.let { put("tab", it) }
                tickSecs?.let { put("tick_secs", it.coerceAtMost(Long.MAX_VALUE.toULong()).toLong()) }
            }
        return if (t.isEmpty()) null else buildJsonObject { put("tui", t) }
    }

    fun webFlags(
        host: String?,
        port: UShort?,
    ): JsonObject? {
        val w =
            buildJsonObject {
                host?.let { put("host", it) }
                port?.let { put("port", it.toLong()) }
            }
        return if (w.isEmpty()) null else buildJsonObject { put("web", w) }
    }

    internal fun dotenvPairs(
        home: String,
        cwd: String?,
    ) = readDotenv(home, cwd).pairs

    fun load(
        home: String,
        configFile: String?,
        flags: JsonObject?,
        cwd: String? = runCatching { cwd() }.getOrNull(),
    ): Config {
        val dotenv = RtokEnv.fromDotenv(home, cwd)
        val warnings = ArrayList(dotenv.warnings)
        val fig = assemble(home, configFile, flags, RtokEnv.fromProcess(), cwd, dotenv, warnings)
        val cfg =
            try {
                fig.extract()
            } catch (e: ConfigException) {
                warnings.forEach { onEarly?.invoke(it) }
                throw e
            }
        val done = cfg.finish(home)
        done.loadedFrom = Config.userPath(home, configFile)
        for (w in warnings) done.notes += LoadNote(if (w.contains(": ignored project setting ")) "project" else "dotenv", w, w)
        notify(done)
        return done
    }

    fun leafKeys(): List<String> =
        envLeafTable.values
            .map { it.first }
            .distinct()
            .sorted()

    private val SECRET_KEYS = listOf("otel.headers", "mcp.token")

    private fun legacySourceFor(
        fig: Fig,
        key: String,
    ): String? {
        if (key.startsWith("web.")) return fig.findMetadata(key.replaceFirst("web.", "dashboard."))
        val legacy =
            when (key) {
                "log.path" -> "core.log_file"
                "log.level" -> "core.log_level"
                "log.to_db" -> "core.log_to_db"
                "plugins.inject.budget_tokens" -> "core.inject_budget_tokens"
                else -> return null
            }
        return fig.findMetadata(legacy)
    }

    /** (key, value, source) for every leaf key. */
    fun sourced(fig: Fig): List<Triple<String, JsonElement, String>> =
        leafKeys().mapNotNull { key ->
            val v = fig.findValue(key) ?: return@mapNotNull null
            val src = fig.findMetadata(key) ?: "default"
            Triple(key, v, if (src == "legacy") legacySourceFor(fig, key) ?: src else src)
        }

    /** (key, shown value, source) with secrets redacted — `rtok config show --sources`. */
    fun entries(fig: Fig): List<Triple<String, String, String>> =
        sourced(fig).map { (k, v, s) ->
            var shown = display(v)
            if (k in SECRET_KEYS && shown.isNotEmpty()) shown = "<redacted>"
            Triple(k, shown, s)
        }

    /** figment `Value` Display. */
    fun display(v: JsonElement): String =
        when (v) {
            is JsonNull -> ""
            is JsonPrimitive -> v.content
            is JsonObject -> "{ " + v.entries.joinToString(", ") { (k, x) -> "$k = ${display(x)}" } + " }"
            is JsonArray -> "[" + v.joinToString(", ") { display(it) } + "]"
        }
}

/** `.env` reader (dotenvy subset): `KEY=VALUE`, optional `export `, quotes, `#` comments. */
object Dotenv {
    /** Pairs up to the first malformed line, and that line's error (dotenvy stops there too). */
    fun parse(text: String): Pair<List<Pair<String, String>>, String?> {
        val out = ArrayList<Pair<String, String>>()
        try {
            parseInto(text, out)
        } catch (e: ConfigException) {
            return out to e.message
        }
        return out to null
    }

    private fun parseInto(
        text: String,
        out: MutableList<Pair<String, String>>,
    ) {
        for ((n, raw) in text.lines().withIndex()) {
            var line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            line = line.removePrefix("export ").trimStart()
            val eq = line.indexOf('=')
            if (eq <= 0) throw ConfigException("Error parsing line: '$raw', error at line index: $n")
            val key = line.substring(0, eq).trim()
            var v = line.substring(eq + 1).trim()
            v =
                when {
                    v.startsWith('"') -> {
                        val end = v.indexOf('"', 1)
                        if (end < 0) throw ConfigException("unterminated quote on line ${n + 1}")
                        v.substring(1, end).replace("\\n", "\n").replace("\\\"", "\"")
                    }

                    v.startsWith('\'') -> {
                        val end = v.indexOf('\'', 1)
                        if (end < 0) throw ConfigException("unterminated quote on line ${n + 1}")
                        v.substring(1, end)
                    }

                    else -> {
                        v.substringBefore(" #").trim()
                    }
                }
            out += key to v
        }
    }
}

fun isFile(path: String): Boolean =
    memScoped {
        val st = alloc<platform.posix.stat>()
        platform.posix.stat(path, st.ptr) == 0 && (st.st_mode.toInt() and platform.posix.S_IFMT) == platform.posix.S_IFREG
    }
