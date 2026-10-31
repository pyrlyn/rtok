import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

// `rtok web` / `rtok dashboard` server core (port of src/web). Engine-neutral: HTTP goes through :http-server.
// The Compose UI (:rtok-web-compose, wasmJs) built and embedded as `EMBEDDED_UI` (docs/web.md "Compose UI"); the
// .wasm modules go in as base64 of a zstd frame (decoded by :zstd-native), source maps stay out.
val embeddedUi =
    embedDir(
        "embedWebUi",
        "rtok-web-compose/build/web-dist",
        ":rtok-web-compose:webDist",
        "rtok.web",
        "EMBEDDED_UI",
        "EmbeddedUiFiles",
        exclude = { it.endsWith(".map") },
    )

configure<KotlinMultiplatformExtension> {
    sourceSets.nativeMain {
        kotlin.srcDir(embeddedUi)
        dependencies { implementation(project(":zstd-native")) }
    }
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
