plugins {
    id("saien.magrathea.kmp-web-library")
}

kotlin {
    android {
        namespace = "saien.magrathea.catalog"
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":magrathea-core"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
