import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

// migrations/<version>/up.sql are copied verbatim from crates/rtok-store/migrations and embedded
// (like diesel's embed_migrations!), one constant per migration, sorted by version.
val genDir = layout.buildDirectory.dir("generated/migrations")
val genMigrations by tasks.registering {
    val src = layout.projectDirectory.dir("migrations")
    val out = genDir
    inputs.dir(src)
    outputs.dir(out)
    doLast {
        val dirs =
            src.asFile
                .listFiles()!!
                .filter { it.isDirectory }
                .sortedBy { it.name }

        fun esc(t: String) =
            t
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\$", "\\\$")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
        val sb = StringBuilder("// GENERATED from migrations/ — do not edit.\npackage rtok.store\n\n")
        dirs.forEachIndexed { i, d -> sb.append("private const val M$i: String = \"${esc(File(d, "up.sql").readText())}\"\n") }
        sb.append("\ninternal val EMBEDDED_MIGRATIONS: List<Pair<String, String>> = listOf(\n")
        dirs.forEachIndexed { i, d -> sb.append("    \"${d.name}\" to M$i,\n") }
        sb.append(")\n")
        val f = out.get().file("rtok/store/Migrations.gen.kt").asFile
        f.parentFile.mkdirs()
        f.writeText(sb.toString())
    }
}
configure<KotlinMultiplatformExtension> {
    sourceSets.commonMain {
        kotlin.srcDir(genMigrations.map { genDir.get() })
        dependencies {
            api("pyrlyn.kit:db")
            api(project(":rtok-plugin-sdk"))
            api(project(":rtok-sys"))
            implementation(project(":humantime"))
        }
    }
}
// schema.rs + schema_snapshot.txt copied from the Rust crate: the drift test checks the Kotlin-migrated
// database against the same golden files, so both builds keep one on-disk schema.
// Tests run on the same adapter the app installs; store code itself sees only rtok.db.
configure<KotlinMultiplatformExtension> { sourceSets.commonTest.dependencies { implementation("pyrlyn.kit:db-room") } }
val storeFixtures =
    layout.projectDirectory
        .dir("src/nativeTest/fixtures")
        .asFile.absolutePath
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach { environment("RTOK_FIXTURES", storeFixtures) }
