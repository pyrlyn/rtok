// Port of the src/measure/image.rs tests (T137): PNG and JPEG headers give their size; tokens match the docs' table.
@file:Suppress("MagicNumber")

package rtok.measure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImageCostTest {
    companion object {
        private const val A = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

        private fun b64(bytes: List<Int>): String =
            buildString {
                bytes.chunked(3).forEach { c ->
                    val n = (c[0] shl 16) or ((c.getOrNull(1) ?: 0) shl 8) or (c.getOrNull(2) ?: 0)
                    for (k in 0..c.size) append(A[(n shr (18 - 6 * k)) and 63])
                }
                while (length % 4 != 0) append('=')
            }

        private fun be(
            v: Int,
            n: Int,
        ) = (n - 1 downTo 0).map { (v shr (8 * it)) and 0xFF }

        /** PNG signature + IHDR for a `w`×`h` image, base64. */
        fun png(
            w: Int,
            h: Int,
        ) = b64(listOf(0x89, 'P'.code, 'N'.code, 'G'.code, 13, 10, 0x1a, 10, 0, 0, 0, 13) + "IHDR".map { it.code } + be(w, 4) + be(h, 4) + listOf(8, 2, 0, 0, 0))

        /** JPEG SOI, an APP0 segment, then SOF0 for a `w`×`h` image, base64. */
        fun jpeg(
            w: Int,
            h: Int,
        ) = b64(
            listOf(0xFF, 0xD8, 0xFF, 0xE0, 0, 16) + "JFIF".map { it.code } + listOf(0, 1, 1, 0, 0, 1, 0, 1, 0, 0) +
                listOf(0xFF, 0xC0, 0, 17, 8) + be(h, 2) + be(w, 2) + listOf(3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1),
        )
    }

    @Test fun pngAndJpegHeadersGiveTheirSize() {
        assertEquals(1920 to 1080, ImageCost.dims(png(1920, 1080)))
        assertEquals(800 to 600, ImageCost.dims(jpeg(800, 600)))
        assertNull(ImageCost.dims("R0lGODlh"))
        assertEquals(29L, ImageCost.decodedLen(png(1, 1)))
    }

    @Test fun tokensMatchThePublishedTable() {
        assertEquals(64L, ImageCost.tokens(200, 200))
        assertEquals(1296L, ImageCost.tokens(1000, 1000))
        assertEquals(2691L, ImageCost.tokens(1920, 1080))
        assertEquals(4784L, ImageCost.tokens(3840, 2160))
    }
}
