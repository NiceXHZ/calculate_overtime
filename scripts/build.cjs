const fs = require("node:fs");
const path = require("node:path");
const { inlineTouchIcon } = require("./inline-touch-icon.cjs");
const root = path.resolve(__dirname, "..");
const out = path.join(root, "dist");
const html = inlineTouchIcon(
  fs.readFileSync(path.join(root, "index.html"), "utf8"),
  fs.readFileSync(path.join(root, "apple-touch-icon.png")),
);
fs.rmSync(out, { recursive: true, force: true });
fs.mkdirSync(out, { recursive: true });
fs.writeFileSync(path.join(out, "index.html"), html);
for (const file of [
  "icon.png",
  "apple-touch-icon.png",
  "manifest.webmanifest",
  "overtime_log.example.json",
])
  fs.copyFileSync(path.join(root, file), path.join(out, file));
fs.cpSync(path.join(root, "web"), path.join(out, "web"), { recursive: true });
console.log("Static site built in dist/ (no runtime dependencies).");
