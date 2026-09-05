plugins {
    kotlin("jvm") version "2.4.0"
    application
}

val magratheaVersion = providers.gradleProperty("magrathea.version").orElse("0.1.0-alpha.11") // x-release-please-version
providers.gradleProperty("magrathea.consumer.buildDir").orNull?.let { consumerBuildDirectory ->
    layout.buildDirectory.set(file(consumerBuildDirectory))
}

configurations.configureEach {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("saien.magrathea:magrathea-runtime:${magratheaVersion.get()}") { isChanging = true }
    implementation("saien.magrathea:magrathea-chatbot:${magratheaVersion.get()}") { isChanging = true }
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

application {
    mainClass.set("saien.magrathea.samples.jvm.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
