plugins {
    id("com.android.application") version "9.1.1"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0"
}

val magratheaVersion = providers.gradleProperty("magrathea.version").orElse("0.1.0-alpha.6")
providers.gradleProperty("magrathea.consumer.buildDir").orNull?.let { consumerBuildDirectory ->
    layout.buildDirectory.set(file(consumerBuildDirectory))
}

configurations.configureEach {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}

android {
    namespace = "saien.magrathea.samples.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "saien.magrathea.samples.android"
        testApplicationId = "saien.magrathea.samples.android.device.test"
        testInstrumentationRunner = "saien.magrathea.samples.android.MagratheaDeviceTestInstrumentation"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    sourceSets.getByName("main").kotlin.directories.add(
        "../../samples/android-chat/src/main/kotlin",
    )
}

dependencies {
    implementation("saien.magrathea:magrathea-chatbot:${magratheaVersion.get()}") { isChanging = true }
    implementation("saien.magrathea:magrathea-core:${magratheaVersion.get()}") { isChanging = true }
    implementation("saien.magrathea:magrathea-provider-api:${magratheaVersion.get()}") { isChanging = true }
    implementation("saien.magrathea:magrathea-credentials:${magratheaVersion.get()}") { isChanging = true }
    implementation("saien.magrathea:magrathea-provider-gemini:${magratheaVersion.get()}") { isChanging = true }
    implementation("saien.magrathea:magrathea-runtime:${magratheaVersion.get()}") { isChanging = true }
    implementation("saien.magrathea:magrathea-storage-room:${magratheaVersion.get()}") { isChanging = true }
    testImplementation("junit:junit:4.13.2")
}
