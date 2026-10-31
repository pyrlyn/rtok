import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

// default.toml is copied verbatim from crates/rtok-config/default.toml and embedded (buildSrc `embedText`).
val defaultToml = embedText("genDefaultToml", "default.toml", "rtok.config", "DEFAULT_TOML", "DefaultToml")
configure<KotlinMultiplatformExtension> {
    sourceSets.commonMain {
        kotlin.srcDir(defaultToml)
        dependencies {
            api(project(":rtok-sys"))
            api(project(":rtok-hook"))
            api("pyrlyn.kit:toml")
            implementation(project(":toml-ktoml"))
        }
    }
}
