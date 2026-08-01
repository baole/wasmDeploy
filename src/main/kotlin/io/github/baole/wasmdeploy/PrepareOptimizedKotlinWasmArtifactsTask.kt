package io.github.baole.wasmdeploy

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Selects compiler outputs produced by the current Kotlin/Wasm compilation.")
abstract class PrepareOptimizedKotlinWasmArtifactsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val compilationDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val packagesDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val compilation = compilationDirectory.get().asFile.toPath()
        val fallback = compilation.resolve("kotlin")
        val optimized = compilation.resolve("optimized")
        require(Files.isDirectory(fallback)) { "Kotlin/Wasm fallback artifacts are missing: $fallback" }
        val wasm = Files.list(fallback).use { entries ->
            entries.filter { it.isRegularFile() && it.extension == "wasm" }.toList().singleOrNull()
        } ?: error("Expected one Kotlin/Wasm binary in $fallback")
        val moduleName = wasm.fileName.toString().removeSuffix(".wasm")
        val target = packagesDirectory.get().asFile.toPath().resolve(moduleName).resolve("kotlin")
        Files.createDirectories(target)
        listOf(
            "$moduleName.import-object.mjs",
            "$moduleName.js-builtins.mjs",
            "$moduleName.mjs",
            "$moduleName.wasm",
            "$moduleName.wasm.map",
        ).forEach { name ->
            val source = optimized.resolve(name).takeIf { Files.exists(it) } ?: fallback.resolve(name)
            if (Files.exists(source)) {
                Files.copy(source, target.resolve(name), StandardCopyOption.REPLACE_EXISTING)
                logger.lifecycle("wasmDeploy selected ${if (source.startsWith(optimized)) "optimized" else "fallback"} Kotlin/Wasm artifact: $name")
            }
        }
    }
}
