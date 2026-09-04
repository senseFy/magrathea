import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension
import org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnRootExtension

plugins {
    kotlin("multiplatform") version "2.4.0"
}

val secureWebpackVersion = "5.104.1"
val secureWsVersion = "8.21.0"

YarnRootExtension[rootProject].apply {
    resolution("ws", secureWsVersion)
}
WasmYarnRootExtension[rootProject].apply {
    resolution("ws", secureWsVersion)
}
rootProject.extensions.getByType<NodeJsRootExtension>().versions.webpack.version = secureWebpackVersion

val magratheaVersion = providers.gradleProperty("magrathea.version").orElse("0.1.0-alpha.9")
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
