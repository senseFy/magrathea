plugins {
    id("saien.magrathea.kmp-web-library")
    alias(libs.plugins.jetbrainsKotlinSerialization)
}

kotlin {
    android {
        namespace = "saien.magrathea.gateway.protocol"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":magrathea-core"))
            api(project(":magrathea-provider-api"))
            api(libs.kotlinx.serialization.json)
        }
    }
}
