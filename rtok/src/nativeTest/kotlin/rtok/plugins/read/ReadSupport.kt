package rtok.plugins.read

import kotlinx.cinterop.ExperimentalForeignApi
import rtok.config.Config
import rtok.plugin.Runtime
import rtok.plugin.sdk.Ctx
import rtok.support.diskConfig

/** Port of read `tests::cx`: a disk runtime whose fresh temp dir is also the one `allow_paths` root. */
fun readRt(
    name: String,
    tune: (Config) -> Config = { it },
): Pair<Runtime, String> {
    val (c, dir) = diskConfig("read-$name")
    val cfg = tune(c.copy(plugins = c.plugins.copy(read = c.plugins.read.copy(allowPaths = listOf(dir)))))
    return Runtime.open(cfg, name) to dir
}

/** A disk runtime whose `allow_paths` is the Vfs root `ws`. */
fun vfsRt(
    name: String,
    tune: (rtok.config.Read) -> rtok.config.Read = { it },
): Runtime {
    val (c, _) = diskConfig("read-$name")
    return Runtime.open(c.copy(plugins = c.plugins.copy(read = tune(c.plugins.read.copy(allowPaths = listOf("ws"))))), name)
}

fun Runtime.ctx() = Ctx(this)

@OptIn(ExperimentalForeignApi::class)
fun symlink(
    target: String,
    link: String,
) = check(platform.posix.symlink(target, link) == 0) { "symlink $link" }

/** The id inside `… archived <id> …`. */
fun archiveId(out: String): String {
    val pre = "… archived "
    val start = out.indexOf(pre).also { check(it >= 0) { out } } + pre.length
    return out.substring(start, out.indexOf(" …", start))
}
