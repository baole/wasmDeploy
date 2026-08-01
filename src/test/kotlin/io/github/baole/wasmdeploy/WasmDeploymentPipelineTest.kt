package io.github.baole.wasmdeploy

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WasmDeploymentPipelineTest {
    @Test
    fun `optimize fingerprints artifacts rewrites references and verifies Wasm imports`() {
        val source = createTempDirectory("wasm-deploy-source")
        val release = createTempDirectory("wasm-deploy-release")
        val assets = Files.createDirectories(source.resolve("assets"))
        source.resolve("index.html").writeText("<a href=\"/\">Trang chủ</a><script src=\"./assets/main.js\"></script>")
        assets.resolve("main.js").writeText(
            """
            const moduleUrl = new URL("./app.wasm", import.meta.url)
            const imports = { "fixture.import": () => Unit }
            """.trimIndent(),
        )
        assets.resolve("app.wasm").writeBytes(wasmWithJsCodeImport("fixture.import"))

        WasmDeploymentPipeline.optimize(source, release)

        assertTrue(release.resolve("wasm-deploy-manifest.json").exists())
        assertFalse(release.resolve("assets/main.js").exists())
        assertFalse(release.resolve("assets/app.wasm").exists())
        val hashedBundle = filesIn(release).single { it.name.matches(Regex("main\\.[0-9a-f]{16}\\.js")) }
        val hashedWasm = filesIn(release).single { it.name.matches(Regex("app\\.[0-9a-f]{16}\\.wasm")) }
        assertTrue(hashedBundle.readText().contains(hashedWasm.name))

        WasmDeploymentPipeline.verify(release)
    }

    @Test
    fun `verify rejects a Wasm JavaScript import that is absent from the loader`() {
        val source = createTempDirectory("wasm-deploy-source")
        val release = createTempDirectory("wasm-deploy-release")
        source.resolve("main.js").writeText("const imports = { \"different.import\": () => Unit }")
        source.resolve("app.wasm").writeBytes(wasmWithJsCodeImport("fixture.import"))

        WasmDeploymentPipeline.optimize(source, release)

        assertFailsWith<IllegalArgumentException> {
            WasmDeploymentPipeline.verify(release)
        }
    }

    @Test
    fun `optimize preserves Kotlin webpack assets that are already fingerprinted`() {
        val source = createTempDirectory("wasm-deploy-source")
        val release = createTempDirectory("wasm-deploy-release")
        val bundleName = "main.0123456789abcdef.js"
        val wasmName = "app.0123456789abcdef.wasm"
        source.resolve("index.html").writeText("<script src=\"/$bundleName\"></script>")
        source.resolve(bundleName).writeText("const imports = { \"fixture.import\": () => Unit }")
        source.resolve(wasmName).writeBytes(wasmWithJsCodeImport("fixture.import"))

        WasmDeploymentPipeline.optimize(source, release)

        assertTrue(release.resolve(bundleName).exists())
        assertTrue(release.resolve(wasmName).exists())
        WasmDeploymentPipeline.verify(release)
    }

    @Test
    fun `verify rejects source maps and unmanaged deploy artifacts`() {
        val source = createTempDirectory("wasm-deploy-source")
        val release = createTempDirectory("wasm-deploy-release")
        source.resolve("main.js").writeText("const imports = { \"fixture.import\": () => Unit }")
        source.resolve("app.wasm").writeBytes(wasmWithJsCodeImport("fixture.import"))

        WasmDeploymentPipeline.optimize(source, release)
        release.resolve("leak.map").writeText("{}")
        assertFailsWith<IllegalArgumentException> { WasmDeploymentPipeline.verify(release) }

        release.resolve("leak.map").toFile().delete()
        release.resolve("unmanaged.js").writeText("console.log('unexpected')")
        assertFailsWith<IllegalArgumentException> { WasmDeploymentPipeline.verify(release) }
    }

    @Test
    fun `verify rejects a missing local asset reference and an unreferenced entry point`() {
        val source = createTempDirectory("wasm-deploy-source")
        val release = createTempDirectory("wasm-deploy-release")
        source.resolve("index.html").writeText("<script src=\"./main.js\"></script>")
        source.resolve("main.js").writeText(
            "const missing = new URL('./missing.css', import.meta.url); const imports = { \"fixture.import\": () => Unit }",
        )
        source.resolve("app.wasm").writeBytes(wasmWithJsCodeImport("fixture.import"))

        WasmDeploymentPipeline.optimize(source, release)
        assertFailsWith<IllegalArgumentException> { WasmDeploymentPipeline.verify(release) }

        val releaseWithBadEntry = createTempDirectory("wasm-deploy-release")
        source.resolve("main.js").writeText("const imports = { \"fixture.import\": () => Unit }")
        WasmDeploymentPipeline.optimize(source, releaseWithBadEntry)
        releaseWithBadEntry.resolve("index.html").writeText("<main>missing bootstrap</main>")
        assertFailsWith<IllegalArgumentException> { WasmDeploymentPipeline.verify(releaseWithBadEntry) }
    }

    @Test
    fun `verify ignores JavaScript diagnostic strings that merely end in an asset extension`() {
        val source = createTempDirectory("wasm-deploy-source")
        val release = createTempDirectory("wasm-deploy-release")
        source.resolve("index.html").writeText("<script src=\"./main.js\"></script>")
        source.resolve("main.js").writeText(
            "const message = 'Could not load sql.js'; const imports = { \"fixture.import\": () => Unit }",
        )
        source.resolve("app.wasm").writeBytes(wasmWithJsCodeImport("fixture.import"))

        WasmDeploymentPipeline.optimize(source, release)

        WasmDeploymentPipeline.verify(release)
    }

    @Test
    fun `verify enforces configured JavaScript deployment policy`() {
        val source = createTempDirectory("wasm-deploy-source")
        val release = createTempDirectory("wasm-deploy-release")
        source.resolve("main.js").writeText("eval(''); const imports = { \"fixture.import\": () => Unit }")
        source.resolve("app.wasm").writeBytes(wasmWithJsCodeImport("fixture.import"))

        WasmDeploymentPipeline.optimize(source, release)

        assertFailsWith<IllegalArgumentException> {
            WasmDeploymentPipeline.verify(release, forbiddenJavaScriptPatterns = listOf("eval("))
        }
    }

    private fun wasmWithJsCodeImport(importName: String): ByteArray {
        val module = "js_code".encodeToByteArray()
        val name = importName.encodeToByteArray()
        val importPayload = byteArrayOf(1, module.size.toByte()) + module + byteArrayOf(name.size.toByte()) + name + byteArrayOf(0, 0)
        return byteArrayOf(0, 97, 115, 109, 1, 0, 0, 0, 1, 4, 1, 0x60, 0, 0, 2, importPayload.size.toByte()) + importPayload
    }

    private fun filesIn(directory: java.nio.file.Path) = Files.walk(directory).use { paths ->
        paths.iterator().asSequence().toList()
    }
}
