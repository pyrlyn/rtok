// Port of src/bytes.rs tests.
package rtok

import rtok.sys.ChangePreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private const val BARE = 512L
private const val TEN_MB = 10L shl 20
private const val ONE_AND_HALF_GB = 3L shl 29
private const val TWO_MB = 2L shl 20

class BytesTest {
    @Test
    fun sizesParseInBinaryUnitsAndBadOnesAreNamed() {
        assertEquals(BARE, Bytes.parse("512"))
        assertEquals(TEN_MB, Bytes.parse("10MB"))
        assertEquals(ONE_AND_HALF_GB, Bytes.parse("1.5 gb"))
        assertFailsWith<IllegalArgumentException> { Bytes.parse("ten") }
        assertFailsWith<IllegalArgumentException> { Bytes.parse("5 parsecs") }
        assertEquals(TWO_MB, Bytes.parse(ChangePreview.humanBytes(TWO_MB)))
    }
}
