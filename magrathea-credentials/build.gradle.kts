plugins {
    id("saien.magrathea.kmp-library")
    alias(libs.plugins.jetbrainsKotlinSerialization)
}

kotlin {
    android {
        namespace = "saien.magrathea.credentials"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":magrathea-core"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
