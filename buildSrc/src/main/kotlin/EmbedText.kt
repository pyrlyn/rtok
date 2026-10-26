import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider

/**
 * Register [taskName], which embeds [src] verbatim as `const val [constName]: String` in
 * `[pkg]/[fileName].kt` — the Kotlin twin of `include_str!`. Returns the generated source root,
 * wired to the task, for `kotlin.srcDir(...)`.
 */
fun Project.embedText(taskName: String, src: String, pkg: String, constName: String, fileName: String): Provider<Directory> {
    val genDir = layout.buildDirectory.dir("generated/$taskName")
    val source = file(src)
    val task = tasks.register(taskName) {
        inputs.file(source); outputs.dir(genDir)
        doLast {
            val escaped = source.readText().replace("\\", "\\\\").replace("\"", "\\\"").replace("\$", "\\\$").replace("\n", "\\n")
            val f = genDir.get().file("${pkg.replace('.', '/')}/$fileName.kt").asFile
            f.parentFile.mkdirs()
            f.writeText("// GENERATED from ${source.name} — do not edit.\npackage $pkg\n\nconst val $constName: String = \"$escaped\"\n")
        }
    }
    return task.map { genDir.get() }
}
