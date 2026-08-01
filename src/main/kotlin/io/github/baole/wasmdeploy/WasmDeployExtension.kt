package io.github.baole.wasmdeploy

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

abstract class WasmDeployExtension @Inject constructor(
    objects: ObjectFactory,
) {
    private val inputDirectoryPath: Property<String> = objects.property(String::class.java)
    private val outputDirectoryPath: Property<String> = objects.property(String::class.java)
    private val staticResourcePaths: ListProperty<String> = objects.listProperty(String::class.java)

    var inputDirectory: String
        get() = inputDirectoryPath.get()
        set(value) {
            inputDirectoryPath.set(value)
        }

    var outputDirectory: String
        get() = outputDirectoryPath.get()
        set(value) {
            outputDirectoryPath.set(value)
        }

    var staticResources: List<String>
        get() = staticResourcePaths.orNull.orEmpty()
        set(value) {
            staticResourcePaths.set(value)
        }

    private val additionalAssetPaths: ListProperty<String> = objects.listProperty(String::class.java)
    private val defaultMaxWasmBrotliBytesProperty: Property<Long> = objects.property(Long::class.java)
    private val maxTotalWasmBrotliBytesProperty: Property<Long> = objects.property(Long::class.java)

    /** Uses Kotlin's compiler-produced optimized Wasm artifacts before Webpack packages the distribution. */
    var useOptimizedKotlinWasmArtifacts: Boolean = true

    private val wasmBrotliBudgetRules = mutableListOf<WasmBrotliBudget>()

    /** Adds an ordered Brotli budget rule. The last matching rule wins. */
    fun wasmBrotliBudget(pattern: String, maximumBytes: Long) {
        require(pattern.isNotBlank()) { "Wasm Brotli budget pattern must not be blank" }
        require(maximumBytes > 0) { "Wasm Brotli budget must be greater than zero" }
        wasmBrotliBudgetRules += WasmBrotliBudget(pattern, maximumBytes)
    }

    /** Runs the deployment pipeline after direct Kotlin/Wasm distribution tasks. */
    var finalizeKotlinWasmDistribution: Boolean = true

    /** Fails configuration when Kotlin/Wasm optimized-artifact wiring is unavailable. */
    var strictOptimizedKotlinWasmArtifacts: Boolean = false

    /** Kotlin/Wasm task names and output locations vary between Kotlin versions; all are configurable. */
    var kotlinWasmCompileTaskName: String = "wasmJsProductionExecutableCompileSync"
    var kotlinWasmWebpackTaskName: String = "wasmJsBrowserProductionWebpack"
    var kotlinWasmCompilationDirectory: String = "build/compileSync/wasmJs/main/productionExecutable"
    var kotlinWasmPackagesDirectory: String = "build/wasm/packages"

    /** Allows outputting releases to directories outside the consuming project's build directory. */
    var allowExternalOutputDirectory: Boolean = false

    val compression: CompressionConfig = objects.newInstance(CompressionConfig::class.java)

    fun compression(action: org.gradle.api.Action<CompressionConfig>) {
        action.execute(compression)
    }

    /** JavaScript snippets that must not occur in the Kotlin/Wasm bootstrap bundle. */
    var forbiddenJavaScriptPatterns: List<String> = emptyList()

    internal fun wasmBrotliBudgets(): List<WasmBrotliBudget> = wasmBrotliBudgetRules.toList()

    var additionalAssets: List<String>
        get() = additionalAssetPaths.orNull.orEmpty()
        set(value) {
            additionalAssetPaths.set(value)
        }

    var defaultMaxWasmBrotliBytes: Long?
        get() = defaultMaxWasmBrotliBytesProperty.orNull
        set(value) {
            if (value == null) defaultMaxWasmBrotliBytesProperty.unset() else defaultMaxWasmBrotliBytesProperty.set(value)
        }

    var maxTotalWasmBrotliBytes: Long?
        get() = maxTotalWasmBrotliBytesProperty.orNull
        set(value) {
            if (value == null) maxTotalWasmBrotliBytesProperty.unset() else maxTotalWasmBrotliBytesProperty.set(value)
        }
}

open class CompressionConfig @Inject constructor(objects: ObjectFactory) {
    var enabled: Boolean = true
    var brotli: Boolean = true
    var gzip: Boolean = false
    var level: Int = 9
    var includes: List<String> = listOf("**/*.wasm", "**/*.js", "**/*.mjs", "**/*.css", "**/*.html", "**/*.json", "**/*.svg")
    var excludes: List<String> = emptyList()
}

data class WasmBrotliBudget(
    val pattern: String,
    val maximumBytes: Long,
)
