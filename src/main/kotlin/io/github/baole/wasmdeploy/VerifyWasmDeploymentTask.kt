package io.github.baole.wasmdeploy

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Verification reports failures but does not produce a reusable output.")
abstract class VerifyWasmDeploymentTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val releaseDirectory: DirectoryProperty

    @get:Input
    abstract val forbiddenJavaScriptPatterns: ListProperty<String>

    @TaskAction
    fun verify() {
        WasmDeploymentPipeline.verify(
            release = releaseDirectory.get().asFile.toPath(),
            forbiddenJavaScriptPatterns = forbiddenJavaScriptPatterns.getOrElse(emptyList()),
        )
    }
}
