// Port of crates/rtok-config/src/root.rs — project root discovery (main checkout, linked
// worktrees, submodules) and path containment that follows symlinks.
package rtok.config

import rtok.sys.*

interface ReadFs {
    fun readToString(path: String): String

    fun canonicalize(path: String): String?
}

object HostFs : ReadFs {
    override fun readToString(path: String) = rtok.sys.readToString(path)

    override fun canonicalize(path: String) = rtok.sys.canonicalize(path)
}

data class ProjectRoot(
    val checkout: String,
    val main: String,
    val gitDir: String,
) {
    companion object {
        fun find(cwd: String): ProjectRoot? = findWith(HostFs, cwd)

        fun findWith(
            fs: ReadFs,
            cwd: String,
        ): ProjectRoot? {
            var dir = cwd
            while (true) {
                val dot = join(dir, ".git")
                val gitDir =
                    try {
                        commonDir(fs, dir, fs.readToString(dot)) ?: dot
                    } catch (e: IoException) {
                        if (e.kind == IoException.Kind.NotFound && fs.canonicalize(dot) == null) {
                            dir = parent(dir) ?: return null
                            continue
                        }
                        dot
                    }
                val main = parent(gitDir) ?: return null
                return ProjectRoot(dir, main, gitDir)
            }
        }

        private fun commonDir(
            fs: ReadFs,
            root: String,
            dotGit: String,
        ): String? {
            val gitdir = dotGit.lines().firstNotNullOfOrNull { it.removePrefix("gitdir:").takeIf { s -> s !== it && it.startsWith("gitdir:") } }?.trim() ?: return null
            val gd = normalize(root, gitdir)
            val common =
                try {
                    fs.readToString(join(gd, "commondir"))
                } catch (_: IoException) {
                    return null
                }
            return normalize(gd, common.trim())
        }
    }
}

/** [path] with its deepest existing ancestor canonicalized and the rest folded lexically. */
fun resolved(path: String): String {
    for (a in ancestors(path)) {
        val canon = rtok.sys.canonicalize(a) ?: continue
        val rel = stripPrefix(path, a) ?: continue
        return normalize(canon, rel)
    }
    return canon(path)
}

/** [path] (relative to [root] or absolute) when it resolves inside [root]; PermissionDenied otherwise. */
fun contain(
    root: String,
    path: String,
): String {
    val landed = resolved(join(root, path))
    if (pathStartsWith(landed, resolved(root))) return landed
    throw IoException(IoException.Kind.PermissionDenied, "$path resolves to $landed, outside $root")
}
