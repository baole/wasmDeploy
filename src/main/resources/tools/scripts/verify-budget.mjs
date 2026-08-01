import fs from "node:fs";
import path from "node:path";
import { brotliCompressSync, constants } from "node:zlib";

const [releaseDirectory, defaultMaxBrotliBytes, maxTotalBrotliBytes, ...budgetArguments] = process.argv.slice(2);
if (!releaseDirectory) throw new Error("Usage: verify-budget.mjs <release-directory> [default-max-brotli-bytes] [max-total-brotli-bytes]");

const defaultBudget = defaultMaxBrotliBytes ? Number(defaultMaxBrotliBytes) : undefined;
const totalBudget = maxTotalBrotliBytes ? Number(maxTotalBrotliBytes) : undefined;
const fileBudgets = [];
for (let index = 0; index < budgetArguments.length; index += 3) {
  const [option, pattern, maximum] = budgetArguments.slice(index, index + 3);
  if (option !== "--file" || !pattern || !maximum || !Number.isFinite(Number(maximum))) {
    throw new Error("Usage: verify-budget.mjs <release-directory> [default-max-brotli-bytes] [max-total-brotli-bytes] [--file <glob> <max-brotli-bytes>]...");
  }
  fileBudgets.push({ pattern, maximum: Number(maximum) });
}
const files = [];
const walk = (directory) => {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const target = path.join(directory, entry.name);
    if (entry.isDirectory()) walk(target);
    else if (entry.isFile() && entry.name.endsWith(".wasm")) files.push(target);
  }
};
walk(releaseDirectory);
const options = { params: { [constants.BROTLI_PARAM_QUALITY]: 11 } };
let total = 0;
for (const file of files) {
  const relativePath = path.relative(releaseDirectory, file).split(path.sep).join("/");
  const rawBytes = fs.statSync(file).size;
  const compressed = brotliCompressSync(fs.readFileSync(file), options).length;
  total += compressed;
  const matchedBudget = fileBudgets.filter((budget) => matchesGlob(relativePath, budget.pattern)).at(-1)?.maximum;
  const maximum = matchedBudget ?? defaultBudget;
  console.log(`Wasm size: ${relativePath} ${rawBytes} raw bytes, ${compressed} Brotli bytes`);
  if (maximum != null && compressed > maximum) {
    throw new Error(`Wasm Brotli budget exceeded for ${relativePath}: ${compressed} > ${maximum}`);
  }
}
if (totalBudget != null && total > totalBudget) {
  throw new Error(`Wasm total Brotli budget exceeded: ${total} > ${totalBudget}`);
}
console.log(`Wasm total Brotli size: ${total} bytes`);

function matchesGlob(value, pattern) {
  let expression = "^";
  for (let index = 0; index < pattern.length; index += 1) {
    const character = pattern[index];
    if (character === "*" && pattern[index + 1] === "*") {
      expression += ".*";
      index += 1;
    } else if (character === "*") {
      expression += "[^/]*";
    } else if (character === "?") {
      expression += "[^/]";
    } else {
      expression += character.replace(/[\\^$.*+?()[\]{}|]/g, "\\$&");
    }
  }
  return new RegExp(`${expression}$`).test(value);
}
