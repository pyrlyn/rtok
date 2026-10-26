// Port of the `Vfs` in src/testutil.rs (the ReadFs part): files by absolute path, directories implied, symlinks.
package rtok.support

import rtok.config.ReadFs
import rtok.sys.IoException
import rtok.sys.components
import rtok.sys.joinComponents

class Vfs : ReadFs {
    private val files = LinkedHashMap<String, ByteArray>()
    private val links = HashMap<String, String>()
    private fun key(p: String) = joinComponents(components(p))
    fun write(path: String, text: String) { files[key(path)] = text.encodeToByteArray() }
    fun writeBytes(path: String, bytes: ByteArray) { files[key(path)] = bytes }
    /** A symlink at [link] pointing at [target]; [canonicalize] follows it. */
    fun symlink(link: String, target: String) { links[key(link)] = key(target) }
    /** Every file key, in insertion order. */
    fun keys(): List<String> = files.keys.toList()
    fun bytes(path: String): ByteArray? = files[key(path)]
    override fun readToString(path: String): String {
        val k = canonicalize(path) ?: key(path)
        return files[k]?.decodeToString() ?: throw IoException(IoException.Kind.NotFound, "vfs missing: ${key(path)}")
    }
    override fun canonicalize(path: String): String? {
        var parts = components(path); var i = 0; var hops = 0
        while (i < parts.size && hops < 40) {
            val prefix = joinComponents(parts.subList(0, i + 1))
            val target = links[prefix]
            if (target != null) { parts = components(target) + parts.drop(i + 1); i = 0; hops++; continue }
            i++
        }
        val k = joinComponents(parts)
        return k.takeIf { it in files || files.keys.any { f -> f.startsWith("$k/") } }
    }
}
