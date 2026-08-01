package io.github.baole.wasmdeploy

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

internal data class PipelineCompressionOptions(
    val enabled: Boolean = false,
    val brotli: Boolean = true,
    val gzip: Boolean = false,
    val level: Int = 9,
    val includes: List<String> = listOf("**/*.wasm", "**/*.js", "**/*.mjs", "**/*.css", "**/*.html", "**/*.json", "**/*.svg"),
    val excludes: List<String> = emptyList(),
)

internal object WasmDeploymentPipeline {
    private val fingerprintedExtensions = setOf("css", "js", "mjs", "wasm")
    private val textExtensions = setOf("css", "html", "js", "mjs")
    private val assetExtensions = setOf(
        "css", "js", "mjs", "wasm", "png", "jpg", "jpeg", "gif", "svg", "ico",
        "webp", "woff", "woff2", "ttf", "otf", "json", "xml", "txt", "html",
    )
    private const val manifestName = "wasm-deploy-manifest.json"

    fun optimize(
        source: Path,
        destination: Path,
        projectBuildDir: Path? = null,
        allowExternalOutputDirectory: Boolean = false,
        compressionOptions: PipelineCompressionOptions = PipelineCompressionOptions(),
    ) {
        val normalizedSource = source.toAbsolutePath().normalize()
        val normalizedDestination = destination.toAbsolutePath().normalize()
        require(Files.isDirectory(normalizedSource)) { "Wasm distribution directory does not exist: $normalizedSource" }
        require(normalizedDestination != normalizedSource) {
            "Wasm deploy output directory must be different from the input directory: $normalizedDestination"
        }
        require(!normalizedDestination.startsWith(normalizedSource)) {
            "Wasm deploy output directory must not be inside the input directory: $normalizedDestination"
        }
        require(normalizedDestination.parent != null) {
            "Wasm deploy output directory must not be a filesystem root: $normalizedDestination"
        }
        val rootPath = normalizedDestination.root
        require(normalizedDestination != rootPath) {
            "Wasm deploy output directory must not be a filesystem root: $normalizedDestination"
        }

        val userHome = runCatching { Path.of(System.getProperty("user.home")).toAbsolutePath().normalize() }.getOrNull()
        require(userHome == null || normalizedDestination != userHome) {
            "Wasm deploy output directory must not be the user home directory: $normalizedDestination"
        }

        if (projectBuildDir != null) {
            val normalizedBuildDir = projectBuildDir.toAbsolutePath().normalize()
            val projectRoot = normalizedBuildDir.parent
            require(projectRoot == null || normalizedDestination != projectRoot) {
                "Wasm deploy output directory must not be the project root directory: $normalizedDestination"
            }
            if (!allowExternalOutputDirectory) {
                require(normalizedDestination.startsWith(normalizedBuildDir)) {
                    "Wasm deploy output directory ($normalizedDestination) must be inside the project build directory ($normalizedBuildDir). Set allowExternalOutputDirectory = true to allow external output paths."
                }
            }
        }

        val parentDir = normalizedDestination.parent ?: error("Invalid destination parent")
        Files.createDirectories(parentDir)
        val stagingDir = Files.createTempDirectory(parentDir, ".wasm-deploy-staging-")

        try {
            normalizedSource.toFile().copyRecursively(stagingDir.toFile(), overwrite = true)
            Files.walk(stagingDir).use { paths ->
                paths.filter { it.isRegularFile() && it.extension == "map" }.forEach(Files::delete)
            }

            val candidates = files(stagingDir).filter { it.extension in fingerprintedExtensions }
            require(candidates.any { it.extension == "wasm" }) { "No Wasm binaries found in $stagingDir" }
            val originalContent = candidates.associateWith { it.readBytes() }
            val supportingTextContent = files(stagingDir)
                .filter { it.extension in textExtensions && it !in originalContent }
                .associateWith { it.readText() }
            var manifest = originalContent.entries.associate { (file, bytes) ->
                relative(stagingDir, file) to fingerprint(relative(stagingDir, file), bytes)
            }
            var converged = false
            for (attempt in 0 until 12) {
                val rewritten = originalContent.mapValues { (file, bytes) ->
                    if (file.extension in textExtensions) {
                        rewrite(bytes.decodeToString(), manifest, relative(stagingDir, file)).encodeToByteArray()
                    } else {
                        bytes
                    }
                }
                val nextManifest = rewritten.entries.associate { (file, bytes) ->
                    relative(stagingDir, file) to fingerprint(relative(stagingDir, file), bytes)
                }.toMutableMap()
                originalContent.filterKeys { it.extension == "wasm" }.forEach { (file, bytes) ->
                    nextManifest[relative(stagingDir, file)] = fingerprint(relative(stagingDir, file), bytes)
                }
                if (nextManifest == manifest) {
                    rewritten.forEach { (file, bytes) -> file.writeBytes(bytes) }
                    supportingTextContent.forEach { (file, text) -> file.writeText(rewrite(text, manifest, relative(stagingDir, file))) }
                    manifest = nextManifest
                    converged = true
                    break
                }
                manifest = nextManifest
            }
            require(converged) { "Unable to produce stable fingerprinted asset names after 12 passes" }

            originalContent.forEach { (file, bytes) -> if (file.extension == "wasm") file.writeBytes(bytes) }
            manifest.entries.sortedBy { it.key }.forEach { (original, fingerprinted) ->
                val originalPath = stagingDir.resolve(original)
                val fingerprintedPath = stagingDir.resolve(fingerprinted)
                if (original != fingerprinted && Files.exists(originalPath)) {
                    Files.createDirectories(fingerprintedPath.parent)
                    Files.move(originalPath, fingerprintedPath, StandardCopyOption.REPLACE_EXISTING)
                }
            }

            if (compressionOptions.enabled) {
                generateCompressionArtifacts(stagingDir, compressionOptions)
            }

            writeManifest(stagingDir, manifest, compressionOptions)

            if (Files.exists(normalizedDestination)) {
                val backupDir = Files.createTempDirectory(parentDir, ".wasm-deploy-backup-")
                try {
                    Files.move(normalizedDestination, backupDir, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: Exception) {
                    deleteDirectoryRecursively(normalizedDestination)
                }
                try {
                    Files.move(stagingDir, normalizedDestination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    deleteDirectoryRecursively(backupDir)
                } catch (e: Exception) {
                    if (Files.exists(backupDir) && !Files.exists(normalizedDestination)) {
                        Files.move(backupDir, normalizedDestination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    }
                    throw e
                }
            } else {
                Files.move(stagingDir, normalizedDestination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (Files.exists(stagingDir)) {
                deleteDirectoryRecursively(stagingDir)
            }
        }
    }

    fun verify(release: Path, forbiddenJavaScriptPatterns: List<String> = emptyList()) {
        val manifestPath = release.resolve(manifestName)
        require(Files.isRegularFile(manifestPath)) { "Missing $manifestName" }
        val manifest = parseManifest(manifestPath.readText())
        require(manifest.isNotEmpty()) { "$manifestName must contain at least one deploy artifact" }
        manifest.forEach { (original, fingerprinted) ->
            require(isSafeRelativePath(original)) { "Unsafe artifact path in manifest: $original" }
            require(isSafeRelativePath(fingerprinted)) { "Unsafe fingerprinted path in manifest: $fingerprinted" }
            val artifact = release.resolve(fingerprinted)
            require(Files.isRegularFile(artifact)) { "Missing fingerprinted artifact: $fingerprinted" }
            if (Path.of(original).extension in fingerprintedExtensions) {
                require(original == fingerprinted || !Files.exists(release.resolve(original))) {
                    "Unfingerprinted artifact remains: $original"
                }
                require(fingerprint(original, artifact.readBytes()) == fingerprinted) { "Fingerprint mismatch: $fingerprinted" }
            }
        }

        require(files(release).none { it.extension == "map" }) { "Production source maps must not be published" }
        val bundle = files(release).singleOrNull { it.name.matches(Regex("(?:main|composeApp)\\.[0-9a-f]{16,}\\.js")) }
            ?: error("Expected one fingerprinted Kotlin/Wasm JavaScript bundle")
        verifyReferences(release, manifest, bundle)
        val bundleText = bundle.readText()
        forbiddenJavaScriptPatterns.forEach { pattern ->
            require(pattern !in bundleText) { "Forbidden JavaScript pattern in ${bundle.name}: $pattern" }
        }
        files(release).filter { it.extension == "wasm" }.flatMap { wasm ->
            WasmImports.functionImports(wasm.readBytes(), "js_code").map { wasm.name to it }
        }.forEach { (wasm, importName) ->
            val issue = JavaScriptImportValidator.findIssue(bundleText, importName)
            require(issue == null) {
                "Invalid JavaScript import for $wasm: js_code.$importName ($issue)"
            }
        }
    }

    private fun generateCompressionArtifacts(stagingDir: Path, options: PipelineCompressionOptions) {
        files(stagingDir).forEach { file ->
            val relPath = relative(stagingDir, file)
            if (matchesIncludeExclude(relPath, options.includes, options.excludes)) {
                val bytes = file.readBytes()
                if (options.brotli) {
                    val brBytes = brotliCompress(bytes, options.level)
                    if (brBytes != null) {
                        stagingDir.resolve("$relPath.br").writeBytes(brBytes)
                    }
                }
                if (options.gzip) {
                    val gzBytes = gzipCompress(bytes)
                    stagingDir.resolve("$relPath.gz").writeBytes(gzBytes)
                }
            }
        }
    }

    private fun gzipCompress(bytes: ByteArray): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { it.write(bytes) }
        return bos.toByteArray()
    }

    private fun brotliCompress(bytes: ByteArray, level: Int): ByteArray? {
        return runCatching {
            val process = ProcessBuilder(
                "node",
                "-e",
                "import fs from 'node:fs'; import { brotliCompressSync, constants } from 'node:zlib'; const buf = fs.readFileSync(0); const res = brotliCompressSync(buf, { params: { [constants.BROTLI_PARAM_QUALITY]: $level } }); process.stdout.write(res);",
            ).start()
            process.outputStream.use { it.write(bytes) }
            val compressed = process.inputStream.readBytes()
            if (process.waitFor() == 0 && compressed.isNotEmpty()) compressed else null
        }.getOrNull()
    }

    private fun matchesIncludeExclude(path: String, includes: List<String>, excludes: List<String>): Boolean {
        if (excludes.any { matchesGlob(path, it) }) return false
        return includes.isEmpty() || includes.any { matchesGlob(path, it) }
    }

    private fun matchesGlob(value: String, pattern: String): Boolean {
        var expression = "^"
        var index = 0
        while (index < pattern.length) {
            if (pattern.substring(index).startsWith("**/*")) {
                expression += ".*"
                index += 4
            } else if (pattern.substring(index).startsWith("**/")) {
                expression += "(?:|.*/)"
                index += 3
            } else {
                val character = pattern[index]
                if (character == '*' && index + 1 < pattern.length && pattern[index + 1] == '*') {
                    expression += ".*"
                    index += 2
                } else if (character == '*') {
                    expression += "[^/]*"
                    index += 1
                } else if (character == '?') {
                    expression += "[^/]"
                    index += 1
                } else {
                    expression += Regex.escape(character.toString())
                    index += 1
                }
            }
        }
        return Regex("$expression$").matches(value)
    }

    private fun writeManifest(stagingDir: Path, manifest: Map<String, String>, compressionOptions: PipelineCompressionOptions) {
        val sortedManifest = manifest.entries.sortedBy { it.key }
        val jsonLines = mutableListOf<String>()
        jsonLines += "{"
        jsonLines += "  \"artifacts\": {"

        val artifactEntries = files(stagingDir)
            .filter { !it.name.endsWith(".br") && !it.name.endsWith(".gz") && it.name != manifestName }
            .sortedBy { relative(stagingDir, it) }

        val artifactJsonBlocks = artifactEntries.map { file ->
            val relPath = relative(stagingDir, file)
            val bytes = file.readBytes()
            val sha256Str = sha256(bytes)
            val size = bytes.size
            val original = manifest.entries.firstOrNull { it.value == relPath }?.key ?: relPath

            val brFile = stagingDir.resolve("$relPath.br")
            val gzFile = stagingDir.resolve("$relPath.gz")
            val compressedFields = mutableListOf<String>()
            if (Files.exists(brFile)) {
                val brBytes = brFile.readBytes()
                compressedFields += "        \"brotli\": { \"path\": \"$relPath.br\", \"sha256\": \"${sha256(brBytes)}\", \"sizeBytes\": ${brBytes.size} }"
            }
            if (Files.exists(gzFile)) {
                val gzBytes = gzFile.readBytes()
                compressedFields += "        \"gzip\": { \"path\": \"$relPath.gz\", \"sha256\": \"${sha256(gzBytes)}\", \"sizeBytes\": ${gzBytes.size} }"
            }

            buildString {
                append("    \"$original\": {\n")
                append("      \"fingerprinted\": \"$relPath\",\n")
                append("      \"sha256\": \"$sha256Str\",\n")
                append("      \"sizeBytes\": $size")
                if (compressedFields.isNotEmpty()) {
                    append(",\n      \"compressed\": {\n")
                    append(compressedFields.joinToString(",\n"))
                    append("\n      }")
                }
                append("\n    }")
            }
        }

        jsonLines += artifactJsonBlocks.joinToString(",\n")
        jsonLines += "  },"
        jsonLines += "  \"mapping\": {"
        jsonLines += sortedManifest.joinToString(",\n") { (original, fingerprinted) -> "    \"$original\": \"$fingerprinted\"" }
        jsonLines += "  }"
        jsonLines += "}"

        stagingDir.resolve(manifestName).writeText(jsonLines.joinToString("\n") + "\n")
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun files(root: Path): List<Path> = Files.walk(root).use { paths -> paths.filter(Path::isRegularFile).toList() }

    private fun relative(root: Path, path: Path): String = path.relativeTo(root).toString().replace('\\', '/')

    private fun isSafeRelativePath(path: String): Boolean {
        val parsed = Path.of(path)
        return !parsed.isAbsolute && parsed.none { it.toString() == ".." }
    }

    private fun verifyReferences(release: Path, manifest: Map<String, String>, bundle: Path) {
        val index = release.resolve("index.html")
        require(Files.isRegularFile(index)) { "Missing index.html" }
        val bundlePath = relative(release, bundle)
        val indexText = index.readText()
        val entryPoints = quotedHtmlReference.findAll(indexText).mapNotNull { localReference(it.groupValues[1]) }.toList()
        require(bundlePath in entryPoints) { "index.html does not reference the fingerprinted Kotlin/Wasm entry point: $bundlePath" }

        files(release).filter { it.extension in textExtensions }.forEach { textFile ->
            val text = textFile.readText()
            val references = buildList {
                if (textFile.extension == "html") {
                    quotedHtmlReference.findAll(text).map { it.groupValues[1] }.filter { isStaticAssetReference(it) }.forEach { add(it) }
                    htmlSrcsetReference.findAll(text).flatMap { parseSrcsetUrls(it.groupValues[1]) }.forEach { add(it) }
                }
                if (textFile.extension == "css") {
                    cssUrlReference.findAll(text).map { it.groupValues[1] }.forEach { add(it) }
                    cssImportReference.findAll(text).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.forEach { add(it) }
                }
                if (textFile.extension == "js" || textFile.extension == "mjs") {
                    javaScriptAssetReference.findAll(text).map { it.groupValues[1] }.forEach { add(it) }
                }
            }
            references.forEach { reference -> verifyLocalReference(release, manifest, relative(release, textFile), reference) }
        }
    }

    private fun isStaticAssetReference(reference: String): Boolean {
        val clean = reference.substringBefore('#').substringBefore('?')
        val ext = Path.of(clean).extension.lowercase()
        return ext in assetExtensions
    }

    private fun parseSrcsetUrls(srcsetValue: String): List<String> =
        srcsetValue.split(',').mapNotNull { part ->
            part.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotEmpty() }
        }

    private fun verifyLocalReference(release: Path, manifest: Map<String, String>, source: String, reference: String) {
        val localPath = localReference(reference) ?: return
        val target = if (reference.substringBefore('#').substringBefore('?').startsWith("/")) {
            localPath
        } else {
            val sourceParent = Path.of(source).parent ?: Path.of("")
            sourceParent.resolve(localPath).normalize().toString().replace('\\', '/')
        }
        require(manifest[target] == null || manifest[target] == target) { "Unrewritten reference in $source: $reference" }
        require(isSafeRelativePath(target)) { "Unsafe local reference in $source: $reference" }
        require(Files.isRegularFile(release.resolve(target))) { "Missing local asset referenced by $source: $reference" }
    }

    private fun localReference(reference: String): String? {
        val value = reference.substringBefore('#').substringBefore('?')
        if (value.isEmpty() || value.startsWith("//") || value.startsWith("data:") || value.startsWith("blob:") ||
            value.startsWith("mailto:") || value.startsWith("tel:") || value.matches(Regex("^[A-Za-z][A-Za-z0-9+.-]*:.*"))
        ) return null
        return value.removePrefix("/").removePrefix("./").ifEmpty { null }
    }

    private fun fingerprint(path: String, bytes: ByteArray): String {
        val input = Path.of(path)
        if (input.fileName.toString().matches(Regex(".+\\.[0-9a-f]{16,}\\.[^.]+"))) {
            return path.replace('\\', '/')
        }
        val extension = input.extension
        val stem = input.fileName.toString().removeSuffix(".$extension")
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(16)
        val fingerprintedFileName = "$stem.$digest.$extension"
        return input.parent?.resolve(fingerprintedFileName)?.toString()?.replace('\\', '/') ?: fingerprintedFileName
    }

    private fun rewrite(text: String, manifest: Map<String, String>, source: String): String {
        val sourceDirectory = Path.of(source).parent ?: Path.of("")
        val replacements = manifest.entries.flatMap { (original, fingerprinted) ->
            val relativeOriginal = sourceDirectory.relativize(Path.of(original)).toString().replace('\\', '/')
            val relativeFingerprinted = sourceDirectory.relativize(Path.of(fingerprinted)).toString().replace('\\', '/')
            listOf(original to fingerprinted, relativeOriginal to relativeFingerprinted)
        }.distinct().sortedByDescending { it.first.length }
        return replacements.fold(text) { current, (original, fingerprinted) ->
            Regex("([\"'])(/|\\./)?${Regex.escape(original)}([?#][^\"']*)?\\1").replace(current) { match ->
                "${match.groupValues[1]}${match.groupValues[2]}$fingerprinted${match.groupValues[3]}${match.groupValues[1]}"
            }
        }
    }

    fun parseManifest(content: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        if (content.contains("\"mapping\"")) {
            val mappingBlock = content.substringAfter("\"mapping\"").substringAfter("{").substringBefore("}")
            Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"").findAll(mappingBlock).forEach {
                map[it.groupValues[1]] = it.groupValues[2]
            }
        } else {
            Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"").findAll(content).forEach {
                val key = it.groupValues[1]
                val value = it.groupValues[2]
                if (key != "fingerprinted" && key != "sha256" && key != "path" && key != "sizeBytes" && key != "brotli" && key != "gzip" && key != "artifacts") {
                    map[key] = value
                }
            }
        }
        return map
    }

    private fun deleteDirectoryRecursively(path: Path) {
        if (Files.exists(path)) {
            path.toFile().deleteRecursively()
        }
    }

    private val quotedHtmlReference = Regex("(?:src|href|data-src)=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val htmlSrcsetReference = Regex("srcset=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val cssUrlReference = Regex("url\\(\\s*[\"']?([^\"')]+)[\"']?\\s*\\)", RegexOption.IGNORE_CASE)
    private val cssImportReference = Regex("@import\\s+(?:url\\(['\"]?([^'\")]+)['\"]?\\)|['\"]([^'\"]+)['\"]);?", RegexOption.IGNORE_CASE)
    private val javaScriptAssetReference = Regex(
        "(?:new\\s+URL|import|importScripts|new\\s+Worker)\\s*\\(\\s*[\"']([^\"']+\\.(?:css|html|ico|js|json|mjs|png|svg|wasm)(?:[?#][^\"']*)?)[\"']",
        RegexOption.IGNORE_CASE,
    )
}

private object JavaScriptImportValidator {
    private val nonCallableIdentifiers = setOf("undefined", "null", "true", "false", "NaN", "Infinity")
    private val identifier = Regex("[A-Za-z_$][A-Za-z0-9_$]*")

    fun findIssue(bundleText: String, importName: String): String? {
        val property = Regex("[\"']${Regex.escape(importName)}[\"']\\s*:\\s*").find(bundleText)
            ?: return "missing import property"
        val expression = bundleText.substring(property.range.last + 1).trimStart()
        return if (isCallableLooking(expression, bundleText)) {
            null
        } else {
            "import property value is not callable-looking: ${expression.take(80)}"
        }
    }

    private fun isCallableLooking(expression: String, bundleText: String): Boolean {
        if (expression.startsWith("function") || expression.startsWith("async function")) return true
        if (Regex("^(?:async\\s*)?(?:\\([^)]*\\)|[A-Za-z_$][A-Za-z0-9_$]*)\\s*=>").containsMatchIn(expression)) {
            return true
        }
        if (Regex("^[^,{};?]+\\?\\s*(?:async\\s*)?(?:\\([^)]*\\)|[A-Za-z_$][A-Za-z0-9_$]*)\\s*=>").containsMatchIn(expression)) {
            return true
        }
        val referencedIdentifier = identifier.find(expression)?.takeIf { it.range.first == 0 }?.value ?: return false
        if (referencedIdentifier in nonCallableIdentifiers) return false
        return hasCallableDeclaration(bundleText, referencedIdentifier)
    }

    private fun hasCallableDeclaration(bundleText: String, name: String): Boolean {
        val escaped = Regex.escape(name)
        return Regex("\\bfunction\\s+$escaped\\s*\\(").containsMatchIn(bundleText) ||
            Regex("\\b(?:const|let|var)\\s+$escaped\\s*=\\s*(?:async\\s+)?function\\b").containsMatchIn(bundleText) ||
            Regex("\\b(?:const|let|var)\\s+$escaped\\s*=\\s*(?:async\\s*)?(?:\\([^)]*\\)|[A-Za-z_$][A-Za-z0-9_$]*)\\s*=>").containsMatchIn(bundleText)
    }
}

private object WasmImports {
    fun functionImports(bytes: ByteArray, module: String): List<String> {
        require(bytes.take(4).toByteArray().decodeToString() == "\u0000asm") { "Invalid Wasm header" }
        var index = 8
        val imports = mutableListOf<String>()
        while (index < bytes.size) {
            val section = bytes[index++].toInt()
            val (size, start) = readVarUInt(bytes, index)
            index = start
            val end = index + size
            if (section == 2) {
                val (count, afterCount) = readVarUInt(bytes, index)
                index = afterCount
                repeat(count) {
                    val (importModule, afterModule) = readString(bytes, index)
                    val (name, afterName) = readString(bytes, afterModule)
                    val kind = bytes[afterName].toInt()
                    index = afterName + 1
                    if (kind == 0) {
                        val (_, afterType) = readVarUInt(bytes, index)
                        index = afterType
                        if (importModule == module) imports += name
                    } else {
                        index = skipImportDescriptor(bytes, index, kind)
                    }
                }
            }
            index = end
        }
        return imports
    }

    private fun skipImportDescriptor(bytes: ByteArray, index: Int, kind: Int): Int = when (kind) {
        1 -> skipLimits(bytes, skipValueType(bytes, index))
        2 -> skipLimits(bytes, index)
        3 -> skipValueType(bytes, index) + 1
        4 -> readVarUInt(bytes, index + 1).second
        else -> error("Unsupported Wasm import kind: $kind")
    }

    private fun skipValueType(bytes: ByteArray, index: Int): Int = when (bytes[index].toInt() and 0xff) {
        0x63, 0x64 -> readVarUInt(bytes, index + 1).second
        else -> index + 1
    }

    private fun skipLimits(bytes: ByteArray, index: Int): Int {
        val (flags, afterFlags) = readVarUInt(bytes, index)
        val (_, afterMinimum) = readVarUInt(bytes, afterFlags)
        return if (flags and 1 != 0) readVarUInt(bytes, afterMinimum).second else afterMinimum
    }

    private fun readString(bytes: ByteArray, index: Int): Pair<String, Int> {
        val (length, start) = readVarUInt(bytes, index)
        val end = start + length
        return bytes.copyOfRange(start, end).decodeToString() to end
    }

    private fun readVarUInt(bytes: ByteArray, index: Int): Pair<Int, Int> {
        var value = 0
        var shift = 0
        var cursor = index
        while (true) {
            val byte = bytes[cursor++].toInt() and 0xff
            value = value or ((byte and 0x7f) shl shift)
            if (byte and 0x80 == 0) return value to cursor
            shift += 7
            require(shift < 35) { "Invalid Wasm unsigned integer" }
        }
    }
}
