// Where the user rule files come from. Rust has a disk loader plus a cfg(test) Vfs twin of every
// function; here both sit behind one interface, so loading and checking are written once.
package rtok.plugins.cmd

import rtok.sys.IoException
import rtok.sys.Utf8Order
import rtok.sys.isDir
import rtok.sys.isFile
import rtok.sys.listDir

interface RuleFiles {
    /** UTF-8 body, null when [path] is missing; throws for any other read error. */
    fun read(path: String): String?
    /** The `*.toml` drop-ins under [dir], sorted by name; empty when [dir] is missing. */
    fun dropIns(dir: String): List<String>
}

/** The real filesystem. */
object DiskRuleFiles : RuleFiles {
    override fun read(path: String): String? {
        if (!isFile(path)) return null
        return try { rtok.sys.readToString(path) } catch (e: IoException) { if (e.kind == IoException.Kind.NotFound) null else throw e }
    }

    override fun dropIns(dir: String): List<String> {
        if (!isDir(dir)) return emptyList()
        // `Path::extension() == "toml"`: a dot after the first character, `toml` after the last one.
        val names = try { listDir(dir) } catch (_: IoException) { return emptyList() }
        return names.filter { n -> n.lastIndexOf('.').let { it > 0 && n.substring(it + 1) == "toml" } }
            .map { "${dir.trimEnd('/')}/$it" }.sortedWith(Utf8Order)
    }
}
