import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.GradleBuild
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.jvm.tasks.Jar
import org.gradle.api.tasks.bundling.Zip
import org.gradle.plugins.signing.SigningExtension
import org.cyclonedx.Version
import org.cyclonedx.gradle.CyclonedxAggregateTask
import org.cyclonedx.gradle.CyclonedxDirectTask
import org.cyclonedx.model.License
import org.cyclonedx.model.LicenseChoice
import org.cyclonedx.parsers.JsonParser

plugins {
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
    alias(libs.plugins.binaryCompatibilityValidator)
    alias(libs.plugins.cyclonedxBom)
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.jetbrainsKotlinSerialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.androidxRoom) apply false
    `maven-publish`
}

apiValidation {
    ignoredProjects.addAll(
        listOf(
            "provider-live-harness",
            "tooling-gateway-e2e-server",
            "mcp-conformance-client",
        ),
    )
}

val sdkPublishProjects = listOf(
    ":magrathea-core",
    ":magrathea-provider-api",
    ":magrathea-provider-openai",
    ":magrathea-provider-gemini",
    ":magrathea-provider-anthropic",
    ":magrathea-runtime",
    ":magrathea-mcp",
    ":magrathea-storage-room",
    ":magrathea-credentials",
    ":magrathea-policy",
    ":magrathea-gateway-protocol",
    ":magrathea-gateway-server",
    ":magrathea-provider-gateway",
    ":magrathea-chatbot",
    ":magrathea-storage-web",
    ":magrathea-web-client",
)

val sdkKmpMobilePublishProjects = setOf(
    ":magrathea-core",
    ":magrathea-provider-api",
    ":magrathea-provider-openai",
    ":magrathea-provider-gemini",
    ":magrathea-provider-anthropic",
    ":magrathea-runtime",
    ":magrathea-mcp",
    ":magrathea-policy",
    ":magrathea-storage-room",
    ":magrathea-credentials",
    ":magrathea-gateway-protocol",
    ":magrathea-provider-gateway",
    ":magrathea-chatbot",
)
val sdkKmpWebOnlyPublishProjects = setOf(
    ":magrathea-storage-web",
    ":magrathea-web-client",
)
val sdkKmpPublishProjects = sdkKmpMobilePublishProjects + sdkKmpWebOnlyPublishProjects
val sdkKmpWebPublishProjects = setOf(
    ":magrathea-core",
    ":magrathea-provider-api",
    ":magrathea-runtime",
    ":magrathea-mcp",
    ":magrathea-policy",
    ":magrathea-gateway-protocol",
    ":magrathea-provider-gateway",
    ":magrathea-chatbot",
) + sdkKmpWebOnlyPublishProjects
val kmpMobilePublications = linkedMapOf(
    "kotlinMultiplatform" to ("KotlinMultiplatform" to ""),
    "android" to ("Android" to "-android"),
    "jvm" to ("Jvm" to "-jvm"),
    "iosArm64" to ("IosArm64" to "-iosarm64"),
    "iosSimulatorArm64" to ("IosSimulatorArm64" to "-iossimulatorarm64"),
)
val kmpWebPublications = linkedMapOf(
    "js" to ("Js" to "-js"),
    "wasmJs" to ("WasmJs" to "-wasm-js"),
)
val kmpPublicationsByProject = sdkKmpPublishProjects.associateWith { projectPath ->
    when {
        projectPath in sdkKmpWebOnlyPublishProjects -> linkedMapOf(
            "kotlinMultiplatform" to ("KotlinMultiplatform" to ""),
        ) + kmpWebPublications
        projectPath in sdkKmpWebPublishProjects -> kmpMobilePublications + kmpWebPublications
        else -> kmpMobilePublications
    }
}
val sdkJvmPublishProjects = sdkPublishProjects.filterNot { it in sdkKmpPublishProjects }
val sdkTestTasks = sdkPublishProjects.flatMap { projectPath ->
    when {
        projectPath in sdkKmpWebOnlyPublishProjects -> listOf(
            "$projectPath:jsBrowserTest",
            "$projectPath:wasmJsBrowserTest",
        )
        projectPath in sdkKmpMobilePublishProjects -> listOf("$projectPath:jvmTest")
        else -> listOf("$projectPath:test")
    }
}
val sdkWebBrowserTestTasks = sdkKmpWebPublishProjects.flatMap { projectPath ->
    listOf("$projectPath:jsBrowserTest", "$projectPath:wasmJsBrowserTest")
}
val sdkKmpAndroidHostTestTasks = sdkKmpMobilePublishProjects.map { "$it:testAndroidHostTest" }
val explicitlyNonPublishedProjects = setOf(
    ":provider-live-harness",
    ":tooling-gateway-e2e-server",
    ":mcp-conformance-client",
)

val sdkGroup = providers.gradleProperty("magrathea.group").get()
val sdkVersion = providers.gradleProperty("magrathea.version").get()
val pomProjectUrl = providers.gradleProperty("magrathea.pom.url").get()

group = sdkGroup
version = sdkVersion

val productionSbomConfigurations = listOf(
    "^(compileClasspath|runtimeClasspath|releaseCompileClasspath|releaseRuntimeClasspath)$",
    "^(android|jvm|js|wasmJs)(Compile|Runtime)Classpath$",
    "^.*MainResolvableDependenciesMetadata$",
    "^ios(Arm64|SimulatorArm64)(CompilationDependenciesMetadata|CompileKlibraries)$",
    "^metadata.*MainCompileClasspath$",
    "^resolvableIos(Arm64|SimulatorArm64)CompilationApi$",
)

allprojects {
    tasks.withType<CyclonedxDirectTask>().configureEach {
        val included = project == rootProject || project.path in sdkPublishProjects
        enabled = included
        if (included) {
            includeConfigs.set(productionSbomConfigurations)
            includeMetadataResolution.set(true)
            includeBuildEnvironment.set(false)
            includeBomSerialNumber.set(false)
            includeLicenseText.set(false)
            componentGroup.set(sdkGroup)
            componentName.set(project.name)
            componentVersion.set(sdkVersion)
            licenseChoice.set(
                LicenseChoice().apply {
                    addLicense(License().apply { id = "MIT" })
                },
            )
            xmlOutput.unsetConvention()
        } else {
            jsonOutput.set(layout.buildDirectory.file("reports/cyclonedx-disabled/bom.json"))
            xmlOutput.unsetConvention()
        }
    }
}

tasks.withType<CyclonedxAggregateTask>().configureEach {
    componentGroup.set(sdkGroup)
    componentName.set(rootProject.name)
    componentVersion.set(sdkVersion)
    includeBomSerialNumber.set(false)
    includeLicenseText.set(false)
    licenseChoice.set(
        LicenseChoice().apply {
            addLicense(License().apply { id = "MIT" })
        },
    )
    xmlOutput.unsetConvention()
}

subprojects {
    group = sdkGroup
    version = sdkVersion

    if (path in sdkPublishProjects) {
        configureSdkPublishing()
    }
}

val publishingArtifactCoordinates: (Set<String>?) -> List<String> = { selected ->
    sdkPublishProjects
        .filter { selected == null || project(it).name in selected }
        .flatMap { projectPath ->
            val sdkProject = project(projectPath)
            val artifactIds = when {
                projectPath in sdkJvmPublishProjects -> {
                    listOf(sdkProject.name)
                }
                else -> kmpPublicationsByProject.getValue(projectPath).values.map { (_, suffix) ->
                    sdkProject.name + suffix
                }
            }
            artifactIds.map { artifactId ->
                "${sdkProject.group}:$artifactId:${sdkProject.version}"
            }
        }
        .sorted()
}

tasks.register("printPublishingCoordinates") {
    group = "publishing"
    description = "Print Maven coordinates for all publishable Magrathea SDK modules."

    doLast {
        sdkPublishProjects.forEach { projectPath ->
            val sdkProject = project(projectPath)
            println("${sdkProject.group}:${sdkProject.name}:${sdkProject.version}")
        }
    }
}

tasks.register("printPublishingArtifactCoordinates") {
    group = "publishing"
    description = "Print every concrete Maven artifact coordinate selected for publication."

    doLast {
        val selected = providers.gradleProperty("magrathea.publish.selectedModules")
            .orNull
            ?.split(',')
            ?.filter(String::isNotBlank)
            ?.toSet()
        publishingArtifactCoordinates(selected).forEach(::println)
    }
}

val verifySdkModuleClassification = tasks.register("verifySdkModuleClassification") {
    group = "verification"
    description = "Fail when a Magrathea module is neither publishable nor explicitly excluded."

    doLast {
        val actualProjects = subprojects.map { it.path }.toSet()
        val classifiedProjects = sdkPublishProjects.toSet() + explicitlyNonPublishedProjects
        check(actualProjects == classifiedProjects) {
            buildString {
                append("SDK module classification is out of sync.")
                val unclassified = actualProjects - classifiedProjects
                val missing = classifiedProjects - actualProjects
                if (unclassified.isNotEmpty()) append(" Unclassified: ${unclassified.sorted()}.")
                if (missing.isNotEmpty()) append(" Missing: ${missing.sorted()}.")
            }
        }
    }
}

val verifySdkPublicationMetadata = tasks.register("verifySdkPublicationMetadata") {
    group = "verification"
    description = "Generate and validate Maven POM metadata for every published SDK module."
    dependsOn(
        sdkJvmPublishProjects.flatMap { projectPath ->
            listOf(
                "$projectPath:generatePomFileForMavenPublication",
                "$projectPath:generateMetadataFileForMavenPublication",
            )
        } + sdkKmpPublishProjects.flatMap { projectPath ->
            kmpPublicationsByProject.getValue(projectPath).values.flatMap { (taskSuffix, _) ->
                listOf(
                    "$projectPath:generatePomFileFor${taskSuffix}Publication",
                    "$projectPath:generateMetadataFileFor${taskSuffix}Publication",
                )
            }
        },
    )

    doLast {
        sdkPublishProjects.forEach { projectPath ->
            val sdkProject = project(projectPath)
            val expectedPublications = when {
                projectPath in sdkKmpPublishProjects -> kmpPublicationsByProject.getValue(projectPath).mapValues { (_, value) ->
                    sdkProject.name + value.second
                }
                else -> mapOf("maven" to sdkProject.name)
            }
            expectedPublications.forEach { (publicationName, artifactId) ->
                val pomFile = sdkProject.layout.buildDirectory
                    .file("publications/$publicationName/pom-default.xml")
                    .get()
                    .asFile
                check(pomFile.isFile) { "Missing $publicationName POM for $projectPath" }
                val pom = pomFile.readText()
                check("<groupId>${sdkProject.group}</groupId>" in pom) {
                    "Incorrect groupId in $projectPath/$publicationName POM"
                }
                check("<artifactId>$artifactId</artifactId>" in pom) {
                    "Incorrect artifactId in $projectPath/$publicationName POM"
                }
                check("<version>${sdkProject.version}</version>" in pom) {
                    "Incorrect version in $projectPath/$publicationName POM"
                }
            }
        }
    }
}

val verifySdkPublicationArtifactIsolation = tasks.register("verifySdkPublicationArtifactIsolation") {
    group = "verification"
    description = "Ensure each Maven publication owns distinct signable artifact files."

    doLast {
        sdkPublishProjects.forEach { projectPath ->
            val sdkProject = project(projectPath)
            val ownersByFile = mutableMapOf<String, MutableSet<String>>()
            sdkProject.extensions
                .getByType(PublishingExtension::class.java)
                .publications
                .withType(MavenPublication::class.java)
                .forEach { publication ->
                    publication.artifacts.forEach { artifact ->
                        ownersByFile
                            .getOrPut(artifact.file.absoluteFile.normalize().path) { mutableSetOf() }
                            .add(publication.name)
                    }
                }
            val sharedOutputs = ownersByFile.filterValues { owners -> owners.size > 1 }
            check(sharedOutputs.isEmpty()) {
                buildString {
                    append("Maven publications in $projectPath share signable artifact outputs:")
                    sharedOutputs.toSortedMap().forEach { (file, owners) ->
                        append("\n  $file <- ${owners.sorted().joinToString()}")
                    }
                }
            }
        }
    }
}

val verifyPersistenceSchemas = tasks.register<Exec>("verifyPersistenceSchemas") {
    group = "verification"
    description = "Validate Magrathea-owned schema versions, adapter bindings, and frozen fixtures."
    inputs.file("persistence/schema-ledger.json")
    inputs.file("scripts/verify_persistence_schemas.py")
    inputs.file("docs/adr/ADR-005-persistence-contracts.md")
    inputs.dir("serialization-fixtures")
    inputs.dir("magrathea-core/src/commonMain/kotlin/saien/magrathea/core")
    commandLine(
        "python3",
        file("scripts/verify_persistence_schemas.py").absolutePath,
        "--root",
        rootDir.absolutePath,
    )
}

val verifyPersistenceSchemaContract = tasks.register<Exec>("verifyPersistenceSchemaContract") {
    group = "verification"
    description = "Mutation-test the append-only Magrathea persistence schema gate."
    dependsOn(verifyPersistenceSchemas)
    inputs.file("persistence/schema-ledger.json")
    inputs.file("scripts/verify_persistence_schemas.py")
    commandLine(
        "python3",
        file("scripts/verify_persistence_schemas.py").absolutePath,
        "--root",
        rootDir.absolutePath,
        "--self-test",
    )
}

val verifySdkCompatibility = tasks.register("verifySdkCompatibility") {
    group = "verification"
    description = "Validate published JVM/Android ABI dumps and versioned serialization fixtures."
    dependsOn(verifyPersistenceSchemaContract)
    dependsOn(sdkPublishProjects.map { "$it:apiCheck" })
    dependsOn(":magrathea-core:jvmTest", ":magrathea-provider-api:jvmTest")
}

val verifyPublishSdkContract = tasks.register<Exec>("verifyPublishSdkContract") {
    group = "verification"
    description = "Reject dirty, unverified, or command-line-secret remote publish paths."
    commandLine(
        "/bin/bash",
        file("scripts/verify-publish-sdk-contract").absolutePath,
        rootDir.absolutePath,
    )
}

val verifyResumePublishSdkContract = tasks.register<Exec>("verifyResumePublishSdkContract") {
    group = "verification"
    description = "Rehearse exact-coordinate recovery after an interrupted immutable publication."
    inputs.file("scripts/extract-completed-publication-coordinates")
    inputs.file("scripts/resume-publish-sdk")
    inputs.file("scripts/verify-resume-publish-sdk-contract")
    inputs.file("scripts/verify-remote-version-absent")
    inputs.file("scripts/verify-remote-version-present")
    inputs.file("scripts/write-maven-manifest")
    inputs.file("scripts/rollback-fixture-server.py")
    commandLine(
        "/bin/bash",
        file("scripts/verify-resume-publish-sdk-contract").absolutePath,
        rootDir.absolutePath,
    )
}

val verifyReleaseCandidateContract = tasks.register<Exec>("verifyReleaseCandidateContract") {
    group = "verification"
    description = "Verify immutable candidate evidence, source binding, manifests, and PGP signatures."
    inputs.file("scripts/publish-sdk")
    inputs.file("scripts/select-publishing-coordinates")
    inputs.file("scripts/verify-release-candidate")
    inputs.file("scripts/verify-release-candidate-contract")
    inputs.file("scripts/write-maven-manifest")
    commandLine(
        "/bin/bash",
        file("scripts/verify-release-candidate-contract").absolutePath,
        rootDir.absolutePath,
    )
}

val rollbackCoordinatesFile = layout.buildDirectory.file("rollback-contract/coordinates.txt")
val writeRollbackCoordinates = tasks.register("writeRollbackCoordinates") {
    group = "verification"
    description = "Write the complete immutable coordinate set used by the rollback rehearsal."
    inputs.property("sdkVersion", sdkVersion)
    outputs.file(rollbackCoordinatesFile)

    doLast {
        val coordinates = publishingArtifactCoordinates(null)
        check(coordinates.size == 88) {
            "Rollback rehearsal expected 88 publication coordinates, found ${coordinates.size}"
        }
        check(coordinates.toSet().size == coordinates.size) {
            "Rollback rehearsal contains duplicate publication coordinates"
        }
        val output = rollbackCoordinatesFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(coordinates.joinToString(separator = "\n", postfix = "\n"))
    }
}

val verifySdkRollbackContract = tasks.register<Exec>("verifySdkRollbackContract") {
    group = "verification"
    description = "Rehearse immutable-version rejection and rollback by previous consumer pin."
    dependsOn(writeRollbackCoordinates)
    inputs.file("scripts/verify-remote-version-absent")
    inputs.file("scripts/verify-remote-version-present")
    inputs.file("scripts/verify-rollback-contract")
    inputs.file("scripts/rollback-fixture-server.py")
    inputs.file("docs/publishing.md")
    inputs.file("docs/known-issues.md")
    inputs.file(rollbackCoordinatesFile)
    commandLine(
        "/bin/bash",
        file("scripts/verify-rollback-contract").absolutePath,
        rootDir.absolutePath,
        sdkVersion,
        rollbackCoordinatesFile.get().asFile.absolutePath,
    )
}

val verifyCiContract = tasks.register<Exec>("verifyCiContract") {
    group = "verification"
    description = "Parse and validate verification, nightly, and single-run release workflow contracts."
    commandLine(
        "/bin/bash",
        file("scripts/verify-ci-contract").absolutePath,
        rootDir.absolutePath,
    )
}

val verifyReleaseTagContract = tasks.register<Exec>("verifyReleaseTagContract") {
    group = "verification"
    description = "Mutation-test automated annotated-tag version, changelog, and release-note enforcement."
    inputs.file("scripts/verify-release-tag")
    inputs.file("scripts/verify-release-tag-contract")
    commandLine(
        file("scripts/verify-release-tag-contract").absolutePath,
        rootDir.absolutePath,
    )
}

val verifyPrepareReleaseContract = tasks.register<Exec>("verifyPrepareReleaseContract") {
    group = "verification"
    description = "Mutation-test the one-command release preparation entry point."
    inputs.file("scripts/prepare-release")
    inputs.file("scripts/release-version-files.txt")
    inputs.file("scripts/verify-prepare-release-contract")
    commandLine(
        file("scripts/verify-prepare-release-contract").absolutePath,
        rootDir.absolutePath,
    )
}

val verifyReleaseCommandContract = tasks.register<Exec>("verifyReleaseCommandContract") {
    group = "verification"
    description = "Mutation-test the guarded one-command release orchestrator."
    inputs.file("scripts/release")
    inputs.file("scripts/verify-release-command-contract")
    commandLine(
        file("scripts/verify-release-command-contract").absolutePath,
        rootDir.absolutePath,
    )
}

val normalizedSdkSbom = layout.buildDirectory.file("reports/supply-chain/magrathea-sbom.cdx.json")
val sdkLicenseReport = layout.buildDirectory.file("reports/supply-chain/third-party-licenses.tsv")
val webRuntimeLicenseLedger = layout.projectDirectory.file("third-party/web-runtime-licenses.json")
val generateSdkSbom = tasks.register<Exec>("generateSdkSbom") {
    group = "distribution"
    description = "Generate the production SBOM in an isolated task graph."
    commandLine(
        file("gradlew").absolutePath,
        "cyclonedxBom",
        "--no-daemon",
        "--project-cache-dir",
        layout.buildDirectory.dir("sbom-project-cache").get().asFile.absolutePath,
        "--console=plain",
    )
}
val verifySdkSupplyChain = tasks.register<Exec>("verifySdkSupplyChain") {
    group = "verification"
    description = "Validate the wrapper and fixed dependency policy, then produce the production CycloneDX/license inventory."
    dependsOn(generateSdkSbom)
    // The committed lock is also the output of Kotlin's store task. When both tasks are present in
    // an aggregate graph, validate only after that task has proved the generated lock is unchanged;
    // do not force npm installation for a standalone supply-chain inventory check.
    mustRunAfter("kotlinStoreYarnLock")
    inputs.file("gradle/wrapper/gradle-wrapper.properties")
    inputs.file("gradle/wrapper/gradle-wrapper.jar")
    inputs.file("gradle/libs.versions.toml")
    inputs.file("build/reports/cyclonedx/bom.json")
    inputs.file("tooling/web-browser-e2e/package-lock.json")
    inputs.file("kotlin-js-store/yarn.lock")
    inputs.file(webRuntimeLicenseLedger)
    inputs.file("scripts/verify-supply-chain")
    inputs.file("scripts/verify_normalized_sbom.rb")
    outputs.file(normalizedSdkSbom)
    outputs.file(sdkLicenseReport)
    commandLine(
        file("scripts/verify-supply-chain").absolutePath,
        rootDir.absolutePath,
        sdkVersion,
    )
    doLast {
        val schemaErrors = JsonParser().validate(normalizedSdkSbom.get().asFile, Version.VERSION_16)
        check(schemaErrors.isEmpty()) {
            "Normalized SDK SBOM failed the official CycloneDX 1.6 schema (${schemaErrors.size} errors)"
        }
    }
}
val verifySdkSupplyChainContract = tasks.register<Exec>("verifySdkSupplyChainContract") {
    group = "verification"
    description = "Mutation-test the wrapper, SBOM, and license gates."
    dependsOn(verifySdkSupplyChain)
    inputs.file(webRuntimeLicenseLedger)
    inputs.file("scripts/verify-supply-chain-contract")
    inputs.file("scripts/verify-supply-chain")
    inputs.file("scripts/verify_normalized_sbom.rb")
    commandLine(
        file("scripts/verify-supply-chain-contract").absolutePath,
        rootDir.absolutePath,
        sdkVersion,
    )
}

val verifySdkQuick = tasks.register("verifySdkQuick") {
    group = "verification"
    description = "Run deterministic tests plus API and serialization compatibility checks."
    dependsOn(verifySdkModuleClassification)
    dependsOn(verifySdkCompatibility)
    dependsOn(
        verifyPublishSdkContract,
        verifyResumePublishSdkContract,
        verifyReleaseCandidateContract,
        verifySdkRollbackContract,
        verifyCiContract,
        verifyPrepareReleaseContract,
        verifyReleaseCommandContract,
        verifyReleaseTagContract,
        verifySdkPublicationArtifactIsolation,
    )
    dependsOn(verifySdkSupplyChainContract)
    dependsOn(sdkTestTasks)
    dependsOn(":provider-live-harness:test")
    dependsOn(":mcp-conformance-client:test")
}

val sdkVerificationRepository = layout.buildDirectory.dir("sdk-verification-repository")
val cleanSdkVerificationRepository = tasks.register<Delete>("cleanSdkVerificationRepository") {
    group = "verification"
    description = "Remove the build-local Maven repository before publishing the current SDK graph."
    delete(sdkVerificationRepository)
}
allprojects {
    tasks.matching { task -> task.name.endsWith("ToSdkVerificationRepository") }.configureEach {
        dependsOn(cleanSdkVerificationRepository)
    }
}
val nestedBuildProxyProperties = listOf(
    "http.proxyHost",
    "http.proxyPort",
    "http.proxyUser",
    "http.proxyPassword",
    "http.nonProxyHosts",
    "https.proxyHost",
    "https.proxyPort",
    "https.proxyUser",
    "https.proxyPassword",
).mapNotNull { key ->
    System.getProperty(key)?.let { value -> key to value }
}.toMap()

val verifyKmpPublishedConsumerJvmAndroid = tasks.register<GradleBuild>("verifyKmpPublishedConsumerJvmAndroid") {
    group = "verification"
    description = "Publish all KMP SDK modules to an isolated repository and consume their JVM and Android variants."
    dependsOn(sdkKmpMobilePublishProjects.map { "$it:publishAllPublicationsToSdkVerificationRepository" })
    buildName = "kmp-consumer-jvm-android"
    dir = file("tooling/kmp-consumer")
    tasks = listOf("clean", "jvmTest", "compileAndroidMain")
    startParameter.projectProperties = mapOf(
        "magrathea.repository" to sdkVerificationRepository.get().asFile.absolutePath,
        "magrathea.version" to sdkVersion,
        "magrathea.consumer.buildDir" to file("tooling/kmp-consumer/build/jvm-android").absolutePath,
    )
    startParameter.systemPropertiesArgs = startParameter.systemPropertiesArgs + nestedBuildProxyProperties
}

val kmpPublishedConsumerAppleBuildDirectory = file("tooling/kmp-consumer/build/apple")
val publishedConsumerAppleFrameworkBaseName = "MagratheaPublishedConsumer"
val verifyKmpPublishedConsumerApple = tasks.register<GradleBuild>("verifyKmpPublishedConsumerApple") {
    group = "verification"
    description = "Consume the complete published KMP graph and link aggregate device and Simulator frameworks."
    dependsOn(sdkKmpMobilePublishProjects.map { "$it:publishAllPublicationsToSdkVerificationRepository" })
    buildName = "kmp-consumer-apple"
    dir = file("tooling/kmp-consumer")
    tasks = listOf(
        "clean",
        "compileTestKotlinIosArm64",
        "compileTestKotlinIosSimulatorArm64",
        "linkReleaseFrameworkIosArm64",
        "linkReleaseFrameworkIosSimulatorArm64",
    )
    startParameter.projectProperties = mapOf(
        "magrathea.repository" to sdkVerificationRepository.get().asFile.absolutePath,
        "magrathea.version" to sdkVersion,
        "magrathea.consumer.buildDir" to kmpPublishedConsumerAppleBuildDirectory.absolutePath,
    )
    startParameter.systemPropertiesArgs = startParameter.systemPropertiesArgs + nestedBuildProxyProperties

    doLast {
        listOf("iosArm64", "iosSimulatorArm64").forEach { target ->
            val binary = kmpPublishedConsumerAppleBuildDirectory.resolve(
                "bin/$target/releaseFramework/" +
                    "$publishedConsumerAppleFrameworkBaseName.framework/$publishedConsumerAppleFrameworkBaseName",
            )
            check(binary.isFile && binary.length() > 0) {
                "Missing published-consumer $target framework binary"
            }
        }
    }
}

val verifyPublishedAndroidConsumer = tasks.register<GradleBuild>("verifyPublishedAndroidConsumer") {
    group = "verification"
    description = "Consume all Android-facing SDK coordinates from the isolated Maven repository."
    dependsOn(sdkPublishProjects.map { "$it:publishAllPublicationsToSdkVerificationRepository" })
    buildName = "published-android-consumer"
    dir = file("tooling/android-consumer")
    tasks = listOf("clean", "testDebugUnitTest", "assembleDebug")
    startParameter.projectProperties = mapOf(
        "magrathea.repository" to sdkVerificationRepository.get().asFile.absolutePath,
        "magrathea.version" to sdkVersion,
        "magrathea.consumer.buildDir" to file("tooling/android-consumer/build/verified").absolutePath,
    )
    startParameter.systemPropertiesArgs = startParameter.systemPropertiesArgs + nestedBuildProxyProperties
}

val androidDeviceConsumerBuildDirectory = file("tooling/android-consumer/build/device")
val assembleAndroidDeviceConsumer = tasks.register<GradleBuild>("assembleAndroidDeviceConsumer") {
    group = "verification"
    description = "Build the isolated published-artifact Android application and its device instrumentation APK."
    dependsOn(sdkPublishProjects.map { "$it:publishAllPublicationsToSdkVerificationRepository" })
    buildName = "android-device-consumer"
    dir = file("tooling/android-consumer")
    tasks = listOf("clean", "assembleDebug", "assembleDebugAndroidTest")
    startParameter.projectProperties = mapOf(
        "magrathea.repository" to sdkVerificationRepository.get().asFile.absolutePath,
        "magrathea.version" to sdkVersion,
        "magrathea.consumer.buildDir" to androidDeviceConsumerBuildDirectory.absolutePath,
    )
    startParameter.systemPropertiesArgs = startParameter.systemPropertiesArgs + nestedBuildProxyProperties
}

tasks.register<Exec>("verifyAndroidDevice") {
    group = "verification"
    description = "Run the external Android physical-device Keystore, Room, HTTP/Gemini, cancellation, and performance gate."
    dependsOn(assembleAndroidDeviceConsumer)
    commandLine(
        "/bin/bash",
        file("tooling/android-device/verify.sh").absolutePath,
        rootDir.absolutePath,
        androidDeviceConsumerBuildDirectory.absolutePath,
    )
}

val verifyJvmChatSample = tasks.register<GradleBuild>("verifyJvmChatSample") {
    group = "verification"
    description = "Run the published-artifact JVM chatbot and deterministic provider-protocol sample."
    dependsOn(
        ":magrathea-chatbot:publishAllPublicationsToSdkVerificationRepository",
        ":magrathea-core:publishAllPublicationsToSdkVerificationRepository",
        ":magrathea-provider-api:publishAllPublicationsToSdkVerificationRepository",
        ":magrathea-runtime:publishAllPublicationsToSdkVerificationRepository",
    )
    buildName = "jvm-chat-sample"
    dir = file("samples/jvm-chat")
    tasks = listOf("clean", "test", "run")
    startParameter.projectProperties = mapOf(
        "magrathea.repository" to sdkVerificationRepository.get().asFile.absolutePath,
        "magrathea.version" to sdkVersion,
        "magrathea.consumer.buildDir" to file("samples/jvm-chat/build/verified").absolutePath,
    )
    startParameter.systemPropertiesArgs = startParameter.systemPropertiesArgs + nestedBuildProxyProperties
}

var webGatewayE2eProcess: Process? = null
val startWebGatewayE2eServer = {
    check(webGatewayE2eProcess?.isAlive != true) { "Gateway E2E server is already running" }
    val executable = project(":tooling-gateway-e2e-server").layout.buildDirectory
        .file("install/tooling-gateway-e2e-server/bin/tooling-gateway-e2e-server")
        .get()
        .asFile
    check(executable.canExecute()) { "Gateway E2E executable is missing: $executable" }
    val log = layout.buildDirectory.file("reports/web-gateway-e2e-server.log").get().asFile
    log.parentFile.mkdirs()
    val process = ProcessBuilder(executable.absolutePath)
        .directory(rootDir)
        .redirectErrorStream(true)
        .redirectOutput(log)
        .start()
    webGatewayE2eProcess = process

    var ready = false
    for (attempt in 0 until 100) {
        if (!process.isAlive) break
        ready = runCatching {
            val connection = java.net.URI("http://127.0.0.1:18081/health").toURL().openConnection()
                as java.net.HttpURLConnection
            connection.connectTimeout = 200
            connection.readTimeout = 200
            try {
                connection.responseCode == 200
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
        if (ready) break
        Thread.sleep(100)
    }
    check(ready) {
        "Gateway E2E server did not become ready. Log: ${log.takeIf { it.isFile }?.readText().orEmpty()}"
    }
}
val stopWebGatewayE2eProcess = {
    webGatewayE2eProcess?.let { process ->
        process.destroy()
        if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        }
    }
    webGatewayE2eProcess = null
}
val stopWebGatewayE2eServer = tasks.register("stopWebGatewayE2eServer") {
    group = "verification"
    description = "Stop the loopback Gateway process used only by the Web sample E2E."
    doLast {
        stopWebGatewayE2eProcess()
    }
}

val webSamplePublishProjects = listOf(
    ":magrathea-core",
    ":magrathea-provider-api",
    ":magrathea-gateway-protocol",
    ":magrathea-provider-gateway",
    ":magrathea-runtime",
    ":magrathea-chatbot",
    ":magrathea-storage-web",
    ":magrathea-web-client",
)
val verifyWebChatSample = tasks.register<GradleBuild>("verifyWebChatSample") {
    group = "verification"
    description = "Consume published JS/Wasm artifacts and run the authenticated real-HTTP Gateway browser E2E."
    dependsOn(webSamplePublishProjects.map { "$it:publishAllPublicationsToSdkVerificationRepository" })
    dependsOn(":tooling-gateway-e2e-server:installDist")
    finalizedBy(stopWebGatewayE2eServer)
    buildName = "web-chat-sample"
    dir = file("samples/web-chat")
    tasks = listOf(
        "clean",
        "jsBrowserTest",
        "wasmJsBrowserTest",
        "jsBrowserProductionWebpack",
        "wasmJsBrowserProductionWebpack",
    )
    startParameter.projectProperties = mapOf(
        "magrathea.repository" to sdkVerificationRepository.get().asFile.absolutePath,
        "magrathea.version" to sdkVersion,
        "magrathea.consumer.buildDir" to file("samples/web-chat/build/verified").absolutePath,
    )
    startParameter.systemPropertiesArgs = startParameter.systemPropertiesArgs + nestedBuildProxyProperties

    doFirst {
        startWebGatewayE2eServer()
    }
}

val webBrowserE2eDirectory = file("tooling/web-browser-e2e")
val installWebBrowserE2eDependencies = tasks.register<Exec>("installWebBrowserE2eDependencies") {
    group = "build setup"
    description = "Install the lockfile-pinned Playwright Node dependency without lifecycle scripts."
    workingDir(webBrowserE2eDirectory)
    commandLine("npm", "ci", "--ignore-scripts")
    inputs.files(
        webBrowserE2eDirectory.resolve("package.json"),
        webBrowserE2eDirectory.resolve("package-lock.json"),
    )
    outputs.file(webBrowserE2eDirectory.resolve("node_modules/.package-lock.json"))
}

val installWebBrowserE2eBrowsers = tasks.register<Exec>("installWebBrowserE2eBrowsers") {
    group = "build setup"
    description = "Install the exact Chromium, Firefox, and WebKit builds required by pinned Playwright."
    dependsOn(installWebBrowserE2eDependencies)
    workingDir(webBrowserE2eDirectory)
    commandLine("npx", "playwright", "install", "--only-shell", "chromium", "firefox", "webkit")
}

val stopWebCrossBrowserGatewayE2eServer = tasks.register("stopWebCrossBrowserGatewayE2eServer") {
    group = "verification"
    description = "Stop the loopback Gateway process used by the Playwright browser matrix."
    doLast {
        stopWebGatewayE2eProcess()
    }
}

val verifyWebCrossBrowserRuntime = tasks.register<Exec>("verifyWebCrossBrowserRuntime") {
    group = "verification"
    description = "Run the production JS and Wasm sample in Playwright Chromium, Firefox, and WebKit."
    dependsOn(verifyWebChatSample, installWebBrowserE2eBrowsers)
    finalizedBy(stopWebCrossBrowserGatewayE2eServer)
    workingDir(webBrowserE2eDirectory)
    commandLine(
        "node",
        webBrowserE2eDirectory.resolve("verify.mjs").absolutePath,
        file("samples/web-chat/src/webMain/resources").absolutePath,
        file("samples/web-chat/build/verified/kotlin-webpack/js/productionExecutable").absolutePath,
        file("samples/web-chat/build/verified/kotlin-webpack/wasmJs/productionExecutable").absolutePath,
    )
    doFirst {
        startWebGatewayE2eServer()
    }
}

val webClientProject = project(":magrathea-web-client")
val generatedWebClientPackageJson = layout.buildDirectory.file("js/packages/magrathea-web-client/package.json")
val kotlinJsNodeModulesDirectory = layout.buildDirectory.dir("js/node_modules")
val webThirdPartyNoticeDirectory = webClientProject.layout.buildDirectory.dir("generated/web-third-party-notices")
val generateWebThirdPartyNotices = webClientProject.tasks.register("generateWebThirdPartyNotices") {
    group = "distribution"
    description = "Verify the resolved Web runtime license ledger and generate distributable notices."
    dependsOn(":magrathea-web-client:jsPackageJson", ":kotlinNpmInstall", ":kotlinStoreYarnLock")
    inputs.file(webRuntimeLicenseLedger)
    inputs.dir(layout.projectDirectory.dir("third-party/licenses"))
    inputs.file(generatedWebClientPackageJson)
    inputs.file(layout.projectDirectory.file("kotlin-js-store/yarn.lock"))
    inputs.file(layout.projectDirectory.file("gradle/libs.versions.toml"))
    outputs.dir(webThirdPartyNoticeDirectory)
    // ResolutionResult cannot be modeled as a file input without also claiming every project
    // compilation output. Always execute so transitive metadata changes cannot skip the legal gate.
    outputs.upToDateWhen { false }

    doLast {
        val ledger = groovy.json.JsonSlurper().parse(webRuntimeLicenseLedger.asFile) as? Map<*, *>
            ?: error("Web runtime license ledger must be a JSON object")
        check(ledger["formatVersion"] == 1) { "Unsupported Web runtime license ledger format" }
        val licenses = ledger["licenses"] as? Map<*, *> ?: error("Web runtime license ledger has no licenses")
        val components = ledger["components"] as? List<*>
            ?: error("Web runtime license ledger has no components")
        val npmComponents = ledger["npmComponents"] as? List<*>
            ?: error("Web runtime license ledger has no npm components")
        check(licenses.isNotEmpty() && components.isNotEmpty() && npmComponents.isNotEmpty()) {
            "Web runtime license ledger must not be empty"
        }

        val reviewedCoordinates = linkedMapOf<String, Map<*, *>>()
        components.forEach { rawComponent ->
            val component = rawComponent as? Map<*, *> ?: error("Invalid Web runtime component entry")
            val name = component["name"] as? String ?: error("Web runtime component has no name")
            val repository = component["repository"] as? String
                ?: error("Web runtime component $name has no repository")
            check(repository.startsWith("https://")) { "Web runtime component $name has an unsafe repository URL" }
            val licenseId = component["license"] as? String
                ?: error("Web runtime component $name has no license")
            check(licenseId in licenses) { "Web runtime component $name references unknown license $licenseId" }
            val coordinates = component["coordinates"] as? List<*>
                ?: error("Web runtime component $name has no coordinates")
            check(coordinates.isNotEmpty()) { "Web runtime component $name has no coordinates" }
            coordinates.forEach { rawCoordinate ->
                val coordinate = rawCoordinate as? String
                    ?: error("Web runtime component $name has a non-string coordinate")
                check(coordinate.matches(Regex("[^:]+:[^:]+:[^:]+"))) {
                    "Invalid Web runtime coordinate: $coordinate"
                }
                check(reviewedCoordinates.put(coordinate, component) == null) {
                    "Duplicate Web runtime coordinate: $coordinate"
                }
            }
        }

        val runtimeConfiguration = webClientProject.configurations.getByName("jsRuntimeClasspath")
        val resolvedCoordinates = runtimeConfiguration.incoming.resolutionResult.allComponents
            .mapNotNull { component ->
                val identifier = component.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier
                    ?: return@mapNotNull null
                "${identifier.group}:${identifier.module}:${identifier.version}"
            }
            .toSortedSet()
        val expectedCoordinates = reviewedCoordinates.keys.toSortedSet()
        check(resolvedCoordinates == expectedCoordinates) {
            val unreviewed = resolvedCoordinates - expectedCoordinates
            val stale = expectedCoordinates - resolvedCoordinates
            "Web runtime license ledger differs from jsRuntimeClasspath; " +
                "unreviewed=${unreviewed.joinToString()} stale=${stale.joinToString()}"
        }

        val reviewedNpmPackages = linkedMapOf<String, Map<*, *>>()
        npmComponents.forEach { rawComponent ->
            val component = rawComponent as? Map<*, *> ?: error("Invalid Web npm component entry")
            val name = component["name"] as? String ?: error("Web npm component has no name")
            val version = component["version"] as? String ?: error("Web npm component $name has no version")
            check(version.isNotBlank() && !version.contains(Regex("SNAPSHOT|latest|\\+", RegexOption.IGNORE_CASE))) {
                "Web npm component $name has an unpinned version: $version"
            }
            val role = component["role"] as? String ?: error("Web npm component $name has no role")
            check(role == "runtimeDependency" || role == "bundleGenerator") {
                "Web npm component $name has unknown role: $role"
            }
            val repository = component["repository"] as? String
                ?: error("Web npm component $name has no repository")
            check(repository.startsWith("https://")) { "Web npm component $name has an unsafe repository URL" }
            val licenseId = component["license"] as? String ?: error("Web npm component $name has no license")
            check(licenseId in licenses) { "Web npm component $name references unknown license $licenseId" }
            check(reviewedNpmPackages.put(name, component) == null) { "Duplicate Web npm package: $name" }
        }

        val generatedPackage = groovy.json.JsonSlurper().parse(generatedWebClientPackageJson.get().asFile) as? Map<*, *>
            ?: error("Generated Web client package metadata must be a JSON object")
        val generatedRuntimeDependencies = (generatedPackage["dependencies"] as? Map<*, *>).orEmpty()
            .map { (name, version) -> name as String to version as String }
            .toMap()
        val reviewedRuntimeDependencies = reviewedNpmPackages.values
            .filter { component -> component["role"] == "runtimeDependency" }
            .associate { component -> component["name"] as String to component["version"] as String }
        check(generatedRuntimeDependencies == reviewedRuntimeDependencies) {
            "Generated Web npm runtime dependencies differ from the license ledger; " +
                "actual=$generatedRuntimeDependencies reviewed=$reviewedRuntimeDependencies"
        }
        val generatedDevelopmentDependencies = (generatedPackage["devDependencies"] as? Map<*, *>).orEmpty()
            .map { (name, version) -> name as String to version as String }
            .toMap()
        reviewedNpmPackages.values.filter { component -> component["role"] == "bundleGenerator" }.forEach { component ->
            val name = component["name"] as String
            val version = component["version"] as String
            check(generatedDevelopmentDependencies[name] == version) {
                "Generated Web bundle tool $name differs from the license ledger: " +
                    "actual=${generatedDevelopmentDependencies[name]} reviewed=$version"
            }
        }

        val outputDirectory = webThirdPartyNoticeDirectory.get().asFile
        outputDirectory.deleteRecursively()
        val outputLicenses = outputDirectory.resolve("LICENSES").apply { mkdirs() }
        val licenseFiles = linkedMapOf<String, String>()
        val vendoredLicenseSources = linkedMapOf<String, File>()
        licenses.forEach { (rawLicenseId, rawDetails) ->
            val licenseId = rawLicenseId as? String ?: error("Invalid Web runtime license ID")
            val details = rawDetails as? Map<*, *> ?: error("Invalid Web runtime license entry: $licenseId")
            val spdx = details["spdx"] as? String ?: error("License $licenseId has no SPDX identifier")
            check(spdx == "Apache-2.0" || spdx == "MIT") { "License $licenseId has an unreviewed SPDX identifier: $spdx" }
            val relativePath = details["file"] as? String ?: error("License $licenseId has no vendored file")
            check(relativePath.startsWith("third-party/licenses/") && ".." !in relativePath) {
                "License $licenseId points outside third-party/licenses"
            }
            val source = details["source"] as? String ?: error("License $licenseId has no source URL")
            check(source.startsWith("https://")) { "License $licenseId has an unsafe source URL" }
            val expectedSha256 = details["sha256"] as? String ?: error("License $licenseId has no SHA-256")
            check(expectedSha256.matches(Regex("[0-9a-f]{64}"))) { "License $licenseId has an invalid SHA-256" }
            val sourceFile = layout.projectDirectory.file(relativePath).asFile
            check(sourceFile.isFile && sourceFile.length() > 0) { "Missing vendored license: $relativePath" }
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(sourceFile.readBytes())
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
            check(digest == expectedSha256) { "Vendored license digest changed: $relativePath" }
            val outputName = sourceFile.name
            check(licenseFiles.put(licenseId, outputName) == null) { "Duplicate license ID: $licenseId" }
            vendoredLicenseSources[licenseId] = sourceFile
            sourceFile.copyTo(outputLicenses.resolve(outputName), overwrite = true)
        }

        val nodeModulesDirectory = kotlinJsNodeModulesDirectory.get().asFile
        reviewedNpmPackages.forEach { (name, component) ->
            val installedDirectory = nodeModulesDirectory.resolve(name)
            val installedPackageFile = installedDirectory.resolve("package.json")
            check(installedPackageFile.isFile) { "Reviewed Web npm package is not installed: $name" }
            val installedPackage = groovy.json.JsonSlurper().parse(installedPackageFile) as? Map<*, *>
                ?: error("Installed Web npm package metadata is invalid: $name")
            val expectedVersion = component["version"] as String
            val licenseId = component["license"] as String
            val expectedSpdx = ((licenses[licenseId] ?: error("Unknown license $licenseId")) as Map<*, *>)["spdx"] as String
            check(installedPackage["name"] == name && installedPackage["version"] == expectedVersion) {
                "Installed Web npm package differs from the license ledger: $name"
            }
            check(installedPackage["license"] == expectedSpdx) {
                "Installed Web npm package has unexpected license metadata: $name:${installedPackage["license"]}"
            }
            val installedLicense = installedDirectory.resolve("LICENSE")
            check(installedLicense.isFile && installedLicense.length() > 0) {
                "Installed Web npm package has no LICENSE file: $name"
            }
            check(installedLicense.readBytes().contentEquals(vendoredLicenseSources.getValue(licenseId).readBytes())) {
                "Vendored license no longer matches the installed Web npm package: $name@$expectedVersion"
            }
        }

        val extractedNotices = mutableListOf<String>()
        runtimeConfiguration.incoming.artifacts.artifacts
            .filter { artifact ->
                artifact.id.componentIdentifier is org.gradle.api.artifacts.component.ModuleComponentIdentifier
            }
            .sortedBy { artifact -> artifact.id.displayName }
            .forEach { artifact ->
                val identifier = artifact.id.componentIdentifier as org.gradle.api.artifacts.component.ModuleComponentIdentifier
                val coordinate = "${identifier.group}:${identifier.module}:${identifier.version}"
                try {
                    java.util.zip.ZipFile(artifact.file).use { archive ->
                        archive.entries().asSequence()
                            .filter { entry ->
                                !entry.isDirectory && entry.name.substringAfterLast('/').matches(
                                    Regex("NOTICE(?:\\.[A-Za-z0-9._-]+)?", RegexOption.IGNORE_CASE),
                                )
                            }
                            .sortedBy { entry -> entry.name }
                            .forEach { entry ->
                                val noticeBytes = archive.getInputStream(entry).use { it.readBytes() }
                                check(noticeBytes.isNotEmpty()) {
                                    "Resolved artifact contains an empty NOTICE: ${artifact.id.displayName}!/${entry.name}"
                                }
                                val entryDigest = java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(entry.name.toByteArray())
                                    .take(6)
                                    .joinToString(separator = "") { byte -> "%02x".format(byte) }
                                val outputName = "${coordinate.replace(Regex("[^A-Za-z0-9._-]"), "_")}-$entryDigest.txt"
                                val relativeOutput = "UPSTREAM-NOTICES/$outputName"
                                val noticeFile = outputDirectory.resolve(relativeOutput)
                                noticeFile.parentFile.mkdirs()
                                noticeFile.writeBytes(noticeBytes)
                                extractedNotices += "$coordinate!/${entry.name} -> $relativeOutput"
                            }
                    }
                } catch (_: java.util.zip.ZipException) {
                    // Some resolved artifacts are not ZIP containers and therefore cannot embed NOTICE files.
                }
            }

        val noticeText = buildString {
            appendLine("Magrathea Web SDK third-party notices")
            appendLine("=========================================")
            appendLine()
            appendLine("This distribution bundles the following external runtime components.")
            appendLine("Full license terms are provided in the LICENSES directory.")
            appendLine()
            components.map { it as Map<*, *> }.sortedBy { it["name"] as String }.forEach { component ->
                val name = component["name"] as String
                val repository = component["repository"] as String
                val licenseId = component["license"] as String
                appendLine(name)
                appendLine("  Repository: $repository")
                val spdx = ((licenses[licenseId] ?: error("Unknown license $licenseId")) as Map<*, *>)["spdx"] as String
                appendLine("  License: $spdx (LICENSES/${licenseFiles.getValue(licenseId)})")
                appendLine("  Resolved coordinates:")
                (component["coordinates"] as List<*>).map { it as String }.sorted().forEach { coordinate ->
                    appendLine("    - $coordinate")
                }
                appendLine()
            }
            appendLine("Bundled npm and generated runtime code")
            appendLine("--------------------------------------")
            appendLine()
            npmComponents.map { it as Map<*, *> }.sortedBy { it["name"] as String }.forEach { component ->
                val name = component["name"] as String
                val version = component["version"] as String
                val role = component["role"] as String
                val repository = component["repository"] as String
                val licenseId = component["license"] as String
                val spdx = ((licenses[licenseId] ?: error("Unknown license $licenseId")) as Map<*, *>)["spdx"] as String
                appendLine("$name@$version")
                appendLine("  Role: $role")
                appendLine("  Repository: $repository")
                appendLine("  License: $spdx (LICENSES/${licenseFiles.getValue(licenseId)})")
                appendLine()
            }
            if (extractedNotices.isEmpty()) {
                appendLine("No upstream NOTICE files were present in the resolved runtime artifacts.")
            } else {
                appendLine("Upstream NOTICE files preserved from resolved runtime artifacts:")
                extractedNotices.forEach { notice -> appendLine("  - $notice") }
            }
        }
        outputDirectory.resolve("THIRD_PARTY_NOTICES.txt").writeText(noticeText)
    }
}

val webSdkPackageDirectory = layout.buildDirectory.dir("web-package/magrathea-web-client")
val stageWebSdkPackage = tasks.register<Sync>("stageWebSdkPackage") {
    group = "distribution"
    description = "Stage the local JS chatbot bundle and compiler-generated TypeScript definitions."
    dependsOn(
        ":magrathea-web-client:jsBrowserProductionWebpack",
        ":magrathea-web-client:jsProductionExecutableValidateGeneratedByCompilerTypeScript",
        generateWebThirdPartyNotices,
    )
    from(
        project(":magrathea-web-client").layout.buildDirectory
            .dir("kotlin-webpack/js/productionExecutable"),
    ) {
        include("*.js", "*.js.map")
    }
    from(
        project(":magrathea-web-client").layout.buildDirectory
            .file("compileSync/js/main/productionExecutable/kotlin/magrathea-web-client.d.ts"),
    )
    from("magrathea-web-client/npm/package.json") {
        expand("version" to sdkVersion)
    }
    from("magrathea-web-client/npm/README.md")
    from("LICENSE")
    from(webThirdPartyNoticeDirectory)
    into(webSdkPackageDirectory)

    doLast {
        val directory = webSdkPackageDirectory.get().asFile
        val absolutePathPattern = Regex(
            "(?i)(?:^|/)(?:Users|home|private|var|opt|mnt|workspace)(?:/|$)|(?:^|/)[A-Za-z]:[\\\\/]",
        )
        val buildWorkspacePattern = Regex("(?:^|/)(?:work|__w)/[^/]+/(.+)$")
        directory.listFiles().orEmpty().filter { it.name.endsWith(".js.map") }.forEach { sourceMapFile ->
            @Suppress("UNCHECKED_CAST")
            val sourceMap = groovy.json.JsonSlurper().parse(sourceMapFile) as? MutableMap<String, Any?>
                ?: error("Invalid Web source map: $sourceMapFile")
            val sources = sourceMap["sources"] as? List<*>
                ?: error("Web source map has no sources: $sourceMapFile")
            val normalizedSources = sources.map { rawSource ->
                val source = rawSource as? String ?: error("Web source map contains a non-string source: $sourceMapFile")
                if (!absolutePathPattern.containsMatchIn(source)) {
                    source
                } else {
                    val workspaceMatch = buildWorkspacePattern.find(source)
                    if (workspaceMatch == null) {
                        source
                    } else {
                        val namespaceEnd = source.indexOf('/', startIndex = "webpack://".length)
                        val namespace = if (namespaceEnd >= 0) source.substring(0, namespaceEnd) else "webpack://magrathea-web-client"
                        "$namespace/third-party/${workspaceMatch.groupValues[1]}"
                    }
                }
            }
            sourceMap["sources"] = normalizedSources
            sourceMapFile.writeText(groovy.json.JsonOutput.toJson(sourceMap) + "\n")
            val leakedSource = normalizedSources.firstOrNull { source ->
                source.contains(rootDir.absolutePath) ||
                    source.contains(System.getProperty("user.home")) ||
                    absolutePathPattern.containsMatchIn(source)
            }
            check(leakedSource == null) {
                "Web source map exposes a local absolute path: $leakedSource"
            }
        }
    }
}

val assembleWebSdkPackage = tasks.register<Zip>("assembleWebSdkPackage") {
    group = "distribution"
    description = "Archive the verified local JS/TypeScript Web SDK package without publishing it."
    dependsOn(stageWebSdkPackage)
    from(webSdkPackageDirectory)
    archiveFileName.set("magrathea-web-client-${sdkVersion}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
}

val verifyWebSdkPackage = tasks.register("verifyWebSdkPackage") {
    group = "verification"
    description = "Validate Web package API, size, metadata, and absence of secrets/direct providers."
    dependsOn(stageWebSdkPackage, assembleWebSdkPackage, verifySdkSupplyChain)

    doLast {
        val licenseLedger = groovy.json.JsonSlurper().parse(webRuntimeLicenseLedger.asFile) as Map<*, *>
        val ledgerLicenses = licenseLedger["licenses"] as? Map<*, *>
            ?: error("Web runtime license ledger has no licenses")
        val expectedLicenseArtifacts = ledgerLicenses.values.map { rawDetails ->
            val details = rawDetails as? Map<*, *> ?: error("Invalid Web runtime license entry")
            val relativePath = details["file"] as? String ?: error("Web runtime license entry has no file")
            "LICENSES/${relativePath.substringAfterLast('/')}"
        }.toSet()
        val directory = webSdkPackageDirectory.get().asFile
        val bundle = directory.resolve("magrathea-web-client.js")
        val definitions = directory.resolve("magrathea-web-client.d.ts")
        val packageJsonFile = directory.resolve("package.json")
        (listOf(
            bundle,
            definitions,
            packageJsonFile,
            directory.resolve("LICENSE"),
            directory.resolve("README.md"),
            directory.resolve("THIRD_PARTY_NOTICES.txt"),
        ) + expectedLicenseArtifacts.map(directory::resolve))
            .forEach { artifact -> check(artifact.isFile && artifact.length() > 0) { "Missing Web package artifact: $artifact" } }
        check(directory.listFiles().orEmpty().any { it.name.matches(Regex("[0-9]+\\.js")) }) {
            "Web package is missing its production split chunk"
        }
        check(bundle.length() <= 1_450_000) {
            "Web production bundle exceeded 1,450,000 bytes: ${bundle.length()}"
        }

        val definitionsText = definitions.readText()
        listOf(
            "createMagratheaWebChatbot",
            "Promise<saien.magrathea.web.client.MagratheaWebChatSession>",
            "MagratheaWebChatModel",
            "MagratheaWebChatSnapshot",
            "MagratheaWebChatToolCall",
            "MagratheaWebChatCitation",
            "MagratheaWebChatAttachment",
        ).forEach { required -> check(required in definitionsText) { "TypeScript API is missing $required" } }
        listOf("setApiKey", "GeminiProviderAdapter", "OpenAiProviderAdapter", "Room").forEach { forbidden ->
            check(forbidden !in definitionsText) { "Forbidden direct/platform API leaked into TypeScript: $forbidden" }
        }

        val packageJson = groovy.json.JsonSlurper().parse(packageJsonFile) as Map<*, *>
        check(packageJson["name"] == "@saien/magrathea-web-client")
        check(packageJson["version"] == sdkVersion)
        check((packageJson["description"] as? String).orEmpty().isNotBlank())
        check(packageJson["main"] == bundle.name)
        check(packageJson["browser"] == bundle.name)
        check(packageJson["types"] == definitions.name)
        check(packageJson["license"] == "MIT")
        check(packageJson["homepage"] == "https://github.com/senseFy/magrathea#readme")
        val repository = packageJson["repository"] as? Map<*, *> ?: error("Web package repository metadata is missing")
        check(repository["type"] == "git")
        check(repository["url"] == "git+https://github.com/senseFy/magrathea.git")
        check(repository["directory"] == "magrathea-web-client")
        val bugs = packageJson["bugs"] as? Map<*, *> ?: error("Web package bugs metadata is missing")
        check(bugs["url"] == "https://github.com/senseFy/magrathea/issues")
        val keywords = packageJson["keywords"] as? List<*> ?: error("Web package keywords are missing")
        check(keywords.containsAll(listOf("agent", "chatbot", "kotlin", "multiplatform")))
        check("scripts" !in packageJson && "publishConfig" !in packageJson)

        val scannedText = directory.walkTopDown()
            .filter { it.isFile && (it.extension == "js" || it.extension == "map" || it.extension == "ts") }
            .joinToString("\n") { it.readText() }
        listOf(
            "server-only-e2e-provider-secret",
            "browser-auth-canary",
            "provider-secret-canary",
            "facade-secret",
            "e2e-browser-session",
            "e2e-csrf",
            "GeminiProviderAdapter",
            "OpenAiProviderAdapter",
        ).forEach { forbidden ->
            check(forbidden !in scannedText) { "Forbidden secret/direct-provider marker in Web package: $forbidden" }
        }

        val sourceMapFiles = directory.listFiles().orEmpty().filter { file -> file.name.endsWith(".js.map") }
        check(sourceMapFiles.isNotEmpty()) { "Web package is missing production source maps" }
        val bundledNpmPackages = mutableSetOf<String>()
        sourceMapFiles.forEach { sourceMapFile ->
            val sourceMap = groovy.json.JsonSlurper().parse(sourceMapFile) as? Map<*, *>
                ?: error("Invalid Web source map: $sourceMapFile")
            val sources = sourceMap["sources"] as? List<*>
                ?: error("Web source map has no sources: $sourceMapFile")
            sources.map { rawSource -> rawSource as? String ?: error("Non-string Web source map entry") }.forEach { source ->
                if ("/node_modules/" in source) {
                    val packagePath = source.substringAfterLast("/node_modules/")
                    val segments = packagePath.split('/')
                    val packageName = if (segments.firstOrNull()?.startsWith('@') == true) {
                        check(segments.size >= 2) { "Invalid scoped npm source path: $source" }
                        "${segments[0]}/${segments[1]}"
                    } else {
                        segments.firstOrNull().orEmpty()
                    }
                    check(packageName.isNotBlank()) { "Invalid npm source path: $source" }
                    bundledNpmPackages += packageName
                }
                if (
                    "/webpack/bootstrap" in source ||
                    "/webpack/runtime/" in source ||
                    "/webpack/startup" in source ||
                    "/webpack/universalModuleDefinition" in source
                ) {
                    bundledNpmPackages += "webpack"
                }
            }
        }
        val expectedNpmPackages = (licenseLedger["npmComponents"] as? List<*>)
            ?.map { rawComponent ->
                val component = rawComponent as? Map<*, *> ?: error("Invalid Web npm component entry")
                component["name"] as? String ?: error("Web npm component has no name")
            }
            ?.toSet()
            ?: error("Web runtime license ledger has no npm components")
        check(bundledNpmPackages == expectedNpmPackages) {
            "Bundled npm/generated runtime differs from the license ledger; " +
                "actual=$bundledNpmPackages reviewed=$expectedNpmPackages"
        }

        val expectedNpmInventory = (licenseLedger["npmComponents"] as List<*>).associate { rawComponent ->
            val component = rawComponent as Map<*, *>
            val name = component["name"] as String
            val version = component["version"] as String
            val role = component["role"] as String
            "$name@$version" to role
        }
        val normalizedSbom = groovy.json.JsonSlurper().parse(normalizedSdkSbom.get().asFile) as? Map<*, *>
            ?: error("Normalized SDK SBOM must be a JSON object")
        val sbomNpmInventory = (normalizedSbom["components"] as? List<*>)
            ?.mapNotNull { rawComponent ->
                val component = rawComponent as? Map<*, *> ?: return@mapNotNull null
                val properties = component["properties"] as? List<*> ?: return@mapNotNull null
                val role = properties.mapNotNull { rawProperty ->
                    val property = rawProperty as? Map<*, *> ?: return@mapNotNull null
                    if (property["name"] == "magrathea:webBundleRole") property["value"] as? String else null
                }.singleOrNull() ?: return@mapNotNull null
                val name = component["name"] as? String ?: error("Web SBOM component has no name")
                val version = component["version"] as? String ?: error("Web SBOM component $name has no version")
                "$name@$version" to role
            }
            ?.toMap()
            ?: error("Normalized SDK SBOM has no components")
        check(sbomNpmInventory == expectedNpmInventory) {
            "Normalized SDK SBOM differs from the bundled npm/generated runtime; " +
                "actual=$sbomNpmInventory reviewed=$expectedNpmInventory"
        }
        val licenseRows = sdkLicenseReport.get().asFile.readLines().drop(1).toSet()
        (licenseLedger["npmComponents"] as List<*>).forEach { rawComponent ->
            val component = rawComponent as Map<*, *>
            val name = component["name"] as String
            val version = component["version"] as String
            val licenseId = component["license"] as String
            val spdx = (ledgerLicenses[licenseId] as Map<*, *>)["spdx"] as String
            check("\t$name\t$version\t$spdx" in licenseRows) {
                "SDK license inventory is missing bundled Web component $name@$version"
            }
        }

        val noticesText = directory.resolve("THIRD_PARTY_NOTICES.txt").readText()
        (licenseLedger["components"] as List<*>).flatMap { rawComponent ->
            val component = rawComponent as Map<*, *>
            (component["coordinates"] as List<*>).map { it as String }
        }.forEach { coordinate ->
            check(coordinate in noticesText) { "Web third-party notices are missing $coordinate" }
        }
        (licenseLedger["npmComponents"] as List<*>).forEach { rawComponent ->
            val component = rawComponent as Map<*, *>
            val packageVersion = "${component["name"]}@${component["version"]}"
            check(packageVersion in noticesText) { "Web third-party notices are missing $packageVersion" }
        }

        val webArchive = assembleWebSdkPackage.get().archiveFile.get().asFile
        val archiveEntries = java.util.zip.ZipFile(webArchive).use { archive ->
            archive.entries().asSequence().map { entry -> entry.name }.toSet()
        }
        (setOf("THIRD_PARTY_NOTICES.txt") + expectedLicenseArtifacts).forEach { expected ->
            check(expected in archiveEntries) { "Web package archive is missing $expected" }
        }
    }
}

val verifyWebTypeScriptConsumer = tasks.register<Exec>("verifyWebTypeScriptConsumer") {
    group = "verification"
    description = "Compile a strict TypeScript consumer against the staged browser package."
    dependsOn(stageWebSdkPackage)
    commandLine(
        file("build/js/node_modules/.bin/tsc").absolutePath,
        "--project",
        file("tooling/web-package-consumer/tsconfig.json").absolutePath,
    )
}

val verifySdkMavenDistribution = tasks.register<Exec>("verifySdkMavenDistribution") {
    group = "verification"
    description = "Validate all 88 build-local Maven coordinates and their documentation artifacts."
    dependsOn(sdkPublishProjects.map { "$it:publishAllPublicationsToSdkVerificationRepository" })
    commandLine(
        "/bin/bash",
        file("scripts/verify-sdk-distribution").absolutePath,
        rootDir.absolutePath,
        sdkVersion,
    )
}

val verifySdkDistribution = tasks.register("verifySdkDistribution") {
    group = "verification"
    description = "Validate Maven artifacts and the browser package consumer boundary."
    dependsOn(verifySdkMavenDistribution, verifyWebSdkPackage, verifyWebTypeScriptConsumer)
}

val sdkReleaseBundle = tasks.register<Zip>("assembleSdkReleaseBundle") {
    group = "distribution"
    description = "Assemble the verified Maven and Web SDK artifacts with release metadata."
    dependsOn(verifySdkDistribution, assembleWebSdkPackage, verifySdkSupplyChain)
    archiveFileName.set("Magrathea-${sdkVersion}-release-bundle.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(sdkVerificationRepository) {
        into("maven")
    }
    from(assembleWebSdkPackage.flatMap { it.archiveFile }) {
        into("web")
    }
    from("LICENSE", "CHANGELOG.md")
    from("docs/known-issues.md") {
        into("docs")
    }
    from("docs/releases/v${sdkVersion}.md") {
        into("docs/releases")
    }
    from("docs/release-signing-key.asc") {
        into("docs")
    }
    from("samples/README.md") {
        into("samples")
    }
    from(normalizedSdkSbom) {
        into("supply-chain")
    }
    from(sdkLicenseReport) {
        into("supply-chain")
    }
}

val sdkReleaseBundleChecksum = layout.buildDirectory.file(
    "distributions/Magrathea-${sdkVersion}-release-bundle.zip.sha256",
)
val checksumSdkReleaseBundle = tasks.register("checksumSdkReleaseBundle") {
    group = "distribution"
    description = "Write the SHA-256 checksum for the verified release-candidate bundle."
    dependsOn(sdkReleaseBundle)
    inputs.file(sdkReleaseBundle.flatMap { it.archiveFile })
    outputs.file(sdkReleaseBundleChecksum)

    doLast {
        val bundle = sdkReleaseBundle.get().archiveFile.get().asFile
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        bundle.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val checksum = digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
        val output = sdkReleaseBundleChecksum.get().asFile
        output.parentFile.mkdirs()
        output.writeText("$checksum  ${bundle.name}\n")
    }
}

val verifySdkReleaseBundle = tasks.register("verifySdkReleaseBundle") {
    group = "verification"
    description = "Validate the release-candidate bundle layout and checksum."
    dependsOn(checksumSdkReleaseBundle)

    doLast {
        val bundle = sdkReleaseBundle.get().archiveFile.get().asFile
        val entries = java.util.zip.ZipFile(bundle).use { zip ->
            zip.entries().asSequence().map { it.name }.toSet()
        }
        listOf(
            "LICENSE",
            "CHANGELOG.md",
            "docs/known-issues.md",
            "docs/releases/v${sdkVersion}.md",
            "docs/release-signing-key.asc",
            "samples/README.md",
            "web/magrathea-web-client-${sdkVersion}.zip",
            "supply-chain/magrathea-sbom.cdx.json",
            "supply-chain/third-party-licenses.tsv",
        ).forEach { expected ->
            check(expected in entries) { "Release bundle is missing $expected" }
        }
        check(entries.any { it.startsWith("maven/saien/magrathea/magrathea-core/$sdkVersion/") }) {
            "Release bundle is missing the Maven repository"
        }
        val checksum = sdkReleaseBundleChecksum.get().asFile.readText().trim()
        check(checksum.endsWith("  ${bundle.name}")) { "Release bundle checksum has the wrong filename" }
        check(checksum.substringBefore("  ").matches(Regex("[0-9a-f]{64}"))) {
            "Release bundle checksum is not SHA-256"
        }
    }
}

tasks.register("prepareSdkRelease") {
    group = "distribution"
    description = "Assemble and validate signed release evidence after the exact commit passed CI."
    dependsOn(verifySdkModuleClassification)
    dependsOn(verifySdkPublicationMetadata)
    dependsOn(verifySdkPublicationArtifactIsolation)
    dependsOn(verifySdkReleaseBundle)

    doLast {
        check(System.getenv("MAGRATHEA_SIGNING_KEY").orEmpty().isNotBlank()) {
            "prepareSdkRelease requires MAGRATHEA_SIGNING_KEY"
        }
        val repository = sdkVerificationRepository.get().asFile
        publishingArtifactCoordinates(null).forEach { coordinate ->
            val (group, artifact, version) = coordinate.split(":")
            val pomSignature = repository.resolve(
                "${group.replace('.', '/')}/$artifact/$version/$artifact-$version.pom.asc",
            )
            check(pomSignature.isFile && pomSignature.length() > 0) {
                "Signed release evidence is missing ${pomSignature.relativeTo(repository)}"
            }
        }
    }
}

tasks.register("verifySdkLinux") {
    group = "verification"
    description = "Run the Linux-compatible JVM/Android SDK gate."
    dependsOn(verifySdkQuick)
    dependsOn(verifySdkPublicationMetadata)
    dependsOn(verifyKmpPublishedConsumerJvmAndroid)
    dependsOn(verifyPublishedAndroidConsumer)
    dependsOn(verifyJvmChatSample)
    dependsOn(verifySdkMavenDistribution)
    dependsOn(sdkKmpAndroidHostTestTasks)
    dependsOn(sdkPublishProjects.filterNot { it in sdkKmpPublishProjects }.map { "$it:assemble" })
    dependsOn(sdkKmpMobilePublishProjects.flatMap { listOf("$it:jvmJar", "$it:assembleAndroidMain") })
    dependsOn(sdkKmpWebOnlyPublishProjects.flatMap { listOf("$it:compileKotlinJs", "$it:compileKotlinWasmJs") })
    dependsOn(":provider-live-harness:assemble")
    dependsOn(":mcp-conformance-client:assemble")
}

val verifyKmpAppleTests = tasks.register("verifyKmpAppleTests") {
    group = "verification"
    description = "Compile the supported iOS device test variants and run all iOS Simulator tests."
    dependsOn(sdkKmpMobilePublishProjects.flatMap { projectPath ->
        listOf(
            "$projectPath:iosSimulatorArm64Test",
            "$projectPath:compileTestKotlinIosArm64",
        )
    })
}

tasks.register("verifySdkApple") {
    group = "verification"
    description = "Run KMP iOS tests and link the published graph through an isolated Apple consumer."
    dependsOn(verifyKmpPublishedConsumerApple)
    dependsOn(verifyKmpAppleTests)
}

val verifySdkWeb = tasks.register("verifySdkWeb") {
    group = "verification"
    description = "Run JS/Wasm browser contracts, the real-HTTP sample, and JS/TypeScript package gates."
    dependsOn(sdkWebBrowserTestTasks)
    dependsOn(verifyWebChatSample, verifyWebCrossBrowserRuntime)
    dependsOn(verifyWebSdkPackage, verifyWebTypeScriptConsumer)
}

tasks.register("verifySdkRelease") {
    group = "verification"
    description = "Run compatibility, platform tests, published consumers, assembly, and distribution gates."
    dependsOn(verifySdkQuick)
    dependsOn(verifySdkPublicationMetadata)
    dependsOn(verifyKmpPublishedConsumerJvmAndroid)
    dependsOn(verifyKmpPublishedConsumerApple)
    dependsOn(verifyPublishedAndroidConsumer)
    dependsOn(verifyJvmChatSample)
    dependsOn(verifySdkDistribution)
    dependsOn(verifySdkReleaseBundle)
    dependsOn(verifySdkWeb)
    dependsOn(sdkKmpAndroidHostTestTasks)
    dependsOn(sdkKmpMobilePublishProjects.map { "$it:iosSimulatorArm64Test" })
    dependsOn(sdkPublishProjects.map { "$it:assemble" })
    dependsOn(":provider-live-harness:assemble")
    dependsOn(":mcp-conformance-client:assemble")
}

tasks.register("publishSdkToMavenLocal") {
    group = "publishing"
    description = "Publish all Magrathea SDK modules to the local Maven repository."
    dependsOn(sdkPublishProjects.map { "$it:publishToMavenLocal" })
}

tasks.register("publishSdk") {
    group = "publishing"
    description = "Publish all Magrathea SDK modules to the configured Maven repository."
    dependsOn(sdkPublishProjects.map { "$it:publish" })
}

gradle.taskGraph.whenReady {
    if (gradle.startParameter.isDryRun) return@whenReady
    val remotePublishRequested = allTasks.any { task ->
        task.name == "publishSdk" ||
            task.name == "publish" ||
            (
                task.name.startsWith("publish") &&
                    task.name.contains("PublicationTo") &&
                    !task.name.contains("SdkVerificationRepository") &&
                    !task.name.contains("MavenLocal")
            )
    }
    if (remotePublishRequested) {
        check(System.getenv("MAGRATHEA_SIGNING_KEY").orEmpty().isNotBlank()) {
            "Remote publication requires MAGRATHEA_SIGNING_KEY"
        }
        check(findProperty("magrathea.publish.preflightVerified")?.toString() == "$sdkGroup:$sdkVersion") {
            "Remote publication must run the immutable-version preflight through scripts/publish-sdk"
        }
    }
}

fun Project.registerSdkJavadocJar(publication: MavenPublication) =
    tasks.register<Jar>("sdk${publication.name.replaceFirstChar { it.uppercase() }}JavadocJar") {
        group = "documentation"
        description = "Package Dokka HTML for the ${publication.name} publication."
        dependsOn("dokkaGeneratePublicationHtml")
        archiveBaseName.set(provider { publication.artifactId })
        archiveVersion.set(provider { publication.version })
        archiveClassifier.set("javadoc")
        destinationDirectory.set(layout.buildDirectory.dir("publication-docs/${publication.name}"))
        from(layout.buildDirectory.dir("dokka/html"))
    }

fun Project.configureSdkPublishing() {
    val sdkProject = this
    pluginManager.apply("maven-publish")
    tasks.withType<AbstractArchiveTask>().configureEach {
        from(rootProject.layout.projectDirectory.file("LICENSE")) {
            into("META-INF")
        }
    }
    // Dokka must observe the final Android/KMP plugin model. Applying it while the
    // root project is still configuring subprojects leaves AGP 9 source sets empty.
    afterEvaluate {
        pluginManager.apply("org.jetbrains.dokka")
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<JavaPluginExtension> {
            withSourcesJar()
        }
        configureMavenPublishing(
            componentName = "java",
            publicationName = "maven",
            configurePublication = { artifact(registerSdkJavadocJar(this)) },
        )
    }

    plugins.withId("com.android.library") {
        val sourcesJar = tasks.register<Jar>("releaseSourcesJar") {
            archiveClassifier.set("sources")
            from("src/main/java", "src/main/kotlin")
        }

        afterEvaluate {
            configureMavenPublishing(
                componentName = "release",
                publicationName = "release",
                configurePublication = {
                    artifact(sourcesJar)
                    artifact(registerSdkJavadocJar(this))
                },
            )
        }
    }

    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<PublishingExtension> {
            publications.withType(MavenPublication::class.java).configureEach {
                groupId = sdkProject.group.toString()
                version = sdkProject.version.toString()
                configurePom(sdkProject)
                artifact(sdkProject.registerSdkJavadocJar(this))
            }
            configureSdkRepositories(sdkProject, includeVerificationRepository = true)
        }
        configureOptionalSigning()
    }
}

fun Project.configureMavenPublishing(
    componentName: String,
    publicationName: String,
    configurePublication: MavenPublication.() -> Unit = {},
) {
    extensions.configure<PublishingExtension> {
        publications {
            if (findByName(publicationName) == null) {
                create(publicationName, MavenPublication::class.java) {
                    groupId = project.group.toString()
                    artifactId = project.name
                    version = project.version.toString()
                    from(components[componentName])
                    configurePublication()
                    configurePom(project)
                }
            }
        }

        configureSdkRepositories(project, includeVerificationRepository = true)
    }

    configureOptionalSigning()
}

fun PublishingExtension.configureSdkRepositories(
    sourceProject: Project,
    includeVerificationRepository: Boolean = false,
) {
    repositories {
        maven {
            name = sourceProject.publishProperty("repositoryName", "GitHubPackages")
            url = sourceProject.uri(
                sourceProject.publishProperty(
                    "repositoryUrl",
                    "https://maven.pkg.github.com/sensefy/magrathea",
                )
            )
            credentials {
                username = System.getenv("MAGRATHEA_PUBLISH_USERNAME")
                    ?: System.getenv("GITHUB_SAIEN_MAVEN_USERNAME").orEmpty()
                password = System.getenv("MAGRATHEA_PUBLISH_PASSWORD")
                    ?: System.getenv("GITHUB_SAIEN_MAVEN_TOKEN").orEmpty()
            }
        }
        if (includeVerificationRepository) {
            maven {
                name = "SdkVerification"
                url = sourceProject.rootProject.layout.buildDirectory.dir("sdk-verification-repository").get().asFile.toURI()
            }
        }
    }
}

fun MavenPublication.configurePom(sourceProject: Project) {
    pom {
        name.set(sourceProject.name)
        description.set(
            "Magrathea SDK module ${sourceProject.name}: multiplatform agent runtime components."
        )
        url.set(pomProjectUrl)
        licenses {
            license {
                name.set(sourceProject.publishProperty("licenseName", "MIT License"))
                url.set(sourceProject.publishProperty("licenseUrl", "https://opensource.org/license/mit"))
            }
        }
        developers {
            developer {
                id.set(sourceProject.publishProperty("developerId", "senseFy"))
                name.set(sourceProject.publishProperty("developerName", "senseFy"))
            }
        }
        scm {
            connection.set(
                sourceProject.publishProperty(
                    "scmConnection",
                    "scm:git:https://github.com/senseFy/magrathea.git",
                )
            )
            developerConnection.set(
                sourceProject.publishProperty(
                    "scmDeveloperConnection",
                    "scm:git:https://github.com/senseFy/magrathea.git",
                )
            )
            url.set(sourceProject.publishProperty("scmUrl", pomProjectUrl))
        }
    }
}

fun Project.configureOptionalSigning() {
    val signingKey = System.getenv("MAGRATHEA_SIGNING_KEY").orEmpty()
    if (signingKey.isBlank()) return

    pluginManager.apply("signing")
    extensions.configure<SigningExtension> {
        val signingPassword = System.getenv("MAGRATHEA_SIGNING_PASSWORD").orEmpty()
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(extensions.getByType(PublishingExtension::class.java).publications)
    }
}

fun Project.publishProperty(name: String, defaultValue: String): String {
    return findProperty("magrathea.publish.$name")?.toString()
        ?: findProperty("magrathea.$name")?.toString()
        ?: defaultValue
}
