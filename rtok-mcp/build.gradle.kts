import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

// crates/rtok-mcp: the per-host MCP install/status core (spec, config read/write behind Fs, apply).
configure<KotlinMultiplatformExtension> {
    sourceSets.commonMain.dependencies {
        api(project(":rtok-sys"))
        api(project(":rtok-agent-sdk"))
        implementation(project(":rtok-config"))
    }
}
