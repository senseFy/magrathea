plugins {
    id("saien.magrathea.kmp-library")
    alias(libs.plugins.jetbrainsKotlinSerialization)
}

kotlin {
    android {
        namespace = "saien.magrathea.provider.anthropic"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":magrathea-core"))
            api(project(":magrathea-provider-api"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
            implementation(project(":magrathea-runtime"))
        }
    }
}
