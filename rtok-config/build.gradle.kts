import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

// default.toml is copied verbatim from crates/rtok-config/default.toml and embedded as a Kotlin
// constant (Kotlin/Native has no classpath resources), like Rust's include_str!.
val genDir = layout.buildDirectory.dir("generated/defaultToml")
val genDefaultToml by tasks.registering {
    val src = file("default.toml"); val out = genDir
    inputs.file(src); outputs.dir(out)
    doLast {
        val text = src.readText()
        val escaped = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\$", "\\\$").replace("\n", "\\n")
        val f = out.get().file("rtok/config/DefaultToml.kt").asFile
        f.parentFile.mkdirs()
        f.writeText("// GENERATED from default.toml — do not edit.\npackage rtok.config\n\nconst val DEFAULT_TOML: String = \"$escaped\"\n")
    }
}
configure<KotlinMultiplatformExtension> {
    sourceSets.commonMain {
        kotlin.srcDir(genDefaultToml.map { genDir.get() })
        dependencies { api(project(":rtok-sys")); api(project(":rtok-hook")) }
    }
}
