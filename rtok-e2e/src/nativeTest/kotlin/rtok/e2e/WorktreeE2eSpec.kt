// Port of tests/worktree.rs (the list and gc tests): `rtok worktree list|gc` against real git repositories.
package rtok.e2e

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import rtok.sys.*

private const val ME = "Claude Code / sonnet"
private const val BIN = 4096
private const val REPORT = 100
private const val TAG = "Signature: 8a477f597d28d172789f06886806bc55\n"

private fun Sandbox.g(
    dir: String,
    vararg args: String,
) = git(dir, "-c", "init.defaultBranch=main", "-c", "commit.gpgsign=false", *args).out

private fun Sandbox.commit(
    dir: String,
    file: String,
) {
    writeString("$dir/$file", file)
    g(dir, "add", file)
    g(dir, "commit", "-q", "-m", file)
}

private fun Sandbox.json(
    cwd: String,
    vararg args: String,
): JsonArray {
    val p = rtok(*args, cwd = cwd)
    check(p.code == 0) { "rtok ${args.joinToString(" ")}: ${p.err}" }
    return Json.parseToJsonElement(p.out).jsonArray
}

private fun JsonArray.byName(name: String): JsonObject = map { it.jsonObject }.firstOrNull { it["path"]!!.jsonPrimitive.content.endsWith("/$name") } ?: error("$name missing in $this")

private fun JsonElement?.str() = (this as JsonPrimitive).content

private fun Sandbox.addWt(
    work: String,
    name: String,
    lock: String?,
) {
    val args = mutableListOf("worktree", "add", "-q")
    when (lock) {
        null -> {}

        "" -> {
            args += "--lock"
        }

        else -> {
            args += listOf("--lock", "--reason", lock)
        }
    }
    args += listOf("-b", "t-$name", "../wt-$name")
    g(work, *args.toTypedArray())
}

class WorktreeE2eSpec :
    FunSpec({
        context("rtok worktree list") {
            test("T151: list splits cache from source and reports an orphan") {
                Sandbox("wt-list").use { s ->
                    val tmp = s.root
                    s.g(tmp, "init", "-q", "work")
                    val work = "$tmp/work"
                    s.commit(work, "a.txt")
                    writeString("$work/.git/info/exclude", "target/\nout/\n")
                    for (name in listOf("cache", "dirty", "orphan")) s.g(work, "worktree", "add", "-q", "-b", "t-$name", "../wt-$name")
                    val cache = "$tmp/wt-cache"
                    createDirAll("$cache/target/debug")
                    createDirAll("$cache/out")
                    writeString("$cache/target/CACHEDIR.TAG", TAG)
                    writeString("$cache/target/debug/bin", "\u0000".repeat(BIN))
                    writeString("$cache/out/report", "\u0000".repeat(REPORT))
                    writeString("$tmp/wt-dirty/new.txt", "x")
                    removeDirAll("$work/.git/worktrees/wt-orphan")

                    val rows = s.json(cache, "worktree", "list", "--json", "--sizes")
                    rows.size shouldBe listOf("work", "wt-cache", "wt-dirty", "wt-orphan").size
                    rows.byName("work")["state"].str() shouldBe "main"
                    rows.byName("work")["cache_bytes"]!!.jsonPrimitive.long shouldBe 0
                    rows.byName("wt-dirty")["state"].str() shouldBe "dirty"
                    val cached = rows.byName("wt-cache")
                    cached["state"].str() shouldBe "unmerged"
                    cached["branch"].str() shouldBe "t-cache"
                    cached["cache_bytes"]!!.jsonPrimitive.long shouldBe BIN.toLong() + TAG.length
                    val source = cached["source_bytes"]!!.jsonPrimitive.long
                    (source in REPORT until BIN) shouldBe true
                    (cached["modified_unix"]!!.jsonPrimitive.long > 0) shouldBe true
                    val orphan = rows.byName("wt-orphan")
                    orphan["state"].str() shouldBe "orphan"
                    (orphan["branch"] == JsonNull && orphan["owner"] == JsonNull) shouldBe true

                    val table = s.rtok("worktree", "list", cwd = work).out
                    table shouldStartWith "path "
                    table shouldContain " orphan "
                    table shouldContain "4 worktrees: "
                    table shouldContain "build cache not sized (--sizes)"

                    // T936: without `--sizes` the build cache is not walked.
                    val plain = s.json(cache, "worktree", "list", "--json").byName("wt-cache")
                    (plain["cache_bytes"] == JsonNull) shouldBe true
                    (plain["source_bytes"]!!.jsonPrimitive.long < BIN) shouldBe true

                    val outside = s.rtok("worktree", "list", cwd = tmp)
                    (outside.code != 0) shouldBe true
                    outside.err shouldContain "not a git repository"
                }
            }
        }
        context("rtok worktree gc") {
            test("T153: gc removes only finished worktrees and never opens a foreign lock") {
                Sandbox("wt-gc").use { s ->
                    val tmp = s.root
                    s.g(tmp, "init", "-q", "--bare", "origin.git")
                    s.g(tmp, "clone", "-q", "origin.git", "work")
                    val work = "$tmp/work"
                    writeString("$work/list.txt", "1\n2\n3\n")
                    s.g(work, "add", "list.txt")
                    s.commit(work, "a.txt")
                    s.g(work, "push", "-q", "-u", "origin", "main")
                    s.g(work, "remote", "set-head", "origin", "main")

                    val mine = "$ME | t1 | 2026-09-22"
                    val theirs = "Cursor / grok | t2 | 2026-09-22"
                    for (name in listOf("done", "adj", "dirty", "open", "gone")) s.addWt(work, name, null)
                    s.addWt(work, "mine", mine)
                    s.addWt(work, "gone-mine", mine)
                    s.addWt(work, "theirs", theirs)
                    s.addWt(work, "gone-theirs", theirs)
                    val today = sh(listOf("date", "-u", "+%F"), tmp).out.trim()
                    s.addWt(work, "gone-fresh", "Cursor / grok | t3 | $today")
                    s.addWt(work, "bare-lock", "")

                    s.commit("$tmp/wt-done", "done.txt")
                    s.g("$tmp/wt-done", "push", "-q", "origin", "t-done")
                    s.commit("$tmp/wt-mine", "mine.txt")
                    s.commit("$tmp/wt-open", "open.txt")
                    writeString("$tmp/wt-dirty/new.txt", "x")
                    writeString("$tmp/wt-adj/list.txt", "1\n2\n3\nx\n")
                    s.g("$tmp/wt-adj", "commit", "-q", "-am", "x")
                    for (b in listOf("t-done", "t-mine", "t-adj")) {
                        s.g(work, "merge", "-q", "--squash", b)
                        s.g(work, "commit", "-q", "-m", b)
                    }
                    writeString("$work/list.txt", "1\n2\n3\nx\ny\n")
                    s.g(work, "commit", "-q", "-am", "y")
                    s.g(work, "push", "-q", "origin", "main")
                    for (name in listOf("gone", "gone-mine", "gone-theirs", "gone-fresh")) removeDirAll("$tmp/wt-$name")
                    val listed = { s.g(work, "worktree", "list", "--porcelain") }
                    val before = listed()

                    val idle0 = arrayOf("worktree", "gc", "--json", "--owner", ME, "--idle", "0h")
                    val plan = s.json("$tmp/wt-done", *idle0)

                    fun planned(name: String) = plan.byName(name).let { "${it["action"].str()}: ${it["note"].str()}" }
                    planned("work") shouldBe "keep: main checkout"
                    planned("wt-done") shouldBe "keep: the worktree this command runs from"
                    planned("wt-mine") shouldBe "remove: merged, clean and idle"
                    planned("wt-adj") shouldBe "remove: merged, clean and idle"
                    planned("wt-dirty") shouldBe "keep: uncommitted changes"
                    planned("wt-open") shouldBe "keep: not merged into the base; check `gh pr view t-open`"
                    planned("wt-theirs") shouldBe "keep: locked by Cursor / grok"
                    planned("wt-bare-lock") shouldBe "keep: locked, owner unknown"
                    planned("wt-gone") shouldBe "drop-record: directory is gone"
                    planned("wt-gone-mine") shouldBe "drop-record: directory is gone"
                    planned("wt-gone-theirs") shouldBe "drop-record: directory is gone"
                    planned("wt-gone-fresh") shouldBe "keep: locked by Cursor / grok"
                    val stale = s.json(work, *idle0, "--stale-lock", "0h")

                    fun note(
                        rows: JsonArray,
                        name: String,
                    ) = rows.byName(name)["note"].str()
                    note(stale, "wt-theirs") shouldBe "merged, clean, abandoned lock by Cursor / grok"
                    note(stale, "wt-bare-lock") shouldBe "merged, clean, abandoned lock, owner unknown"
                    note(stale, "wt-gone-fresh") shouldBe "directory is gone"
                    listed() shouldBe before

                    val cautious = s.json(work, "worktree", "gc", "--json", "--yes")
                    note(cautious, "wt-done") shouldBe "modified within the idle window"
                    note(cautious, "wt-mine") shouldBe "locked by $ME"
                    note(cautious, "wt-gone") shouldBe "removed with its branch"
                    note(cautious, "wt-gone-mine") shouldBe "removed with its branch"
                    note(cautious, "wt-gone-theirs") shouldBe "removed with its branch"
                    note(cautious, "wt-gone-fresh") shouldBe "locked by Cursor / grok"
                    exists("$work/.git/worktrees/wt-gone-theirs") shouldBe false

                    val applied = s.json(work, *idle0, "--yes")
                    note(applied, "wt-done") shouldBe "removed with its branch; remote left: git push origin --delete t-done"
                    val kept = listOf("work", "wt-dirty", "wt-open", "wt-theirs", "wt-bare-lock", "wt-gone-fresh")
                    val after = listed()
                    val paths = after.lines().filter { it.startsWith("worktree ") }
                    paths.size shouldBe kept.size
                    kept.all { k -> paths.any { it.endsWith(k) } } shouldBe true
                    s
                        .g(work, "branch", "--format=%(refname:short)")
                        .lines()
                        .filter { it.isNotEmpty() }
                        .sorted() shouldContainExactly
                        listOf("main", "t-bare-lock", "t-dirty", "t-gone-fresh", "t-open", "t-theirs")
                    readDir("$work/.git/worktrees").sorted() shouldContainExactly
                        listOf("wt-bare-lock", "wt-dirty", "wt-gone-fresh", "wt-open", "wt-theirs")
                    after shouldContain "locked $theirs"
                }
            }
        }
    })
