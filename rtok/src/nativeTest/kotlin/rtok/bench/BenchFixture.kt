// A temp bench directory: six harmless tasks (checks are `true` or `false`, nothing touches a repo), two arm
// settings files, and a graph.toml with ten questions over three repos. Never live: RTOK_BENCH_LIVE stays unset.
package rtok.bench

import rtok.config.Bench
import rtok.config.Config
import rtok.sys.createDirAll
import rtok.sys.pid
import rtok.sys.removeDirAll
import rtok.sys.tempDir
import rtok.sys.writeString

private var seq = 0

internal class BenchFixture(
    tasks: Int = 6,
) : AutoCloseable {
    val dir =
        "${tempDir()}/rtok-bench-${pid()}-${seq++}".also {
            removeDirAll(it)
            createDirAll(it)
        }

    init {
        check(rtok.config.envVar("RTOK_BENCH_LIVE") == null) { "bench tests never run live" }
        val ids = listOf("add-fn", "fix-bug", "write-test", "rename", "doc", "run-tests").take(tasks)
        writeString(
            "$dir/tasks.toml",
            ids.mapIndexed { i, id -> "[[tasks]]\nid = \"$id\"\nprompt = \"Do $id.\"\ncheck = \"${if (i % 2 == 0) "true" else "false"}\"\n" }.joinToString("\n"),
        )
        writeString("$dir/a.json", "{}")
        writeString("$dir/b.json", "{}")
        val repos = listOf("rtok", "mini-rs", "mini-py").joinToString("\n") { "[[repos]]\nid = \"$it\"\npath = \"$dir\"\n" }
        val qs =
            (listOf("plugin" to "rtok", "config-class" to "mini-py") + (1..8).map { "q$it" to "mini-rs" })
                .joinToString("\n") { (id, repo) -> "[[questions]]\nid = \"$id\"\nrepo = \"$repo\"\nprompt = \"Where is $id?\"\nexpect = \"(?i)$id\"\n" }
        writeString("$dir/graph.toml", "$repos\n$qs")
    }

    fun cfg(
        dryRun: Boolean,
        suite: String = "",
        runs: UInt = 3u,
    ) = Config().copy(bench = Bench(tasks = "$dir/tasks.toml", runs = runs, dryRun = dryRun, suite = suite, configs = rtok.config.sortedMap("a" to "$dir/a.json", "b" to "$dir/b.json")))

    override fun close() = removeDirAll(dir)
}
