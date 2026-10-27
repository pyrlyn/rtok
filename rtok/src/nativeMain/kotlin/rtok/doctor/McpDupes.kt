@file:Suppress("MagicNumber")

// Port of src/doctor/mcp_dupes.rs. Duplicate MCP entries (T331.4): one agent would start the same server twice.
// Entries are read with the `rtok-mcp` readers over the surfaces `agents` already knows (`McpSurfaces.surfaces`),
// plus Claude Code's project `.mcp.json` and local `projects.<cwd>` scopes. Two entries are copies when their
// normalized launch matches: the command resolved through `PATH` and symlinks, the same args and env, `npx -y pkg@x`
// as the package `pkg`, a URL compared after lowercasing scheme and host and dropping a trailing slash. The same
// package at two versions is a conflict, not a duplicate. The same name in two scopes is resolved by the host (local
// over project over user): the overridden entry is reported as unused. A server a file marks disabled is not running
// and not compared. Env values take part in the comparison and are never printed. Every copy is fixable (T331.6).
//
// A server of an enabled Claude plugin (T331.11) is read from the plugin's `.mcp.json`, with `${CLAUDE_PLUGIN_ROOT}`
// resolved, under the name `plugin:<plugin>:<server>`; the plugin's copy is the one kept and never fixable.
//
// rtok's own entries (T331.10) are not compared by launch: the entry named `rtok` in the host's own config file is
// kept and never fixable; a same-name copy in another scope or a plugin's copy is `own-mcp` information; only a
// hand-written copy under another name is a removable `duplicate-mcp` extra.

package rtok.doctor

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.agent.sdk.runsBin
import rtok.agents.Hosts
import rtok.agents.McpSurfaces
import rtok.agents.isRtokBin
import rtok.config.Config
import rtok.config.HOSTS
import rtok.mcp.config.McpConfig
import rtok.mcp.config.McpFs
import rtok.mcp.config.McpSpec
import rtok.mcp.config.RTOK
import rtok.sys.join

/** Where an entry sits in its file, for `--fix` to remove it (T331.6): the table and the name. */
internal data class Loc(
    val source: String,
    val path: String,
    val spec: McpSpec,
    val name: String,
)

@Suppress("TooManyFunctions")
internal object McpDupes {
    /** Where an entry lives, which decides the copy to keep and which same-name entry the host uses. */
    enum class Scope(
        val why: String,
        /** Claude Code resolves a same-name entry by precedence: local, project, user. */
        val wins: Int,
    ) {
        /** An enabled Claude plugin's `.mcp.json`: the plugin owns the file, so this copy is kept. */
        Plugin("enabled plugin", 0),
        Project("shared project file", 2),
        User("user file", 1),
        Local("local scope", 3),
        ;

        /** Keep order, best first: the same rule as for hooks (project, user, local). */
        val keep get() = ordinal
    }

    private class Source(
        val agent: String,
        val set: String,
        val spec: McpSpec,
        val scope: Scope,
        val plugin: Pair<String, String>?,
    )

    private class Srv(
        val agent: String,
        val set: String,
        val source: String,
        val path: String,
        val name: String,
        /** The command and args, or the URL. Never env. */
        val shown: String,
        /** The normalized launch, env included, version left out. */
        val key: String,
        val version: String?,
        val scope: Scope,
        val spec: McpSpec,
        val own: Boolean,
    )

    /** The `rtok-mcp` reader over the injected [ProbeFs], so mocks serve it too. */
    private class Reader(
        val fs: ProbeFs,
    ) : McpFs {
        override fun read(path: String): String? = runCatching { fs.read(path) }.getOrNull()

        override fun write(
            path: String,
            text: String,
        ): Unit = error("the doctor check never writes")
    }

    /** The location of every entry the check reads; a plugin's file is never edited, so its entries are not read. */
    fun locations(
        cfg: Config,
        p: Probes,
    ): List<Loc> = entries(cfg, p, emptyList()).map { Loc(it.source, it.path, it.spec, it.name) }

    @Suppress("NestedBlockDepth")
    private fun sources(
        cfg: Config,
        p: Probes,
        plugins: List<Pair<String, String>>,
    ): List<Source> {
        val out = mutableListOf<Source>()
        val cwd = p.env.cwd()?.takeIf { it.isNotEmpty() }
        for (agent in HOSTS.mapNotNull { Hosts.host(it) }) {
            for (v in agent.variants) {
                for (s in McpSurfaces.surfaces(agent.id, cfg, v.kind)) {
                    val set = "${agent.id}:${s.label}"

                    // By host, not set: variants that read one file (Cursor, Codex) would otherwise report every
                    // duplicate in it once per variant.
                    fun add(
                        spec: McpSpec,
                        scope: Scope,
                        plugin: Pair<String, String>?,
                    ) {
                        val seen = out.any { it.agent == agent.id && it.spec.configPath == spec.configPath && it.spec.keyPath == spec.keyPath }
                        if (!seen) out += Source(agent.id, set, spec, scope, plugin)
                    }
                    if (agent.id == "gemini") {
                        add(s.spec.copy(configPath = geminiManifest(cfg)), Scope.Plugin, null)
                    }
                    if (agent.id == "claude" && s.label == "cli" && cwd != null) {
                        add(s.spec.copy(configPath = join(cwd, ".mcp.json"), keyPath = listOf("mcpServers")), Scope.Project, null)
                        add(s.spec.copy(keyPath = listOf("projects", cwd, "mcpServers")), Scope.Local, null)
                        for ((id, dir) in plugins) {
                            add(s.spec.copy(configPath = join(dir, ".mcp.json"), keyPath = listOf("mcpServers")), Scope.Plugin, id to dir)
                        }
                    }
                    add(s.spec, Scope.User, null)
                }
            }
        }
        return out
    }

    /** agents/gemini `plugin_manifest`: `<extensions>/rtok/gemini-extension.json`. */
    private fun geminiManifest(cfg: Config) = join(join(join(cfg.setup.gemini.dir, "extensions"), RTOK.name), "gemini-extension.json")

    private fun str(e: JsonElement?) = (e as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun bool(e: JsonElement?) = (e as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()

    /** The `url` crate's normal form for the comparison: scheme and host lowercased, a trailing slash dropped. */
    internal fun normalUrl(url: String): String {
        val m = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://([^/?#]*)(.*)$").matchEntire(url) ?: return url.lowercase()
        val (scheme, authority, rest) = m.destructured
        val at = authority.lastIndexOf('@')
        val host = if (at < 0) authority.lowercase() else authority.substring(0, at + 1) + authority.substring(at + 1).lowercase()
        return "${scheme.lowercase()}://$host${rest.ifEmpty { "/" }}".trimEnd('/')
    }

    /** Rust's `{:?}` of a string list, so keys sort as they do there. */
    private fun debug(xs: List<String>) = xs.joinToString(", ", "[", "]") { "\"$it\"" }

    private data class Launch(
        val shown: String,
        val key: String,
        val version: String?,
    )

    private val RUNNERS = listOf("npx", "bunx", "pnpx", "uvx")

    /** `npx -y pkg@x` as the package `pkg` at version `x`. */
    private fun runner(
        base: String,
        rest: List<String>,
        shown: String,
        env: String,
    ): Launch? {
        val after = rest.dropWhile { it.startsWith("-") }
        val spec = after.firstOrNull() ?: return null
        val i = spec.lastIndexOf('@')
        val (pkg, version) = if (i > 0) spec.substring(0, i) to spec.substring(i + 1) else spec to null
        return Launch(shown, "$base $pkg ${debug(after.drop(1))} $env", version)
    }

    /** A server entry's launch; `null` for what is not running. */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun launch(
        entry: JsonElement,
        p: Probes,
    ): Launch? {
        val o = entry as? JsonObject ?: return null
        if (bool(o["disabled"]) == true || bool(o["enabled"]) == false) return null
        val env =
            (o["env"] as? JsonObject)
                .orEmpty()
                .entries
                .sortedBy { it.key }
                .joinToString(", ", "{", "}") { "${it.key}: ${it.value}" }
        listOf("url", "serverUrl", "httpUrl").firstNotNullOfOrNull { str(o[it]) }?.let { url ->
            return Launch(url, "url ${normalUrl(url)} ${str(o["type"]).orEmpty()} $env", null)
        }
        // opencode and kilo spell the whole argv as one `command` array.
        val argv =
            when (val c = o["command"]) {
                is JsonPrimitive -> if (c.isString) mutableListOf(c.content) else return null
                is JsonArray -> c.mapNotNull { str(it) }.toMutableList()
                else -> return null
            }
        argv += (o["args"] as? JsonArray).orEmpty().mapNotNull { str(it) }
        val shown = argv.joinToString(" ")
        val program = argv.firstOrNull() ?: return null
        val rest = argv.drop(1)
        val base = program.substringAfterLast('/').substringAfterLast('\\')
        if (base in RUNNERS) runner(base, rest, shown, env)?.let { return it }
        val path = if (program.contains('/') || program.contains('\\')) program else p.which.find(program) ?: program
        return Launch(shown, "${p.fs.canonical(path)} ${debug(rest)} $env", null)
    }

    /** `${CLAUDE_PLUGIN_ROOT}` in every string of a plugin's entry, as Claude Code resolves it. */
    private fun expandRoot(
        v: JsonElement,
        root: String,
    ): JsonElement =
        when (v) {
            is JsonObject -> JsonObject(v.mapValues { expandRoot(it.value, root) })
            is JsonArray -> JsonArray(v.map { expandRoot(it, root) })
            is JsonPrimitive -> if (v.isString) JsonPrimitive(v.content.replace("\${CLAUDE_PLUGIN_ROOT}", root)) else v
        }

    private fun entries(
        cfg: Config,
        p: Probes,
        plugins: List<Pair<String, String>>,
    ): List<Srv> {
        val out = mutableListOf<Srv>()
        for (s in sources(cfg, p, plugins)) {
            // A file that does not parse is `agents info`'s to report, not a duplicate check's.
            val servers = runCatching { McpConfig.readServers(Reader(p.fs), s.spec) }.getOrNull() ?: continue
            for ((raw, value) in servers) {
                var entry = value
                // Claude Code scopes a plugin's server as `plugin:<plugin>:<server>`.
                val name =
                    s.plugin?.let { (id, dir) ->
                        entry = expandRoot(entry, dir)
                        "plugin:${id.substringBefore('@')}:$raw"
                    } ?: raw
                val l = launch(entry, p) ?: continue
                out +=
                    Srv(
                        s.agent,
                        s.set,
                        s.spec.configPath,
                        "${s.spec.keyPath.joinToString(".")}.$name",
                        name,
                        l.shown,
                        l.key,
                        l.version,
                        s.scope,
                        s.spec,
                        runsBin(entry, ::isRtokBin),
                    )
            }
        }
        return out
    }

    private fun problem(
        s: Srv,
        kind: String,
        group: Int,
        keep: Boolean,
        detail: String,
    ) = Problem(
        kind = kind,
        agent = s.agent,
        source = s.source,
        path = s.path,
        command = s.shown,
        detail = detail,
        // The plugin owns its `.mcp.json` (T331.11).
        fixable = kind == "duplicate-mcp" && s.scope != Scope.Plugin,
        group = group,
        keep = keep,
    )

    private val pairOrder = compareBy<Pair<String, String>>({ it.first }, { it.second })

    /** Rust's `BTreeMap<K, Vec<_>>`: groups in key order, members in input order. */
    private fun <K> grouped(
        xs: List<Srv>,
        order: Comparator<K>,
        key: (Srv) -> K,
    ): List<List<Srv>> =
        xs
            .groupBy(key)
            .entries
            .sortedWith { a, b -> order.compare(a.key, b.key) }
            .map { it.value }

    /** Rust `max_by_key`: the last of the greatest. */
    private fun <T> lastMax(
        xs: List<T>,
        key: (T) -> Int,
    ): T = xs.reduce { best, x -> if (key(x) >= key(best)) x else best }

    /** Every group of copies, shadowed entries and version conflicts as one finding per entry. */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun check(
        cfg: Config,
        p: Probes,
        plugins: List<Pair<String, String>>,
    ): List<Problem> {
        val (own, all) = entries(cfg, p, plugins).partition { it.own }
        val out = mutableListOf<Problem>()
        var group = 0
        val next = { group++ }
        // The host uses one entry per name; the others never start.
        val live = mutableListOf<Srv>()
        for (copies in grouped(all, pairOrder) { it.set to it.name }) {
            val used = lastMax(copies) { it.scope.wins }
            live += used
            if (copies.size > 1) {
                val g = next()
                for (s in copies) {
                    val me = s === used
                    val detail =
                        if (me) {
                            "`${s.name}` is defined ${copies.size} times; the host uses this one"
                        } else {
                            "`${s.name}` is defined ${copies.size} times; unused, overridden by another scope"
                        }
                    out += problem(s, "duplicate-mcp", g, me, detail)
                }
            }
        }
        val versionOrder = Comparator<String?> { a, b -> if (a == null || b == null) compareValues(a != null, b != null) else a.compareTo(b) }
        val tripleOrder = compareBy<Triple<String, String, String?>>({ it.first }, { it.second }).thenBy(versionOrder) { it.third }
        val same = grouped(live, tripleOrder) { Triple(it.set, it.key, it.version) }
        val versions = grouped(live, pairOrder) { it.set to it.key }
        for (copies in same.filter { it.size > 1 }) {
            val g = next()
            // The first of equals is the first in load order.
            val keep = copies.minBy { it.scope.keep }
            for (s in copies) {
                val me = s === keep
                val detail = if (me) "runs ${copies.size} times; keep this copy (${s.scope.why})" else "runs ${copies.size} times; an extra copy"
                out += problem(s, "duplicate-mcp", g, me, detail)
            }
        }
        for (copies in versions) {
            val v = copies.map { it.version }.distinct()
            if (v.size > 1) {
                val g = next()
                for (s in copies) {
                    val at = s.version ?: "unpinned"
                    out += problem(s, "conflicting-mcp", g, false, "same package at ${v.size} versions, not duplicates; this one is $at")
                }
            }
        }
        out += ownFindings(own, next)
        return out
    }

    /**
     * rtok's own entries per host surface (T331.10). The kept copy is the entry `rtok` in the file install writes, else
     * the best-ranked one named `rtok`, else the best-ranked: never fixable.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun ownFindings(
        own: List<Srv>,
        next: () -> Int,
    ): List<Problem> {
        val out = mutableListOf<Problem>()
        val rank = compareBy<Srv>({ it.name != RTOK.name }, { it.scope != Scope.User }, { it.scope.keep })
        for (copies in grouped(own, naturalOrder()) { it.set }.filter { it.size > 1 }) {
            val kept = copies.minWith(rank)
            val host = kept.agent
            val (extra, info) = copies.filter { it !== kept }.partition { it.name != RTOK.name && it.scope != Scope.Plugin }
            // The host starts one process per name; a plugin copy or a same-name one is merged.
            val named = copies.filter { it.name == RTOK.name && it.scope != Scope.Plugin }
            val used = if (named.isEmpty()) "the config" else lastMax(named) { it.scope.wins }.scope.why

            // The kept copy is what install writes again, so removing it would only be undone.
            fun ownProblem(
                s: Srv,
                kind: String,
                g: Int,
                keep: Boolean,
                detail: String,
            ) = problem(s, kind, g, keep, detail).let { it.copy(fixable = it.fixable && !keep) }
            if (extra.isNotEmpty()) {
                val g = next()
                val n = extra.size + 1
                out += ownProblem(kept, "duplicate-mcp", g, true, "runs $n times; keep this copy (rtok's own entry, install writes it)")
                for (s in extra) out += ownProblem(s, "duplicate-mcp", g, false, "runs $n times; an extra copy that also launches rtok")
            }
            if (info.isNotEmpty()) {
                val g = next()
                val n = info.size + 1
                val said = "rtok is configured $n times; information only"
                if (extra.isEmpty()) out += ownProblem(kept, "own-mcp", g, true, "$said; this is the entry install writes, kept")
                for (s in info) {
                    val why =
                        if (s.scope == Scope.Plugin) {
                            "the host shadows or merges the plugin copy; `rtok agents update $host` brings it up to date"
                        } else {
                            "the host uses the $used entry named `rtok` and merges the rest"
                        }
                    out += ownProblem(s, "own-mcp", g, false, "$said: $why")
                }
            }
        }
        return out
    }
}
