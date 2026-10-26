// Port of the `Vfs` in src/testutil.rs (the ReadFs part): files by absolute path, directories implied.
package rtok.support

import rtok.config.ReadFs
import rtok.sys.IoException
import rtok.sys.components
import rtok.sys.joinComponents

class Vfs : ReadFs {
    private val files = HashMap<String, String>()
    private fun key(p: String) = joinComponents(components(p))
    fun write(path: String, text: String) { files[key(path)] = text }
    override fun readToString(path: String) = files[key(path)] ?: throw IoException(IoException.Kind.NotFound, "vfs missing: ${key(path)}")
    override fun canonicalize(path: String): String? {
        val k = key(path)
        return k.takeIf { it in files || files.keys.any { f -> f.startsWith("$k/") } }
    }
}
