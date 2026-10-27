// The UI compiled into the binary (spa.rs `Embedded`): :rtok-web-ui's build, generated as EMBEDDED_UI.
package rtok.web

/** The embedded Kotlin/JS UI; each file's bytes are decoded once, on first request. */
object EmbeddedAssets : Assets {
    private val files = HashMap<String, AssetFile>()

    override fun read(name: String): AssetFile? =
        files[name] ?: EMBEDDED_UI[name]?.let { (chunks, etag) ->
            AssetFile(chunks.joinToString("").encodeToByteArray(), etag).also { files[name] = it }
        }

    override fun placeholder() = "index.html" !in EMBEDDED_UI
}
