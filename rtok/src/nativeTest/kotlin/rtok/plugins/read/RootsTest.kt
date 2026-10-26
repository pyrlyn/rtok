package rtok.plugins.read

import rtok.sys.*
import kotlin.test.*

class RootsTest {
    private fun git(
        dir: String,
        vararg args: String,
    ) {
        val (c, _) = ProgressRunner("", "git", listOf("-C", dir, "-c", "user.name=t", "-c", "user.email=t@t") + args).quiet().runCapped(1L shl 20)
        check(c.exitCode == 0) { "git ${args.toList()}: ${c.stderr.decodeToString()}" }
    }

    /** Main checkout + linked worktree + an unrelated dir, all canonical (macOS `/var`). */
    private fun repo(tag: String): List<String> {
        val base0 = rtok.support.tmpDir("roots-$tag")
        createDirAll("$base0/main")
        createDirAll("$base0/other")
        val base = canonicalize(base0)!!
        val main = "$base/main"
        git(main, "init", "-q")
        writeString("$main/a.txt", "a\n")
        git(main, "add", ".")
        git(main, "commit", "-q", "-m", "init")
        git(main, "worktree", "add", "-q", "$base/wt", "-b", "w")
        return listOf(base, main, "$base/wt", "$base/other")
    }

    @AfterTest fun reset() = Roots.reset()

    @Test fun worktreesOfTheRepoAreListedAndOtherDirsAreNot() {
        val (base, main, wt, other) = repo("list")
        val listed = Roots.worktrees(main).map { canonicalize(it)!! }
        assertTrue(wt in listed && main in listed, "$listed")
        assertFalse(other in listed)
        assertTrue(Roots.worktrees(other).isEmpty())
        removeDirAll(base)
    }

    @Test fun guardAcceptsSiblingWorktreeAndClientRoots() {
        val (base, main, wt, other) = repo("guard")
        createDirAll("$base/scratchpad")
        val files = listOf("$wt/a.txt", "$other/b.txt", "$base/scratchpad/c.txt")
        for (f in files.drop(1)) writeString(f, "x\n")
        val ok = { p: String -> runCatching { resolve(main, p, emptyList()) }.isSuccess }
        Roots.enable()
        assertTrue(ok(files[0]), "sibling worktree")
        assertFalse(ok(files[1]) || ok(files[2]), "unrelated dir, scratchpad")
        Roots.setClientRoots(listOf(other, "$base/gone"))
        assertTrue(ok(files[1]), "client root")
        assertFalse(ok(files[2]))
        assertTrue("path outside cwd" in assertFails { resolve(main, files[2], emptyList()) }.message!!)
        Roots.setClientRoots(listOf(main))
        assertTrue(runCatching { resolve(other, files[0], emptyList()) }.isSuccess, "worktree of a client root's repository")
        assertTrue(runCatching { resolve(other, files[2], emptyList()) }.isFailure)
        removeDirAll(base)
    }

    @Test fun aMissingPathUnderAnOldWorktreeRootNamesWhereTheFileIsNow() {
        val (base, main, wt, _) = repo("moved")
        val old = "$main/_worktrees/wt/a.txt"
        assertEquals("", Roots.movedHint(main, old), "only `rtok mcp` looks")
        Roots.enable()
        val hint = " (moved? it exists at $wt/a.txt)"
        assertEquals(hint, Roots.movedHint(main, old))
        assertEquals("", Roots.movedHint(main, "$main/_worktrees/wt/gone.txt"))
        assertEquals("", Roots.movedHint(main, "$wt/gone.txt"))
        val (rt, dir) = readRt("moved-read")
        rt.use { r ->
            val err = assertFailsWith<ReadException> { readWith(r.ctx(), rtok.config.HostFs, main, old, "full", null) }.message!!
            assertTrue(err.endsWith(hint), err)
        }
        removeDirAll(dir)
        removeDirAll(base)
    }

    @Test fun relativePathsFollowTheCallerWorktreeNotTheServerCheckout() {
        val (base, main, wt, _) = repo("caller")
        writeString("$wt/a.txt", "edited in the worktree\n")
        val (rt, dir) = readRt("callerroot")
        rt.use { r ->
            val guard = Roots.enterCaller(main, wt) ?: fail("sibling worktree is accepted")
            assertEquals(wt, Roots.cwd())
            assertTrue("edited in the worktree" in read(r.ctx(), "a.txt", "full", null))
            assertTrue("a.txt:1:" in Search.search(r.ctx(), "edited", "", null))
            assertTrue("a.txt" in Search.tree(r.ctx(), ".", null))
            guard.close()
            assertEquals(cwd(), Roots.cwd())
        }
        removeDirAll(base)
        removeDirAll(dir)
    }

    @Test fun aCallerRootOutsideTheServerProjectIsRefused() {
        val (base, main, _, other) = repo("callerbad")
        for (d in listOf(other, "/", "$base/gone")) assertNull(Roots.enterCaller(main, d), d)
        createDirAll("$main/sub")
        Roots.enterCaller(main, "$main/sub")!!.close()
        removeDirAll(base)
    }
}
