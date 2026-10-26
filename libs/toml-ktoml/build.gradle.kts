configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
    sourceSets.commonMain.dependencies {
        api(project(":toml"))
        implementation("com.akuleshov7:ktoml-core:0.7.1")
    }
}
