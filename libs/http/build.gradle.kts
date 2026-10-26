// rtok's own HTTP interface: the only HTTP surface the rest of the code sees (adapter in :http-ktor).
configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
    // `suspend` API; the version Ktor 3.3.3 itself requires, so no second coroutines line enters the build.
    sourceSets.commonMain.dependencies { api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0") }
}
