// Port of src/modes/ladder.rs tests.
package rtok.modes

import kotlin.test.Test
import kotlin.test.assertEquals

class LadderTest {
    @Test fun securityForcesMinimum() = assertEquals(LadderDecision.Minimum, evaluateLadder(LadderContext(speculative = true, mustNotSimplify = true)))

    @Test fun speculativeSkips() = assertEquals(LadderDecision.YagniSkip, evaluateLadder(LadderContext(speculative = true, stdlib = true)))

    @Test fun nativeBeforeDep() = assertEquals(LadderDecision.NativePlatform, evaluateLadder(LadderContext(native = true, installedDep = true)))
}
