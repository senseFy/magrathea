pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Kotlin Web tooling registers its pinned Node/Yarn distribution repositories on the project.
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        maven {
            url = uri(
                providers.gradleProperty("magrathea.repository")
                    .orElse(file("../../build/sdk-verification-repository").absolutePath)
                    .get(),
            )
            content { includeGroup("saien.magrathea") }
        }
        mavenCentral()
    }
}

rootProject.name = "magrathea-web-chat-sample"
