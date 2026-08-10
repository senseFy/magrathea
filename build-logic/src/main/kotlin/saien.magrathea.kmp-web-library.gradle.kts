import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import saien.magrathea.buildlogic.configurePortableKlibSourcePaths

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    `maven-publish`
}

configurePortableKlibSourcePaths()

@OptIn(ExperimentalWasmDsl::class)
kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        compileSdk = 35
        minSdk = 24
        withHostTestBuilder { }.configure { }
    }
    jvm()
    js {
        browser {
            testTask {
                useKarma {
                    useChromeHeadless()
                    useConfigDirectory(rootProject.file("karma.config.d"))
                }
            }
        }
    }
    wasmJs {
        browser {
            testTask {
                useKarma {
                    useChromeHeadless()
                    useConfigDirectory(rootProject.file("karma.config.d"))
                }
            }
        }
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
