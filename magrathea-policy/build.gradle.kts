plugins {
    id("saien.magrathea.kmp-web-library")
    alias(libs.plugins.jetbrainsKotlinSerialization)
}

kotlin {
    android {
        namespace = "saien.magrathea.policy"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":magrathea-core"))
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
