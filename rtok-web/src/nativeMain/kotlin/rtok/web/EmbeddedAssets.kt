// The UI compiled into the binary (spa.rs `Embedded`): :rtok-web-compose's webDist, generated as EMBEDDED_UI.
// Text files are embedded as is, binary ones (the .wasm modules) as base64 (buildSrc EmbedDir.kt).
package rtok.web

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** The bytes of one embedded file: its [chunks] joined, decoded from base64 when [base64]. */
@OptIn(ExperimentalEncodingApi::class)
internal fun embeddedBytes(
    chunks: List<String>,
    base64: Boolean,
): ByteArray = chunks.joinToString("").let { if (base64) Base64.decode(it) else it.encodeToByteArray() }

/** The embedded Compose UI; each file's bytes are decoded once, on first request. */
object EmbeddedAssets : Assets {
    private val files = HashMap<String, AssetFile>()

    override fun read(name: String): AssetFile? =
        files[name] ?: EMBEDDED_UI[name]?.let { (chunks, etag, base64) ->
            AssetFile(embeddedBytes(chunks, base64), etag).also { files[name] = it }
        }

    override fun placeholder() = "index.html" !in EMBEDDED_UI
}
