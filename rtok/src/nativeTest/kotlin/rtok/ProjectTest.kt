package rtok

import rtok.config.ProjectRoot
import rtok.support.Vfs
import rtok.sys.*
import kotlin.test.*

class ProjectTest {
    private fun fixture(url: String) =
        Vfs().apply {
            write("/home/me/rtok/.git/config", "[core]\n\tbare = false\n[remote \"origin\"]\n\turl = $url\n\tfetch = +refs/heads/*:refs/remotes/origin/*\n")
            write("/home/me/rtok/.git/HEAD", "ref: refs/heads/main\n")
            write("/home/me/rtok/.git/worktrees/rtok-t133/commondir", "../..\n")
            write("/home/me/rtok/.git/worktrees/rtok-t133/gitdir", "/home/me/_worktrees/rtok-t133/.git\n")
            write("/home/me/_worktrees/rtok-t133/.git", "gitdir: ../../rtok/.git/worktrees/rtok-t133\n")
            write("/home/me/_worktrees/rtok-t133/src/lib.rs", "")
        }

    private fun Vfs.localRemoteFixture(
        dir: String,
        url: String,
        exists: Boolean,
    ) {
        write("$dir/checkout/.git/config", "[remote \"origin\"]\n\turl = $url\n")
        if (exists) write("$dir/remote.git/HEAD", "ref: refs/heads/main\n")
    }

    @Test fun mainCheckoutAndLinkedWorktreeResolveToOneProject() {
        val vfs = fixture("git@github.com:listepo/rtok-notes.git")
        val main = projectNameWith(vfs, "/home/me/rtok/src")
        assertEquals("rtok-notes", main)
        assertEquals(main, projectNameWith(vfs, "/home/me/_worktrees/rtok-t133/src"))
    }

    @Test fun projectRootSeparatesTheWorktreeFromTheMainCheckout() {
        val vfs = fixture("https://github.com/pyrlyn/rtok")
        val linked = ProjectRoot.findWith(vfs, "/home/me/_worktrees/rtok-t133/src")!!
        assertEquals("/home/me/_worktrees/rtok-t133", linked.checkout)
        assertEquals("/home/me/rtok", linked.main)
        assertEquals("/home/me/rtok/.git", linked.gitDir)
        val main = ProjectRoot.findWith(vfs, "/home/me/rtok")!!
        assertEquals("/home/me/rtok" to "/home/me/rtok", main.checkout to main.main)
    }

    @Test fun plainDirectoryIsNoProject() {
        val vfs = Vfs().apply { write("/home/me/scratch/notes.txt", "") }
        assertNull(projectNameWith(vfs, "/home/me/scratch"))
    }

    @Test fun withoutOriginTheMainCheckoutBasenameNamesTheProject() {
        val vfs =
            Vfs().apply {
                write("/home/me/local-only/.git/HEAD", "ref: refs/heads/main\n")
                write("/home/me/local-only/.git/worktrees/wt/commondir", "../..\n")
                write("/home/me/wt/.git", "gitdir: /home/me/local-only/.git/worktrees/wt\n")
            }
        assertEquals("local-only", projectNameWith(vfs, "/home/me/local-only"))
        assertEquals("local-only", projectNameWith(vfs, "/home/me/wt"))
    }

    @Test fun originUrlShapesGiveTheRepoName() {
        for (url in listOf("https://github.com/pyrlyn/rtok", "https://github.com/pyrlyn/rtok.git/", "ssh://git@github.com/pyrlyn/rtok.git", "file:///srv/git/rtok.git")) {
            assertEquals("rtok", projectNameWith(fixture(url), "/home/me/rtok"), url)
        }
    }

    @Test fun submoduleKeepsItsOwnName() {
        val vfs =
            fixture("https://github.com/pyrlyn/rtok").apply {
                write("/home/me/rtok/vendor/dep/.git", "gitdir: ../../.git/modules/dep\n")
                write("/home/me/rtok/.git/modules/dep/HEAD", "ref: refs/heads/main\n")
            }
        assertEquals("dep", projectNameWith(vfs, "/home/me/rtok/vendor/dep/src"))
    }

    @Test fun remotesCanonicalizeAcrossProtocols() {
        for ((url, key) in listOf(
            "git@github.com:Pyrlyn/rtok.git" to "github.com/pyrlyn/rtok",
            "ssh://git@github.com/pyrlyn/rtok" to "github.com/pyrlyn/rtok",
            "https://me@github.com/pyrlyn/rtok/" to "github.com/pyrlyn/rtok",
            "https://gitlab.example.com/g/sub/n.git" to "gitlab.example.com/g/sub/n",
            "https://host/a/b@v2" to "host/a/b@v2",
            "file:///srv/git/rtok.git" to "/srv/git/rtok",
            "/srv/git/rtok" to "/srv/git/rtok",
        )) {
            assertEquals(key, canonicalUrl(url), url)
        }
    }

    @Test fun oneRelativeOriginInTwoCheckoutsIsTwoKeys() {
        for (exists in listOf(true, false)) {
            val vfs =
                Vfs().apply {
                    localRemoteFixture("/a", "../remote.git", exists)
                    localRemoteFixture("/b", "../remote.git", exists)
                }
            assertEquals("/a/remote", projectKeyWith(vfs, "/a/checkout"))
            assertEquals("/b/remote", projectKeyWith(vfs, "/b/checkout"))
        }
    }

    @Test fun spellingsOfOneLocalRemoteShareAKey() {
        for (url in listOf("../remote.git", "../remote.git/", "../x/../remote", "/a/remote.git", "file:///a/remote.git", "file://localhost/a/remote.git")) {
            val vfs =
                Vfs().apply {
                    localRemoteFixture("/a", url, true)
                    write("/a/checkout/.git/worktrees/wt/commondir", "../..\n")
                    write("/wt/.git", "gitdir: /a/checkout/.git/worktrees/wt\n")
                }
            val main = projectKeyWith(vfs, "/a/checkout/src")
            assertEquals("/a/remote", main, url)
            assertEquals(main, projectKeyWith(vfs, "/wt"), url)
        }
    }

    @Test fun networkRemotesAreNotResolvedAgainstTheCheckout() {
        for ((url, key) in listOf(
            "git@github.com:Owner/Repo.git" to "github.com/owner/repo",
            "github.com:Owner/Repo" to "github.com/owner/repo",
            "https://Example.com/Owner/Repo" to "example.com/owner/repo",
        )) {
            val vfs = Vfs().apply { localRemoteFixture("/a", url, false) }
            assertEquals(key, projectKeyWith(vfs, "/a/checkout"), url)
        }
    }

    @Test fun aMissingLocalRemoteKeepsItsSpelledCase() {
        val vfs = Vfs().apply { localRemoteFixture("/a", "../Remote With Space.git", false) }
        assertEquals("/a/Remote With Space", projectKeyWith(vfs, "/a/checkout"))
    }

    @Test fun worktreesShareTheRemoteKeyAndFallBackToTheMainPath() {
        val vfs = fixture("git@github.com:listepo/rtok.git")
        val main = projectKeyWith(vfs, "/home/me/rtok/src")
        assertEquals("github.com/listepo/rtok", main)
        assertEquals(main, projectKeyWith(vfs, "/home/me/_worktrees/rtok-t133/src"))
        val local =
            Vfs().apply {
                write("/home/me/local-only/.git/HEAD", "ref: refs/heads/main\n")
                write("/home/me/local-only/a.rs", "")
            }
        assertEquals("/home/me/local-only", projectKeyWith(local, "/home/me/local-only"))
        assertNull(projectKeyWith(local, "/home/me"))
    }

    @Test fun projectDirPrefersCwdThenExtraAndNeverGitFatal() {
        val base = "${tempDir()}/rtok-project-dir-${pid()}"
        removeDirAll(base)
        val (home, repo, plain) = Triple("$base/home", "$base/repo", "$base/plain")
        listOf(home, plain, "$repo/.git", "$repo/src").forEach(::createDirAll)
        assertEquals(repo, projectDir(repo, listOf(home, plain)))
        assertEquals(repo, projectDir(home, listOf(plain, repo)))
        assertEquals("$repo/src", projectDir("$repo/src", emptyList()))
        val err = assertFails { projectDir(home, listOf(plain)) }.message!!
        assertTrue("no project root" in err && "pass a path or open a project" in err && "fatal:" !in err, err)
        removeDirAll(base)
    }
}
