package io.github.baole.wasmdeploy

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Action
import org.gradle.api.tasks.TaskProvider
import com.github.gradle.node.NodeExtension
import com.github.gradle.node.npm.task.NpmTask

class WasmDeployPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("wasmDeploy", WasmDeployExtension::class.java)
        extension.inputDirectory = "build/dist/wasmJs/productionExecutable"
        extension.outputDirectory = "build/wasmDeploy/release"
        extension.staticResources = listOf("src/wasmJsMain/resources")
        val toolsDirectory = project.layout.buildDirectory.dir("wasmDeploy/tools")

        project.pluginManager.apply("com.github.node-gradle.node")
        project.extensions.configure(NodeExtension::class.java) { node ->
            node.version.set("22.14.0")
            node.download.set(true)
            node.nodeProjectDir.set(toolsDirectory)
        }

        val prepareTools = project.tasks.register(
            "wasmDeployPrepareTools",
            PrepareWasmDeployToolsTask::class.java,
            Action { task ->
                task.group = "wasm deploy"
                task.description = "Extracts wasmDeploy's pinned JavaScript and CSS minification tools."
                task.toolsDirectory.set(toolsDirectory)
                task.toolVersion.set(embeddedToolFingerprint())
            },
        )
        val installTools = project.tasks.register(
            "wasmDeployInstallTools",
            NpmTask::class.java,
            Action { task ->
                task.group = "wasm deploy"
                task.description = "Installs wasmDeploy's pinned JavaScript and CSS minification tools."
                task.dependsOn(prepareTools)
                task.npmCommand.set(listOf("ci", "--ignore-scripts"))
            },
        )
        val minify = project.tasks.register(
            "wasmDeployMinify",
            NpmTask::class.java,
            Action { task ->
                task.group = "wasm deploy"
                task.description = "Copies and minifies static JavaScript and CSS assets for the Wasm distribution."
                task.dependsOn(installTools)
            },
        )
        val prepareOptimizedArtifacts = project.tasks.register(
            "wasmDeployPrepareOptimizedKotlinArtifacts",
            PrepareOptimizedKotlinWasmArtifactsTask::class.java,
            Action { task ->
                task.group = "wasm deploy"
                task.description = "Selects Kotlin compiler optimized Wasm artifacts before Webpack packaging."
            },
        )
        project.afterEvaluate {
            minify.configure { task ->
                task.npmCommand.set(
                    listOf(
                            "run",
                            "minify",
                        "--",
                        project.file(extension.inputDirectory).absolutePath,
                        ) + configuredStaticResources(project, extension).flatMap { listOf("--resource", it.absolutePath) } +
        extension.additionalAssets.flatMap { listOf("--asset", project.file(it).absolutePath) },
                    )
            }
        }

        val optimize: TaskProvider<OptimizeWasmDeploymentTask> = project.tasks.register(
            "wasmDeployOptimize",
            OptimizeWasmDeploymentTask::class.java,
            Action { task ->
                task.group = "wasm deploy"
                task.description = "Packages fingerprinted, cache-safe Wasm deployment artifacts."
                task.inputDirectory.set(directoryProvider(project, extension.inputDirectory))
                task.outputDirectory.set(directoryProvider(project, extension.outputDirectory))
                task.allowExternalOutputDirectory.set(project.provider { extension.allowExternalOutputDirectory })
                task.projectBuildDirectory.set(project.layout.buildDirectory)
                task.compressionEnabled.set(project.provider { extension.compression.enabled })
                task.compressionBrotli.set(project.provider { extension.compression.brotli })
                task.compressionGzip.set(project.provider { extension.compression.gzip })
                task.compressionLevel.set(project.provider { extension.compression.level })
                task.compressionIncludes.set(project.provider { extension.compression.includes })
                task.compressionExcludes.set(project.provider { extension.compression.excludes })
                task.dependsOn(minify)
            },
        )
        val verify: TaskProvider<VerifyWasmDeploymentTask> = project.tasks.register(
            "wasmDeployVerify",
            VerifyWasmDeploymentTask::class.java,
            Action { task ->
                task.group = "verification"
                task.description = "Verifies the optimized Wasm deployment artifacts."
                task.releaseDirectory.set(directoryProvider(project, extension.outputDirectory))
                task.forbiddenJavaScriptPatterns.set(project.provider { extension.forbiddenJavaScriptPatterns })
                task.dependsOn(optimize)
            },
        )
        val verifyWasmImports = project.tasks.register(
            "wasmDeployVerifyWasmImports",
            NpmTask::class.java,
            Action { task ->
                task.group = "verification"
                task.description = "Validates Wasm imports with the host JavaScript runtime parser."
                task.dependsOn(installTools, optimize)
            },
        )
        val verifyBudget = project.tasks.register(
            "wasmDeployVerifySizeBudget",
            NpmTask::class.java,
            Action { task ->
                task.group = "verification"
                task.description = "Verifies optional Brotli-compressed Wasm size budgets."
                task.dependsOn(installTools, optimize)
            },
        )
        project.afterEvaluate {
            verifyBudget.configure { task ->
                task.onlyIf { extension.defaultMaxWasmBrotliBytes != null || extension.maxTotalWasmBrotliBytes != null }
                if (extension.defaultMaxWasmBrotliBytes != null || extension.maxTotalWasmBrotliBytes != null) {
                    task.npmCommand.set(
                        listOf(
                            "run",
                            "verify-budget",
                            "--",
                            project.file(extension.outputDirectory).absolutePath,
                            extension.defaultMaxWasmBrotliBytes?.toString().orEmpty(),
                            extension.maxTotalWasmBrotliBytes?.toString().orEmpty(),
                        ) + extension.wasmBrotliBudgets().flatMap { budget ->
                            listOf("--file", budget.pattern, budget.maximumBytes.toString())
                        },
                    )
                }
            }
            verify.configure { it.dependsOn(verifyBudget) }
            verifyWasmImports.configure { task ->
                task.npmCommand.set(
                    listOf("run", "verify-wasm-imports", "--", project.file(extension.outputDirectory).absolutePath),
                )
            }
            verify.configure { it.dependsOn(verifyWasmImports) }
        }
        project.tasks.register("wasmDeployRelease", Action { task ->
            task.group = "wasm deploy"
            task.description = "Builds and verifies an optimized Wasm deployment release."
            task.dependsOn(verify)
        })

        wireKotlinWasmDistribution(project, minify, verify, prepareOptimizedArtifacts, extension)
    }

    private fun wireKotlinWasmDistribution(
        project: Project,
        minify: TaskProvider<NpmTask>,
        verify: TaskProvider<VerifyWasmDeploymentTask>,
        prepareOptimizedArtifacts: TaskProvider<PrepareOptimizedKotlinWasmArtifactsTask>,
        extension: WasmDeployExtension,
    ) {
        project.afterEvaluate {
            val candidateDistributionTaskNames = listOf(
                "wasmJsBrowserDistribution",
                "wasmJsBrowserProductionDistribution",
                "wasmBrowserDistribution",
                "wasmBrowserProductionDistribution",
            )
            val discoveredDistributionTasks = candidateDistributionTaskNames
                .mapNotNull(project.tasks::findByName)
                .ifEmpty {
                    project.tasks.filter { task ->
                        val name = task.name.lowercase()
                        name.contains("wasm") && name.contains("distribution")
                    }
                }
            val releaseDistribution = discoveredDistributionTasks.firstOrNull { it.name == "wasmJsBrowserDistribution" }
                ?: discoveredDistributionTasks.firstOrNull()

            if (releaseDistribution != null) minify.configure { it.dependsOn(releaseDistribution) }
            if (extension.finalizeKotlinWasmDistribution) discoveredDistributionTasks.forEach { it.finalizedBy(verify) }

            val compileTask = project.tasks.findByName(extension.kotlinWasmCompileTaskName)
                ?: project.tasks.firstOrNull { task ->
                    val name = task.name.lowercase()
                    name.contains("wasm") && name.contains("compile") && name.contains("sync")
                }
            val webpackTask = project.tasks.findByName(extension.kotlinWasmWebpackTaskName)
                ?: project.tasks.firstOrNull { task ->
                    val name = task.name.lowercase()
                    name.contains("wasm") && name.contains("webpack")
                }

            if (extension.useOptimizedKotlinWasmArtifacts && webpackTask != null && compileTask != null) {
                prepareOptimizedArtifacts.configure { task ->
                    task.compilationDirectory.set(directoryProvider(project, extension.kotlinWasmCompilationDirectory))
                    task.packagesDirectory.set(directoryProvider(project, extension.kotlinWasmPackagesDirectory))
                    task.dependsOn(compileTask)
                }
                webpackTask.dependsOn(prepareOptimizedArtifacts)
            }

            project.logger.info(
                "[wasmDeploy] Discovered Kotlin/Wasm tasks: compile=${compileTask?.name}, webpack=${webpackTask?.name}, distributions=${discoveredDistributionTasks.map { it.name }}",
            )

            if (extension.useOptimizedKotlinWasmArtifacts && extension.strictOptimizedKotlinWasmArtifacts && discoveredDistributionTasks.isNotEmpty()) {
                require(compileTask != null) {
                    "Kotlin/Wasm compile task was not found: ${extension.kotlinWasmCompileTaskName}"
                }
                require(webpackTask != null) {
                    "Kotlin/Wasm Webpack task was not found: ${extension.kotlinWasmWebpackTaskName}"
                }
            }
        }
    }

    private fun configuredStaticResources(project: Project, extension: WasmDeployExtension): List<java.io.File> =
        extension.staticResources.map(project::file)

    private fun directoryProvider(project: Project, path: String) =
        project.layout.dir(project.provider { project.file(path) })

    private fun embeddedToolFingerprint(): String =
        javaClass.protectionDomain.codeSource.location.toExternalForm()
}
