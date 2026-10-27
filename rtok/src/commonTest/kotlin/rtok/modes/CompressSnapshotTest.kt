// Port of tests/compress_snapshot.rs: one snapshot of a stable text rendering, `compressProse` at Full
// intensity. The fence body must survive byte-identical while the prose around it is stripped. The
// `.snap` file is the Rust one, unchanged.
package rtok.modes

import rtok.modes.CaveIntensity.Full
import snapshot.SnapshotSettings
import snapshot.assertSnapshot
import snapshot.withSettings
import kotlin.test.Test

class CompressSnapshotTest {
    @Test fun fullCompressRenderingIsStable() {
        val input =
            "Sure! I'd be happy to help. The bug is just really in the auth middleware.\n```rs\n" +
                "fn check(t: u64) { assert!(t <= MAX); }\n```\nFix the comparison."
        withSettings(SnapshotSettings(prefix = "compress_snapshot")) {
            assertSnapshot("full_compress_rendering_is_stable", compressProse(input, Full), "compressProse(input, Full)")
        }
    }
}
