// Adapter: androidx.sqlite (Room KMP's driver layer, bundled SQLite) behind rtok.db.
configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
    sourceSets.commonMain.dependencies { api(project(":db")); implementation("androidx.sqlite:sqlite-bundled:2.7.1") }
}
