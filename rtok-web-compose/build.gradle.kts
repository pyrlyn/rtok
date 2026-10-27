import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

// The `rtok web` frontend in Compose Multiplatform (docs/web.md "Compose UI"): wasmJs only, pure logic from
// :rtok-web-model, charts and the graph on Compose Canvas. `webDist` collects the production webpack
// distribution into build/web-dist; :rtok-web embeds it with -Prtok.web.ui=compose until it reaches parity.
plugins {
    id("org.jetbrains.compose")
    kotlin("plugin.compose")
}

configure<KotlinMultiplatformExtension> {
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("app")
        browser {
            commonWebpackConfig { outputFileName = "app.js" }
            testTask { enabled = false }
        }
        binaries.executable()
    }
    sourceSets.getByName("wasmJsMain").dependencies {
        implementation(project(":rtok-web-model"))
        implementation("org.jetbrains.compose.runtime:runtime:1.12.2")
        implementation("org.jetbrains.compose.foundation:foundation:1.12.2")
        implementation("org.jetbrains.compose.ui:ui:1.12.2")
    }
}

// The built UI: index.html and the webpack output (app.js, the .wasm files, skiko) in one directory.
tasks.register<Sync>("webDist") {
    from(tasks.named("wasmJsBrowserDistribution"))
    into(layout.buildDirectory.dir("web-dist"))
}
