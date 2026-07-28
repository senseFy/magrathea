plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.jetbrainsKotlinSerialization)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("saien.magrathea.tooling.provider.live.ProviderLiveHarnessKt")
}

dependencies {
    implementation(project(":magrathea-chatbot"))
    implementation(project(":magrathea-core"))
    implementation(project(":magrathea-provider-api"))
    implementation(project(":magrathea-provider-openai"))
    implementation(project(":magrathea-provider-gemini"))
    implementation(project(":magrathea-provider-anthropic"))
    implementation(project(":magrathea-runtime"))
    implementation(project(":magrathea-storage-room"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
