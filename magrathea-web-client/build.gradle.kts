plugins {
    id("saien.magrathea.kmp-web-only-library")
}

kotlin {
    js {
        outputModuleName = "magrathea-web-client"
        binaries.executable()
        generateTypeScriptDefinitions()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":magrathea-chatbot"))
            api(project(":magrathea-provider-gateway"))
            api(project(":magrathea-storage-web"))
            implementation(project(":magrathea-core"))
            implementation(project(":magrathea-provider-api"))
            implementation(project(":magrathea-runtime"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(project(":magrathea-gateway-protocol"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
