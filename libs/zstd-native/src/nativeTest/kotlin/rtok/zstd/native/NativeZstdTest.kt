package rtok.zstd.native

import rtok.zstd.ZstdException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.*

@OptIn(ExperimentalEncodingApi::class)
class NativeZstdTest {
    // `printf 'hello rtok\n' | zstd -19 | base64` and two such frames concatenated.
    private val hello = Base64.decode(HELLO)

    @Test fun decodesOneFrame() = assertEquals("hello rtok\n", NativeZstd().decodeAll(hello).decodeToString())

    @Test fun concatenatedFramesDecodeInOrder() = assertEquals("hello rtok\nhello rtok\n", NativeZstd().decodeAll(hello + hello).decodeToString())

    @Test fun garbageIsAZstdError() {
        assertFailsWith<ZstdException> { NativeZstd().decodeAll("not zstd at all".encodeToByteArray()) }
    }

    @Test fun aTruncatedFrameIsAnError() {
        assertFailsWith<ZstdException> { NativeZstd().decodeAll(hello.copyOf(hello.size - CUT)) }
    }

    @Test fun theSizeCapStopsAnOversizedFrame() {
        assertFailsWith<ZstdException> { NativeZstd(capBytes = 4).decodeAll(hello) }
    }

    @Test fun emptyInputIsEmptyOutput() = assertEquals(0, NativeZstd().decodeAll(ByteArray(0)).size)

    private companion object {
        const val CUT = 3
        const val HELLO = "KLUv/QRoWQAAaGVsbG8gcnRvawqIPEb7"
    }
}
