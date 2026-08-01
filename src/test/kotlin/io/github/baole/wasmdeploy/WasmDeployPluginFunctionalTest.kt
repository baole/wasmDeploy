package io.github.baole.wasmdeploy

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import org.gradle.testkit.runner.GradleRunner
import kotlin.test.Test
import kotlin.test.assertTrue

class WasmDeployPluginFunctionalTest {
    @Test
    fun `settings plugin provides Kotlin Wasm distribution repositories through settings`() {
        val project = Files.createTempDirectory("wasm-deploy-settings-functional")
        project.resolve("gradle.properties").writeText("wasmdeploy.strictRepositoriesMode=true")
        project.resolve("settings.gradle.kts").writeText(
            """
            import org.gradle.api.initialization.resolve.RepositoriesMode

            plugins { id("io.github.baole.wasmdeploy.settings") }

            rootProject.name = "fixture"
            dependencyResolutionManagement {
                repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
            }
            gradle.settingsEvaluated {
                check(dependencyResolutionManagement.repositoriesMode.get() == RepositoriesMode.PREFER_SETTINGS)
                check(dependencyResolutionManagement.repositories.findByName("wasmDeploy Node.js") != null)
                check(dependencyResolutionManagement.repositories.findByName("wasmDeploy Yarn") != null)
                check(dependencyResolutionManagement.repositories.findByName("wasmDeploy Binaryen") != null)
            }
            """.trimIndent(),
        )
        project.resolve("build.gradle.kts").writeText("tasks.register(\"verifyFixture\")")

        val result = GradleRunner.create()
            .withProjectDir(project.toFile())
            .withPluginClasspath()
            .withArguments("verifyFixture")
            .build()

        assertTrue(result.output.contains("BUILD SUCCESSFUL"))
    }

    @Test
    fun `project plugin can be applied without a version after the settings plugin`() {
        val project = Files.createTempDirectory("wasm-deploy-combined-functional")
        project.resolve("settings.gradle.kts").writeText(
            """
            plugins { id("io.github.baole.wasmdeploy.settings") }
            rootProject.name = "fixture"
            """.trimIndent(),
        )
        project.resolve("build.gradle.kts").writeText(
            """
            plugins { id("io.github.baole.wasmdeploy") }

            tasks.register("wasmJsProductionExecutableCompileSync")
            tasks.register("wasmJsBrowserProductionWebpack")
            tasks.register("wasmJsBrowserDistribution")
            """.trimIndent(),
        )

        val result = GradleRunner.create()
            .withProjectDir(project.toFile())
            .withPluginClasspath()
            .withArguments("wasmJsBrowserDistribution", "--dry-run")
            .build()

        assertTrue(result.output.contains(":wasmDeployVerify SKIPPED"))
        assertTrue(result.output.contains("BUILD SUCCESSFUL"))
    }

    @Test
    fun `raw Kotlin Wasm distribution includes deployment finalizer without a task cycle`() {
        val project = Files.createTempDirectory("wasm-deploy-functional")
        project.resolve("settings.gradle.kts").writeText("rootProject.name = \"fixture\"")
        project.resolve("build.gradle.kts").writeText(
            """
            plugins { id("io.github.baole.wasmdeploy") }

            tasks.register("wasmJsProductionExecutableCompileSync")
            tasks.register("wasmJsBrowserProductionWebpack")
            tasks.register("wasmJsBrowserDistribution")
            """.trimIndent(),
        )

        val result = GradleRunner.create()
            .withProjectDir(project.toFile())
            .withPluginClasspath()
            .withArguments("wasmJsBrowserDistribution", "--dry-run")
            .build()

        assertTrue(result.output.contains(":wasmDeployVerify SKIPPED"))
        assertTrue(result.output.contains("BUILD SUCCESSFUL"))
    }

    @Test
    fun `strict optimized artifact wiring fails clearly when the configured task is absent`() {
        val project = Files.createTempDirectory("wasm-deploy-functional")
        project.resolve("settings.gradle.kts").writeText("rootProject.name = \"fixture\"")
        project.resolve("build.gradle.kts").writeText(
            """
            plugins { id("io.github.baole.wasmdeploy") }
            wasmDeploy {
                strictOptimizedKotlinWasmArtifacts = true
                kotlinWasmCompileTaskName = "missingCompile"
            }
            tasks.register("wasmJsBrowserDistribution")
            """.trimIndent(),
        )

        val result = GradleRunner.create()
            .withProjectDir(project.toFile())
            .withPluginClasspath()
            .withArguments("help")
            .buildAndFail()

        assertTrue(result.output.contains("Kotlin/Wasm compile task was not found: missingCompile"))
    }

    @Test
    fun `strict optimized artifact wiring does not fail when the Kotlin Wasm target is disabled`() {
        val project = Files.createTempDirectory("wasm-deploy-functional")
        project.resolve("settings.gradle.kts").writeText("rootProject.name = \"fixture\"")
        project.resolve("build.gradle.kts").writeText(
            """
            plugins { id("io.github.baole.wasmdeploy") }
            wasmDeploy {
                strictOptimizedKotlinWasmArtifacts = true
            }
            """.trimIndent(),
        )

        val result = GradleRunner.create()
            .withProjectDir(project.toFile())
            .withPluginClasspath()
            .withArguments("help")
            .build()

        assertTrue(result.output.contains("BUILD SUCCESSFUL"))
    }

    @Test
    fun `budget script discovers nested Wasm files and applies the final matching rule`() {
        val directory = Files.createTempDirectory("wasm-deploy-budget")
        directory.resolve("nested").createDirectories().resolve("runtime.wasm").writeBytes(ByteArray(1_024))
        val script = directory.resolve("verify-budget.mjs")
        javaClass.classLoader.getResourceAsStream("tools/scripts/verify-budget.mjs").use { input ->
            requireNotNull(input)
            Files.copy(input, script)
        }

        val success = ProcessBuilder("node", script.toString(), directory.toString(), "", "", "--file", "**/*.wasm", "100000")
            .redirectErrorStream(true)
            .start()
        assertTrue(success.waitFor() == 0)
        assertTrue(success.inputStream.bufferedReader().readText().contains("nested/runtime.wasm"))

        val failure = ProcessBuilder(
            "node", script.toString(), directory.toString(), "", "", "--file", "**/*.wasm", "100000",
            "--file", "nested/*.wasm", "1",
        ).redirectErrorStream(true).start()
        assertTrue(failure.waitFor() != 0)
        assertTrue(failure.inputStream.bufferedReader().readText().contains("nested/runtime.wasm"))
    }
}
