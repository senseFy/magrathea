plugins {
    id("org.jetbrains.kotlin.multiplatform") version "2.4.0"
    id("com.android.kotlin.multiplatform.library") version "9.1.1"
}

val magratheaVersion = providers.gradleProperty("magrathea.version").orElse("0.1.0-alpha.6")
val appleFrameworkBaseName = "MagratheaPublishedConsumer"
providers.gradleProperty("magrathea.consumer.buildDir").orNull?.let { consumerBuildDirectory ->
    layout.buildDirectory.set(file(consumerBuildDirectory))
}

configurations.configureEach {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}

kotlin {
    android {
        namespace = "saien.magrathea.tooling.consumer"
        compileSdk = 35
        minSdk = 24
    }
    jvm()
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = appleFrameworkBaseName
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("saien.magrathea:magrathea-core:${magratheaVersion.get()}") { isChanging = true }
            implementation("saien.magrathea:magrathea-provider-api:${magratheaVersion.get()}") { isChanging = true }
            implementation("saien.magrathea:magrathea-provider-openai:${magratheaVersion.get()}") { isChanging = true }
            implementation("saien.magrathea:magrathea-provider-gemini:${magratheaVersion.get()}") { isChanging = true }
            implementation("saien.magrathea:magrathea-provider-anthropic:${magratheaVersion.get()}") { isChanging = true }
            implementation("saien.magrathea:magrathea-runtime:${magratheaVersion.get()}") { isChanging = true }
            implementation("saien.magrathea:magrathea-mcp:${magratheaVersion.get()}") { isChanging = true }
            implementation("saien.magrathea:magrathea-policy:${magratheaVersion.get()}") { isChanging = true }
            implementation("saien.magrathea:magrathea-storage-room:${magratheaVersion.get()}") { isChanging = true }
            implementation("saien.magrathea:magrathea-credentials:${magratheaVersion.get()}") { isChanging = true }
            implementation("saien.magrathea:magrathea-gateway-protocol:${magratheaVersion.get()}") { isChanging = true }
            implementation("saien.magrathea:magrathea-provider-gateway:${magratheaVersion.get()}") { isChanging = true }
            implementation("saien.magrathea:magrathea-chatbot:${magratheaVersion.get()}") { isChanging = true }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
