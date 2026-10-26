package rtok.plugins.read

import kotlin.test.*

class OutlineTest {
    private fun pad(src: String): String =
        buildString {
            append(src)
            if (!src.endsWith('\n')) append('\n')
            for (i in src.trimEnd('\n').lines().size until 20) append("// pad $i\n")
        }

    @Test fun goldenPerLanguage() {
        val cases =
            listOf(
                Triple("a.rs", "fn main() {}\nfn helper() {}\n", "fn main"),
                Triple("a.ts", "function greet(n: string) { return n; }\n", "function greet"),
                Triple("a.js", "function add(x) { return x; }\n", "function add"),
                Triple("a.py", "def run():\n    return 1\n", "def run"),
                Triple("a.dart", "void start() {}\n", "void start"),
                Triple("a.c", "int add(int x) { return x; }\n", "int add"),
                Triple("a.go", "func Sum() int { return 0 }\n", "func Sum"),
                Triple("a.java", "class App { void start() {} }\n", "class App"),
                Triple("a.kt", "class App { fun start() {} }\n", "class App"),
                Triple("a.swift", "class App { func start() {} }\n", "class App"),
                Triple("a.cs", "class App { void Start() {} }\n", "class App"),
                Triple("a.rb", "def run; end\n", "def run"),
                Triple("a.php", "<?php\nfunction run() {}\n", "function run"),
            )
        for ((path, src0, needle) in cases) {
            val src = pad(src0)
            val map = render(path, src, "map")
            assertTrue(needle in map, "$path map: $map")
            val sig = render(path, src, "signatures")
            val name = needle.split(' ').getOrElse(1) { needle }
            assertTrue(sig.lines().any { name in it }, "$path signatures: $sig")
        }
    }

    @Test fun eachLanguageCompilesOnce() {
        val a = Languages.tags("rust")!!.getOrThrow()
        assertSame(a, Languages.tags("rust")!!.getOrThrow(), "a second .rs file recompiled the query")
        assertNotSame(a, Languages.tags("typescript")!!.getOrThrow(), "two languages share one configuration")
    }

    @Test fun unknownLanguageFallsBack() {
        val out = render("a.txt", "hello\nworld\n", "map")
        assertTrue("1:hello" in out, out)
        assertTrue("unknown language" in out, out)
    }

    @Test fun markdownMapSkipsFencedHeadings() {
        val src = "# One\nFirst.\n```sh\n# not a heading\n```\n## Two\nSecond.\n### Three\nThird.\n"
        val hs = markdownHeadings(src)
        assertEquals(3, hs.size)
        assertEquals("First.", hs[0].body)
        assertEquals("## Two", hs[1].text)
        assertEquals("### Three", hs[2].text)
        val map = render("doc.md", src, "map")
        for (s in listOf("h1 One 1", "h2 Two 6", "h3 Three 8", "  Second.")) assertTrue(s in map, map)
        assertFalse("not a heading" in map, map)
        assertEquals(3, render("doc.mdx", src, "signatures").lines().size)
        val digest = markdownDigest(src)
        assertTrue("## Two\n  Second." in digest)
        assertFalse("not a heading" in digest)
    }

    @Test fun llmsTxtMapListsLinksAndSkipsFences() {
        val src = "# Docs\nIntro.\n## Pages\n- [Real](https://example.com/a): a note\n```sh\n- [Fake](https://example.com/no)\n```\n- [Other](docs/b.md)\n"
        val links = llmsLinks(src)
        assertEquals(listOf(LlmsLink("Real", "https://example.com/a", "a note"), LlmsLink("Other", "docs/b.md", "")), links)
        val map = render("docs/llms.txt", src, "map")
        assertTrue("h1 Docs 1" in map, map)
        assertTrue("link Real https://example.com/a — a note" in map, map)
        assertTrue("link Other docs/b.md" in map, map)
        assertFalse("Fake" in map, map)
        assertTrue("link Other docs/b.md" in render("llms-full.txt", src, "map"))
        val md = render("doc.md", src, "map")
        assertFalse("link " in md, md)
        assertTrue("h1 Docs 1" in md, md)
    }

    @Test fun strippedPerLanguage() {
        val cases =
            listOf(
                listOf("a.rs", "/// gone\nfn keep() {}\n", "gone", "fn keep"),
                listOf("a.ts", "// gone\nfunction keep() { return 1; }\n", "gone", "function keep"),
                listOf("a.js", "// gone\nfunction keep() { return 1; }\n", "gone", "function keep"),
                listOf("a.py", "# gone\ndef keep():\n    return 1\n", "gone", "def keep"),
                listOf("a.dart", "// gone\nvoid keep() {}\n", "gone", "void keep"),
                listOf("a.c", "/* gone */\nint keep(void) { return 1; }\n", "gone", "int keep"),
                listOf("a.go", "// gone\nfunc Keep() int { return 1 }\n", "gone", "func Keep"),
            )
        for ((path, src, gone, keep) in cases) {
            val out = assertNotNull(stripped(path, pad(src)), path)
            assertFalse(gone in out, "$path still has comment: $out")
            assertTrue(keep in out, "$path lost body: $out")
        }
    }

    @Test fun strippedUnknownLanguageIsNull(): Unit = assertNull(stripped("a.txt", "// gone\nkeep\n"))

    @Test fun importQueriesCaptureLastSegment() {
        val cases =
            listOf(
                Triple("a.rs", "use crate::foo::Bar;\nfn main() {}\n", "Bar"),
                Triple("a.ts", "import { foo } from './mod/helper';\nexport function greet() {}\n", "helper"),
                Triple("a.js", "const x = require('pkg/util');\nfunction add(x) { return x; }\n", "util"),
                Triple("a.py", "from pkg.mod import helper\ndef run():\n    return 1\n", "helper"),
                Triple("a.go", "package p\nimport \"github.com/x/y\"\nfunc Sum() int { return 0 }\n", "y"),
                Triple("a.dart", "import 'package:foo/bar.dart';\nvoid start() {}\n", "bar"),
            )
        for ((path, src0, name) in cases) {
            val src = pad(src0)
            val hits = tags(path, src)
            val imports = hits.filter { it.kind == "import" && !it.isDef }.map { it.name }
            assertTrue(name in imports, "$path missing import $name: $imports")
            if (path == "a.rs") assertEquals("crate::foo::Bar", hits.first { it.kind == "import" && it.name == "Bar" }.importPath)
            val map = render(path, src, "map")
            assertTrue(map.startsWith("imports: "), "$path outline: $map")
            assertTrue(name in map, "$path outline: $map")
        }
    }

    @Test fun rustUseGroupKeepsEachFullPath() {
        val imports =
            tags("a.rs", pad("use crate::foo::{Bar, baz::Qux as Alias};\nfn main() {}\n"))
                .filter { it.kind == "import" && !it.isDef }
                .map { it.name to it.importPath }
        assertTrue("Bar" to "crate::foo::Bar" in imports, "$imports")
        assertTrue("Qux" to "crate::foo::baz::Qux" in imports, "$imports")
    }

    @Test fun tagHitKeepsTheByteRange() {
        val src = "fn keep() {\n    1\n}\n"
        val def = tags("a.rs", src).first { it.isDef && it.name == "keep" }
        assertTrue(def.endByte > def.startByte)
        val slice = src.substring(def.startByte, def.endByte)
        assertTrue(slice.startsWith("fn keep"), slice)
        assertTrue('1' in slice, slice)
    }
}
