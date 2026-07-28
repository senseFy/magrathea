pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
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

rootProject.name = "magrathea-jvm-chat-sample"
