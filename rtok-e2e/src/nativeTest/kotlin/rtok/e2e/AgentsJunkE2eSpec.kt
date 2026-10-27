@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

// Port of tests/agents_junk.rs: `rtok agents junk list|clear` through the binary over a fixture home that is both
// `HOME` and `RTOK_HOME`. Not here yet: the host folders test (every host faked), `--trash` (Linux only in Rust)
// and doctor's reclaimable line (the doctor port).

package rtok.e2e

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import rtok.sys.*

private const val DAY = 86_400L
private const val NANOS = 1_000_000_000L
private const val LOG_FILES = 5
private const val STALE_BYTES = 5
private const val SMALL = 10
private const val BIG = 100
private const val CACHE_BYTES = 300
private const val MONTH_AND_A_DAY = 31L
private const val MONTH_LESS_A_DAY = 29L
private const val WEEK_AND_THREE = 10L
private const val HOUR = 3_600L
private const val LOG_A = 30
private const val LOG_B = 10
private const val LOG_C = 20
private const val MIN_ONE = "25"
private const val MIN_TWO = "15"
private const val THREE = 3
private const val USAGE = 2
private const val FAILED = 1
private const val STALE_DAYS = 20L
private const val ROWS = 8
private const val EXEC = "755"
private const val OCTAL = 8

/** A fixture home: `HOME`, `RTOK_HOME`, the XDG roots and an empty PATH of fake host CLIs under one folder. */
private class JunkHome(
    tag: String,
) : AutoCloseable {
    val home =
        "${tempDir()}/rtok-e2e-junk-$tag-${pid()}".also {
            removeDirAll(it)
            createDirAll("$it/.fake-hosts")
        }
    val cache = "$home/Library/Caches/rtok"
    val log = "$home/logs/rtok.log"
    private val env =
        mapOf(
            "RTOK_HOME" to home,
            "HOME" to home,
            "RTOK_CONFIG" to null,
            "XDG_CACHE_HOME" to "$home/.cache",
            "XDG_DATA_HOME" to "$home/.local/share",
            "XDG_CONFIG_HOME" to null,
            "CLAUDE_CONFIG_DIR" to null,
            "CODEX_HOME" to null,
            "RTOK_HOST_SANDBOX" to "1",
            "PATH" to "$home/.fake-hosts",
            "NO_COLOR" to "1",
        )

    fun rtok(vararg args: String) = sh(listOf(RTOK) + args, home, env)

    fun rtokIn(
        cwd: String,
        vararg args: String,
    ) = sh(listOf(RTOK) + args, cwd, env)

    /** `rtok agents junk clear <args>` in [cwd]: the exit code and the JSON it printed (T182's plain form is an array). */
    fun clearIn(
        cwd: String,
        vararg args: String,
    ): Pair<Int, JsonElement> = rtokIn(cwd, "agents", "junk", "clear", *args).let { it.code to Json.parseToJsonElement(it.out) }

    /** `rtok agents junk clear <args>`, which must exit 0. */
    fun clear(vararg args: String): JsonObject = ok("agents", "junk", "clear", *args).let { Json.parseToJsonElement(it).jsonObject }

    fun ok(vararg args: String): String = rtok(*args).also { check(it.code == 0) { "rtok ${args.toList()}: ${it.err}" } }.out

    /** A host CLI that answers `--version` only: the host counts as installed (T426). */
    fun fakeHost(bin: String) {
        val p = "$home/.fake-hosts/$bin"
        writeString(p, "#!/bin/sh\n[ \"$1\" = --version ] && { echo 0.0.0; exit 0; }\necho \"fake host: unsupported\" >&2\nexit 1\n")
        platform.posix.chmod(p, EXEC.toUShort(OCTAL).convert())
    }

    override fun close() = removeDirAll(home)
}

private fun write(
    path: String,
    bytes: Int,
) {
    createDirAll(parent(path)!!)
    writeString(path, "x".repeat(bytes))
}

private fun nowNanos(): Long = platform.posix.time(null) * NANOS

private fun age(
    path: String,
    secs: Long,
) = setModified(path, nowNanos() - secs * NANOS)

private fun items(plan: JsonElement): List<JsonObject> = plan.jsonObject["items"]!!.jsonArray.map { it.jsonObject }

private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content

/** File names of the planned (`clear`) items of [kind], sorted. */
private fun planned(
    plan: JsonObject,
    kind: String,
): List<String> = items(plan).filter { it.str("action") == "clear" && it.str("kind") == kind }.map { fileName(it.str("path"))!! }.sorted()

private val GIT_ENV =
    mapOf(
        "GIT_CONFIG_GLOBAL" to "/dev/null",
        "GIT_AUTHOR_NAME" to "t",
        "GIT_AUTHOR_EMAIL" to "t@t",
        "GIT_COMMITTER_NAME" to "t",
        "GIT_COMMITTER_EMAIL" to "t@t",
    )

/** tests/agents_junk.rs `git`: must succeed; stdout. */
private fun git(
    dir: String,
    vararg args: String,
): String {
    val p = sh(listOf("git", "-c", "commit.gpgsign=false") + args, dir, GIT_ENV)
    check(p.code == 0) { "git ${args.toList()}: ${p.err}" }
    return p.out
}

/** Every file under [dir], the worktree's `.git` pointer included, last modified [secs] ago. */
private fun ageAll(
    dir: String,
    secs: Long,
) {
    for (name in listDir(dir)) {
        val p = "$dir/$name"
        if (isDir(p) && name != ".git") {
            ageAll(p, secs)
        } else if (isFile(p)) {
            age(p, secs)
        }
    }
}

/** File names of the [items] that [keep] picks, sorted. */
private fun names(
    items: List<JsonObject>,
    keep: (JsonObject) -> Boolean,
): List<String> = items.filter(keep).map { fileName(it.str("path"))!! }.sorted()

/** Where the git binary is, from this process's PATH. */
private fun realGit(): String =
    platform.posix
        .getenv("PATH")!!
        .toKString()
        .split(':')
        .map { "$it/git" }
        .first(::isFile)

/** Three stray log siblings past `[log] files` = 5, of 30, 10 and 20 bytes. */
private fun JunkHome.strayLogs(): List<String> {
    createDirAll(parent(log)!!)
    return listOf(1 to LOG_A, 2 to LOG_B, THREE to LOG_C).map { (n, size) -> "$log.${LOG_FILES + n}".also { write(it, size) } }
}

class AgentsJunkE2eSpec :
    FunSpec({
        test("the T182 dry run lists the stale log and --yes removes it without touching the db") {
            JunkHome("clear").use { h ->
                h.ok("agents", "junk", "list")
                val stale = "${h.log}.6"
                write(stale, STALE_BYTES)
                val rows = Json.parseToJsonElement(h.ok("agents", "junk", "clear", "--json")).jsonArray
                rows.map { it.jsonObject.str("path") } shouldBe listOf(stale)
                isFile(stale) shouldBe true
                h.ok("agents", "junk", "clear", "--yes", "--json")
                exists(stale) shouldBe false
            }
        }
        test("list reports the stale log with exact bytes and removes nothing") {
            JunkHome("list").use { h ->
                val stale = "${h.log}.6"
                write(stale, STALE_BYTES)
                val report = Json.parseToJsonElement(h.ok("agents", "junk", "list", "--json")).jsonObject
                val own = report["agents"]!!.jsonArray[0].jsonObject
                own.str("name") shouldBe "rtok"
                own["kinds"]!!.jsonArray[0].jsonObject.str("kind") shouldBe "log"
                own["kinds"]!!.jsonArray[0].jsonObject.str("size_bytes") shouldBe "5"
                report.str("freed_default_bytes") shouldBe "5"
                h.ok("agents", "junk", "list", "--bytes") shouldContain "Freed by `clear`: 5\n"
                isFile(stale) shouldBe true
            }
        }
        test("clear with a kind plans exactly and --yes empties the cache keeping the store") {
            JunkHome("clear-kind").use { h ->
                h.ok("agents", "junk", "list")
                write("${h.cache}/a/blob", CACHE_BYTES)
                age("${h.cache}/a/blob", 2 * DAY)
                write("${h.home}/project/Cargo.lock", SMALL)
                val plan = h.clear("--agent", "rtok", "--kind", "cache", "--json")
                val all = items(plan)
                all.size shouldBe 1
                all[0].str("kind") shouldBe "cache"
                all[0].str("path") shouldBe h.cache
                all[0].str("action") shouldBe "clear"
                plan.str("freed_bytes") shouldBe "0"
                isFile("${h.cache}/a/blob") shouldBe true
                val done = h.clear("--agent", "rtok", "--kind", "cache", "--yes", "--json")
                done.str("freed_bytes") shouldBe plan.str("planned_bytes")
                (isDir(h.cache) && !exists("${h.cache}/a")) shouldBe true
                (isFile("${h.home}/rtok.db") && isFile("${h.home}/project/Cargo.lock")) shouldBe true
                h.clear("--kind", "cache", "--yes", "--json").str("planned_bytes") shouldBe "0"
            }
        }
        test("--yes skips an item modified in the last minute and exits 1") {
            JunkHome("clear-fresh").use { h ->
                write("${h.cache}/blob", SMALL)
                val out = h.rtok("agents", "junk", "clear", "--kind", "cache", "--yes", "--json")
                out.code shouldBe FAILED
                out.err shouldContain "some junk could not be removed"
                items(Json.parseToJsonElement(out.out))[0].str("note") shouldBe "modified in the last minute"
                isFile("${h.cache}/blob") shouldBe true
            }
        }
        test("a bad clear flag is a usage error") {
            JunkHome("clear-usage").use { h ->
                for (args in listOf(listOf("--kind", "nope"), listOf("--agent", "nope"), listOf("--older-than", "soon"), listOf("--include", "everything"))) {
                    h.rtok("agents", "junk", "clear", *args.toTypedArray()).code shouldBe USAGE
                }
            }
        }
        test("review logs need the flag and follow keep_logs_days") {
            JunkHome("clear-logs").use { h ->
                h.fakeHost("claude")
                val debug = "${h.home}/.claude/debug"
                write("$debug/old.log", BIG)
                write("$debug/new.log", SMALL)
                age("$debug/old.log", MONTH_AND_A_DAY * DAY)
                age("$debug/new.log", MONTH_LESS_A_DAY * DAY)
                planned(h.clear("--agent", "claude", "--json"), "logs") shouldBe emptyList()
                planned(h.clear("--agent", "claude", "--kind", "logs", "--json"), "logs") shouldBe listOf("old.log")
                planned(h.clear("--agent", "claude", "--include", "review", "--json"), "logs") shouldBe listOf("old.log")
                writeString("${h.home}/config.toml", "[agents.junk]\nkeep_logs_days = 7\n")
                planned(h.clear("--agent", "claude", "--include", "review", "--json"), "logs") shouldBe listOf("new.log", "old.log")
                h.clear("--agent", "claude", "--kind", "logs", "--yes", "--json")
                (exists("$debug/old.log") || exists("$debug/new.log")) shouldBe false
                isDir(debug) shouldBe true
            }
        }
        test("sessions need --kind sessions and follow the threshold") {
            JunkHome("clear-sessions").use { h ->
                h.fakeHost("claude")
                val id = "0b1c2d3e-4f50-6172-8394-a5b6c7d8e9f0"
                val transcript = "${h.home}/.claude/projects/p/$id.jsonl"
                val history = "${h.home}/.claude/file-history/$id/f@v1"
                val memory = "${h.home}/.claude/projects/p/memory/MEMORY.md"
                for (p in listOf(transcript, history, memory)) {
                    write(p, SMALL)
                    age(p, MONTH_AND_A_DAY * DAY)
                }
                val byKind = arrayOf("--agent", "claude", "--kind", "sessions")
                planned(h.clear("--agent", "claude", "--json"), "sessions").size shouldBe 0
                planned(h.clear("--agent", "claude", "--include", "review", "--json"), "sessions").size shouldBe 0
                planned(h.clear(*byKind, "--json"), "sessions").size shouldBe 2
                planned(h.clear(*byKind, "--session-days", "60", "--json"), "sessions").size shouldBe 0
                val list = h.ok("agents", "junk", "list")
                list shouldContain "old sessions: not touched for more than 30 days"
                list shouldContain "cleanupPeriodDays = 30 (default)"
                writeString("${h.home}/config.toml", "[agents.junk]\nstale_session_days = 60\n")
                planned(h.clear(*byKind, "--json"), "sessions").size shouldBe 0
                writeString("${h.home}/config.toml", "[agents.junk]\nstale_session_days = 30\n")
                h.clear(*byKind, "--yes", "--json")
                (exists(transcript) || exists(history)) shouldBe false
                isFile(memory) shouldBe true
            }
        }
        test("an extra crash folder clears only old dumps unless named") {
            JunkHome("clear-crash").use { h ->
                h.fakeHost("cursor-agent")
                val dumps = "${h.home}/dumps"
                write("$dumps/old.dmp", SMALL)
                write("$dumps/young.dmp", SMALL)
                age("$dumps/old.dmp", WEEK_AND_THREE * DAY)
                age("$dumps/young.dmp", 2 * DAY)
                writeString("${h.home}/config.toml", "[[agents.junk.extra]]\nhost = \"cursor\"\nkind = \"crash-dumps\"\npath = '$dumps'\n")
                planned(h.clear("--agent", "cursor", "--json"), "crash-dumps") shouldBe listOf("old.dmp")
                planned(h.clear("--agent", "cursor", "--kind", "crash-dumps", "--json"), "crash-dumps") shouldBe listOf("old.dmp", "young.dmp")
            }
        }
        test("list prints each item with its reason and cuts a kind at --items") {
            JunkHome("items").use { h ->
                val paths = h.strayLogs()
                val all = h.ok("agents", "junk", "list", "--bytes", "--items", "all")
                val line = { p: String -> all.lines().first { it.contains(fileName(p)!!) } }
                line(paths[0]) shouldContain "  30  last used "
                line(paths[0]) shouldContain "past `[log] files`"
                (all.indexOf(fileName(paths[0])!!) < all.indexOf(fileName(paths[1])!!)) shouldBe true
                h.ok("agents", "junk", "list", "--bytes", "--items", "1") shouldContain "+2 more (30)"
                h.ok("agents", "junk", "list", "--bytes", "--items", "0").also {
                    it shouldNotContain "last used"
                    it shouldNotContain "more ("
                }
                val byPath = h.ok("agents", "junk", "list", "--sort", "path", "--items", "all")
                (byPath.indexOf(fileName(paths[0])!!) < byPath.indexOf(fileName(paths[1])!!)) shouldBe true
            }
        }
        test("json carries every item and --min-size limits both list and clear") {
            JunkHome("min-size").use { h ->
                val paths = h.strayLogs()
                val own = { json: String ->
                    Json
                        .parseToJsonElement(json)
                        .jsonObject["agents"]!!
                        .jsonArray
                        .map { it.jsonObject }
                        .first { it.str("name") == "rtok" }["items"]!!
                        .jsonArray
                        .map { it.jsonObject }
                        .filter { it.str("kind") == "log" }
                }
                val logs = own(h.ok("agents", "junk", "list", "--json", "--items", "1"))
                logs.size shouldBe THREE
                logs[0].str("size_bytes") shouldBe "30"
                logs[0].str("path") shouldBe paths[0]
                logs[0].str("will_clear") shouldBe "true"
                (logs[0]["last_used"]!!.jsonPrimitive.longOrNull != null) shouldBe true
                logs[0].str("reason") shouldContain "past `[log] files`"
                own(h.ok("agents", "junk", "list", "--json", "--min-size", MIN_ONE)).size shouldBe 1
                val plan = items(h.clear("--kind", "log", "--min-size", MIN_TWO, "--json"))
                plan.size shouldBe 2
                (plan[0]["last_used"]!!.jsonPrimitive.longOrNull != null && plan[0]["reason"] is JsonPrimitive) shouldBe true
                paths.all(::exists) shouldBe true
                paths.forEach { age(it, HOUR) }
                h.ok("agents", "junk", "clear", "--kind", "log", "--min-size", MIN_TWO, "--yes")
                (!exists(paths[0]) && !exists(paths[2]) && exists(paths[1])) shouldBe true
            }
        }

        // T330.5.3: `stale-worktrees` is `rtok worktree gc`'s verdict. `list` shows the one it would remove and every
        // other worktree with gc's reason, `--include review --yes` removes the first through `detach` with its merged
        // branch, and nothing else is touched: not a dirty, unmerged, locked or fresh worktree, not a record whose
        // directory is gone (no `prune`), not an orphan, and not through `--trash` or a plain `clear --yes`.
        test("stale worktrees are what gc removes and clear removes only those") {
            JunkHome("stale-wt").use { h ->
                // Rust fakes every host; Claude Code is the one whose row this test reads.
                h.fakeHost("claude")
                // The fixture PATH holds only the fake hosts; rtok needs git, and no real agent.
                symlink(realGit(), "${h.home}/.fake-hosts/git")
                val repo = "${h.home}/repo".also(::createDirAll)
                git(repo, "init", "-q", "-b", "main")
                git(repo, "commit", "-q", "--allow-empty", "-m", "base")
                // gc judges "merged" against `origin/HEAD`, so the repository needs an origin.
                val origin = "${h.home}/origin.git"
                git(h.home, "init", "-q", "--bare", "-b", "main", origin)
                git(repo, "remote", "add", "origin", origin)
                git(repo, "push", "-q", "origin", "main")
                git(repo, "remote", "set-head", "origin", "main")
                val pool = "${h.home}/pool"
                val add = { dir: String, branch: String -> dir.also { git(repo, "worktree", "add", "-q", "-b", branch, it) } }
                val days = { n: Long -> n * DAY }
                val old = add("$pool/old", "old").also { ageAll(it, days(STALE_DAYS)) }
                // Claude Code's own pool, so the item sits under that host's row.
                val claude = add("$repo/.claude/worktrees/c1", "c1").also { ageAll(it, days(STALE_DAYS)) }
                add("$pool/fresh", "fresh")
                val dirty = add("$pool/dirty", "dirty")
                write("$dirty/wip.txt", STALE_BYTES)
                ageAll(dirty, days(STALE_DAYS))
                val unmerged = add("$pool/unmerged", "unmerged")
                write("$unmerged/work.txt", STALE_BYTES)
                git(unmerged, "add", ".")
                git(unmerged, "commit", "-q", "-m", "work")
                ageAll(unmerged, days(STALE_DAYS))
                val locked = add("$pool/locked", "locked")
                git(repo, "worktree", "lock", "--reason", "Cursor / grok | t9 | 2026-09-01", locked)
                ageAll(locked, days(STALE_DAYS))
                val gone = add("$pool/gone", "gone")
                removeDirAll(gone)
                val orphan = "$pool/orphan"
                write("$orphan/f", STALE_BYTES)
                writeString("$orphan/.git", "gitdir: $repo/.git/worktrees/orphan\n")

                // `list`: the removable ones are counted; each other worktree says why gc keeps it.
                val list = Json.parseToJsonElement(h.rtokIn(repo, "agents", "junk", "list", "--json").out).jsonObject
                val rows =
                    list["agents"]!!.jsonArray.map { it.jsonObject }.flatMap { a ->
                        a["items"]!!.jsonArray.map { it.jsonObject }.filter { it.str("kind") == "stale-worktrees" }.map { i ->
                            val reason = (i["skip_reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                            Triple(a.str("name"), fileName(i.str("path"))!!, reason.removePrefix("review kind: add --include review"))
                        }
                    }
                val row = { name: String -> rows.firstOrNull { it.second == name } ?: error("$name: $rows") }
                (row("old").first to row("old").third) shouldBe ("rtok" to "")
                (row("c1").first to row("c1").third) shouldBe ("claude" to "")
                row("fresh").third shouldContain "idle window"
                row("dirty").third shouldContain "uncommitted"
                row("unmerged").third shouldContain "not merged"
                row("locked").third shouldContain "lock by Cursor / grok"
                row("gone").third shouldContain "directory is gone"
                row("orphan").third shouldContain "orphan"
                rows.size shouldBe ROWS

                // The plan is gc's dry run (its lock override switched off, which junk never takes).
                val plan = h.clearIn(repo, "--include", "review", "--json")
                plan.first shouldBe 0
                val planned = names(items(plan.second)) { it.str("kind") == "stale-worktrees" && it.str("action") == "clear" }
                val gc = Json.parseToJsonElement(h.rtokIn(repo, "worktree", "gc", "--json", "--idle", "14d", "--stale-lock", "3650d").out)
                val gcRemoves = names(gc.jsonArray.map { it.jsonObject }) { it.str("action") == "remove" }
                planned shouldBe listOf("c1", "old")
                planned shouldBe gcRemoves

                // No flag, or `--trash`, removes no worktree.
                h.clearIn(repo, "--yes", "--json").first shouldBe 0
                h.clearIn(repo, "--include", "review", "--trash", "--yes", "--json").first shouldBe FAILED
                (isDir(old) && isDir(claude)) shouldBe true

                h.clearIn(repo, "--include", "review", "--yes", "--json").first shouldBe 0
                (!exists(old) && !exists(claude)) shouldBe true
                val branches = git(repo, "branch", "--format=%(refname:short)").lines()
                listOf("old", "c1").forEach { (it in branches) shouldBe false }
                listOf("fresh", "dirty", "unmerged", "locked", "gone").forEach { (it in branches) shouldBe true }
                listOf("fresh", "dirty", "unmerged", "locked", "orphan").forEach { isDir("$pool/$it") shouldBe true }
                git(repo, "worktree", "list", "--porcelain") shouldContain "pool/gone"

                val again = h.clearIn(repo, "--include", "review", "--yes", "--json")
                again.first shouldBe 0
                names(items(again.second)) { it.str("kind") == "stale-worktrees" }.isEmpty() shouldBe true
            }
        }
    })
