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
const wasmModules = wasmFiles.map((wasmFile) => {
  const module = new WebAssembly.Module(fs.readFileSync(wasmFile));
  return {
    file: wasmFile,
    imports: WebAssembly.Module.imports(module)
      .filter((entry) => entry.module === "js_code" && entry.kind === "function")
      .map((entry) => entry.name),
  };
});
const wasmImports = wasmModules.flatMap(({ imports }) => imports);
const indexFile = path.join(releaseDirectory, "index.html");
if (!fs.existsSync(indexFile)) throw new Error("Missing index.html");

const releaseRoot = path.resolve(releaseDirectory);
const scriptSources = [...fs.readFileSync(indexFile, "utf8").matchAll(/<script\b[^>]*\ssrc\s*=\s*["']([^"']+)["'][^>]*>/gi)]
  .map((match) => match[1])
  .map((reference) => reference.split(/[?#]/, 1)[0])
  .filter((reference) => reference && !/^(?:[a-z][a-z\d+.-]*:|\/\/)/i.test(reference))
  .map((reference) => path.resolve(releaseRoot, reference.replace(/^(?:\.\/|\/)+/, "")))
  .filter((file) => file.startsWith(`${releaseRoot}${path.sep}`) && path.extname(file).toLowerCase() === ".js")
  .filter((file) => fs.existsSync(file) && fs.statSync(file).isFile());
const bundleFiles = [...new Set(scriptSources)].filter((file) => {
  const bundle = fs.readFileSync(file, "utf8");
  return wasmImports.length > 0
    ? wasmImports.every((importName) => bundle.includes(`${JSON.stringify(importName)}:`))
    : wasmModules.some(({ file: wasmFile }) => bundle.includes(path.basename(wasmFile)));
});
if (wasmFiles.length === 0 || bundleFiles.length !== 1) {
  throw new Error(`Expected Wasm files and exactly one Kotlin/Wasm JavaScript bundle referenced from index.html with the Wasm imports; found ${bundleFiles.length}`);
}

const bundle = fs.readFileSync(bundleFiles[0], "utf8");
const missingImports = wasmModules.flatMap(({ file, imports }) => imports
  .filter((importName) => !bundle.includes(`${JSON.stringify(importName)}:`))
  .map((importName) => `${path.relative(releaseDirectory, file)}: js_code.${importName}`));

if (missingImports.length > 0) {
  throw new Error(`Missing JavaScript imports for Wasm:\n${missingImports.join("\n")}`);
}
