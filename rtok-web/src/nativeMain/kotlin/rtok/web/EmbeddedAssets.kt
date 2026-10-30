// The UI compiled into the binary (spa.rs `Embedded`): :rtok-web-compose's webDist, generated as EMBEDDED_UI.
// Text files are embedded as is, binary ones (the .wasm modules) as base64 of a zstd frame (buildSrc EmbedDir.kt).
package rtok.web

import rtok.zstd.ZstdDecoder
import rtok.zstd.native.NativeZstd
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

// The embedded modules are our own build output (~9 MB for the largest); a cap keeps a corrupt frame bounded.
private const val MAX_DECODED = 64L shl 20

/** The bytes of one embedded file: its [chunks] joined, base64-decoded and zstd-decoded when [zstd]. */
@OptIn(ExperimentalEncodingApi::class)
internal fun embeddedBytes(
    chunks: List<String>,
    zstd: Boolean,
    decoder: ZstdDecoder = NativeZstd(MAX_DECODED),
): ByteArray = chunks.joinToString("").let { if (zstd) decoder.decodeAll(Base64.decode(it)) else it.encodeToByteArray() }

/** The embedded Compose UI; each file's bytes are decoded once, on first request. */
object EmbeddedAssets : Assets {
    private val files = HashMap<String, AssetFile>()

    override fun read(name: String): AssetFile? =
        files[name] ?: EMBEDDED_UI[name]?.let { (chunks, etag, zstd) ->
            AssetFile(embeddedBytes(chunks, zstd), etag).also { files[name] = it }
        }

    override fun placeholder() = "index.html" !in EMBEDDED_UI
}
