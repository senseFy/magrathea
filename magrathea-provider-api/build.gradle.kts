import org.gradle.api.tasks.Copy

plugins {
    id("saien.magrathea.kmp-web-library")
    alias(libs.plugins.jetbrainsKotlinSerialization)
}

kotlin {
    android {
        namespace = "saien.magrathea.provider.api"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":magrathea-core"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        jsMain.dependencies {
            implementation(libs.ktor.client.js)
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
        }
        jvmTest {
            kotlin.srcDir("src/test/kotlin")
            dependencies {
                implementation(libs.junit)
            }
        }
    }
}

tasks.named<Copy>("jvmTestProcessResources") {
    from(rootProject.file("serialization-fixtures"))
}
