const fs = require("node:fs");
const path = require("node:path");
const root = path.resolve(__dirname, "..");
const out = path.join(root, "dist");
fs.rmSync(out, { recursive: true, force: true });
fs.mkdirSync(out, { recursive: true });
for (const file of [
  "index.html",
  "icon.png",
  "apple-touch-icon.png",
  "manifest.webmanifest",
  "overtime_log.example.json",
])
  fs.copyFileSync(path.join(root, file), path.join(out, file));
fs.cpSync(path.join(root, "web"), path.join(out, "web"), { recursive: true });
console.log("Static site built in dist/ (no runtime dependencies).");
