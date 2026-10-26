// Port of src/tokens.rs tests.
package rtok

import rtok.config.Estimator
import rtok.plugin.sdk.Class
import kotlin.test.Test
import kotlin.test.assertEquals

class TokensTest {
    private val rates = Estimator(code = 3.5f, prose = 4.2f, json = 3.0f, cjk = 1.0f)

    @Test fun emptyIsZero() {
        assertEquals(0, estimate("", Class.Code, rates))
        assertEquals(0, estimate("", Class.Cjk, rates))
    }

    @Test fun fixtures() {
        assertEquals(10, estimate("fn main() { println!(\"hi there\"); }", Class.Code, rates))
        assertEquals(11, estimate("The quick brown fox jumps over the lazy dog", Class.Prose, rates))
        assertEquals(10, estimate("""{"a":1,"b":[1,2,3],"c":"xyz"}""", Class.Json, rates))
        assertEquals(6, estimate("東京都渋谷区", Class.Cjk, rates))
    }

    @Test fun savedNeverNegative() {
        assertEquals(60, tokensSaved(100, 40))
        assertEquals(0, tokensSaved(40, 100))
    }

    @Test fun skipWithoutKey() {
        assertEquals("skipped", calibrateOrSkip(Estimator()))
    }
}
