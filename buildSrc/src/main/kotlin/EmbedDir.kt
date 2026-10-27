import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import java.security.MessageDigest

private const val CHUNK = 8192

private fun literal(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\$", "\\\$")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

/**
 * Register [taskName], which embeds every text file under [dir] (built by [builtBy]) as
 * `internal val [valName]: Map<String, Pair<List<String>, String>>` in `[pkg]/[fileName].kt`: relative name to
 * (content in chunks, quoted ETag). Kotlin/Native has no resources, so this is the twin of Rust's include_dir!.
 * Returns the generated source root, wired to the task, for `kotlin.srcDir(...)`.
 */
fun Project.embedDir(
    taskName: String,
    dir: String,
    builtBy: String,
    pkg: String,
    valName: String,
    fileName: String,
): Provider<Directory> {
    val genDir = layout.buildDirectory.dir("generated/$taskName")
    val source = rootProject.file(dir)
    val task = tasks.register(taskName) {
        dependsOn(builtBy)
        inputs.dir(source); outputs.dir(genDir)
        doLast {
            val files = source.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(source).invariantSeparatorsPath }
            val entries = files.joinToString(",\n") { f ->
                val text = f.readText()
                val etag = contentHash(f, 8)
                val chunks = text.chunked(CHUNK).joinToString(",\n        ") { literal(it) }
                "    ${literal(f.relativeTo(source).invariantSeparatorsPath)} to (\n        listOf(\n        $chunks,\n        ) to ${literal("\"$etag\"")}\n    )"
            }
            val out = genDir.get().file("${pkg.replace('.', '/')}/$fileName.kt").asFile
            out.parentFile.deleteRecursively(); out.parentFile.mkdirs()
            out.writeText(
                "// GENERATED from $dir — do not edit.\npackage $pkg\n\n" +
                    "internal val $valName: Map<String, Pair<List<String>, String>> =\n    mapOf(\n$entries,\n    )\n",
            )
        }
    }
    return task.map { genDir.get() }
}

/** The first [bytes] bytes of the SHA-256 of [file], in hex: a content hash for cache-busting names and ETags. */
fun contentHash(file: java.io.File, bytes: Int): String =
    MessageDigest.getInstance("SHA-256").digest(file.readBytes()).take(bytes).joinToString("") { "%02x".format(it) }
