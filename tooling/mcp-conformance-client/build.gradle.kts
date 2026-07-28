plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("saien.magrathea.tooling.mcp.conformance.McpConformanceClientKt")
}

dependencies {
    implementation(project(":magrathea-core"))
    implementation(project(":magrathea-mcp"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.okhttp)
    testImplementation(libs.junit)
}
