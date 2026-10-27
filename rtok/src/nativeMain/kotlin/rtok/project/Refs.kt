// Port of src/project/refs.rs (T329.7): the directories outside a project's root that its
// manifests point at, plus a warning for each path that does not exist. A pure read of the
// manifests in the order Cargo, npm/pnpm/yarn, Go, Python, `.gitmodules`.
package rtok.project

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.config.Toml
import rtok.json.StrictJson
import rtok.sys.IoException
import rtok.sys.components
import rtok.sys.exists
import rtok.sys.globToRegex
import rtok.sys.isDir
import rtok.sys.isFile
import rtok.sys.listDir
import rtok.sys.normalize
import rtok.sys.parent
import rtok.sys.pathStartsWith
import rtok.sys.readToString
import rtok.sys.stripPrefix

/** One referenced directory and the manifest entry that names it. */
data class Reference(
    val dir: String,
    val reason: String,
)

/** Everything [Refs.discover] found: the existing directories, and what could not be followed. */
data class Found(
    val refs: MutableList<Reference> = ArrayList(),
    val warnings: MutableList<String> = ArrayList(),
)

object Refs {
    /** The directories outside [root] that the manifests under it reference, first source wins. */
    fun discover(root: String): Found {
        val c = Reader(normalize("/", root))
        cargo(c)
        npm(c)
        go(c)
        python(c)
        submodules(c)
        return c.found
    }

    internal class Reader(
        val root: String,
    ) {
        val found = Found()

        /** Record [written] (as the manifest [from] spells it), resolved against [base]. */
        fun add(
            from: String,
            base: String,
            written: String,
            reason: String,
            insideOk: Boolean,
        ) {
            if (written.isEmpty() || written.startsWith('~')) return
            val dir = normalize(base, written)
            // A path that contains the root would swallow the project instead of referencing it.
            val own = pathStartsWith(dir, root) || pathStartsWith(root, dir)
            if (own && !insideOk) return
            if (isDir(dir)) {
                if (found.refs.none { it.dir == dir }) found.refs += Reference(dir, reason)
            } else if (!exists(dir)) {
                // A file is a tarball or a wheel, not a directory to index.
                val w = "${rel(from)}: references $written, not found"
                if (w !in found.warnings) found.warnings += w
            }
        }

        private fun rel(p: String) = stripPrefix(p, root)?.ifEmpty { null } ?: p

        /** The text of a manifest. A missing file is no finding; one that cannot be read is. */
        fun read(path: String): String? =
            try {
                readToString(path)
            } catch (e: IoException) {
                if (e.kind != IoException.Kind.NotFound && exists(path)) warn(path, e.message ?: "error")
                null
            }

        fun warn(
            path: String,
            e: String,
        ) {
            found.warnings += "${rel(path)}: not read: $e"
        }

        fun toml(path: String): JsonObject? {
            val text = read(path) ?: return null
            return try {
                Toml.parse(text)
            } catch (e: Exception) {
                warn(path, e.message ?: "invalid TOML")
                null
            }
        }

        fun json(path: String): JsonObject? {
            val text = read(path) ?: return null
            return try {
                StrictJson.parse(text) as? JsonObject ?: throw IllegalArgumentException("not an object")
            } catch (e: Exception) {
                warn(path, e.message ?: "invalid JSON")
                null
            }
        }

        /** Workspace member patterns into the directories to read; the ones outside the root are references. */
        fun members(
            from: String,
            patterns: List<String>,
            marker: String,
            what: String,
        ): List<String> {
            val base = parent(from) ?: root
            // pnpm and npm: a `!pattern` removes its matches from the others, wherever it stands.
            val skipped =
                patterns
                    .mapNotNull { it.removePrefix("!").takeIf { _ -> it.startsWith('!') } }
                    .map { Regex(globToRegex(it.removePrefix("./").trimEnd('/') + "/" + marker)) }
            val dirs = ArrayList<String>()
            for (pat in patterns.filter { !it.startsWith('!') }) {
                val glob = pat.any { it in GLOB }
                for (dir in expand(base, pat, marker)) {
                    val r = diff(dir, base) + "/" + marker
                    if (skipped.any { it.matches(r) }) continue
                    add(from, base, if (glob) dir else pat, "$what member $pat", false)
                    dirs += dir
                }
            }
            return dirs
        }
    }

    private const val GLOB = "*?[{"

    /** `pathdiff::diff_paths(dir, base)`, both absolute and normalized. */
    internal fun diff(
        dir: String,
        base: String,
    ): String {
        val d = components(dir)
        val b = components(base)
        var i = 0
        while (i < d.size && i < b.size && d[i] == b[i]) i++
        return (List(b.size - i) { ".." } + d.drop(i)).joinToString("/").ifEmpty { "." }
    }

    /** One path segment of a globset pattern: `*`, `?`, `[...]`, `{a,b}`. */
    internal fun segment(glob: String): Regex {
        val alt = Regex("\\{([^{}]*)\\}").find(glob)
        val body =
            if (alt == null) {
                globToRegex(glob)
            } else {
                val pre = glob.substring(0, alt.range.first)
                val post = glob.substring(alt.range.last + 1)
                alt.groupValues[1].split(',').joinToString("|", "(?:", ")") { segment(pre + it + post).pattern }
            }
        return Regex(body)
    }

    /** [pattern] below [base]: the directory itself without a glob, else the matches that hold [marker]. */
    internal fun expand(
        base: String,
        pattern: String,
        marker: String,
    ): List<String> {
        if (pattern.none { it in GLOB }) return listOf(normalize(base, pattern))
        var dirs = listOf(base)
        for (raw in components(pattern)) {
            val seg = if (raw == "**") "*" else raw
            if (seg.none { it in GLOB }) {
                dirs = dirs.map { normalize(it, seg) }
                continue
            }
            val re = runCatching { segment(seg) }.getOrNull() ?: return emptyList()
            dirs = dirs.flatMap { d -> runCatching { listDir(d) }.getOrDefault(emptyList()).filter { re.matches(it) }.map { "$d/$it" } }.sorted()
        }
        return dirs.filter { isFile("$it/$marker") }
    }

    private fun obj(e: JsonElement?) = e as? JsonObject

    private fun str(e: JsonElement?) = (e as? JsonPrimitive)?.takeIf { it.isString }?.content

    internal fun strings(e: JsonElement?): List<String> = (e as? JsonArray)?.mapNotNull(::str).orEmpty()

    /** `path = "…"` of each dependency of a table, with the dependency's name. */
    internal fun depPaths(t: JsonObject?): List<Pair<String, String>> = t?.entries?.mapNotNull { (name, v) -> str(obj(v)?.get("path"))?.let { name to it } }.orEmpty()

    private fun cargo(c: Reader) {
        val manifest = "${c.root}/Cargo.toml"
        val doc = c.toml(manifest) ?: return
        val members = strings(obj(doc["workspace"])?.get("members"))
        val dirs = listOf(c.root) + c.members(manifest, members, "Cargo.toml", "cargo workspace")
        val kinds = listOf("dependencies", "dev-dependencies", "build-dependencies")
        for (dir in dirs) {
            val file = "$dir/Cargo.toml"
            val d = (if (dir == c.root) doc else c.toml(file)) ?: continue
            val tables = ArrayList<Pair<String, JsonObject?>>()
            kinds.forEach { tables += "path dependency" to obj(d[it]) }
            tables += "workspace dependency" to obj(obj(d["workspace"])?.get("dependencies"))
            obj(d["target"])?.values?.forEach { t -> kinds.forEach { tables += "path dependency" to obj(obj(t)?.get(it)) } }
            obj(d["patch"])?.values?.forEach { tables += "[patch]" to obj(it) }
            for ((what, t) in tables) for ((name, path) in depPaths(t)) c.add(file, dir, path, "cargo $what $name", false)
        }
    }

    private val NPM_LOCAL = listOf("file:", "link:", "workspace:")

    private fun npm(c: Reader) {
        val manifest = "${c.root}/package.json"
        val rootPkg = c.json(manifest)
        val patterns = ArrayList<String>()
        rootPkg?.get("workspaces")?.let { ws -> patterns += strings(obj(ws)?.get("packages") ?: ws) }
        val pnpm = "${c.root}/pnpm-workspace.yaml"
        c.read(pnpm)?.let { patterns += pnpmPackages(it) }
        val dirs = listOf(c.root) + c.members(manifest, patterns, "package.json", "npm workspace")
        for (dir in dirs) {
            val file = "$dir/package.json"
            val pkg = (if (dir == c.root) rootPkg else c.json(file)) ?: continue
            for (table in listOf("dependencies", "devDependencies", "optionalDependencies", "peerDependencies")) {
                for ((name, spec) in obj(pkg[table]) ?: continue) {
                    val s = str(spec) ?: continue
                    val local = NPM_LOCAL.firstNotNullOfOrNull { p -> s.takeIf { it.startsWith(p) }?.removePrefix(p) }
                    // `workspace:*` and `workspace:^1` name a workspace package, not a path.
                    if (local != null && (local.startsWith('.') || local.startsWith('/'))) c.add(file, dir, local, "npm $table $name", false)
                }
            }
        }
    }

    /** The `packages:` list of a `pnpm-workspace.yaml` (block or flow sequence of scalars). */
    internal fun pnpmPackages(text: String): List<String> {
        val out = ArrayList<String>()
        var inside = false

        fun unquote(s: String) = s.trim().removeSurrounding("'").removeSurrounding("\"")
        for (raw in text.lines()) {
            val line = raw.substringBefore(" #").trimEnd()
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            if (!line.startsWith(' ') && !line.startsWith('-')) {
                inside = false
                val (k, v) = line.split(':', limit = 2).let { it[0].trim() to it.getOrElse(1) { "" }.trim() }
                if (k == "packages") {
                    if (v.startsWith('[')) {
                        out +=
                            v
                                .removePrefix("[")
                                .removeSuffix("]")
                                .split(',')
                                .map(::unquote)
                                .filter { it.isNotEmpty() }
                    } else {
                        inside = true
                    }
                }
            } else if (inside && line.trimStart().startsWith("- ")) {
                out += unquote(line.trimStart().removePrefix("- "))
            }
        }
        return out
    }

    internal fun goDirectives(
        text: String,
        verbs: List<String>,
    ): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var block: String? = null
        for (raw in text.lines()) {
            val line = raw.substringBefore("//").trim()
            if (line == ")") {
                block = null
                continue
            }
            if (block != null) {
                out += block to line
                continue
            }
            val opens = verbs.firstOrNull { line.startsWith(it) && line.removePrefix(it).trim() == "(" }
            if (opens != null) {
                block = opens
                continue
            }
            for (v in verbs) {
                val rest = line.removePrefix(v)
                if (rest.length < line.length && (rest.startsWith(' ') || rest.startsWith('\t'))) {
                    out += v to rest.trim()
                    break
                }
            }
        }
        return out
    }

    private fun goPath(s: String) = s.trim('"', '`')

    private fun go(c: Reader) {
        val work = "${c.root}/go.work"
        val dirs = arrayListOf(c.root)
        c.read(work)?.let { text ->
            for ((verb, rest) in goDirectives(text, listOf("use", "replace"))) {
                if (verb == "use") {
                    val used = goPath(rest)
                    c.add(work, c.root, used, "go.work use $used", false)
                    dirs += normalize(c.root, used)
                } else {
                    goReplace(c, work, c.root, rest)
                }
            }
        }
        for (dir in dirs) {
            val file = "$dir/go.mod"
            val text = c.read(file) ?: continue
            for ((_, rest) in goDirectives(text, listOf("replace"))) goReplace(c, file, dir, rest)
        }
    }

    private fun goReplace(
        c: Reader,
        file: String,
        dir: String,
        spec: String,
    ) {
        val at = spec.indexOf("=>").takeIf { it >= 0 } ?: return
        val path =
            goPath(
                spec
                    .substring(at + 2)
                    .trim()
                    .split(' ', '\t')
                    .firstOrNull()
                    .orEmpty(),
            )
        if (path.startsWith("./") || path.startsWith("../") || path.startsWith("/")) {
            val old =
                spec
                    .substring(0, at)
                    .trim()
                    .split(' ', '\t')
                    .firstOrNull()
                    .orEmpty()
            c.add(file, dir, path, "go replace $old", false)
        }
    }

    internal fun pep508File(req: String): String? {
        val at = req.indexOf(" @ ").takeIf { it >= 0 } ?: return null
        val url = req.substring(at + 3).substringBefore(';').trim()
        val path = url.takeIf { it.startsWith("file:") }?.removePrefix("file:") ?: return null
        return path.removePrefix("//")
    }

    private fun python(c: Reader) {
        val file = "${c.root}/pyproject.toml"
        val doc = c.toml(file) ?: return requirements(c)
        val found = ArrayList<Pair<String, String>>()
        val project = obj(doc["project"])
        val lists = ArrayList<List<String>>()
        lists += strings(project?.get("dependencies"))
        for (g in listOf(project?.get("optional-dependencies"), doc["dependency-groups"])) obj(g)?.values?.forEach { lists += strings(it) }
        for (req in lists.flatten()) {
            val p = pep508File(req) ?: continue
            found += p to "python path dependency ${req.split('@', ' ').first()}"
        }
        val tool = obj(doc["tool"])
        val poetry = obj(tool?.get("poetry"))
        val tables = arrayListOf(obj(poetry?.get("dependencies")))
        obj(poetry?.get("group"))?.values?.forEach { tables += obj(obj(it)?.get("dependencies")) }
        val uv = obj(tool?.get("uv"))
        tables += obj(uv?.get("sources"))
        for (t in tables) for ((n, p) in depPaths(t)) found += p to "python path dependency $n"
        for ((path, reason) in found) c.add(file, c.root, path, reason, false)
        c.members(file, strings(obj(uv?.get("workspace"))?.get("members")), "pyproject.toml", "uv workspace")
        requirements(c)
    }

    private fun requirements(c: Reader) {
        val file = "${c.root}/requirements.txt"
        val text = c.read(file) ?: return
        for (l in text.lines().map { it.trim() }) {
            val path = (l.takeIf { it.startsWith("-e ") }?.removePrefix("-e ") ?: l.takeIf { it.startsWith("--editable ") }?.removePrefix("--editable ") ?: l).trim()
            if (path.startsWith("./") || path.startsWith("../")) c.add(file, c.root, path, "python requirement $path", false)
        }
    }

    /** `path = …` of each `[submodule "…"]` section of `.gitmodules`. */
    private fun submodules(c: Reader) {
        val file = "${c.root}/.gitmodules"
        val text = c.read(file) ?: return
        var section = false
        for (line in text.lines().map { it.trim() }) {
            if (line.startsWith('[')) {
                section = line.startsWith("[submodule")
                continue
            }
            val eq = line.indexOf('=')
            if (section && eq >= 0 && line.substring(0, eq).trim() == "path") {
                val path = line.substring(eq + 1).trim().trim('"')
                c.add(file, c.root, path, "git submodule $path", true)
            }
        }
    }
}
