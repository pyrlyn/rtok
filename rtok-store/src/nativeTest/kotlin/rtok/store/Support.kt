package rtok.store

import rtok.sys.*

private var nextTmp = 0
/** Port of support.rs `tmp_dir`: a fresh, empty directory under `$TMPDIR`. */
fun tmpDir(tag: String): String {
    val d = "${tempDir()}/rtok-$tag-${pid()}-${nextTmp++}"
    removeDirAll(d); createDirAll(d); return d
}
