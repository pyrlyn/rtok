package rtok.plugins.cmd

import rtok.store.Store
import rtok.store.listMeasurements
import rtok.sys.*
import kotlin.test.*

class CatFileTest {
    private fun argv(s: String) = s.split(' ').filter { it.isNotEmpty() }

    private fun rustFile(): String {
        val s = StringBuilder("use std::fmt;\n\n")
        for (i in 0 until 12) {
            s.append("/// doc $i\npub fn item_$i(a: u32) -> u32 {\n")
            for (j in 0 until 6) s.append("    let v$j = a + $j;\n")
            s.append("    a\n}\n\n")
        }
        s.append("mod holder {\n    pub fn method() {\n        let x = 1;\n    }\n}\n")
        return s.toString()
    }

    @Test fun longSourceBecomesSignaturesWithSpans() {
        val src = rustFile()
        val got = CatFile.outlineOf(argv("cat src/lib.rs"), src)!!
        assertTrue(got.contains("pub fn item_0(a: u32) -> u32 {"), got)
        assertTrue(got.contains("pub fn item_11"), got)
        assertTrue(got.lines().size < src.lines().size / 3, got)
        // The span points at the real lines: item_0 opens on line 4 and closes on line 12.
        assertTrue(got.contains("4-12 pub fn item_0"), got)
    }

    @Test fun nestedDefinitionsAreIndentedUnderTheirParent() {
        val got = CatFile.outlineOf(argv("cat src/lib.rs"), rustFile())!!
        assertTrue(got.lines().first { it.contains("fn method") }.startsWith("  "), got)
    }

    @Test fun shortUnknownOrFlaggedCatIsLeftToTheRule() {
        val src = rustFile()
        assertNull(CatFile.outlineOf(argv("cat src/lib.rs"), "fn a() {}\n"))
        assertNull(CatFile.outlineOf(argv("cat notes.xyz"), src))
        assertNull(CatFile.outlineOf(argv("cat Cargo.toml"), src))
        assertNull(CatFile.outlineOf(argv("cat -n src/lib.rs"), src))
        assertNull(CatFile.outlineOf(argv("cat a.rs b.rs"), src))
    }

    @Test fun rereadKeyNamesTheFileAndRange() {
        val (path, key) = CatFile.rereadKey(argv("cat -n src/a.rs"))!!
        assertTrue(path.endsWith("src/a.rs") && key.endsWith("\tcmd\t"), key)
        assertTrue(CatFile.rereadKey(argv("sed -n 5,20p src/a.rs"))!!.second.endsWith("\tcmd\t5,20p"))
        assertNull(CatFile.rereadKey(argv("sed -i s/a/b/ f")))
        assertNull(CatFile.rereadKey(argv("cat a b")))
        assertNull(CatFile.rereadKey(argv("sed -n /x/p f")))
    }

    /** T674: `cat f` again after `f` changed is a diff against the first read, not the file. */
    @Test fun catOfAChangedFileIsADelta() {
        val (c, dir) = cmdConfig("cat-delta")
        try {
            val path = "$dir/big.txt"

            fun text(n: Int) = (0 until 400).joinToString("\n") { "line ${if (it == 200) n else it}" }
            writeString(path, text(200))
            emitFilteredTo(c, listOf("cat", path), text(200).encodeToByteArray(), 0, null) {}
            writeString(path, text(9999))
            emitFilteredTo(c, listOf("cat", path), text(9999).encodeToByteArray(), 0, null) {}
            val rows = Store.open(c.core.dbPath).use { it.listMeasurements("read") }
            assertTrue(rows.any { it.kind == "delta" && it.beforeBytes > 3L * it.afterBytes }, "$rows")
        } finally {
            removeDirAll(dir)
        }
    }
}
