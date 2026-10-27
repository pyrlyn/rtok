import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

// `rtok web` / `rtok dashboard` server core (port of src/web). Engine-neutral: HTTP goes through :http-server.
configure<KotlinMultiplatformExtension> {
    sourceSets.commonMain {
        dependencies {
            api(project(":rtok-sys"))
        }
    }
}
