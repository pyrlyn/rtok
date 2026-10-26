// Path and file helpers replacing the Rust `fs-kit` crate (pyrlyn/crates) used across rtok.
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package rtok.sys

import kotlinx.cinterop.*
import platform.posix.*

/** Path components; a leading "/" is kept as its own component like Rust `Component::RootDir`. */
fun components(path: String): List<String> {
    val out = ArrayList<String>()
    if (path.startsWith("/")) out += "/"
    path.split('/').forEach { if (it.isNotEmpty() && it != ".") out += it }
    return out
}

fun joinComponents(c: List<String>): String = when {
    c.isEmpty() -> ""
    c[0] == "/" -> "/" + c.drop(1).joinToString("/")
    else -> c.joinToString("/")
}

/** Rust `Path::join`: an absolute [rel] replaces [base]. */
fun join(base: String, rel: String): String = when {
    rel.startsWith("/") -> rel
    base.isEmpty() -> rel
    base.endsWith("/") -> base + rel
    else -> "$base/$rel"
}

/** Lexical `base.join(rel)` with `.` and `..` folded (fs-kit `normalize`). */
fun normalize(base: String, rel: String): String {
    val out = ArrayList<String>()
    for (c in components(join(base, rel))) {
        if (c == "..") { if (out.isNotEmpty() && out.last() != "/" && out.last() != "..") out.removeAt(out.size - 1) else if (out.isEmpty()) out += c }
        else out += c
    }
    return joinComponents(out).ifEmpty { "." }
}

/** realpath(3); null when the path does not exist. */
fun canonicalize(path: String): String? = memScoped {
    val buf = allocArray<ByteVar>(PATH_MAX + 1)
    realpath(path, buf)?.toKString()
}

fun cwd(): String = memScoped { val b = allocArray<ByteVar>(PATH_MAX + 1); getcwd(b, (PATH_MAX + 1).convert())!!.toKString() }

/** Canonical when it exists, otherwise lexically normalized against the cwd (fs-kit `canon`). */
fun canon(path: String): String = canonicalize(path) ?: normalize(cwd(), path)

/** Component-wise prefix test (fs-kit `path_starts_with`, Rust `Path::starts_with`). */
fun pathStartsWith(path: String, prefix: String): Boolean {
    val a = components(path); val b = components(prefix)
    return a.size >= b.size && a.subList(0, b.size) == b
}

/** Rust `Path::ancestors`: the path itself, then each parent. */
fun ancestors(path: String): List<String> {
    val c = components(path); return (c.size downTo 1).map { joinComponents(c.subList(0, it)) }
}

/** Rust `Path::strip_prefix`, component-wise; null when not a prefix. */
fun stripPrefix(path: String, prefix: String): String? {
    val a = components(path); val b = components(prefix)
    if (a.size < b.size || a.subList(0, b.size) != b) return null
    return a.drop(b.size).joinToString("/")
}

fun parent(path: String): String? { val c = components(path); return if (c.size <= 1) null else joinComponents(c.dropLast(1)) }

class IoException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { NotFound, PermissionDenied, InvalidInput, Other }
}

/** Whole file as UTF-8. */
fun readToString(path: String): String {
    val f = fopen(path, "rb") ?: throw IoException(if (errno == ENOENT) IoException.Kind.NotFound else IoException.Kind.Other, "$path: errno $errno")
    try {
        val st = memScoped { val s = alloc<stat>(); fstat(fileno(f), s.ptr); s.st_mode.toInt() and S_IFMT }
        if (st == S_IFDIR) throw IoException(IoException.Kind.Other, "$path is a directory")
        val out = okioLikeRead(f); return out.decodeToString()
    } finally { fclose(f) }
}

private fun okioLikeRead(f: CPointer<FILE>): ByteArray {
    val chunks = ArrayList<ByteArray>()
    memScoped { val buf = allocArray<ByteVar>(65536); while (true) { val n = fread(buf, 1u, 65536u, f).toInt(); if (n <= 0) break; chunks += buf.readBytes(n) } }
    val total = chunks.sumOf { it.size }; val out = ByteArray(total); var p = 0
    for (c in chunks) { c.copyInto(out, p); p += c.size }
    return out
}

fun writeString(path: String, text: String) {
    val f = fopen(path, "wb") ?: throw IoException(IoException.Kind.Other, "$path: errno $errno")
    try { val b = text.encodeToByteArray(); if (b.isNotEmpty()) b.usePinned { fwrite(it.addressOf(0), 1u, b.size.convert(), f) } } finally { fclose(f) }
}

fun createDirAll(dir: String) {
    var cur = ""
    for (c in components(dir)) { cur = if (c == "/") "/" else join(cur, c); mkdir(cur, 0x1ed.convert()) }
}

fun exists(path: String) = access(path, F_OK) == 0
fun removeDirAll(path: String) { if (exists(path)) system("rm -rf '" + path.replace("'", "'\\''") + "'") }
fun tempDir(): String = (getenv("TMPDIR")?.toKString() ?: "/tmp").trimEnd('/')
fun pid(): Int = getpid()

/** Entry names of a directory (no `.`/`..`), unsorted like `std::fs::read_dir`. */
fun listDir(path: String): List<String> {
    val d = opendir(path) ?: throw IoException(IoException.Kind.NotFound, "cannot read dir $path")
    val out = ArrayList<String>()
    try {
        while (true) {
            val e = readdir(d) ?: break
            val n = e.pointed.d_name.toKString()
            if (n != "." && n != "..") out += n
        }
    } finally { closedir(d) }
    return out
}
