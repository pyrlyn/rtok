// Engine-neutral zstd decoding. One implementation (`rtok.zstd.native.NativeZstd`) wraps the vendored C library;
// callers depend only on this file.
package rtok.zstd

/** A frame that is not valid zstd, or that would decode past the size cap. */
class ZstdException(
    message: String,
) : Exception(message)

fun interface ZstdDecoder {
    /** Every frame in [data], concatenated (Rust `zstd::decode_all`); throws [ZstdException] on bad input. */
    fun decodeAll(data: ByteArray): ByteArray
}
