plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":magrathea-core"))
    implementation(project(":magrathea-provider-api"))
    implementation(project(":magrathea-gateway-server"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.server.netty)
}

application {
    mainClass.set("saien.magrathea.tooling.gateway.MainKt")
}
