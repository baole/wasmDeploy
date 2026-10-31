package io.github.baole.wasmdeploy

import java.nio.file.Files
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class VerifyWasmImportsScriptTest {
    @Test
    fun `verifier accepts a custom Kotlin Wasm bundle referenced by index`() {
        val release = Files.createTempDirectory("wasm-deploy-custom-bundle")
        val bundleHash = "0123456789abcdef"
        release.resolve("index.html").writeText("<script type=\"module\" data-src=\"/helper.$bundleHash.js\" src = \"/webApp.$bundleHash.js\"></script>")
        release.resolve("webApp.$bundleHash.js").writeText("const imports = { \"fixture.import\": () => {} }")
        release.resolve("helper.$bundleHash.js").writeText("console.log('static helper')")
        release.resolve("app.$bundleHash.wasm").writeBytes(wasmWithJsCodeImport("fixture.import"))

        assertVerifierSucceeds(release)
    }

    @Test
    fun `verifier finds the custom bundle by its Wasm reference when imports are empty`() {
        val release = Files.createTempDirectory("wasm-deploy-empty-imports")
        release.resolve("index.html").writeText("<script data-src=\"/helper.js\" src = \"/webApp.js\"></script>")
        release.resolve("webApp.js").writeText("const moduleUrl = new URL('./app.wasm', import.meta.url)")
        release.resolve("helper.js").writeText("console.log('static helper')")
        release.resolve("app.wasm").writeBytes(byteArrayOf(0, 97, 115, 109, 1, 0, 0, 0))

        assertVerifierSucceeds(release)
    }

    private fun assertVerifierSucceeds(release: java.nio.file.Path) {
        val script = Files.createTempFile("verify-wasm-imports", ".mjs")
        javaClass.classLoader.getResourceAsStream("tools/scripts/verify-wasm-imports.mjs").use { input ->
            requireNotNull(input)
            Files.copy(input, script, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        val process = ProcessBuilder("node", script.toString(), release.toString())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()

        assertEquals(0, process.waitFor(), output)
    }

    private fun wasmWithJsCodeImport(importName: String): ByteArray {
        val module = "js_code".encodeToByteArray()
        val name = importName.encodeToByteArray()
        val importPayload = byteArrayOf(1, module.size.toByte()) +
            module +
            byteArrayOf(name.size.toByte()) +
            name +
            byteArrayOf(0, 0)
        return byteArrayOf(0, 97, 115, 109, 1, 0, 0, 0, 1, 4, 1, 0x60, 0, 0, 2, importPayload.size.toByte()) + importPayload
    }
}
