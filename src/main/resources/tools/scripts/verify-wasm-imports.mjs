import fs from "node:fs";
import path from "node:path";

const [releaseDirectory] = process.argv.slice(2);
if (!releaseDirectory) throw new Error("Usage: verify-wasm-imports.mjs <release-directory>");

const files = [];
const walk = (directory) => {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const target = path.join(directory, entry.name);
    if (entry.isDirectory()) walk(target);
    else if (entry.isFile()) files.push(target);
  }
};
walk(releaseDirectory);

const wasmFiles = files.filter((file) => file.endsWith(".wasm"));
const bundleFiles = files.filter((file) => /^(?:composeApp|main)\.[0-9a-f]{16,}\.js$/.test(path.basename(file)));
if (wasmFiles.length === 0 || bundleFiles.length !== 1) {
  throw new Error("Expected fingerprinted Wasm files and exactly one Kotlin/Wasm JavaScript bundle");
}

const bundle = fs.readFileSync(bundleFiles[0], "utf8");
const missingImports = wasmFiles.flatMap((wasmFile) => {
  const module = new WebAssembly.Module(fs.readFileSync(wasmFile));
  return WebAssembly.Module.imports(module)
    .filter((entry) => entry.module === "js_code" && entry.kind === "function")
    .filter((entry) => !bundle.includes(`${JSON.stringify(entry.name)}:`))
    .map((entry) => `${path.relative(releaseDirectory, wasmFile)}: ${entry.module}.${entry.name}`);
});

if (missingImports.length > 0) {
  throw new Error(`Missing JavaScript imports for Wasm:\n${missingImports.join("\n")}`);
}
