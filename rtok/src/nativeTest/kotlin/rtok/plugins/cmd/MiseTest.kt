// Port of the src/plugins/cmd/mise.rs tests (4).
package rtok.plugins.cmd

import kotlin.test.*

private const val INSTALL = "mise node@20.11.0 download node-v20.11.0-darwin-arm64.tar.gz\nmise node@20.11.0 checksum node-v20.11.0-darwin-arm64.tar.gz\nmise node@20.11.0 extract node-v20.11.0-darwin-arm64.tar.gz\nmise node@20.11.0 node-v20.11.0-darwin-arm64/bin/node -v\nmise node@20.11.0 ✓ installed\n[test] $ cargo test\ntest download::ok ... ok\ntest result: ok. 1 passed\n"

class MiseTest {
    @Test fun installStepsGoAndEverythingElseStays() {
        val got = Mise.tidy(INSTALL)!!
        assertTrue(got.startsWith("mise: 3 install/progress lines dropped\n"), got)
        for (kept in listOf("mise node@20.11.0 ✓ installed", "mise node@20.11.0 node-v20.11.0-darwin-arm64/bin/node -v", "[test] $ cargo test", "test download::ok ... ok", "test result: ok. 1 passed")) assertTrue(got.contains(kept), "$kept lost: $got")
    }

    @Test fun progressFramesCollapseAndCarriageReturnsShowTheLastFrame() {
        val raw = "mise node@20 download x.tar.gz [=>        ] 1.0MiB/45MiB\rmise node@20 download x.tar.gz [=====>    ] 20MiB/45MiB\n\u001b[32m  [#########>] 45MiB/45MiB\u001b[0m\ndone\n"
        val got = Mise.tidy(raw)!!
        assertTrue(got.endsWith("\ndone"), got)
        assertTrue(got.startsWith("mise: 2 install/progress lines dropped"), got)
    }

    @Test fun warningsAndErrorsAreNeverDroppedEvenOnAStepLine() {
        val raw = "mise node@20 download x.tar.gz\nmise node@20 checksum x.tar.gz\nmise node@20 extract x.tar.gz\nmise node@20 download y.tar.gz\nmise node@20 download failed: connection reset\nmise WARN  deprecated setting\nmise ERROR task failed\n"
        val got = Mise.tidy(raw)!!
        for (k in listOf("download failed: connection reset", "mise WARN  deprecated setting", "mise ERROR task failed")) assertTrue(got.contains(k), got)
    }

    @Test fun outputWithoutNoiseIsLeftAlone() {
        assertNull(Mise.tidy("[test] $ cargo test\ntest result: ok\n"))
        assertNull(Mise.tidy(""))
    }
}
