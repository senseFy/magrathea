plugins {
    id("saien.magrathea.kmp-web-only-library")
    alias(libs.plugins.jetbrainsKotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":magrathea-core"))
            api(project(":magrathea-session-catalog"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
