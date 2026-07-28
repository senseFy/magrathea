pluginManagement {
    includeBuild("build-logic")

    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    // Kotlin's JS/Wasm toolchain adds audited Node/Yarn distribution repositories at the
    // root project, so project-level repositories must remain enabled for Web builds.
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "magrathea"

include(":magrathea-core")
include(":magrathea-provider-api")
include(":magrathea-provider-openai")
include(":magrathea-provider-gemini")
include(":magrathea-provider-anthropic")
include(":magrathea-runtime")
include(":magrathea-mcp")
include(":magrathea-storage-room")
include(":magrathea-credentials")
include(":magrathea-policy")
include(":magrathea-gateway-protocol")
include(":magrathea-gateway-server")
include(":magrathea-provider-gateway")
include(":magrathea-chatbot")
include(":magrathea-storage-web")
include(":magrathea-web-client")
include(":tooling-gateway-e2e-server")
project(":tooling-gateway-e2e-server").projectDir = file("tooling/gateway-e2e-server")
include(":provider-live-harness")
project(":provider-live-harness").projectDir = file("tooling/provider-live-harness")
include(":mcp-conformance-client")
project(":mcp-conformance-client").projectDir = file("tooling/mcp-conformance-client")
