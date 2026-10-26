package rtok.sys

import kotlin.test.*

class ChangePreviewTest {
    @Test fun equalIsEmpty() = assertEquals("", ChangePreview.unifiedDiff("c.toml", "a\n", "a\n"))

    @Test fun oneLineChange() =
        assertEquals(
            "--- a/c.toml\n+++ b/c.toml\n@@ -1,3 +1,3 @@\n a\n-b\n+B\n c\n",
            ChangePreview.unifiedDiff("c.toml", "a\nb\nc\n", "a\nB\nc\n"),
        )

    @Test fun fromEmpty() = assertEquals("--- a/x\n+++ b/x\n@@ -0,0 +1 @@\n+new\n", ChangePreview.unifiedDiff("x", "", "new\n"))

    @Test fun bytesAndGroups() {
        assertEquals("1023 B", ChangePreview.humanBytes(1023))
        assertEquals("1.5 KB", ChangePreview.humanBytes(1536))
        assertEquals("1 204 311", ChangePreview.group(1204311))
    }
}
