# Wasm Deploy Optimizer

`wasmDeploy` packages Kotlin/Wasm production output into a cache-safe, fingerprinted, and deployable release for static web hosting providers.

## What wasmDeploy Adds to the Existing Toolchain

`wasmDeploy` deliberately reuses Kotlin/Wasm's production compiler and Webpack
pipeline. It does not replace the Kotlin compiler, Binaryen, or Webpack.

| Concern | Existing Kotlin/Wasm toolchain | What wasmDeploy adds | Benefit |
| --- | --- | --- | --- |
| Kotlin/Wasm compilation | Compiles Kotlin to Wasm and JavaScript interop glue. | Uses the existing production compilation tasks and can select their optimized artifacts before Webpack runs. | No second compiler pipeline or incompatible Wasm output. |
| Wasm optimization | Kotlin production builds use Binaryen. | Preserves and verifies the compiler-optimized artifacts rather than running an unrelated optimizer afterward. | Keeps optimization aligned with the Kotlin version and Wasm features in use. |
| JavaScript bundling and minification | Kotlin's production Webpack task bundles and minifies generated JavaScript. | Packages the resulting browser distribution into a release directory. | Retains Kotlin/Webpack semantics while making the output host-ready. |
| Project-owned JS and CSS | Usually requires custom Gradle, Node, or shell scripts. | Copies and minifies configured static JS/CSS resources with pinned tools. | Removes bespoke deployment scripts while keeping non-Kotlin assets optimized. |
| Cache-safe hosting | Webpack output and static assets often need project-specific post-processing. | Fingerprints deployable JS, CSS, and Wasm, rewrites local references, and emits a manifest. | Enables immutable caching for changed assets without serving stale files. |
| Compression | Hosts vary in whether they compress at the edge. | Enforces Brotli size budgets and can generate `.br`/`.gz` artifacts. | Prevents unnoticed size regressions and supports hosts that serve precompressed files. |
| Release correctness | Missing files, stale paths, and invalid Wasm imports are normally discovered after deployment. | Verifies hashes, local references, entry points, and Wasm-to-JavaScript imports before release. | Catches deployment and library-upgrade regressions in CI. |

In short: Kotlin owns language-aware compilation and optimization; `wasmDeploy`
owns hosting output, caching, compression, and deployment verification.

## Public Repository Setup

Add the plugin to your `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("io.github.baole.wasmdeploy.settings") version "0.3.7"
}
```

## Minimal Compose Multiplatform Configuration

In your `composeApp/build.gradle.kts` (or Wasm module `build.gradle.kts`):

```kotlin
plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("io.github.baole.wasmdeploy")
}

kotlin {
    wasmJs {
        moduleName = "composeApp"
        browser {
            val projectDir = project.projectDir
            commonWebpackConfig {
                outputFileName = "composeApp.js"
            }
        }
        binaries.executable()
    }
}

wasmDeploy {
    // Uses default input: build/dist/wasmJs/productionExecutable
    // Writes output to: build/wasmDeploy/release
}
```

Building the project or running `./gradlew wasmJsBrowserDistribution` automatically runs `wasmDeployRelease` to fingerprint assets, rewrite references, and verify the deployment release directory.

---

## Precompression Modes

`wasmDeploy` supports both size-budget validation and automated precompression generation (`.br` and `.gz`).

```kotlin
wasmDeploy {
    // 1. Budget validation (does not emit compressed files, validates size limits)
    defaultMaxWasmBrotliBytes = 3_300_000L
    maxTotalWasmBrotliBytes = 5_500_000L
    wasmBrotliBudget("**/skiko*.wasm", 2_000_000L)

    // 2. Artifact precompression (emits .br and .gz files alongside fingerprinted assets)
    compression {
        enabled = true
        brotli = true
        gzip = true
        level = 9
        includes = listOf("**/*.wasm", "**/*.js", "**/*.mjs", "**/*.css", "**/*.html", "**/*.json", "**/*.svg")
    }
}
```

---

## Hosting Providers & Cache Headers

### Firebase Hosting

Firebase Hosting automatically negotiates compression on demand, so precompressed `.br` files are optional. Point `public` in `firebase.json` at `wasmDeploy`'s output directory:

```json
{
  "hosting": {
    "public": "build/wasmDeploy/release",
    "ignore": ["firebase.json", "**/.*"],
    "headers": [
      {
        "source": "**/*.@(js|css|wasm)",
        "headers": [
          {
            "key": "Cache-Control",
            "value": "public, max-age=31536000, immutable"
          }
        ]
      },
      {
        "source": "index.html",
        "headers": [
          {
            "key": "Cache-Control",
            "value": "no-cache"
          }
        ]
      }
    ]
  }
}
```

### Generic Static Web Hosts (Nginx, Caddy, Cloudflare, S3/CloudFront)

For fingerprinted assets (`*.123456789abcdef.js`, `*.123456789abcdef.wasm`):
- `Cache-Control: public, max-age=31536000, immutable`
- `Vary: Accept-Encoding`
- Serve precompressed `.br` with `Content-Encoding: br` and `.gz` with `Content-Encoding: gzip`.

For entry point (`index.html`):
- `Cache-Control: no-cache`

---

## Migration Guide from Custom Wasm Deployment Scripts

If you currently use custom bash or Node scripts to process Webpack output:

1. Remove custom post-processing scripts or `exec` tasks from `build.gradle.kts`.
2. Apply `id("io.github.baole.wasmdeploy.settings")` in `settings.gradle.kts` and `id("io.github.baole.wasmdeploy")` in `build.gradle.kts`.
3. Configure static resources in `wasmDeploy { staticResources = listOf("src/wasmJsMain/resources") }`.
4. Point your deploy step at `build/wasmDeploy/release`.

---

## Complete Configuration Reference

```kotlin
wasmDeploy {
    // Kotlin/Wasm distribution directory to process.
    inputDirectory = "build/dist/wasmJs/productionExecutable"

    // Output directory for the deployment release.
    outputDirectory = "build/wasmDeploy/release"

    // Disallow external output paths outside buildDir unless opt-in:
    allowExternalOutputDirectory = false

    // Static resource directories (JavaScript, CSS minified and preserved).
    staticResources = listOf("src/wasmJsMain/resources")

    // Additional asset directories (copied recursively).
    additionalAssets = listOf("public/.well-known")

    // Optimize Kotlin compiler output before Webpack packaging.
    useOptimizedKotlinWasmArtifacts = true

    // Brotli size budget limits (in bytes).
    defaultMaxWasmBrotliBytes = 3_300_000L
    maxTotalWasmBrotliBytes = 5_500_000L

    // Forbidden JavaScript patterns in bootstrap bundle.
    forbiddenJavaScriptPatterns = listOf("eval(")
}
```

## Tasks

- `wasmDeployPrepareTools` — extracts bundled tool definitions.
- `wasmDeployInstallTools` — provisions plugin-managed Node.js and dependencies.
- `wasmDeployPrepareOptimizedKotlinArtifacts` — selects compiler-optimized Wasm artifacts before Webpack.
- `wasmDeployMinify` — minifies static JS/CSS and strips source maps.
- `wasmDeployOptimize` — fingerprints assets, rewrites references into release directory, and generates precompressed files if enabled.
- `wasmDeployVerifySizeBudget` — enforces configured Brotli size budgets.
- `wasmDeployVerifyWasmImports` — validates Wasm JavaScript imports.
- `wasmDeployVerify` — validates manifest integrity, SHA-256 hashes, entry points, and local references.
- `wasmDeployRelease` — runs the complete end-to-end deployment pipeline.
