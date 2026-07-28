pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            url = uri(providers.gradleProperty("magrathea.repository").get())
            content { includeGroup("saien.magrathea") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "magrathea-published-android-consumer"
