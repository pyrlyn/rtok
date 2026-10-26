// The Rust `Vfs` twins of the rules loader: an in-memory [RuleFiles] keyed by path string.
package rtok.plugins.cmd

import rtok.sys.Utf8Order

class VfsRuleFiles : RuleFiles {
    private val files = LinkedHashMap<String, String>()
    fun write(path: String, text: String) { files[path] = text }
    override fun read(path: String) = files[path]
    /** `paths_under(dir)` filtered to `*.toml`, sorted. */
    override fun dropIns(dir: String): List<String> {
        val prefix = dir.trimEnd('/') + "/"
        return files.keys.filter { it.startsWith(prefix) && it.endsWith(".toml") }.sortedWith(Utf8Order)
    }
}
