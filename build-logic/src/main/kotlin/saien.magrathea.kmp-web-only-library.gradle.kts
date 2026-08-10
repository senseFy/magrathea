import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import saien.magrathea.buildlogic.configurePortableKlibSourcePaths

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    `maven-publish`
}

configurePortableKlibSourcePaths()

@OptIn(ExperimentalWasmDsl::class)
kotlin {
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

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
