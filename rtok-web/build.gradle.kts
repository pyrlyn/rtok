import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

// `rtok web` / `rtok dashboard` server core (port of src/web). Engine-neutral: HTTP goes through :http-server.
// The Kotlin/JS UI (:rtok-web-ui) built and embedded as `EMBEDDED_UI` (docs/web.md step 6).
val embeddedUi = embedDir("embedWebUi", "rtok-web-ui/build/web-dist", ":rtok-web-ui:webDist", "rtok.web", "EMBEDDED_UI", "EmbeddedUiFiles")

configure<KotlinMultiplatformExtension> {
    sourceSets.nativeMain { kotlin.srcDir(embeddedUi) }
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
