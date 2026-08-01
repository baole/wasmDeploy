plugins {
    `java-gradle-plugin`
    id("wasmdeploy.kotlin")
    id("wasmdeploy.publishing")
    alias(libs.plugins.gradle.publish)
}

group = "io.github.baole"
version = libs.versions.wasmdeploy.get()
description = "Packages Kotlin/Wasm production output into a cache-safe deployment release."

repositories {
    gradlePluginPortal()
    mavenCentral()
}

gradlePlugin {
    website.set("https://github.com/baole/wasmDeploy")
    vcsUrl.set("https://github.com/baole/wasmDeploy.git")

    plugins {
        create("wasmDeploy") {
            id = "io.github.baole.wasmdeploy"
            implementationClass = "io.github.baole.wasmdeploy.WasmDeployPlugin"
            displayName = "Wasm Deploy Optimizer"
            description = "Packages Kotlin/Wasm production output into a cache-safe deployment release."
            tags.set(listOf("kotlin", "wasm", "deployment", "gradle-plugin"))
        }
        create("wasmDeploySettings") {
            id = "io.github.baole.wasmdeploy.settings"
            implementationClass = "io.github.baole.wasmdeploy.WasmDeploySettingsPlugin"
            displayName = "Wasm Deploy Optimizer Settings"
            description = "Configures the Kotlin/Wasm tool distribution repositories required by Wasm Deploy Optimizer."
            tags.set(listOf("kotlin", "wasm", "deployment", "gradle-plugin"))
        }
    }
}

dependencies {
    implementation("com.github.node-gradle:gradle-node-plugin:7.1.0")
    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
}

tasks.test {
    useJUnitPlatform()
}
