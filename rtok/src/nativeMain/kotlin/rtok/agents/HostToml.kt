// Port of src/agents/mod.rs `load_toml`: a host's TOML config as a lossless [TomlDoc] (comments and layout kept). An
// absent file is an empty document; an unreadable or invalid one is an error, so a config that was not read is never
// overwritten.
package rtok.agents

import rtok.config.Toml
import rtok.config.TomlException
import rtok.sys.exists
import rtok.sys.readToString
import tomlaot.TomlDoc

object HostToml {
    fun load(path: String): TomlDoc {
        if (!exists(path)) return TomlDoc.parse("")
        val raw = readToString(path)
        try {
            Toml.parse(raw)
        } catch (e: TomlException) {
            throw IllegalStateException("$path: ${e.message}", e)
        }
        return TomlDoc.parse(raw)
    }
}
