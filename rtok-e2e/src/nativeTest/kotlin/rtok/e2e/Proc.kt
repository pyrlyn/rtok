@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
// Subprocess + real-git helpers for the e2e suite. No mocks: a real shell, git and rtok binary.

package rtok.e2e

import kotlinx.cinterop.toKString
import rtok.sys.*

/** One finished process. */
data class Proc(
    val code: Int,
    val out: String,
    val err: String,
)

private var seq = 0

/** POSIX single-quoting. */
fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

/** Runs [argv] in [cwd] with [env] added (a null value unsets); captures stdout/stderr through files. */
fun sh(
    argv: List<String>,
    cwd: String,
    env: Map<String, String?> = emptyMap(),
    stdin: String? = null,
): Proc {
    val tag = "${tempDir()}/rtok-e2e-io-${pid()}-${seq++}"
    stdin?.let { writeString("$tag.in", it) }
    val input = if (stdin != null) "<${q("$tag.in")}" else "</dev/null"
    val unset = env.filterValues { it == null }.keys.joinToString(" ") { "-u $it" }
    val set = env.entries.filter { it.value != null }.joinToString(" ") { (k, v) -> "$k=${q(v!!)}" }
    val envPart = "$unset $set"
    val cmd = "cd ${q(cwd)} && env $envPart ${argv.joinToString(" ") { q(it) }} $input >${q("$tag.out")} 2>${q("$tag.err")}"
    val status = platform.posix.system(cmd)
    val code = (status shr 8) and 0xff
    val p = Proc(code, readToString("$tag.out"), readToString("$tag.err"))
    platform.posix.remove("$tag.out")
    platform.posix.remove("$tag.err")
    platform.posix.remove("$tag.in")
    return p
}

val RTOK: String = platform.posix.getenv("RTOK_BIN")?.toKString() ?: error("RTOK_BIN is not set; run ./gradlew e2eTest")

/** A sandbox: its own RTOK_HOME and HOME, removed afterwards. */
class Sandbox(
    tag: String,
) : AutoCloseable {
    val root =
        "${tempDir()}/rtok-e2e-$tag-${pid()}".also {
            removeDirAll(it)
            createDirAll(it)
        }
    val home = "$root/rtok-home".also(::createDirAll)
    val user = "$root/user".also(::createDirAll)
    private val env get() =
        mapOf(
            "RTOK_HOME" to home,
            "HOME" to user,
            "RTOK_CONFIG" to null,
            "NO_COLOR" to "1",
            "GIT_CONFIG_GLOBAL" to "/dev/null",
            "GIT_AUTHOR_NAME" to "e2e",
            "GIT_AUTHOR_EMAIL" to "e2e@x",
            "GIT_COMMITTER_NAME" to "e2e",
            "GIT_COMMITTER_EMAIL" to "e2e@x",
        )

    fun rtok(
        vararg args: String,
        cwd: String = root,
        stdin: String? = null,
        env: Map<String, String?> = emptyMap(),
    ) = sh(listOf(RTOK) + args, cwd, this.env + env, stdin)

    fun git(
        cwd: String,
        vararg args: String,
    ) = sh(listOf("git", "-c", "protocol.file.allow=always") + args, cwd, env).also {
        check(it.code == 0) { "git ${args.joinToString(" ")} failed: ${it.err}" }
    }

    /** A repo with one commit at [path], optionally with an `origin` URL. */
    fun repo(
        path: String,
        origin: String? = null,
    ): String {
        createDirAll(path)
        git(path, "init", "-q", "-b", "main")
        writeString("$path/README.md", "x\n")
        git(path, "add", ".")
        git(path, "commit", "-q", "-m", "init")
        origin?.let { git(path, "remote", "add", "origin", it) }
        return path
    }

    override fun close() = removeDirAll(root)
}
