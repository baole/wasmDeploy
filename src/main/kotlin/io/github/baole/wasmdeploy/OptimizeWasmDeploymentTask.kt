package io.github.baole.wasmdeploy

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class OptimizeWasmDeploymentTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Input
    abstract val allowExternalOutputDirectory: Property<Boolean>

    @get:Internal
    abstract val projectBuildDirectory: DirectoryProperty

    @get:Input
    abstract val compressionEnabled: Property<Boolean>

    @get:Input
    abstract val compressionBrotli: Property<Boolean>

    @get:Input
    abstract val compressionGzip: Property<Boolean>

    @get:Input
    abstract val compressionLevel: Property<Int>

    @get:Input
    abstract val compressionIncludes: ListProperty<String>

    @get:Input
    abstract val compressionExcludes: ListProperty<String>

    @TaskAction
    fun optimize() {
        val compressionOptions = PipelineCompressionOptions(
            enabled = compressionEnabled.getOrElse(false),
            brotli = compressionBrotli.getOrElse(true),
            gzip = compressionGzip.getOrElse(false),
            level = compressionLevel.getOrElse(9),
            includes = compressionIncludes.getOrElse(emptyList()),
            excludes = compressionExcludes.getOrElse(emptyList()),
        )
        WasmDeploymentPipeline.optimize(
            source = inputDirectory.get().asFile.toPath(),
            destination = outputDirectory.get().asFile.toPath(),
            projectBuildDir = projectBuildDirectory.orNull?.asFile?.toPath(),
            allowExternalOutputDirectory = allowExternalOutputDirectory.getOrElse(false),
            compressionOptions = compressionOptions,
        )
    }
}
