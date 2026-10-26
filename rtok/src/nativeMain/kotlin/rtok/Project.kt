// Port of src/project.rs — project identity: the checkout `cwd` belongs to, resolved so that a
// linked worktree and its main checkout share one name. Lexical: git is never spawned.
package rtok

import rtok.config.HostFs
import rtok.config.ProjectRoot
import rtok.config.ReadFs
import rtok.sys.asciiLowercase
import rtok.sys.canon
import rtok.sys.components
import rtok.sys.join
import rtok.sys.normalize

/** Name of the project `cwd` lies in: the `origin` repo name, else the main checkout's basename. */
fun projectName(cwd: String): String? = projectNameWith(HostFs, cwd)

fun projectNameWith(
    fs: ReadFs,
    cwd: String,
): String? {
    val root = ProjectRoot.findWith(fs, cwd) ?: return null
    return originUrl(fs, root.gitDir)?.let(::urlName) ?: components(root.main).lastOrNull()?.takeIf { it != "/" }
}

/** The key per-project state is filed under: `host/owner/repo` (lower case), a local remote's path, or the main checkout's path. */
fun projectKey(cwd: String): String? = projectKeyWith(HostFs, cwd)

fun projectKeyWith(
    fs: ReadFs,
    cwd: String,
): String? {
    val root = ProjectRoot.findWith(fs, cwd) ?: return null
    val origin = originUrl(fs, root.gitDir)
    origin?.let(::localRemote)?.let { return localKey(fs, root.main, it) }
    origin?.let(::canonicalUrl)?.let { return it }
    return fs.canonicalize(root.main) ?: root.main
}

/** `cwd` when it lies in a checkout; else the first of [extra] that does; else [noProjectRoot]. */
fun projectDir(
    cwd: String,
    extra: List<String>,
): String {
    if (ProjectRoot.find(cwd) != null) return cwd
    return extra.firstOrNull { ProjectRoot.find(it) != null } ?: throw noProjectRoot(cwd)
}

fun noProjectRoot(cwd: String) = IllegalStateException("no project root: rtok mcp runs in ${canon(cwd)}; pass a path or open a project")

private fun isScpHost(host: String) = !host.contains('/') && !host.contains('\\') && host.length > 1

/** The filesystem path an `origin` URL names, or null for a network remote. */
internal fun localRemote(raw: String): String? {
    val url = raw.trim()
    if (url.startsWith("file://")) {
        val rest = url.removePrefix("file://")
        val path = rest.removePrefix("localhost")
        val drive = path.length >= 3 && path[1].isAsciiLetter() && path[2] == ':'
        return if (drive) path.substring(1) else path
    }
    val scp = url.indexOf(':').let { it >= 0 && isScpHost(url.substring(0, it)) }
    return url.takeIf { it.isNotEmpty() && !it.contains("://") && !scp }
}

private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

private fun localKey(
    fs: ReadFs,
    main: String,
    path: String,
): String {
    val abs = normalize("", join(main, path))
    val resolved = (fs.canonicalize(abs) ?: abs).trimEnd('/', '\\')
    return resolved.removeSuffix(".git")
}

/** `[remote "origin"] url` in `<common>/config`. */
private fun originUrl(
    fs: ReadFs,
    common: String,
): String? {
    val config = runCatching { fs.readToString(join(common, "config")) }.getOrNull() ?: return null
    var inOrigin = false
    for (raw in config.lines()) {
        val line = raw.trim()
        if (line.startsWith("[")) {
            inOrigin = line == "[remote \"origin\"]"
            continue
        }
        val eq = line.indexOf('=').takeIf { it >= 0 } ?: continue
        if (inOrigin && line.substring(0, eq).trim() == "url") return line.substring(eq + 1).trim()
    }
    return null
}

/** Last path segment of a remote URL, without `.git`. */
internal fun urlName(url: String): String? {
    val trimmed = url.trimEnd('/')
    val cut = trimmed.lastIndexOfAny(charArrayOf('/', ':'))
    return trimmed.substring(cut + 1).removeSuffixRepeated(".git").ifEmpty { null }
}

/** Rust `trim_end_matches(".git")` removes every trailing repeat. */
private fun String.removeSuffixRepeated(s: String): String {
    var r = this
    while (r.endsWith(s)) r = r.dropLast(s.length)
    return r
}

/** `git@github.com:Owner/Repo.git` and friends → `github.com/owner/repo`; a path keeps its path. */
internal fun canonicalUrl(raw: String): String? {
    var url = raw.trim().trimEnd('/')
    url = url.removeSuffix(".git")
    val sep = url.indexOf("://")
    val scp = sep < 0
    var rest = if (scp) url else url.substring(sep + 3)
    val at = rest.indexOf('@')
    if (at >= 0 && !rest.substring(0, at).contains('/')) rest = rest.substring(at + 1)
    val colon = rest.indexOf(':')
    if (colon >= 0 && scp && isScpHost(rest.substring(0, colon))) rest = rest.substring(0, colon) + "/" + rest.substring(colon + 1).trimStart('/')
    return rest.ifEmpty { null }?.asciiLowercase()
}
