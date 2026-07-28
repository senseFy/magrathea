pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            val packageUsername = providers.environmentVariable("GITHUB_PACKAGES_USERNAME").orNull
            val packageToken = providers.environmentVariable("GITHUB_PACKAGES_TOKEN").orNull
            url = uri(
                providers.gradleProperty("magrathea.repository")
                    .orElse(file("../../build/sdk-verification-repository").absolutePath)
                    .get()
            )
            content {
                includeGroup("saien.magrathea")
            }
            if (!packageUsername.isNullOrBlank() && !packageToken.isNullOrBlank()) {
                credentials {
                    username = packageUsername
                    password = packageToken
                }
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "magrathea-kmp-published-consumer"
