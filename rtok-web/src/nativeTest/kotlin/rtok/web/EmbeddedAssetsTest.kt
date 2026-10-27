@file:Suppress("MagicNumber") // wasm header bytes

package rtok.web

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EmbeddedAssetsTest {
    @Test
    fun textChunksJoinAsUtf8() {
        assertContentEquals("héllo wörld".encodeToByteArray(), embeddedBytes(listOf("héllo ", "wörld"), base64 = false))
    }

    @Test
    fun base64ChunksDecodeToTheOriginalBytesAcrossChunkBoundaries() {
        // "AGFzbQEAAAA=" is the 8-byte wasm header \0asm\1\0\0\0, split mid-quantum.
        val bytes = embeddedBytes(listOf("AGFz", "bQE", "AAAA="), base64 = true)
        assertContentEquals(byteArrayOf(0, 0x61, 0x73, 0x6d, 1, 0, 0, 0), bytes)
    }

    @Test
    fun emptyFilesStayEmpty() {
        assertEquals(0, embeddedBytes(listOf(""), base64 = true).size)
        assertEquals(0, embeddedBytes(listOf(""), base64 = false).size)
    }

    @Test
    fun theEmbeddedComposeBundleHasItsWasmModulesAsBinary() {
        val index = assertNotNull(EmbeddedAssets.read("index.html"))
        assertTrue(index.bytes.decodeToString().contains("/app.js"))
        val wasm = EMBEDDED_UI.keys.filter { it.endsWith(".wasm") }
        assertTrue(wasm.isNotEmpty(), "no .wasm embedded")
        wasm.forEach { name ->
            val f = assertNotNull(EmbeddedAssets.read(name))
            assertContentEquals(byteArrayOf(0, 0x61, 0x73, 0x6d), f.bytes.copyOfRange(0, 4), name)
        }
        assertTrue(EMBEDDED_UI.keys.none { it.endsWith(".map") })
    }
}
