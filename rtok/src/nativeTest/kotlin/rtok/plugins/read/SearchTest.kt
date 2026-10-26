package rtok.plugins.read

import kotlin.test.*
import rtok.Expand
import rtok.config.ReadFs
import rtok.plugins.read.Search.displayRel
import rtok.plugins.read.Search.search
import rtok.plugins.read.Search.tree
import rtok.support.Vfs
import rtok.sys.*

class SearchTest {
    private fun linkedWorktree(main: String, rel: String): String {
        val wt = "$main/$rel"; val admin = "$main/.git/worktrees/${fileName(wt)}"
        createDirAll(admin); createDirAll(wt); writeString("$admin/commondir", "../..\n"); writeString("$wt/.git", "gitdir: $admin\n"); return wt
    }
    private fun submodule(parent: String, rel: String): String {
        val sub = "$parent/$rel"; val admin = "$parent/.git/modules/${fileName(sub)}"
        createDirAll(admin); createDirAll(sub); writeString("$sub/.git", "gitdir: $admin\n"); return sub
    }

    /** Port of `walk::search_hits` over a Vfs: `.git/` keys skipped, size and UTF-8 gates, hits relative to [root]. */
    private fun vfsHits(v: Vfs, root: String, pattern: String, maxBytes: Long, maxHits: Int): List<String> {
        val files = v.keys().asSequence().filter { (root.isEmpty() || pathStartsWith(it, root)) && ".git" !in components(it) }
            .mapNotNull { k -> v.bytes(k)!!.takeIf { it.size <= maxBytes }?.let { b -> runCatching { k to b.decodeToString(throwOnInvalidSequence = true) }.getOrNull() } }
        return Search.hits(files, Expand.regexOrLiteral(pattern), maxHits) { displayRel(it, root, root) }
    }
    private fun vfsTree(v: Vfs, root: String, maxDepth: Int): List<String> =
        v.keys().filter { (root.isEmpty() || pathStartsWith(it, root)) && ".git" !in components(it) }
            .map { displayRel(it, root, root) }.filter { components(it).size <= maxDepth }.map { "$it ${v.bytes(if (root.isEmpty()) it else "$root/$it")!!.size}" }.sorted()

    private fun project(tag: String): Pair<rtok.plugin.Runtime, String> {
        val (rt, dir) = readRt(tag)
        createDirAll("$dir/src"); writeString("$dir/src/main.rs", "fn main() {\n    println!(\"the main\");\n}\n")
        writeString("$dir/README.md", "the readme\nthe second\nthe third\nthe fourth\n")
        return rt to dir
    }

    @Test fun searchFnMainFindsSrcMain() {
        val (rt, dir) = project("fnmain")
        rt.use { val out = search(it.ctx(), "fn main", dir, null); assertTrue("src/main.rs" in out, out)
            assertTrue(out.lines().size <= it.config.plugins.read.searchMax.toInt()) }
    }

    /** Over HTTP `read` refuses `.env`, so `search` must not print its lines; the filter drops it only when denied. */
    @Test fun walkFilterDropsCredentialFilesOnlyWhenDenied() {
        val dir = rtok.support.tmpDir("search-secrets"); createDirAll("$dir/k")
        for (f in listOf(".env", "k/server.pem", "a.txt")) writeString("$dir/$f", "API_TOKEN=x\n")
        fun names(deny: Boolean) = Search.textFiles(walk(dir, filter = Search.walkFilter(deny)), 1uL shl 20).map { stripPrefix(it.first, dir)!! }.toList().sorted()
        assertEquals(listOf("a.txt"), names(true)); assertEquals(listOf(".env", "a.txt", "k/server.pem"), names(false))
        removeDirAll(dir)
    }

    @Test fun searchRespectsMax() { val (rt, dir) = project("max"); rt.use { assertTrue(search(it.ctx(), "the", dir, 3u).lines().size <= 3) } }

    @Test fun displayRelNamesTheRootFileInsteadOfEmpty(): Unit = assertEquals("repo", displayRel("/repo", "/repo", "/repo"))

    @Test fun invalidRegexSearchesLiterally() {
        val (rt, dir) = readRt("litsearch")
        rt.use { writeString("$dir/a.txt", "read(x) literally\n"); assertTrue("a.txt:1:" in search(it.ctx(), "read(", dir, null)) }
    }

    @Test fun searchPathsStayRelativeForAllowPathsRoot() {
        val (rt, dir) = readRt("searchrel")
        rt.use { createDirAll("$dir/nest"); writeString("$dir/nest/hit.rs", "fn needle() {}\n")
            val out = search(it.ctx(), "needle", "$dir/nest", null); assertTrue("hit.rs:" in out && "$dir/nest" !in out, out) }
    }

    @Test fun searchAndTreeSkipGitDir() {
        val (rt, dir) = readRt("skipgit")
        rt.use { r -> createDirAll("$dir/.git/logs"); writeString("$dir/.git/logs/HEAD", "needle in reflog\n"); writeString("$dir/a.txt", "needle in tree\n")
            val s = search(r.ctx(), "needle", dir, null); assertTrue("a.txt:1:" in s && ".git" !in s, s)
            val t = tree(r.ctx(), dir, 3u); assertTrue("a.txt" in t && ".git" !in t, t) }
    }

    /** T660.3: a nested linked worktree is another checkout; submodules and nested clones stay searched. */
    @Test fun searchAndTreeSkipANestedLinkedWorktree() {
        val (rt, dir0) = readRt("skipnested")
        rt.use { r -> val dir = canon(dir0)
            val nested = linkedWorktree(dir, ".rtok/worktrees/x"); val sub = submodule(dir, "mods/sub")
            createDirAll("$dir/deps/clone/.git")
            writeString("$nested/n.txt", "needle in nested\n"); writeString("$sub/s.txt", "needle in submodule\n")
            writeString("$dir/deps/clone/c.txt", "needle in clone\n"); writeString("$dir/.rtok/k.txt", "needle in rtok\n"); writeString("$dir/a.txt", "needle in tree\n")
            val s = search(r.ctx(), "needle", dir, null)
            for (k in listOf("a.txt:1:", "k.txt:1:", "s.txt:1:", "c.txt:1:")) assertTrue(k in s, "$k: $s")
            assertTrue("n.txt" !in s, s)
            val t = tree(r.ctx(), dir, 5u)
            for (k in listOf("a.txt", "k.txt", "s.txt", "c.txt")) assertTrue(k in t, "$k: $t")
            assertTrue("n.txt" !in t, t)
            assertTrue("n.txt:1:" in search(r.ctx(), "needle", nested, null))
        }
    }

    @Test fun searchOutputIsCappedWithArchiveId() {
        val (rt, dir) = readRt("searchcap") { it.copy(plugins = it.plugins.copy(read = it.plugins.read.copy(maxChars = 200u))) }
        rt.use { writeString("$dir/big.txt", "needle line\n".repeat(100))
            val out = search(it.ctx(), "needle", dir, 100u); assertTrue("archived" in out && out.length <= 200, out) }
    }

    @Test fun treePathsStayRelativeForAllowPathsRoot() {
        val (rt, dir) = readRt("treerel")
        rt.use { createDirAll("$dir/nest/a"); writeString("$dir/nest/a/f.txt", "x")
            val out = tree(it.ctx(), "$dir/nest", 3u); assertTrue("a" in out && "$dir/nest" !in out, out) }
    }

    /** On Unix the prefix strip is case-sensitive; mixed-case paths keep the file name. */
    @Test fun displayRelStripsOnlyAnExactPrefixOnUnix(): Unit = assertTrue("a.rs" in displayRel("/Users/Me/Proj/src/a.rs", "/users/me/proj", "/Users/Me/Proj"))

    @Test fun searchSkipsFilesOverSearchMaxBytes() {
        val (rt, dir) = readRt("searchcapbytes") { it.copy(plugins = it.plugins.copy(read = it.plugins.read.copy(searchMaxBytes = 64u))) }
        rt.use { writeString("$dir/small.txt", "needle small\n"); writeString("$dir/large.txt", "needle large\n" + "x".repeat(115))
            val out = search(it.ctx(), "needle", dir, null); assertTrue("small.txt" in out && "large.txt" !in out, out) }
    }

    @Test fun searchMaxBytesGateUsesVfsSizes() {
        val v = Vfs(); v.write("ok.txt", "needle small\n"); v.write("big.txt", "needle large\n" + "x".repeat(187)); v.write("other.txt", "no match\n")
        assertEquals(listOf("ok.txt:1: needle small"), vfsHits(v, "", "needle", 64, 10))
    }

    @Test fun vfsSearchHitPathsStayBasenameRelative() {
        val v = Vfs(); v.write("nest/hit.rs", "fn needle() {}\n"); assertEquals(listOf("nest/hit.rs:1: fn needle() {}"), vfsHits(v, "", "needle", 1024, 10))
    }

    @Test fun vfsSearchRespectsMaxHitsAcrossFiles() {
        val v = Vfs(); v.write("a.txt", "needle\nneedle\n"); v.write("b.txt", "needle\n")
        val hits = vfsHits(v, "", "needle", 1024, 2); assertEquals(2, hits.size); assertTrue(hits.all { "needle" in it })
    }

    @Test fun vfsSearchSkipsNonUtf8Bodies() {
        val v = Vfs(); v.writeBytes("bin.dat", byteArrayOf(-1, -2, 0)); v.write("ok.txt", "needle here\n")
        assertEquals(listOf("ok.txt:1: needle here"), vfsHits(v, "", "needle", 1024, 10))
    }

    @Test fun vfsSearchEmptyIsEmpty(): Unit = assertTrue(vfsHits(Vfs(), "", "needle", 1024, 10).isEmpty())

    @Test fun vfsSearchSpacedKeys() {
        val v = Vfs(); v.write("/Users/Ivan Tuhai/proj/src/a.rs", "fn needle() {}\n")
        val hits = vfsHits(v, "", "needle", 1024, 10); assertEquals(1, hits.size); assertTrue("Ivan Tuhai" in hits[0] && "a.rs:1:" in hits[0])
    }

    @Test fun searchAndTreeSkipGitDirFromVfs() {
        val v = Vfs(); v.write(".git/logs/HEAD", "needle in reflog\n"); v.write("a.txt", "needle in tree\n")
        assertEquals(listOf("a.txt:1: needle in tree"), vfsHits(v, "", "needle", 1024, 10))
        val rows = vfsTree(v, "", 3); assertTrue(rows.any { it.startsWith("a.txt ") } && rows.none { ".git" in it }, "$rows")
    }

    @Test fun treePathsStayRelativeFromVfs() {
        val v = Vfs(); v.write("nest/a/f.txt", "x"); val rows = vfsTree(v, "nest", 3)
        assertTrue(rows.any { it.startsWith("a/f.txt ") } && rows.none { "nest/" in it }, "$rows")
    }

    @Test fun searchPathsStayRelativeFromVfs() {
        val v = Vfs(); v.write("nest/hit.rs", "fn needle() {}\n"); val hits = vfsHits(v, "nest", "needle", 1024, 10)
        assertTrue(hits.any { it.startsWith("hit.rs:") } && hits.none { "nest/" in it }, "$hits")
    }

    @Test fun searchSkipsFilesOverSearchMaxBytesFromVfs() {
        val v = Vfs(); v.write("small.txt", "needle small\n"); v.write("large.txt", "needle large\n" + "x".repeat(115))
        assertEquals(listOf("small.txt:1: needle small"), vfsHits(v, "", "needle", 64, 10))
    }

    @Test fun displayRelKeepsSpacedSegmentWhenPrefixMatches(): Unit =
        assertEquals("src/a.rs", displayRel("/Users/Ivan Tuhai/proj/src/a.rs", "/Users/Ivan Tuhai/proj", "/Users/Ivan Tuhai/proj"))

    /** T59.2: the base is canonicalized once per call; per-row display never touches the adapter. */
    @Test fun displayRelCanonicalizesBaseOncePerCallFromVfs() {
        val v = Vfs(); v.write("src/a.rs", "fn main() {}\n"); v.write("src/b.rs", "fn other() {}\n")
        var calls = 0
        val fs = object : ReadFs { override fun readToString(path: String) = v.readToString(path); override fun canonicalize(path: String): String? { calls++; return v.canonicalize(path) } }
        val base = Search.canonicalBase(fs, "src"); assertEquals(1, calls); assertEquals("src", base)
        assertEquals("a.rs", displayRel("src/a.rs", "src", base)); displayRel("src/b.rs", "src", base); displayRel("elsewhere/x.rs", "src", base)
        assertEquals(1, calls, "per-row display must not canonicalize")
    }
}
