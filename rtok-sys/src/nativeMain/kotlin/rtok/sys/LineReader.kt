// Streamed line reading with Rust `BufRead::lines` semantics: `\n` splits, a trailing `\r` is dropped,
// a line that is not valid UTF-8 is reported as null (Rust's `Err`), so a caller can skip it.
package rtok.sys

import kotlinx.cinterop.*
import platform.posix.*

/** Calls [each] per line of [path]; false when the file cannot be opened. */
@OptIn(ExperimentalForeignApi::class)
fun forEachFileLine(path: String, each: (String?) -> Unit): Boolean {
    val f = fopen(path, "rb") ?: return false
    try {
        memScoped {
            val buf = alloc<CPointerVar<ByteVar>>(); val cap = alloc<ULongVar>()
            try {
                while (true) {
                    val len = getline(buf.ptr, cap.ptr, f)
                    if (len < 0) break
                    var bytes = buf.value!!.readBytes(len.toInt())
                    if (bytes.isNotEmpty() && bytes.last() == '\n'.code.toByte()) {
                        bytes = bytes.copyOf(bytes.size - 1)
                        if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes = bytes.copyOf(bytes.size - 1)
                    }
                    each(runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull())
                }
            } finally { free(buf.value) }
        }
    } finally { fclose(f) }
    return true
}
