// Port of src/doctor/probe.rs: the only door the doctor's config checks (T331) use to reach the machine — files, the
// environment and `PATH`. Production passes the real implementations below; a test passes an in-memory one, so no
// check ever needs a real agent folder. [Writer] is the only way `doctor --fix` changes a file.
package rtok.doctor

import rtok.agent.sdk.writeAtomic
import rtok.agents.HostBins
import rtok.agents.McpPaths
import rtok.config.envVar
import rtok.sys.EntryKind
import rtok.sys.IoException
import rtok.sys.canonicalize
import rtok.sys.entryKind
import rtok.sys.fileMode
import rtok.sys.isDir
import rtok.sys.readLink
import rtok.sys.readToString
import rtok.agent.sdk.backup as sdkBackup

/** What a path is, following a symlink to its target. */
sealed class PathKind {
    data object Missing : PathKind()

    data class File(
        val executable: Boolean,
    ) : PathKind()

    data object Dir : PathKind()

    /** A symlink whose target does not exist; the target as written. */
    data class DanglingSymlink(
        val target: String,
    ) : PathKind()
}

/** Why a [ProbeFs.read] failed: the file is not there, or it is there and cannot be read. */
class ReadError(
    val notFound: Boolean,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

interface ProbeFs {
    /** The file's text. @throws ReadError */
    fun read(path: String): String

    fun kind(path: String): PathKind

    /**
     * The one name of a file reached through a symlinked folder (`/tmp` and `/private/tmp`), so a file named two ways
     * is not read twice. [path] itself when it cannot be resolved.
     */
    fun canonical(path: String): String
}

interface ProbeEnv {
    fun variable(name: String): String?

    fun home(): String?

    /** The directory the check runs for: the project whose settings apply. */
    fun cwd(): String?

    /** Whether hook commands follow Windows rules (`cmd`/PowerShell words, `PATHEXT`); a mock sets it on any OS. */
    fun windows(): Boolean = false
}

interface Which {
    fun find(program: String): String?
}

/** The machine, as the checks see it. */
class Probes(
    val fs: ProbeFs,
    val env: ProbeEnv,
    val which: Which,
) {
    companion object {
        val REAL = Probes(RealFs, RealEnv, RealWhich)
    }
}

object RealFs : ProbeFs {
    private const val EXEC_BITS = 0b001_001_001

    override fun canonical(path: String) = canonicalize(path) ?: path

    override fun read(path: String): String =
        try {
            readToString(path)
        } catch (e: IoException) {
            throw ReadError(e.kind == IoException.Kind.NotFound, e.message ?: "read failed", e)
        }

    override fun kind(path: String): PathKind {
        val link = entryKind(path) ?: return PathKind.Missing
        val mode = fileMode(path)
        return when {
            mode == null && link == EntryKind.Symlink -> PathKind.DanglingSymlink(readLink(path).orEmpty())
            mode == null -> PathKind.Missing
            isDir(path) -> PathKind.Dir
            else -> PathKind.File(mode and EXEC_BITS != 0)
        }
    }
}

object RealEnv : ProbeEnv {
    override fun variable(name: String) = envVar(name)

    override fun home() = McpPaths.home().takeIf { it.isNotEmpty() }

    override fun cwd(): String? = runCatching { rtok.sys.cwd() }.getOrNull()
}

object RealWhich : Which {
    override fun find(program: String) = HostBins.findOnPath(program)
}

/**
 * The only way the doctor changes a file: a copy to `.backup_configs/` first, then an atomic swap. Both are the helpers
 * `agents install` already writes host configs with, so a doctor fix and an install leave the same undo trail.
 */
interface Writer {
    /** Copy [path] into its `.backup_configs/` folder, keeping [keep] generations; null when an identical copy is there. */
    fun backup(
        path: String,
        keep: Int,
    ): String?

    /** Replace [path] with [body] so a reader sees the old or the new file, never half of it. */
    fun write(
        path: String,
        body: String,
    )
}

object RealWriter : Writer {
    override fun backup(
        path: String,
        keep: Int,
    ) = sdkBackup(path, keep)

    override fun write(
        path: String,
        body: String,
    ) = writeAtomic(path, body.encodeToByteArray())
}
