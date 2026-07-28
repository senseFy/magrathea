plugins {
    id("saien.magrathea.kmp-web-library")
    alias(libs.plugins.jetbrainsKotlinSerialization)
}

kotlin {
    android {
        namespace = "saien.magrathea.provider.gateway"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":magrathea-provider-api"))
            api(project(":magrathea-gateway-protocol"))
            implementation(project(":magrathea-core"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
