import org.jetbrains.kotlin.gradle.tasks.Kotlin2JsCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile

plugins {
    id("saien.magrathea.kmp-web-library")
}

val buildInfoModuleName = "saien.magrathea:magrathea-build-info"

kotlin {
    android {
        namespace = "saien.magrathea.buildinfo.testfixture"
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(rootProject.file("magrathea-build-info/src/commonMain/kotlin"))
        }
    }
}

tasks.withType<KotlinNativeCompile>().configureEach {
    compilerOptions.moduleName.set(buildInfoModuleName)
}
tasks.withType<Kotlin2JsCompile>().configureEach {
    compilerOptions.moduleName.set(buildInfoModuleName)
}
