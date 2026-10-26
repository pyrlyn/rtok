// Port of the host-path helpers in src/fs.rs that rtok-sys cannot hold (they need ProjectRoot or ReadFs).
package rtok

import rtok.config.ProjectRoot
import rtok.config.ReadFs
import rtok.sys.*

object HostPaths {
    /**
     * Lexical join of [path] onto [root] where a `..` after a symlink is the parent of the link's target
     * (as a native lookup reads it), not the link's own parent (T590).
     */
    fun normalizeFollowing(
        fs: ReadFs,
        root: String,
        path: String,
    ): String {
        val out = ArrayList<String>(if (path.startsWith("/")) emptyList() else components(root))
        for (c in components(path)) {
            when (c) {
                "/" -> {
                    out.clear()
                    out += "/"
                }

                ".." -> {
                    fs.canonicalize(joinComponents(out))?.let {
                        out.clear()
                        out += components(it)
                    }
                    if (out.isNotEmpty() && out.last() != "/") out.removeAt(out.size - 1)
                }

                else -> {
                    out += c
                }
            }
        }
        return joinComponents(out)
    }

    /** Whether [dir] is a linked git worktree: its `.git` is a file whose admin dir names a main checkout. */
    fun isLinkedWorktree(dir: String): Boolean = isFile(join(dir, ".git")) && ProjectRoot.find(dir)?.let { it.checkout == dir && it.main != dir } == true

    /** Rust `Path::ends_with`, component-wise. */
    fun pathEndsWith(
        path: String,
        suffix: String,
    ): Boolean {
        val a = components(path)
        val b = components(suffix)
        return b.isNotEmpty() && a.size >= b.size && a.subList(a.size - b.size, a.size) == b
    }

    /** Two spellings of one file as hosts send them: equal, or one a component suffix of the other. */
    fun sameSpelling(
        a0: String,
        b0: String,
    ): Boolean {
        val a = a0.trim()
        val b = b0.trim()
        if (a.isEmpty() || b.isEmpty()) return false
        return samePath(a, b) || pathEndsWith(a, b) || pathEndsWith(b, a)
    }

    /** A credential file `read`/`search` refuse over HTTP: `.env*`, keys, ssh identities, rc files with tokens. */
    fun isSecret(path: String): Boolean {
        val name = fileName(path)?.asciiLowercase() ?: return false
        if (name == ".env") return true
        if (name.startsWith(".env.")) return name.removePrefix(".env.") !in setOf("example", "sample", "template", "dist")
        if (extension(name) in setOf("pem", "key", "p12", "pfx", "jks", "keystore")) return true
        if (name in
            setOf(
                "id_rsa",
                "id_dsa",
                "id_ecdsa",
                "id_ed25519",
                "id_ecdsa_sk",
                "id_ed25519_sk",
                ".netrc",
                ".npmrc",
                ".pypirc",
                ".pgpass",
                ".git-credentials",
                ".envrc",
                "credentials",
            )
        ) {
            return true
        }
        return name == "config" && parent(path)?.let(::fileName) == ".git"
    }
}
