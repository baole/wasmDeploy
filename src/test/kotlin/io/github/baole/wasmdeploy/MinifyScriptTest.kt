package io.github.baole.wasmdeploy

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MinifyScriptTest {
    @Test
    fun `minifier rewrites a custom pre-fingerprinted bundle reference`() {
        val root = Files.createTempDirectory("wasm-deploy-minify")
        val scripts = root.resolve("tools/scripts").createDirectories()
        val cleanCss = root.resolve("tools/node_modules/clean-css").createDirectories()
        val terser = root.resolve("tools/node_modules/terser").createDirectories()
        val distribution = root.resolve("distribution").createDirectories()
        val resources = root.resolve("resources").createDirectories()
        val bundleName = "webApp.0123456789abcdef.js"
        val script = scripts.resolve("minify.mjs")

        javaClass.classLoader.getResourceAsStream("tools/scripts/minify.mjs").use { input ->
            requireNotNull(input)
            Files.copy(input, script)
        }
        cleanCss.resolve("package.json").writeText("{\"type\":\"module\",\"exports\":\"./index.js\"}")
        cleanCss.resolve("index.js").writeText("export default class CleanCSS { minify(input) { return { styles: input, errors: [] } } }")
        terser.resolve("package.json").writeText("{\"type\":\"module\",\"exports\":\"./index.js\"}")
        terser.resolve("index.js").writeText("export async function minify(input) { return { code: input } }")
        distribution.resolve("index.html").writeText("<script type=\"module\" data-src=\"./webApp.js\" src = \"./webApp.js?v=1\"></script>")
        distribution.resolve(bundleName).writeText("export {};")
        resources.resolve("style.css").writeText("body { color: black; }")

        val process = ProcessBuilder("node", script.toString(), distribution.toString(), "--resource", resources.toString())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()

        assertEquals(0, process.waitFor(), output)
        assertTrue(distribution.resolve("index.html").readText().contains("data-src=\"./webApp.js\" src = \"./$bundleName?v=1\""))
    }
}
