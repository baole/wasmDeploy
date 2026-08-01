# Wasm Deploy Optimizer

`wasmDeploy` packages Kotlin/Wasm production output into a cache-safe, fingerprinted, and deployable release for static web hosting providers.

## What `wasmDeploy` Adds to the Existing Toolchain

`wasmDeploy` reuses Kotlin/Wasm's production compiler and Webpack pipeline without replacing the Kotlin compiler, Binaryen, or Webpack.

| Concern | Standard Toolchain | What `wasmDeploy` Adds | Key Benefit |
| --- | --- | --- | --- |
| **Wasm Optimization** | Uses Kotlin compiler & Binaryen | Selects & preserves compiler-optimized artifacts before Webpack | Retains original Kotlin/Wasm binary compatibility |
| **JS/CSS Assets** | Requires custom Node or shell scripts | Copies & minifies static assets using pinned tools | Replaces custom build & deployment scripts |
| **Cache Security** | Unfingerprinted assets risk stale caching | Fingerprints JS/CSS/Wasm & rewrites internal references | Enables immutable (`max-age=365d`) edge caching |
| **Compression** | Relies solely on edge host behavior | Generates `.br`/`.gz` files & enforces Brotli size budgets | Prevents size regressions & speeds up static serving |
| **Release Verification** | Broken imports/paths caught post-deploy | Verifies hashes, references, entry points & Wasm-JS imports | Catches broken deployments in CI before release |

> **In short:** Kotlin owns language-aware compilation and optimization; `wasmDeploy` owns hosting output, caching, compression, and deployment verification.

## Setup

### 1. Apply Settings Plugin

Add the plugin to `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("io.github.baole.wasmdeploy.settings") version "0.3.9"
}
```

### 2. Apply Build Plugin

In your module's `build.gradle.kts` (e.g., `composeApp/build.gradle.kts`):

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

## Precompression & Size Budgets

`wasmDeploy` supports automated static precompression (`.br` and `.gz`) alongside size-budget validation.

```kotlin
wasmDeploy {
    // 1. Budget validation (validates Brotli size limits during CI)
    defaultMaxWasmBrotliBytes = 3_300_000L
    maxTotalWasmBrotliBytes = 5_500_000L
    wasmBrotliBudget("**/skiko*.wasm", 2_000_000L)

    // 2. Static precompression block
    compression {
        enabled = true  // Master toggle to enable .br/.gz sidecar generation
        brotli = true   // Emits Brotli precompressed files (*.br)
        gzip = true     // Emits Gzip precompressed files (*.gz)
        level = 9       // Brotli compression quality level (1 to 11, default: 9)
        includes = listOf("**/*.wasm", "**/*.js", "**/*.mjs", "**/*.css", "**/*.html", "**/*.json", "**/*.svg")
        excludes = emptyList()
    }
}
```

### Compression Block Configuration Reference

| Property | Type | Default | Description |
| --- | --- | --- | --- |
| `enabled` | `Boolean` | `true` | Master switch to enable or disable static `.br` and `.gz` sidecar file generation during build. |
| `brotli` | `Boolean` | `true` | When `true`, generates Brotli compressed files (`*.br`) alongside fingerprinted assets for modern browsers and CDNs (`brotli_static on;`). |
| `gzip` | `Boolean` | `false` | When `true`, generates Gzip compressed files (`*.gz`) for legacy web servers (`gzip_static on;`). |
| `level` | `Int` | `9` | Brotli compression quality level (range: `1` to `11`). Level `9` provides optimal compression ratio for production deployments. |
| `includes` | `List<String>` | `listOf("**/*.wasm", ...)` | Glob patterns matching files eligible for precompression (`.wasm`, `.js`, `.css`, `.html`, `.svg`, `.json`). |
| `excludes` | `List<String>` | `emptyList()` | Glob patterns to explicitly exclude specific files or paths from precompression. |


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

## Migration Guide

If migrating from custom deployment scripts:

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

- `wasmDeployPrepareTools` — Extracts bundled tool definitions.
- `wasmDeployInstallTools` — Provisions plugin-managed Node.js and dependencies.
- `wasmDeployPrepareOptimizedKotlinArtifacts` — Selects compiler-optimized Wasm artifacts before Webpack.
- `wasmDeployMinify` — Minifies static JS/CSS and strips source maps.
- `wasmDeployOptimize` — Fingerprints assets, rewrites references into release directory, and generates precompressed files if enabled.
- `wasmDeployVerifySizeBudget` — Enforces configured Brotli size budgets.
- `wasmDeployVerifyWasmImports` — Validates Wasm JavaScript imports.
- `wasmDeployVerify` — Validates manifest integrity, SHA-256 hashes, entry points, and local references.
- `wasmDeployRelease` — Runs the complete end-to-end deployment pipeline.
