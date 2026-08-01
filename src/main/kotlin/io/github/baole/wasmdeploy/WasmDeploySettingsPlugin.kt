package io.github.baole.wasmdeploy

import org.gradle.api.Plugin
import org.gradle.api.Action
import org.gradle.api.artifacts.repositories.IvyArtifactRepository
import org.gradle.api.initialization.Settings
import org.gradle.api.initialization.resolve.RepositoriesMode

/**
 * Makes Kotlin/Wasm's Node, Yarn, and Binaryen tool distributions available
 * when Gradle resolves dependencies exclusively from settings repositories.
 */
class WasmDeploySettingsPlugin : Plugin<Settings> {
    override fun apply(settings: Settings) {
        settings.addIvyDistribution(
            name = nodeRepositoryName,
            url = "https://nodejs.org/dist",
            artifactPattern = "v[revision]/[artifact](-v[revision]-[classifier]).[ext]",
            group = "org.nodejs",
            module = "node",
        )
        settings.addIvyDistribution(
            name = yarnRepositoryName,
            url = "https://github.com/yarnpkg/yarn/releases/download",
            artifactPattern = "v[revision]/[artifact]-v[revision].[ext]",
            group = "com.yarnpkg",
            module = "yarn",
        )
        settings.addIvyDistribution(
            name = binaryenRepositoryName,
            url = "https://github.com/WebAssembly/binaryen/releases/download",
            artifactPattern = "version_[revision]/[artifact]-version_[revision]-[classifier].[ext]",
            group = "com.github.webassembly",
            module = "binaryen",
        )
        val forcePreferSettings = settings.providers.gradleProperty("wasmdeploy.strictRepositoriesMode")
            .map { it.toBoolean() }
            .orElse(false)
            .get()
        if (forcePreferSettings) {
            settings.gradle.settingsEvaluated { evaluatedSettings ->
                evaluatedSettings.dependencyResolutionManagement.repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
            }
        }
    }

    private fun Settings.addIvyDistribution(
        name: String,
        url: String,
        artifactPattern: String,
        group: String,
        module: String,
    ) {
        dependencyResolutionManagement.repositories.ivy(Action<IvyArtifactRepository> { repository ->
            repository.name = name
            repository.setUrl(url)
            repository.patternLayout(Action { layout -> layout.artifact(artifactPattern) })
            repository.metadataSources(Action { sources -> sources.artifact() })
            repository.content(Action { content -> content.includeModule(group, module) })
        })
    }

    private companion object {
        const val nodeRepositoryName = "wasmDeploy Node.js"
        const val yarnRepositoryName = "wasmDeploy Yarn"
        const val binaryenRepositoryName = "wasmDeploy Binaryen"
    }
}
