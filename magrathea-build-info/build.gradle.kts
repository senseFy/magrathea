plugins {
    id("saien.magrathea.kmp-web-library")
}

val generatedBuildInfoDirectory = layout.buildDirectory.dir("generated/buildInfo/commonMain/kotlin")
val generateBuildInfo = tasks.register("generateBuildInfo") {
    val sdkVersion = project.version.toString()
    inputs.property("sdkVersion", sdkVersion)
    outputs.dir(generatedBuildInfoDirectory)

    doLast {
        val packageDirectory = generatedBuildInfoDirectory.get()
            .dir("saien/magrathea/buildinfo")
            .asFile
        packageDirectory.mkdirs()
        packageDirectory.resolve("MagratheaSdkVersion.kt").writeText(
            """
            package saien.magrathea.buildinfo

            internal const val MAGRATHEA_SDK_VERSION: String = "$sdkVersion"
            """.trimIndent() + "\n",
        )
    }
}

kotlin {
    android {
        namespace = "saien.magrathea.buildinfo"
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateBuildInfo)
        }
    }
}
