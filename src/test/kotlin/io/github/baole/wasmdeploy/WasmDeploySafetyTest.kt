package io.github.baole.wasmdeploy

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WasmDeploySafetyTest {

    @Test
    fun `optimize rejects filesystem root`() {
        val source = createTempDirectory("wasm-deploy-source")
        val rootPath = source.toAbsolutePath().root ?: Path.of("/")
        assertFailsWith<IllegalArgumentException> {
            WasmDeploymentPipeline.optimize(source, rootPath)
        }
    }

    @Test
    fun `optimize rejects user home directory`() {
        val source = createTempDirectory("wasm-deploy-source")
        val userHome = Path.of(System.getProperty("user.home"))
        assertFailsWith<IllegalArgumentException> {
            WasmDeploymentPipeline.optimize(source, userHome)
        }
    }

    @Test
    fun `optimize rejects output outside build directory by default`() {
        val projectDir = createTempDirectory("wasm-deploy-project")
        val buildDir = projectDir.resolve("build").createDirectories()
        val source = buildDir.resolve("dist").createDirectories()

        val externalOutput = createTempDirectory("wasm-deploy-external")

        assertFailsWith<IllegalArgumentException> {
            WasmDeploymentPipeline.optimize(
                source = source,
                destination = externalOutput,
                projectBuildDir = buildDir,
                allowExternalOutputDirectory = false,
            )
        }
    }

    @Test
    fun `optimize allows external output directory when explicitly enabled`() {
        val projectDir = createTempDirectory("wasm-deploy-project")
        val buildDir = projectDir.resolve("build").createDirectories()
        val source = buildDir.resolve("dist").createDirectories()
        source.resolve("index.html").writeText("<script src=\"./main.js\"></script>")
        source.resolve("main.js").writeText("const imports = {}")
        source.resolve("app.wasm").writeBytes(dummyWasmHeader())

        val externalOutput = createTempDirectory("wasm-deploy-external")

        WasmDeploymentPipeline.optimize(
            source = source,
            destination = externalOutput,
            projectBuildDir = buildDir,
            allowExternalOutputDirectory = true,
        )

        assertTrue(externalOutput.resolve("wasm-deploy-manifest.json").exists())
    }

    @Test
    fun `failed optimization leaves prior valid release intact`() {
        val source = createTempDirectory("wasm-deploy-source")
        val release = createTempDirectory("wasm-deploy-release")

        // Populate valid existing release
        release.resolve("prior-file.txt").writeText("prior release content")

        // Prepare invalid source (missing Wasm binary)
        source.resolve("index.html").writeText("<script src=\"main.js\"></script>")

        assertFailsWith<IllegalArgumentException> {
            WasmDeploymentPipeline.optimize(source, release)
        }

        // Prior release file must remain untouched
        assertTrue(release.resolve("prior-file.txt").exists())
        assertTrue(release.resolve("prior-file.txt").readText() == "prior release content")
    }

    private fun dummyWasmHeader(): ByteArray =
        byteArrayOf(0, 97, 115, 109, 1, 0, 0, 0)
}
