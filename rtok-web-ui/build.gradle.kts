import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

// The `rtok web` frontend in Kotlin/JS (port of web/src, docs/web.md step 6): kotlinx.html for the DOM,
// kotlin-css for styles, our own SVG charts. No npm bundler: the production executable is one
// whole-program ES file, which :rtok-web embeds together with jsMain/resources (`webDist`).
configure<KotlinMultiplatformExtension> {
    js {
        browser { testTask { enabled = false } }
        binaries.executable()
        compilerOptions { freeCompilerArgs.add("-Xir-per-module=false") }
    }
    sourceSets.jsMain {
        dependencies {
            implementation(project(":rtok-web-model"))
            implementation("org.jetbrains.kotlinx:kotlinx-html:0.12.0")
            implementation("org.jetbrains.kotlin-wrappers:kotlin-css:2026.10.5")
        }
    }
}

// The built UI: index.html, theme-init.js and the compiled bundle, in one directory.
tasks.register<Sync>("webDist") {
    from(tasks.named("jsProcessResources"))
    from(tasks.named("compileProductionExecutableKotlinJs").map { it.outputs.files }) {
        include("*.js")
        into("assets")
    }
    into(layout.buildDirectory.dir("web-dist"))
    // `assets/` is served as immutable, so the bundle gets a content hash in its name and index.html follows it.
    doLast {
        val root = destinationDir
        val bundle =
            root
                .resolve("assets")
                .listFiles()
                .orEmpty()
                .single { it.name.endsWith(".js") }
        val hash = contentHash(bundle, 4)
        val named = "app-$hash.js"
        bundle.renameTo(root.resolve("assets/$named"))
        val index = root.resolve("index.html")
        index.writeText(index.readText().replace("/assets/app.js", "/assets/$named"))
    }
}
