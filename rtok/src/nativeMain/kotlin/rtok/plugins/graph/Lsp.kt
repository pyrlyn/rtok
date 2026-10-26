// Port of src/plugins/graph/lsp.rs (T30.2): the optional language-server backend. Server discovery (`on_path`,
// mise shims and rustup proxies, `rustup which`), `probe`, `pick`, state dirs under the workspace, `spawn` +
// `initialize`, and the `symbol`/`callers`/`impact`/`explore`/`outline` answers behind [LspBackend].
package rtok.plugins.graph

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import rtok.plugin.sdk.Class
import rtok.plugin.sdk.Ctx
import rtok.plugin.sdk.Measurement
import rtok.sys.IoException
import rtok.sys.PipedChild
import rtok.sys.ProgressRunner
import rtok.sys.ancestors
import rtok.sys.canonicalize
import rtok.sys.components
import rtok.sys.createDirAll
import rtok.sys.fileLen
import rtok.sys.isFile
import rtok.sys.join
import rtok.sys.parent
import rtok.sys.pathStartsWith
import rtok.sys.pid
import rtok.sys.readToString
import rtok.sys.tempDir
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

object Lsp : LspBackend {
    private const val POLL_MS = 250L
    private const val MAX_HOPS = 4
    private const val EXPLORE_PATH_HOPS = 3
    private const val RESOLVE_BEST = 5

    private val MARKERS =
        listOf(
            "Cargo.toml" to "rust",
            "compile_commands.json" to "c",
            "tsconfig.json" to "typescript",
            "pubspec.yaml" to "dart",
        )

    fun timeoutMs(): Long = LspTimeout.ms.value

    override fun setTimeoutMs(ms: ULong) {
        LspTimeout.ms.value = ms.toLong()
    }

    // --- discovery ---

    /** True when [bin] is installed; a mise shim or rustup proxy does not count. File presence only. */
    fun onPath(bin: String): Boolean = firstRealBin(bin) != null || (bin == "rust-analyzer" && rustupComponentPath() != null)

    private fun rustupComponentPath(): String? {
        val (c, _) =
            try {
                ProgressRunner("", "rustup", listOf("which", "rust-analyzer")).quiet().runCapped(Long.MAX_VALUE)
            } catch (_: IoException) {
                return null
            }
        if (c.exitCode != 0) return null
        val p = c.stdout.decodeToString().trim()
        return p.takeIf { it.isNotEmpty() && isFile(it) && !isMiseShim(it) }
    }

    private fun firstRealBin(name: String): String? =
        rtok.config
            .envVar("PATH")
            ?.split(':')
            ?.filter { it.isNotEmpty() }
            ?.map { join(it, name) }
            ?.firstOrNull(::isRealBin)

    private fun isRealBin(path: String) = isFile(path) && !isMiseShim(path) && !isRustupProxy(path)

    /** mise puts a shim on PATH for every tool in `mise.toml`, installed or not. */
    internal fun isMiseShim(path: String): Boolean = components(path).let { "mise" in it && "shims" in it }

    /** rustup's component proxies sit beside the `rustup` binary and are that binary (same size). */
    internal fun isRustupProxy(path: String): Boolean {
        val dir = parent(path) ?: return false
        val bin = fileLen(path) ?: return false
        val proxy = fileLen(join(dir, "rustup")) ?: return false
        return bin == proxy
    }

    private fun resolveBin(name: String): String = (if (name == "rust-analyzer") rustupComponentPath() else null) ?: firstRealBin(name) ?: name

    /** The server for the project at [root] by its first marker file that has one. */
    fun pick(root: String): LspServer =
        MARKERS.firstOrNull { isFile(join(root, it.first)) }?.let { Langs.serverOf(it.second) }
            ?: error("lsp: no Cargo.toml / compile_commands.json / tsconfig.json / pubspec.yaml in $root")

    override fun probe(root: String): Absent? {
        val bin = runCatching { pick(root).bin }.getOrElse { return Absent(false, it.message.orEmpty()) }
        return if (onPath(bin)) null else Absent(true, "lsp: $bin not on PATH")
    }

    /** State dirs for a spawned server, always under [root], never the user's home. */
    fun stateRoot(root: String): String =
        when {
            isFile(join(root, "Cargo.toml")) -> join(join(root, "target"), "rtok-lsp-xdg")
            isFile(join(root, "pubspec.yaml")) -> join(join(root, ".dart_tool"), "rtok-lsp-xdg")
            else -> join(root, ".rtok-lsp-xdg")
        }

    private fun stateEnv(root: String): Map<String, String> {
        val base = stateRoot(root)
        val env =
            mapOf(
                "XDG_CACHE_HOME" to join(base, "cache"),
                "XDG_DATA_HOME" to join(base, "data"),
                "XDG_STATE_HOME" to join(base, "state"),
                "PUB_CACHE" to join(base, "pub-cache"),
            )
        env.values.forEach { runCatching { createDirAll(it) } }
        return env
    }

    internal fun folders(root: String): JsonArray =
        buildJsonArray {
            add(
                buildJsonObject {
                    put("uri", LspWire.fileUri(root))
                    put("name", "root")
                },
            )
        }

    /** Starts the server for [root] and runs `initialize` + `initialized`. */
    fun spawn(root: String): LspSession {
        val server = pick(root)
        if (!onPath(server.bin)) error("lsp: ${server.bin} not on PATH")
        val path = resolveBin(server.bin)
        val errPath = join(tempDir(), "rtok-lsp-${pid()}.stderr")
        val child =
            try {
                PipedChild.spawn(path, server.args, cwd = root, extraEnv = stateEnv(root), stderrPath = errPath)
            } catch (e: IoException) {
                error("spawn $path (${server.bin}): ${e.message}")
            }
        val canon = canonicalize(root) ?: root
        val s = LspSession(child, canon, errPath)
        val uri = LspWire.fileUri(canon)
        val init =
            try {
                s.request("initialize", initParams(canon, uri))
            } catch (e: Refused) {
                s.close()
                throw e
            } catch (e: IllegalStateException) {
                val d = s.dead(e.message.orEmpty())
                s.close()
                throw d
            }
        s.syncChange = LspWire.syncKind(init)
        s.notify("initialized", buildJsonObject {})
        return s
    }

    private fun initParams(
        root: String,
        uri: String,
    ) = buildJsonObject {
        // Ours, not null: the server exits when rtok dies, even by SIGKILL.
        put("processId", pid())
        put("rootUri", uri)
        put("rootPath", root)
        putJsonObject("capabilities") {
            putJsonObject("workspace") {
                put("configuration", true)
                put("workspaceFolders", true)
            }
            putJsonObject("textDocument") { putJsonObject("documentSymbol") { put("hierarchicalDocumentSymbolSupport", true) } }
            putJsonObject("window") { put("workDoneProgress", true) }
        }
        put("workspaceFolders", folders(root))
    }

    // --- answers ---

    private class Def(
        val uri: String,
        val pos: JsonElement?,
        val kind: Long,
        val path: String,
        val line: Int,
        val endLine: Int,
    )

    private fun pickDef(
        r: JsonElement?,
        name: String,
        root: String,
    ): Def? =
        r.list.firstNotNullOfOrNull { it ->
            if (it["name"].str != name) return@firstNotNullOfOrNull null
            val loc = it["location"]
            val uri = loc["uri"].str ?: return@firstNotNullOfOrNull null
            val start = loc["range"]["start"]
            Def(
                uri = uri,
                pos = start,
                kind = it["kind"].long ?: 0,
                path = LspWire.rel(root, uri),
                line = (start["line"].long ?: 0).toInt() + 1,
                endLine = (loc["range"]["end"]["line"].long ?: 0).toInt() + 1,
            )
        }

    private fun query(q: String) = buildJsonObject { put("query", q) }

    private fun doc(uri: String) = buildJsonObject { putJsonObject("textDocument") { put("uri", uri) } }

    private fun waitDef(
        s: LspSession,
        name: String,
    ): Def? {
        val deadline = TimeSource.Monotonic.markNow() + timeoutMs().milliseconds
        while (true) {
            pickDef(s.request("workspace/symbol", query(name)), name, s.root)?.let { return it }
            if (deadline.hasPassedNow()) return null
            platform.posix.usleep((POLL_MS * 1000).toUInt())
        }
    }

    private fun contains(
        range: JsonElement?,
        line: Long,
        ch: Long,
    ): Boolean {
        val sl = range["start"]["line"].long ?: 0
        val sc = range["start"]["character"].long ?: 0
        val el = range["end"]["line"].long ?: 0
        val ec = range["end"]["character"].long ?: 0
        return (line > sl || (line == sl && ch >= sc)) && (line < el || (line == el && ch <= ec))
    }

    /** The innermost document symbol around `line:ch`, or "". */
    internal fun enclosing(
        syms: List<JsonElement>,
        line: Long,
        ch: Long,
    ): String {
        var best = ""

        fun walk(list: List<JsonElement>) {
            for (s in list) {
                if (!contains(s["range"], line, ch)) continue
                s["name"].str?.let { best = it }
                walk(s["children"].list)
            }
        }
        walk(syms)
        return best
    }

    private fun finish(
        cx: Ctx,
        tool: String,
        t0: TimeSource.Monotonic.ValueTimeMark,
        text: String,
    ): String {
        val kind =
            when (tool) {
                "symbol" -> "lsp.symbol"
                "callers" -> "lsp.callers"
                "outline" -> "lsp.outline"
                else -> "lsp.impact"
            }
        record(cx, kind, t0, text)
        return Tools.cap(cx, text)
    }

    private fun record(
        cx: Ctx,
        kind: String,
        t0: TimeSource.Monotonic.ValueTimeMark,
        text: String,
    ) {
        val est = cx.estimate(text, Class.Code)
        cx.record(
            Measurement(
                "graph",
                kind,
                t0.elapsedNow().inWholeMilliseconds.toULong(),
                text.encodeToByteArray().size.toULong(),
                est,
                est,
                null,
                cx.callId(),
            ),
        )
    }

    private fun body(
        cx: Ctx,
        root: String,
        path: String,
        line: Int,
        endLine: Int,
        budget: Int,
    ): String {
        val src = runCatching { readToString(join(root, path)) }.getOrDefault("")
        return Tools.bodyLines(src, line, endLine, budget) { runCatching { cx.putArchive(it) }.getOrNull() }
    }

    private fun symbolText(
        cx: Ctx,
        s: LspSession,
        name: String,
        filter: Filter,
        budget: Int,
    ): String {
        val out = StringBuilder()
        for (it in s.request("workspace/symbol", query(name)).list) {
            if (it["name"].str != name) continue
            val d = pickDef(JsonArray(listOf(it)), name, s.root) ?: continue
            val kind = LspWire.kindName(d.kind)
            if (!filter.pathOk(d.path) || !filter.kindOk(kind)) continue
            Tally.hit(s.root, name, listOf(d.path))
            out.append(Tools.defHead(d.path, name, kind, d.line)).append('\n')
            out.append(body(cx, s.root, d.path, d.line, d.endLine, budget))
        }
        return out.ifEmpty { "no definition of $name${filter.scopeNote()}\n" }.toString()
    }

    override fun symbol(
        cx: Ctx,
        root: String,
        name: String,
        filter: Filter,
    ): String {
        val t0 = TimeSource.Monotonic.markNow()
        val budget = graphCfg(cx).bodyLines.toInt()
        return LspSession.with(root) { s ->
            if (waitDef(s, name) == null) {
                finish(cx, "symbol", t0, "no definition of $name${filter.scopeNote()}")
            } else {
                finish(cx, "symbol", t0, symbolText(cx, s, name, filter, budget))
            }
        }
    }

    override fun callers(
        cx: Ctx,
        root: String,
        name: String,
        filter: Filter,
    ): String {
        val t0 = TimeSource.Monotonic.markNow()
        val none = "no references to $name${filter.scopeNote()}"
        return LspSession.with(root) { s ->
            val d = waitDef(s, name) ?: return@with finish(cx, "callers", t0, none)
            s.didOpen(d.uri)
            val refs =
                s.request(
                    "textDocument/references",
                    buildJsonObject {
                        putJsonObject("textDocument") { put("uri", d.uri) }
                        put("position", d.pos ?: buildJsonObject {})
                        putJsonObject("context") { put("includeDeclaration", false) }
                    },
                )
            val groups = HashMap<Pair<String, String>, IntArray>()
            val symsBy = HashMap<String, List<JsonElement>>()
            for (loc in refs.list) {
                val uri = loc["uri"].str.orEmpty()
                s.didOpen(uri)
                val path = LspWire.rel(s.root, uri)
                val line0 = loc["range"]["start"]["line"].long ?: 0
                val ch = loc["range"]["start"]["character"].long ?: 0
                val line = line0.toInt() + 1
                val kids = symsBy.getOrPut(uri) { s.request("textDocument/documentSymbol", doc(uri)).list }
                val e = groups.getOrPut(path to enclosing(kids, line0, ch)) { intArrayOf(0, line) }
                e[0]++
                if (line < e[1]) e[1] = line
            }
            val out = StringBuilder()
            for ((k, v) in groups.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))) {
                val (path, scope) = k
                if (!filter.pathOk(path)) continue
                Tally.hit(s.root, name, listOf(path))
                out.append("$path${if (scope.isEmpty()) "" else "  $scope"} ×${v[0]} (L${v[1]})\n")
            }
            finish(cx, "callers", t0, out.ifEmpty { none }.toString())
        }
    }

    private fun prepare(
        s: LspSession,
        d: Def,
    ): List<JsonElement> {
        s.didOpen(d.uri)
        return s
            .request(
                "textDocument/prepareCallHierarchy",
                buildJsonObject {
                    putJsonObject("textDocument") { put("uri", d.uri) }
                    put("position", d.pos ?: buildJsonObject {})
                },
            ).list
    }

    private fun incoming(
        s: LspSession,
        item: JsonElement,
    ) = s.request("callHierarchy/incomingCalls", buildJsonObject { put("item", item) }).list

    /** Incoming-call BFS from [name] out to [depth]: one row per definition reached (scope "" at file level). */
    private fun impactWalk(
        s: LspSession,
        name: String,
        depth: Int,
        filter: Filter,
    ): List<WalkRow> {
        val d = waitDef(s, name) ?: return emptyList()
        var frontier = prepare(s, d)
        val seen = hashSetOf(name)
        val rows = ArrayList<WalkRow>()
        for (dp in 1..depth.coerceIn(1, MAX_HOPS)) {
            val next = ArrayList<JsonElement>()
            for (item in frontier) {
                for (c in incoming(s, item)) {
                    val from = c["from"] ?: continue
                    val nm = from["name"].str.orEmpty()
                    if (!seen.add(nm)) continue
                    val path = LspWire.rel(s.root, from["uri"].str.orEmpty())
                    if (!filter.pathOk(path)) continue
                    rows += WalkRow(dp, path, nm)
                    if (nm.isNotEmpty()) next += from
                }
            }
            frontier = next
            if (frontier.isEmpty()) break
        }
        return rows
    }

    override fun impact(
        cx: Ctx,
        root: String,
        name: String,
        depth: Int,
        filter: Filter,
        to: String?,
    ): String {
        val t0 = TimeSource.Monotonic.markNow()
        return LspSession.with(root) { s ->
            val target = to?.takeIf { it.isNotEmpty() }
            if (target != null) {
                val chains = callPaths(s, name, target, depth)
                if (chains.isEmpty()) return@with finish(cx, "impact", t0, "no path from $name to $target within depth $depth")
                Tally.hit(s.root, name, emptyList())
                return@with finish(cx, "impact", t0, chains.joinToString("\n") + "\n")
            }
            val rows = impactWalk(s, name, depth, filter)
            if (rows.isEmpty()) return@with finish(cx, "impact", t0, "nothing reaches $name${filter.scopeNote()}")
            rows.forEach { Tally.hit(s.root, name, listOf(it.path)) }
            finish(cx, "impact", t0, Blast.linesText(rows))
        }
    }

    /** Simple call chains `a → … → b` over incoming calls, at most [maxDepth] hops. */
    private fun callPaths(
        s: LspSession,
        a: String,
        b: String,
        maxDepth: Int,
    ): List<String> {
        if (a == b) return emptyList()
        val d = waitDef(s, a) ?: return emptyList()
        var frontier = prepare(s, d).map { Triple(it, a, listOf(a)) }
        val chains = HashSet<String>()
        repeat(maxDepth.coerceIn(1, MAX_HOPS)) {
            if (frontier.isEmpty()) return chains.sorted()
            val next = ArrayList<Triple<JsonElement, String, List<String>>>()
            for ((item, tip, chain) in frontier) {
                if (tip == b) {
                    chains += chain.joinToString(" → ")
                    continue
                }
                for (c in incoming(s, item)) {
                    val from = c["from"] ?: continue
                    val nm = from["name"].str.orEmpty()
                    if (nm.isEmpty() || nm in chain) continue
                    val c2 = chain + nm
                    if (nm == b) chains += c2.joinToString(" → ") else next += Triple(from, nm, c2)
                }
            }
            frontier = next
        }
        return chains.sorted()
    }

    private class LspExplore(
        val cx: Ctx,
        val s: LspSession,
        val filter: Filter,
        val budget: Int,
    ) : ExploreParts {
        override fun resolve(token: String): List<String> {
            val prefixed = HashSet<String>()
            for (it in s.request("workspace/symbol", query(token)).list) {
                val n = it["name"].str ?: continue
                if (n == token) return listOf(token)
                if (n.startsWith(token)) prefixed += n
            }
            return prefixed.sorted().take(RESOLVE_BEST)
        }

        override fun defs(name: String) = symbolText(cx, s, name, filter, budget)

        override fun paths(
            a: String,
            b: String,
        ) = callPaths(s, a, b, EXPLORE_PATH_HOPS)

        override fun impact1(name: String): Pair<String, Int> {
            val rows = impactWalk(s, name, 1, filter)
            return if (rows.isEmpty()) "nothing reaches $name" to 0 else Blast.linesText(rows) to rows.size
        }

        override fun defCount(name: String) = 1
    }

    override fun explore(
        cx: Ctx,
        root: String,
        query: String,
        filter: Filter,
    ): String {
        val t0 = TimeSource.Monotonic.markNow()
        val budget = graphCfg(cx).bodyLines.toInt()
        return LspSession.with(root) { s ->
            val (text, before) = Explore.assemble(query, filter, LspExplore(cx, s, filter, budget))
            record(cx, "lsp.explore", t0, text)
            Tools.capKind(cx, text, before.toULong(), "explore")
        }
    }

    private fun flatten(
        syms: List<JsonElement>,
        out: StringBuilder,
    ) {
        for (s in syms) {
            val name = s["name"].str.orEmpty()
            val line = (s["range"]["start"]["line"].long ?: 0) + 1
            val prefix = LspWire.kindName(s["kind"].long ?: 0).let { if (it == "function" || it == "method") "fn" else it }
            out.append("$prefix $name $line\n")
            flatten(s["children"].list, out)
        }
    }

    /** The nearest ancestor of [file] with a marker that has a server, else its directory. */
    fun workspaceOf(file: String): String = ancestors(file).drop(1).firstOrNull { d -> MARKERS.any { isFile(join(d, it.first)) } } ?: (parent(file) ?: file)

    override fun outlineWorkspace(
        root: String,
        abs: String,
    ): String {
        val near = workspaceOf(abs)
        val a = runCatching { pick(root) }.getOrNull()
        val b = runCatching { pick(near) }.getOrNull()
        return if (a != null && a == b && pathStartsWith(abs, root)) root else near
    }

    override fun outline(
        cx: Ctx,
        root: String,
        path: String,
    ): String {
        val t0 = TimeSource.Monotonic.markNow()
        val abs = if (path.startsWith('/')) path else join(root, path)
        return LspSession.with(outlineWorkspace(root, abs)) { s ->
            val uri = LspWire.fileUri(abs)
            s.didOpen(uri)
            val deadline = TimeSource.Monotonic.markNow() + timeoutMs().milliseconds
            while (true) {
                val text = StringBuilder().also { flatten(s.request("textDocument/documentSymbol", doc(uri)).list, it) }
                if (text.isNotEmpty() || deadline.hasPassedNow()) {
                    if (text.isNotEmpty()) Tally.hit(root, "", listOf(path))
                    return@with finish(cx, "outline", t0, text.trimEnd().toString())
                }
                platform.posix.usleep((POLL_MS * 1000).toUInt())
            }
            @Suppress("UNREACHABLE_CODE")
            ""
        }
    }
}
