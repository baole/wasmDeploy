package io.github.baole.wasmdeploy

import java.nio.file.Files
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Bundled tool resources are extracted locally for the Node toolchain.")
abstract class PrepareWasmDeployToolsTask : DefaultTask() {
    @get:Input
    abstract val toolVersion: Property<String>

    @get:OutputDirectory
    abstract val toolsDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val tools = toolsDirectory.get().asFile.toPath()
        val scripts = tools.resolve("scripts")
        Files.createDirectories(scripts)
        copyResource("tools/package.json", tools.resolve("package.json"))
        copyResource("tools/package-lock.json", tools.resolve("package-lock.json"))
        copyResource("tools/scripts/minify.mjs", scripts.resolve("minify.mjs"))
        copyResource("tools/scripts/verify-budget.mjs", scripts.resolve("verify-budget.mjs"))
        copyResource("tools/scripts/verify-wasm-imports.mjs", scripts.resolve("verify-wasm-imports.mjs"))
    }

    private fun copyResource(resource: String, target: java.nio.file.Path) {
        javaClass.classLoader.getResourceAsStream(resource).use { input ->
            requireNotNull(input) { "Missing bundled wasmDeploy resource: $resource" }
            Files.copy(input, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
