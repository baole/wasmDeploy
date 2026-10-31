import CleanCSS from "clean-css";
import { minify } from "terser";
import fs from "node:fs";
import path from "node:path";

const [distributionDirectory, ...cliArguments] = process.argv.slice(2);
const resourceDirectories = [];
const additionalAssets = [];
for (let index = 0; index < cliArguments.length; index += 2) {
  const option = cliArguments[index];
  const directory = cliArguments[index + 1];
  if (!directory || !["--resource", "--asset"].includes(option)) {
    throw new Error("Usage: minify.mjs <distribution-directory> --resource <directory> [--resource <directory>...] [--asset <directory>...]");
  }
  (option === "--resource" ? resourceDirectories : additionalAssets).push(directory);
}

if (!distributionDirectory || resourceDirectories.length === 0) {
  throw new Error("At least one --resource directory is required");
}

const copyDirectory = (source, destination) => {
  if (!fs.existsSync(source)) return;
  fs.mkdirSync(destination, { recursive: true });
  for (const entry of fs.readdirSync(source, { withFileTypes: true })) {
    const from = path.join(source, entry.name);
    const to = path.join(destination, entry.name);
    if (entry.isDirectory()) copyDirectory(from, to);
    else if (entry.isFile()) fs.copyFileSync(from, to);
  }
};

for (const map of fs.readdirSync(distributionDirectory).filter((file) => file.endsWith(".map"))) {
  fs.rmSync(path.join(distributionDirectory, map));
}

for (const assetDirectory of additionalAssets) {
  copyDirectory(assetDirectory, path.join(distributionDirectory, path.basename(assetDirectory)));
}

const seenOutputs = new Set();
const minifyResourceDirectory = async (resourceDirectory, relativeDirectory = "") => {
  for (const entry of fs.readdirSync(path.join(resourceDirectory, relativeDirectory), { withFileTypes: true })) {
    const relativePath = path.join(relativeDirectory, entry.name);
    if (entry.isDirectory()) {
      await minifyResourceDirectory(resourceDirectory, relativePath);
      continue;
    }
    if (!entry.isFile() || !/\.(?:js|mjs|css)$/.test(entry.name)) continue;
    const source = path.join(resourceDirectory, relativePath);
    const destination = path.join(distributionDirectory, relativePath);
    if (seenOutputs.has(relativePath)) throw new Error(`Duplicate static resource output: ${relativePath}`);
    seenOutputs.add(relativePath);
    fs.mkdirSync(path.dirname(destination), { recursive: true });
    const input = fs.readFileSync(source, "utf8");
    if (entry.name.endsWith(".css")) {
    const result = new CleanCSS({ level: 1 }).minify(input);
    if (result.errors.length) throw new Error(result.errors.join("\n"));
    fs.writeFileSync(destination, result.styles, "utf8");
    } else {
    const result = await minify(input, { compress: true, mangle: true, module: entry.name.endsWith(".mjs") });
    fs.writeFileSync(destination, result.code ?? "", "utf8");
    }
  }
};

for (const resourceDirectory of resourceDirectories) await minifyResourceDirectory(resourceDirectory);

const index = path.join(distributionDirectory, "index.html");
if (fs.existsSync(index)) {
  const html = fs.readFileSync(index, "utf8");
  const updated = html.replace(/(<script\b[^>]*\ssrc\s*=\s*)(["'])([^"']+)\2([^>]*>)/gi, (tag, prefix, quote, reference, suffix) => {
    const pathAndSuffix = reference.match(/^([^?#]*)([?#].*)?$/);
    const originalPath = pathAndSuffix?.[1] ?? reference;
    const queryAndHash = pathAndSuffix?.[2] ?? "";
    if (!originalPath || /^(?:[a-z][a-z\d+.-]*:|\/\/)/i.test(originalPath) || path.posix.extname(originalPath).toLowerCase() !== ".js") {
      return tag;
    }

    const relativePath = originalPath.replace(/^(?:\.\/|\/)+/, "");
    const directory = path.posix.dirname(relativePath);
    const name = path.posix.basename(relativePath);
    const stem = name.slice(0, -path.posix.extname(name).length);
    const distributionRoot = path.resolve(distributionDirectory);
    const directoryPath = path.resolve(distributionRoot, directory);
    if (directoryPath !== distributionRoot && !directoryPath.startsWith(`${distributionRoot}${path.sep}`)) return tag;
    const exactPath = path.join(directoryPath, name);
    if (fs.existsSync(exactPath)) return tag;

    const candidates = fs.existsSync(directoryPath)
      ? fs.readdirSync(directoryPath).filter((file) => file.startsWith(`${stem}.`) && /^\.[0-9a-f]{16,}\.js$/.test(file.slice(stem.length)))
      : [];
    if (candidates.length !== 1) return tag;

    const rewrittenPath = path.posix.join(directory, candidates[0]);
    const prefixPath = reference.startsWith("/") ? "/" : reference.startsWith("./") ? "./" : "";
    return `${prefix}${quote}${prefixPath}${rewrittenPath}${queryAndHash}${quote}${suffix}`;
  });
  if (updated !== html) fs.writeFileSync(index, updated, "utf8");
}
