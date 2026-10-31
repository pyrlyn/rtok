import com.github.luben.zstd.Zstd
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64

private const val CHUNK = 8192

// Highest level without --ultra: the wasm modules are encoded once per build and decoded once per run.
private const val ZSTD_LEVEL = 19

private fun literal(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\$", "\\\$")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

/** [bytes] as text when they are valid UTF-8 without NUL, else null (the file is embedded as zstd + base64). */
fun textOf(bytes: ByteArray): String? {
    if (bytes.any { it == 0.toByte() }) return null
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    return try { decoder.decode(ByteBuffer.wrap(bytes)).toString() } catch (_: CharacterCodingException) { null }
}

/**
 * Register [taskName], which embeds every file under [dir] (built by [builtBy]) as
 * `internal val [valName]: Map<String, Triple<List<String>, String, Boolean>>` in `[pkg]/[fileName].kt`: relative
 * name to (content in chunks, quoted ETag, zstd). Text files are embedded as is; binary ones (not UTF-8, or with
 * NUL, e.g. `.wasm`) as base64 chunks of a zstd frame the runtime decodes (a base64 string of the raw bytes would be
 * 3× the size of the compressed one in the binary). The ETag hashes the original file. Files matching [exclude] (e.g. source maps) are skipped. Kotlin/Native has no resources, so this is the twin of Rust's include_dir!.
 * Returns the generated source root, wired to the task, for `kotlin.srcDir(...)`.
 */
fun Project.embedDir(
    taskName: String,
    dir: String,
    builtBy: String,
    pkg: String,
    valName: String,
    fileName: String,
    exclude: (String) -> Boolean = { false },
): Provider<Directory> {
    val genDir = layout.buildDirectory.dir("generated/$taskName")
    val source = rootProject.file(dir)
    val task = tasks.register(taskName) {
        dependsOn(builtBy)
        inputs.dir(source); outputs.dir(genDir)
        doLast {
            val files = source.walkTopDown().filter { it.isFile && !exclude(it.relativeTo(source).invariantSeparatorsPath) }.sortedBy { it.relativeTo(source).invariantSeparatorsPath }
            val entries = files.joinToString(",\n") { f ->
                val bytes = f.readBytes()
                val text = textOf(bytes)
                val body = text ?: Base64.getEncoder().encodeToString(Zstd.compress(bytes, ZSTD_LEVEL))
                val etag = contentHash(f, 8)
                val chunks = body.chunked(CHUNK).ifEmpty { listOf("") }.joinToString(",\n        ") { literal(it) }
                "    ${literal(f.relativeTo(source).invariantSeparatorsPath)} to Triple(\n        listOf(\n        $chunks,\n        ),\n        ${literal("\"$etag\"")},\n        ${text == null},\n    )"
            }
            val out = genDir.get().file("${pkg.replace('.', '/')}/$fileName.kt").asFile
            out.parentFile.deleteRecursively(); out.parentFile.mkdirs()
            out.writeText(
                "// GENERATED from $dir — do not edit.\npackage $pkg\n\n" +
                    "internal val $valName: Map<String, Triple<List<String>, String, Boolean>> =\n    mapOf(\n$entries,\n    )\n",
            )
        }
    }
    return task.map { genDir.get() }
}

/** The first [bytes] bytes of the SHA-256 of [file], in hex: a content hash for cache-busting names and ETags. */
fun contentHash(file: java.io.File, bytes: Int): String =
    MessageDigest.getInstance("SHA-256").digest(file.readBytes()).take(bytes).joinToString("") { "%02x".format(it) }
