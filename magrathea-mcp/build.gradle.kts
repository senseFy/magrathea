plugins {
    id("saien.magrathea.kmp-web-library")
    alias(libs.plugins.jetbrainsKotlinSerialization)
}

kotlin {
    android {
        namespace = "saien.magrathea.mcp"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":magrathea-core"))
            api(libs.kotlinx.coroutines.core)
            api(libs.mcp.kotlin.sdk.client)
            api(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        jvmTest.dependencies {
            implementation(libs.mcp.kotlin.sdk.server)
            implementation(libs.mcp.kotlin.sdk.testing)
        }
    }
}
