import org.gradle.api.tasks.Copy

plugins {
    id("saien.magrathea.kmp-web-library")
    alias(libs.plugins.jetbrainsKotlinSerialization)
}

val generatedCoreBuildInfoDirectory = layout.buildDirectory.dir("generated/coreBuildInfo/commonMain/kotlin")
val generateCoreBuildInfo = tasks.register("generateCoreBuildInfo") {
    val sdkVersion = project.version.toString()
    inputs.property("sdkVersion", sdkVersion)
    outputs.dir(generatedCoreBuildInfoDirectory)

    doLast {
        val packageDirectory = generatedCoreBuildInfoDirectory.get()
            .dir("saien/magrathea/core")
            .asFile
        packageDirectory.mkdirs()
        packageDirectory.resolve("MagratheaCoreBuildInfo.kt").writeText(
            """
            package saien.magrathea.core

            internal const val MAGRATHEA_CORE_SDK_VERSION: String = "$sdkVersion"
            """.trimIndent() + "\n"
        )
    }
}

kotlin {
    android {
        namespace = "saien.magrathea.core"
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateCoreBuildInfo)
            dependencies {
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.serialization.json)
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
        }
    }
}

tasks.named<Copy>("jvmTestProcessResources") {
    from(rootProject.file("serialization-fixtures"))
}
