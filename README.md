# Wasm Deploy Optimizer

Apply the settings plugin in `settings.gradle.kts`. It configures the restricted
Node, Yarn, and Binaryen distribution repositories required by Kotlin/Wasm,
including builds that use settings-managed repositories (above `dependencyResolutionManagement` block):

```kotlin
plugins {
    id("io.github.baole.wasmdeploy.settings") version "<version>"
}
```

Then apply the deployment plugin in the Compose Multiplatform application module:

```kotlin
plugins {
    id("io.github.baole.wasmdeploy")
}
```

Both IDs are published from this single plugin artifact and always use the same
version. Applying the settings plugin puts the shared artifact on Gradle's
classpath, so the application plugin intentionally has no version. The settings
plugin sets Gradle's repository mode to `PREFER_SETTINGS`,
so Kotlin/Wasm's project-level tool repositories are resolved through the
plugin-managed, module-restricted settings repositories instead.

## Configuration

All configuration is optional. The plugin uses the conventional Kotlin/Wasm production distribution as its input and writes a separate deployable release by default.

```kotlin
wasmDeploy {
    // Kotlin/Wasm distribution to process.
    // Default: build/dist/wasmJs/productionExecutable
    inputDirectory = "build/dist/wasmJs/productionExecutable"

    // Final directory to give to any static host.
    // Default: build/wasmDeploy/release
    outputDirectory = "build/deploy/web"

    // One or more static-resource directories. JavaScript, ECMAScript module,
    // and CSS files are copied recursively, minified, and retain their paths.
    // Default: src/wasmJsMain/resources
    staticResources = listOf(
        "src/wasmJsMain/resources",
        "../shared/web-assets",
    )

    // Optional directories copied recursively into the distribution. Each is
    // copied under its directory name, for example public/.well-known becomes
    // .well-known in the release.
    additionalAssets = listOf(
        "public/.well-known",
        "src/productionWeb/public",
    )

    // Use the Kotlin compiler's optimized artifacts before Webpack packages
    // them. Default: true. Disable only for an incompatible Kotlin layout.
    useOptimizedKotlinWasmArtifacts = true
    strictOptimizedKotlinWasmArtifacts = true
    kotlinWasmCompileTaskName = "wasmJsProductionExecutableCompileSync"
    kotlinWasmWebpackTaskName = "wasmJsBrowserProductionWebpack"
    kotlinWasmCompilationDirectory = "build/compileSync/wasmJs/main/productionExecutable"
    kotlinWasmPackagesDirectory = "build/wasm/packages"

    // Running wasmJsBrowserDistribution directly also runs the deployment
    // finalizer by default. Set false when that task must remain raw.
    finalizeKotlinWasmDistribution = true

    // Optional Brotli-compressed Wasm limits. Omit either property to disable
    // that particular limit.
    defaultMaxWasmBrotliBytes = 3_300_000L
    maxTotalWasmBrotliBytes = 5_500_000L
    // Ordered rules; the final matching rule wins. Patterns use *, ?, and **.
    wasmBrotliBudget("**/skiko*.wasm", 2_000_000L)

    // Optional deployment policy for the Kotlin/Wasm bootstrap bundle.
    forbiddenJavaScriptPatterns = listOf("eval(")
}
```

All directory settings are strings resolved relative to the consuming project (absolute paths also work). Set either Brotli limit to `null` or omit it to disable that check. `wasmBrotliBudget(pattern, maximumBytes)` adds an ordered rule; the final matching glob overrides the default limit. Duplicate resource output paths fail the build rather than silently choosing one directory.

When `outputDirectory` is changed, point the hosting provider at the same directory. For example, Firebase Hosting's `public` property should reference that output directory. The plugin intentionally does not modify hosting-provider configuration.

## Tasks

- `wasmDeployPrepareTools` — extracts the bundled tool definitions.
- `wasmDeployInstallTools` — downloads the plugin-managed Node runtime and installs lockfile-pinned Terser and clean-css.
- `wasmDeployPrepareOptimizedKotlinArtifacts` — selects compiler optimized Kotlin/Wasm artifacts before Webpack packaging.
- `wasmDeployMinify` — copies optional resources/assets, minifies static JS/CSS, normalizes the HTML entry, and removes source maps.
- `wasmDeployOptimize` — fingerprints JS/CSS/Wasm and rewrites local references into a separate release directory.
- `wasmDeployVerifySizeBudget` — enforces configured Brotli Wasm budgets.
- `wasmDeployVerifyWasmImports` — validates Wasm imports with the browser-compatible JavaScript runtime parser.
- `wasmDeployVerify` — validates fingerprints, HTML bootstrap and local references, source-map absence, configured JavaScript policy, and Wasm-to-JavaScript imports.
- `wasmDeployRelease` — runs the complete pipeline.

Run `wasmDeployRelease` to provision the plugin-managed Node toolchain, minify static JS/CSS, fingerprint, budget-check, and verify the release directory.
