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
const bundles = fs.readdirSync(distributionDirectory).filter((file) => /^(?:composeApp|main)\.[0-9a-f]{16,}\.js$/.test(file));
if (fs.existsSync(index) && bundles.length === 1) {
  const html = fs.readFileSync(index, "utf8");
  const updated = html.replace(/\/(?:composeApp|main)\.js/g, `/${bundles[0]}`);
  if (updated !== html) fs.writeFileSync(index, updated, "utf8");
}
