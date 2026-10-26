package rtok.plugins.graph

import kotlin.test.*
import rtok.config.Graph
import rtok.plugins.read.supported
import rtok.support.tmpDir
import rtok.sys.*

class WalkTest {
    private fun matcher(dir: String, exclude: List<String> = emptyList(), include: List<String> = emptyList(), ext: Map<String, String> = emptyMap()) =
        Matcher(dir, Graph(exclude = exclude, include = include, extensions = ext))
    private fun put(dir: String, path: String, text: String = "fn f() {}\n") { createDirAll(parent("$dir/$path")!!); writeString("$dir/$path", text) }
    private fun indexed(m: Matcher, dir: String) = m.walk().filter { it.isFile && m.indexable(it.path) }.map { stripPrefix(it.path, dir)!! }.toList().sorted()

    @Test fun excludeDropsASubtreeAndIncludeBringsGitignoredBack() {
        val dir = tmpDir("graph-walk")
        put(dir, "skip/a.rs"); put(dir, "keep.rs"); put(dir, ".gitignored/hidden.rs"); writeString("$dir/.gitignore", ".gitignored/\n")
        createDirAll("$dir/.git")
        val m = matcher(dir, listOf("skip/"), listOf(".gitignored/"))
        assertFalse(m.indexable("$dir/skip/a.rs")); assertTrue(m.indexable("$dir/keep.rs")); assertTrue(m.indexable("$dir/.gitignored/hidden.rs"))
        assertEquals(listOf(".gitignored/hidden.rs", "keep.rs"), indexed(m, dir)); removeDirAll(dir)
    }

    private fun ignoredTree(tag: String): String {
        val dir = tmpDir(tag); createDirAll("$dir/.git"); writeString("$dir/.gitignore", "wanted/\nunwanted/\nvendor/\n")
        for (p in listOf("wanted/a.rs", "wanted/sub/b.rs", "unwanted/a.rs", "vendor/pkg/lib.rs", "vendor/other/lib.rs", "src/lib.rs", "src/generated/g.rs")) put(dir, p)
        return dir
    }

    /** T544: an include admits its own subtree only; ordinary source needs no include. */
    @Test fun anIncludeDoesNotReviveUnrelatedIgnoredFiles() {
        val cases = listOf(
            emptyList<String>() to listOf("src/generated/g.rs", "src/lib.rs"),
            listOf("wanted/") to listOf("src/generated/g.rs", "src/lib.rs", "wanted/a.rs", "wanted/sub/b.rs"),
            listOf("vendor/pkg/") to listOf("src/generated/g.rs", "src/lib.rs", "vendor/pkg/lib.rs"),
            listOf("wanted/**/b.rs") to listOf("src/generated/g.rs", "src/lib.rs", "wanted/sub/b.rs"),
        )
        for ((include, want) in cases) { val dir = ignoredTree("t544-walk"); assertEquals(want, indexed(matcher(dir, include = include), dir), "$include"); removeDirAll(dir) }
    }

    @Test fun excludeWinsOverIncludeAndUnderstandsGlobs() {
        val dir = ignoredTree("t545-walk")
        assertEquals(listOf("src/lib.rs", "wanted/a.rs"), indexed(matcher(dir, listOf("**/generated/**", "wanted/sub/"), listOf("wanted/")), dir)); removeDirAll(dir)
    }

    /** T660.3: a nested linked worktree is not walked into; submodules, nested clones and the root itself are. */
    @Test fun aNestedLinkedWorktreeIsNotWalkedInto() {
        val dir = ignoredTree("t660-walk")
        val nested = "$dir/.rtok/worktrees/x"; val admin = "$dir/.git/worktrees/x"
        createDirAll(admin); createDirAll(nested); writeString("$admin/commondir", "../..\n"); writeString("$nested/.git", "gitdir: $admin\n")
        val sub = "$dir/mods/sub"; createDirAll("$dir/.git/modules/sub"); createDirAll(sub); writeString("$sub/.git", "gitdir: $dir/.git/modules/sub\n")
        createDirAll("$dir/deps/clone/.git")
        put(nested, "n.rs"); put(sub, "s.rs"); put("$dir/deps/clone", "c.rs"); put(dir, ".rtok/notes.rs")
        assertEquals(listOf(".rtok/notes.rs", "deps/clone/c.rs", "mods/sub/s.rs", "src/generated/g.rs", "src/lib.rs"), indexed(matcher(dir), dir))
        assertEquals(listOf("n.rs"), indexed(matcher(nested), nested)); removeDirAll(dir)
    }

    @Test fun extensionMapIndexesACustomSuffix() {
        val dir = tmpDir("graph-ext"); put(dir, "tpl.rs"); put(dir, "weird.tpl")
        val m = matcher(dir, ext = mapOf("tpl" to "rust"))
        assertFalse(supported("x.tpl")); assertTrue(m.indexable("$dir/weird.tpl"))
        assertTrue(m.usesExtensionMap("$dir/weird.tpl")); assertFalse(m.usesExtensionMap("$dir/tpl.rs")); removeDirAll(dir)
    }
}
