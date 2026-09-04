plugins {
    id("saien.magrathea.kmp-web-library")
}

kotlin {
    android {
        namespace = "saien.magrathea.chatbot"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":magrathea-core"))
            api(project(":magrathea-runtime"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(project(":magrathea-runtime"))
            implementation(project(":magrathea-provider-api"))
            implementation(project(":magrathea-policy"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(project(":magrathea-provider-gemini"))
            implementation(project(":magrathea-storage-room"))
        }
        iosTest.dependencies {
            implementation(project(":magrathea-provider-gemini"))
            implementation(project(":magrathea-storage-room"))
            implementation(project(":magrathea-credentials"))
        }
    }
}
