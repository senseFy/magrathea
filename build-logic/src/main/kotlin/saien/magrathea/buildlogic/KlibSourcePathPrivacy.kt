package saien.magrathea.buildlogic

import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.tasks.Kotlin2JsCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile

/**
 * Stores project-relative source paths in publishable Kotlin libraries.
 *
 * Kotlin serializes source paths into KLIB IR for debug information and uses absolute paths by
 * default. The repository root covers both checked-in sources and Gradle-generated sources. A
 * source outside this root remains absolute intentionally so that the distribution verifier can
 * reject it instead of silently publishing a machine-specific path.
 */
internal fun Project.configurePortableKlibSourcePaths() {
    val repositoryRoot = rootProject.layout.projectDirectory.asFile.absolutePath
    val relativePathBaseArgument = "-Xklib-relative-path-base=$repositoryRoot"

    tasks.withType(Kotlin2JsCompile::class.java).configureEach {
        compilerOptions.freeCompilerArgs.add(relativePathBaseArgument)
    }
    tasks.withType(KotlinNativeCompile::class.java).configureEach {
        compilerOptions.freeCompilerArgs.add(relativePathBaseArgument)
    }
}
