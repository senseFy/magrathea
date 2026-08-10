import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform") version "2.4.0"
}

val magratheaVersion = providers.gradleProperty("magrathea.version").orElse("0.1.0-alpha.3")
providers.gradleProperty("magrathea.consumer.buildDir").orNull?.let { consumerBuildDirectory ->
    layout.buildDirectory.set(file(consumerBuildDirectory))
}

configurations.configureEach {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}

@OptIn(ExperimentalWasmDsl::class)
kotlin {
    js {
        browser()
        binaries.executable()
    }
    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation("saien.magrathea:magrathea-web-client:${magratheaVersion.get()}") { isChanging = true }
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
        }
    }
}
