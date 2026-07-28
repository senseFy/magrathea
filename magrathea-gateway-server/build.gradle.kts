plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.jetbrainsKotlinSerialization)
    `maven-publish`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":magrathea-gateway-protocol"))
    api(project(":magrathea-provider-api"))
    implementation(project(":magrathea-core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
}

tasks.test {
    useJUnitPlatform()
}
