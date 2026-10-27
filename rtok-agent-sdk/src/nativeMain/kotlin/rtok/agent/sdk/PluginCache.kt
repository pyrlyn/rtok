// A user-level, content-addressed cache of plugin trees shared by every rtok process and project on the machine.
// Each tree is stored once as `archives/<hash>.tar.gz` and unpacked on first use into `unpacked/<hash>/`; later
// loads reuse that copy. Writers serialize per hash on `locks/<hash>.lock` (flock) and publish by atomic rename,
// so readers see either a complete directory carrying its marker or nothing.
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package rtok.agent.sdk

import platform.posix.LOCK_EX
import platform.posix.LOCK_UN
import platform.posix.O_CREAT
import platform.posix.O_RDWR
import platform.posix.close
import platform.posix.open
import platform.posix.rename
import rtok.sys.IoException
import rtok.sys.ProgressRunner
import rtok.sys.Sha256
import rtok.sys.createDirAll
import rtok.sys.exists
import rtok.sys.fileLock
import rtok.sys.fileMode
import rtok.sys.isDir
import rtok.sys.isFile
import rtok.sys.join
import rtok.sys.listDir
import rtok.sys.pid
import rtok.sys.readBytes
import rtok.sys.readToString
import rtok.sys.removeDirAll
import rtok.sys.removeFile
import rtok.sys.writeString
import kotlin.random.Random

/** File inside an unpacked tree naming the hash it was verified against; written before the tree is published. */
const val UNPACKED_MARKER = ".rtok-unpacked"

/** The shared plugin cache rooted at [root] (by default `<rtok home>/cache/plugins`, i.e. `~/.rtok/cache/plugins`). */
class PluginCache(
    val root: String,
) {
    private val archives = join(root, "archives")
    private val unpacked = join(root, "unpacked")
    private val locks = join(root, "locks")

    /** Where the compressed copy of [hash] lives. */
    fun archivePath(hash: String) = join(archives, "${checkHash(hash)}.tar.gz")

    /** Where [hash] is unpacked. */
    fun unpackedPath(hash: String) = join(unpacked, checkHash(hash))

    /** Store the tree at [src] compressed (once per content) and return its content hash. */
    fun put(src: String): String {
        require(isDir(src)) { "plugin tree $src is not a directory" }
        val hash = treeHash(src)
        if (isFile(archivePath(hash))) return hash
        withLock(hash) {
            if (isFile(archivePath(hash))) return@withLock
            createDirAll(archives)
            val tmp = join(archives, ".tmp-$hash-${uniq()}.tar.gz")
            try {
                tar("pack", listOf("-czf", tmp, "-C", src, "."))
                publish(tmp, archivePath(hash))
            } finally {
                if (exists(tmp)) removeFile(tmp)
            }
        }
        return hash
    }

    /**
     * The unpacked tree for [hash], unpacking it from its archive on first use. A copy whose marker is missing or
     * wrong (or, with [verify], whose files no longer hash to [hash]) is replaced.
     * @throws IoException when the archive is missing or does not unpack to [hash].
     */
    fun materialize(
        hash: String,
        verify: Boolean = false,
    ): String {
        val dir = unpackedPath(hash)
        if (valid(dir, hash, verify)) return dir
        withLock(hash) {
            if (valid(dir, hash, verify)) return@withLock
            val archive = archivePath(hash)
            if (!isFile(archive)) throw IoException(IoException.Kind.NotFound, "plugin archive $archive is missing")
            createDirAll(unpacked)
            val tmp = join(unpacked, ".tmp-$hash-${uniq()}")
            try {
                createDirAll(tmp)
                tar("unpack", listOf("-xzf", archive, "-C", tmp))
                if (treeHash(tmp) != hash) {
                    removeFile(archive)
                    throw IoException(IoException.Kind.Other, "plugin archive $archive is corrupt (removed)")
                }
                writeString(join(tmp, UNPACKED_MARKER), hash)
                // Under the lock nobody else publishes this hash; a stale copy goes first so rename can replace it.
                removeDirAll(dir)
                publish(tmp, dir)
            } finally {
                removeDirAll(tmp)
            }
        }
        return dir
    }

    /** [put] then [materialize]: the shared unpacked copy of [src]. */
    fun load(src: String): String = materialize(put(src))

    private fun valid(
        dir: String,
        hash: String,
        verify: Boolean,
    ): Boolean {
        val marker = join(dir, UNPACKED_MARKER)
        if (!isFile(marker) || runCatching { readToString(marker) }.getOrNull() != hash) return false
        return !verify || treeHash(dir) == hash
    }

    private inline fun withLock(
        hash: String,
        body: () -> Unit,
    ) {
        createDirAll(locks)
        val path = join(locks, "$hash.lock")
        val fd = open(path, O_CREAT or O_RDWR, LOCK_FILE_MODE)
        if (fd < 0) throw IoException(IoException.Kind.Other, "cannot open $path")
        try {
            if (fileLock(fd, LOCK_EX) != 0) throw IoException(IoException.Kind.Other, "cannot lock $path")
            try {
                body()
            } finally {
                fileLock(fd, LOCK_UN)
            }
        } finally {
            close(fd)
        }
    }

    private fun publish(
        from: String,
        to: String,
    ) {
        if (rename(from, to) != 0) throw IoException(IoException.Kind.Other, "rename $from → $to failed")
    }

    private fun tar(
        what: String,
        args: List<String>,
    ) {
        val (out, _) =
            ProgressRunner("$what plugin", TAR, args)
                .quiet()
                .env("COPYFILE_DISABLE", "1")
                .runCapped(TAR_OUTPUT_CAP)
        if (out.exitCode != 0) throw IoException(IoException.Kind.Other, "tar $what failed: ${out.stderr.decodeToString().trim()}")
    }

    companion object {
        /** The system tar (bsdtar on macOS, GNU tar on Linux); no extra dependency. */
        private const val TAR = "/usr/bin/tar"

        /** The cache every rtok process shares: `<rtokHome>/cache/plugins` (`$RTOK_HOME`, default `~/.rtok`). */
        fun under(rtokHome: String) = PluginCache(join(join(rtokHome, "cache"), "plugins"))

        private const val HEX_RADIX = 16

        private const val TAR_OUTPUT_CAP = 64L * 1024

        /** rw-r--r-- */
        private const val LOCK_FILE_MODE = 0x1a4

        /** Any of the x bits (0111). */
        private const val EXEC_BITS = 0x49

        private val HEX = Regex("[0-9a-f]{64}")

        private fun checkHash(hash: String) = hash.also { require(HEX.matches(it)) { "not a plugin hash: $it" } }

        private fun uniq() = "${pid()}-${Random.nextLong().toULong().toString(HEX_RADIX)}"

        /**
         * sha256 over every file under [dir] in path order: relative path, executable bit and content hash.
         * [UNPACKED_MARKER] at the root is not content.
         */
        fun treeHash(dir: String): String {
            val sb = StringBuilder()
            walk(dir, "") { rel, path ->
                val exec = ((fileMode(path) ?: 0) and EXEC_BITS) != 0
                sb
                    .append(rel)
                    .append('\u0000')
                    .append(if (exec) 'x' else '-')
                    .append(Sha256.hex(readBytes(path)))
                    .append('\n')
            }
            return Sha256.hex(sb.toString().encodeToByteArray())
        }

        private fun walk(
            dir: String,
            rel: String,
            visit: (String, String) -> Unit,
        ) {
            for (name in listDir(dir).sorted()) {
                if (rel.isEmpty() && name == UNPACKED_MARKER) continue
                val path = join(dir, name)
                val r = if (rel.isEmpty()) name else "$rel/$name"
                if (isDir(path)) walk(path, r, visit) else visit(r, path)
            }
        }
    }
}
