// Adapter: Ktor server (CIO engine, every native target) behind rtok.http.server.
val ktor = "3.6.0"
configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
    sourceSets.commonMain.dependencies {
        api(project(":http-server"))
        implementation("io.ktor:ktor-server-core:$ktor")
        implementation("io.ktor:ktor-server-cio:$ktor")
        implementation("io.ktor:ktor-server-websockets:$ktor")
    }
    sourceSets.commonTest.dependencies {
        implementation(project(":http-ktor"))
        implementation("io.ktor:ktor-client-cio:$ktor")
        implementation("io.ktor:ktor-client-websockets:$ktor")
    }
}
