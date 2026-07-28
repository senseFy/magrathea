plugins {
    id("saien.magrathea.kmp-web-library")
    alias(libs.plugins.jetbrainsKotlinSerialization)
}

val generatedMcpBuildInfoDirectory = layout.buildDirectory.dir("generated/mcpBuildInfo/commonMain/kotlin")
val generateMcpBuildInfo = tasks.register("generateMcpBuildInfo") {
    val sdkVersion = project.version.toString()
    inputs.property("sdkVersion", sdkVersion)
    outputs.dir(generatedMcpBuildInfoDirectory)

    doLast {
        val packageDirectory = generatedMcpBuildInfoDirectory.get()
            .dir("saien/magrathea/mcp")
            .asFile
        packageDirectory.mkdirs()
        packageDirectory.resolve("MagratheaMcpBuildInfo.kt").writeText(
            """
            package saien.magrathea.mcp

            internal const val MAGRATHEA_MCP_SDK_VERSION: String = "$sdkVersion"
            """.trimIndent() + "\n",
        )
    }
}

kotlin {
    android {
        namespace = "saien.magrathea.mcp"
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateMcpBuildInfo)
        }
        commonMain.dependencies {
            api(project(":magrathea-core"))
            api(libs.kotlinx.coroutines.core)
            api(libs.mcp.kotlin.sdk.client)
            api(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        jvmTest.dependencies {
            implementation(libs.mcp.kotlin.sdk.server)
            implementation(libs.mcp.kotlin.sdk.testing)
        }
    }
}
