// TOML reading abstraction; the only TOML API config code may depend on. No third-party deps.
configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> { sourceSets.commonMain.dependencies { api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0") } }
