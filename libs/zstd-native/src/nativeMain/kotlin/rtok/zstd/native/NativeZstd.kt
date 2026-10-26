// The vendored C zstd decoder behind rtok.zstd.ZstdDecoder.
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package rtok.zstd.native

import kotlinx.cinterop.*
import rtok.zstd.ZstdDecoder
import rtok.zstd.ZstdException
import rtok.zstd.native.c.rtok_zstd_decode_all
import rtok.zstd.native.c.rtok_zstd_free

class NativeZstd(
    private val capBytes: Long = DEFAULT_CAP,
) : ZstdDecoder {
    override fun decodeAll(data: ByteArray): ByteArray =
        memScoped {
            val out = alloc<COpaquePointerVar>()
            val len = alloc<ULongVar>()
            val err = alloc<CPointerVar<ByteVar>>()
            val src = if (data.isEmpty()) ByteArray(1) else data
            val rc = src.usePinned { rtok_zstd_decode_all(it.addressOf(0), data.size.convert(), capBytes.convert(), out.ptr, len.ptr.reinterpret(), err.ptr.reinterpret()) }
            if (rc != 0) throw ZstdException(err.value?.toKString() ?: "zstd: decode failed")
            val p = out.value ?: return ByteArray(0)
            try {
                p.readBytes(len.value.toInt())
            } finally {
                rtok_zstd_free(p)
            }
        }

    companion object {
        /** Larger than any docs.rs rustdoc JSON; a cap keeps a hostile frame from taking all memory. */
        const val DEFAULT_CAP = 2L shl 30
    }
}
