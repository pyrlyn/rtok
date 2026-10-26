package rtok.plugins.graph

import kotlin.test.*

class ResolveTest {
    @Test fun fivePercentIsTheCommonNameCutoff() {
        assertFalse(Resolve.tooCommon(5, 100)); assertTrue(Resolve.tooCommon(6, 100)); assertFalse(Resolve.tooCommon(0, 10))
        assertTrue(Resolve.idf(1, 100) > Resolve.idf(50, 100))
    }
}
