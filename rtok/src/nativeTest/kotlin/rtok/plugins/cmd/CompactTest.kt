// Port of the src/plugins/cmd/compact.rs tests (18).
package rtok.plugins.cmd

import kotlin.test.*
import rtok.sys.rustLines

private const val TEST_FAIL = "   Compiling c v0.1.0\n    Finished `test` profile [unoptimized + debuginfo] target(s) in 0.00s\n     Running unittests src/lib.rs (target/debug/deps/c-1)\n\nrunning 3 tests\ntest a::ok ... ok\ntest a::bad ... FAILED\ntest a::ok2 ... ok\n\nfailures:\n\n---- a::bad stdout ----\n\nthread 'a::bad' panicked at src/lib.rs:9:9:\nassertion `left == right` failed: nope\n  left: 2\n right: 3\nnote: run with `RUST_BACKTRACE=1` environment variable to display a backtrace\n\n\nfailures:\n    a::bad\n\ntest result: FAILED. 2 passed; 1 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.00s\n"

private fun longStatus(branch: String, rest: String) = "On branch main\n$branch\n\nChanges not staged for commit:\n  (use \"git add <file>...\" to update what will be committed)\n$rest\n"
private fun longLine(name: String, size: Long, perms: String) = "$perms  1 u g $size Oct 14 23:50 $name\n"
private const val RW = "-rw-r--r--"

class CompactTest {
    @Test fun cargoTestKeepsThePanicBodyAndOneTotalsLine() = assertEquals(
        "thread 'a::bad' panicked at src/lib.rs:9:9:\nassertion `left == right` failed: nope\n  left: 2\n right: 3\nFAILED: a::bad\ncargo test: 2 passed, 1 failed (1 suite)", Compact.cargoTest(TEST_FAIL))

    @Test fun cargoTestSumsEverySuite() = assertEquals("cargo test: 2 passed, 1 ignored (2 suites)", Compact.cargoTest(
        "running 2 tests\ntest x ... ok\ntest y ... ok\n\ntest result: ok. 2 passed; 0 failed; 1 ignored; 0 measured; 0 filtered out; finished in 0.00s\n\n   Doc-tests c\n\nrunning 0 tests\n\ntest result: ok. 0 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.00s\n"))

    @Test fun cargoTestCompileErrorIsABuildLog() = assertEquals(
        "error[E0308]: mismatched types\n --> src/lib.rs:3:5\nerror: could not compile `c` (lib test) due to 1 previous error",
        Compact.cargoTest("   Compiling c v0.1.0\nerror[E0308]: mismatched types\n --> src/lib.rs:3:5\n  |\nerror: could not compile `c` (lib test) due to 1 previous error\n"))

    @Test fun cargoBuildSuccessIsOneWordAndWarningCountsGo() = assertEquals("warning: unused variable: `x`\n --> src/lib.rs:1:5\nFinished dev",
        Compact.cargoBuild("   Compiling c v0.1.0\nwarning: unused variable: `x`\n --> src/lib.rs:1:5\n  |\nwarning: `c` (lib) generated 1 warning\n    Finished `dev` profile [unoptimized + debuginfo] target(s) in 1.20s\n"))

    @Test fun gitStatusLongFormatReadsAsPorcelain() = assertEquals("On branch main\nA  a.rs\n M b.rs\n R c.rs -> d.rs\n?? e.txt dir/", Compact.gitStatus(
        "On branch main\nChanges to be committed:\n  (use \"git restore --staged <file>...\" to unstage)\n\tnew file:   a.rs\n\nChanges not staged for commit:\n  (use \"git add <file>...\" to update what will be committed)\n\tmodified:   b.rs\n\trenamed:    c.rs -> d.rs\n\nUntracked files:\n  (use \"git add <file>...\" to include in what will be committed)\n\te.txt\n\tdir/\n\nno changes added to commit (use \"git add\" and/or \"git commit -a\")\n"))

    @Test fun gitStatusCleanStaysAsIs() { val out = "On branch main\nnothing to commit, working tree clean\n"; assertEquals(out.trimEnd(), Compact.gitStatus(out)) }

    @Test fun gitStatusUnknownEntryStandsDown() = assertNull(Compact.gitStatus("On branch main\nChanges not staged for commit:\n\tweird:   a\n"))

    @Test fun gitDiffKeepsHunksAndOneHeaderPerFile() = assertEquals(
        "+++ b/x\n@@ -1 +1 @@\n-a\n+b\nnew file mode 100644\n--- /dev/null\n+++ b/n\n@@ -0,0 +1 @@\n+c\ndiff --git a/bin b/bin\nBinary files a/bin and b/bin differ",
        Compact.gitDiff("diff --git a/x b/x\nindex 1..2 100644\n--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n+b\ndiff --git a/n b/n\nnew file mode 100644\nindex 0..3\n--- /dev/null\n+++ b/n\n@@ -0,0 +1 @@\n+c\ndiff --git a/bin b/bin\nindex 4..5 100644\nBinary files a/bin and b/bin differ\n"))

    @Test fun gitStatusGroupsPathsByDirectoryAndFoldsTheBranchLine() {
        val out = longStatus("Your branch is ahead of 'origin/main' by 3 commits.\n  (use \"git push\" to publish your local commits)",
            "\tmodified:   src/a.rs\n\tmodified:   src/b.rs\n\tmodified:   docs/c.md\n\tdeleted:    src/gone.rs\n\tmodified:   top.toml\n")
        assertEquals("On branch main ahead 3 (origin/main)\n M src/{a.rs,b.rs} docs/c.md top.toml\n D src/gone.rs", Compact.gitStatus(out))
    }

    @Test fun gitStatusBranchLineForUpToDateBehindAndDiverged() {
        val rest = "\tmodified:   a.rs\n"
        assertEquals("On branch main\n M a.rs", Compact.gitStatus(longStatus("Your branch is up to date with 'origin/main'.", rest)))
        assertEquals("On branch main behind 2 (origin/main)\n M a.rs", Compact.gitStatus(longStatus("Your branch is behind 'origin/main' by 2 commits, and can be fast-forwarded.\n  (use \"git pull\" to update your local branch)", rest)))
        assertEquals("On branch main diverged +2 -5 (origin/main)\n M a.rs", Compact.gitStatus(longStatus("Your branch and 'origin/main' have diverged,\nand have 2 and 5 different commits each, respectively.\n  (use \"git pull\" if you want to integrate the remote branch with yours)", rest)))
    }

    @Test fun gitStatusKeepsEveryPathAndIsolatesOddNames() {
        val got = Compact.gitStatus("On branch main\nChanges to be committed:\n\trenamed:    src/old.rs -> src/new.rs\n\tnew file:   src/a b.rs\n\tnew file:   src/c,d.rs\n\tnew file:   src/e.rs\n\nUntracked files:\n\tbuild/\n\tx.log\n\ty.log\n")!!
        for (p in listOf("src/old.rs -> src/new.rs", "src/a b.rs", "src/c,d.rs", "src/e.rs", "build/", "x.log", "y.log")) assertTrue(got.contains(p), "$p lost: $got")
        assertTrue(got.contains("?? build/ x.log y.log"), got); assertTrue(got.contains("A  src/e.rs"), got)
    }

    @Test fun lsLListsDirsThenFilesWithSizes() = assertEquals("dirs: src/\nCargo.toml 3.3K\nrun me.sh* 120B\nlink -> src", Compact.lsL(
        "total 24\ndrwxr-xr-x  5 u g  160 Oct 14 23:50 .\ndrwxr-xr-x  9 u g  288 Oct 13 19:44 ..\ndrwxr-xr-x  2 u g   64 Oct 14 23:50 src\n-rw-r--r--  1 u g 3364 Oct 14 23:49 Cargo.toml\n-rwxr-xr-x  1 u g  120 Oct 14 23:49 run me.sh\nlrwxrwxrwx  1 u g    8 Oct 14 23:49 link -> src\n"))

    @Test fun lsLStandsDownOnOtherShapes() {
        assertNull(Compact.lsL("a.txt\nb.txt\n")); assertTrue(Compact.lsLong(listOf("ls", "-la")))
        assertFalse(Compact.lsLong(listOf("ls", "-a"))); assertFalse(Compact.lsLong(listOf("ls", "--color")))
    }

    @Test fun lsLGroupsByExtensionAboveThirtyFilesAndKeepsEveryName() {
        val out = buildString {
            append("total 99\n")
            for (i in 0 until 20) append(longLine("m$i.rs", 1200L + i, RW))
            for (i in 0 until 8) append(longLine("t$i.toml", 300, RW))
            append(longLine("README.md", 2048, RW)); append(longLine("Makefile", 900, RW))
            append(longLine("run.sh", 120, "-rwxr-xr-x")); append(longLine("x.sh", 10, "-rwxr-xr-x"))
        }
        val got = Compact.lsL(out)!!; val lines = got.rustLines()
        assertEquals(5, lines.size, got); assertEquals("Makefile 900B", lines[0])
        assertTrue(lines[1].startsWith("*.rs (20): m0 1.2K, m1 1.2K"), got); assertTrue(lines[1].endsWith("m19 1.2K"), got)
        assertTrue(lines[2].startsWith("*.toml (8): t0 300B, t1 300B"), got)
        assertEquals("README.md 2.0K", lines[3]); assertEquals("*.sh (2): run* 120B, x* 10B", lines[4])
    }

    @Test fun lsLLeavesThirtyFilesOrFewerUngrouped() {
        val got = Compact.lsL((0 until 30).joinToString("") { longLine("f$it.rs", 10, RW) })!!
        assertEquals(30, got.rustLines().size, got); assertTrue(got.startsWith("f0.rs 10B\nf1.rs 10B"), got)
    }

    @Test fun lsLaDotfilesShareOneLine() {
        val out = longLine(".gitignore", 40, RW) + longLine(".env", 12, RW) + longLine(".editorconfig", 300, RW) + longLine("main.rs", 5000, RW) + longLine(".npmrc", 7, RW)
        assertEquals("main.rs 4.9K\ndotfiles: .gitignore 40B, .env 12B, .editorconfig 300B, .npmrc 7B", Compact.lsL(out))
        assertEquals(".env 12B\n.npmrc 7B", Compact.lsL(longLine(".env", 12, RW) + longLine(".npmrc", 7, RW)))
    }

    /** T684: rustc prints the `#[warn(lint)]` note only on the first block; later ones join through their headline shape. */
    @Test fun cargoBuildGroupsWarningsByLintAndKeepsEveryLocation() {
        val out = "   Compiling c v0.1.0\nwarning: unused import: `a`\n --> src/a.rs:1:5\n  |\n  = note: `#[warn(unused_imports)]` on by default\n\n" +
            "warning: unused import: `b`\n --> src/b.rs:2:5\n  |\n\nwarning: unused import: `c`\n --> src/c.rs:3:5\n  |\n\n" +
            "warning: redundant clone\n --> src/d.rs:4:1\n  |\n  = help: for further information visit https://rust-lang.github.io/rust-clippy/master/index.html#redundant_clone\n\n" +
            "warning: `c` (lib) generated 4 warnings\n    Finished `dev` profile [unoptimized + debuginfo] target(s) in 1.20s\n"
        assertEquals("unused_imports ×3: unused import: `a` (src/a.rs:1:5, src/b.rs:2:5, src/c.rs:3:5)\nwarning: redundant clone\n --> src/d.rs:4:1\nFinished dev", Compact.cargoBuild(out))
    }

    @Test fun cargoBuildGroupsShapeTwinsWithoutAnyLintNote() = assertEquals(
        "unused variable: `x` ×2 (src/a.rs:1:1, src/b.rs:2:2)\nerror[E0308]: mismatched types\n --> src/c.rs:3:3",
        Compact.cargoBuild("warning: unused variable: `x`\n --> src/a.rs:1:1\nwarning: unused variable: `y`\n --> src/b.rs:2:2\nerror[E0308]: mismatched types\n --> src/c.rs:3:3\n"))
}
