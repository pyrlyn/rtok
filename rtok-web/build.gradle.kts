import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

// `rtok web` / `rtok dashboard` server core (port of src/web). Engine-neutral: HTTP goes through :http-server.
configure<KotlinMultiplatformExtension> {
    sourceSets.commonMain {
        dependencies {
            api(project(":rtok-sys"))
            api(project(":http-server"))
            // The version :http already pins (Ktor's own), so no second coroutines line enters the build.
            // api: `CallsFeed` hands a StateFlow to the caller.
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
    }
}
