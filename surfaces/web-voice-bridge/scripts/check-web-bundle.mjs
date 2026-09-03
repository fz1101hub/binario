import { gzipSync } from "node:zlib";
import { readFileSync } from "node:fs";
import path from "node:path";

const publicDir = path.resolve("dist/public");
const manifest = JSON.parse(readFileSync(path.join(publicDir, ".vite/manifest.json"), "utf8"));
const entry = Object.values(manifest).find(item => item.isEntry && item.src === "index.html");
if (!entry?.file) throw new Error("Manifest Vite senza entry index.html");

const limits = { entryGzipBytes: 90 * 1024, cssGzipBytes: 35 * 1024 };
const gzipSize = file => gzipSync(readFileSync(path.join(publicDir, file))).byteLength;
const entryGzipBytes = gzipSize(entry.file);
const cssGzipBytes = (entry.css ?? []).reduce((total, file) => total + gzipSize(file), 0);

if (entryGzipBytes > limits.entryGzipBytes) throw new Error(`Bundle entry oltre budget: ${entryGzipBytes} B gzip > ${limits.entryGzipBytes} B`);
if (cssGzipBytes > limits.cssGzipBytes) throw new Error(`CSS oltre budget: ${cssGzipBytes} B gzip > ${limits.cssGzipBytes} B`);
console.log(`Budget bundle OK · entry ${entryGzipBytes} B gzip · CSS ${cssGzipBytes} B gzip`);
