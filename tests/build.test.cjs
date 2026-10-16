const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { execFileSync } = require("node:child_process");

test("static build embeds the exact touch PNG while preserving source HTML and fallback assets", () => {
  const root = path.resolve(__dirname, "..");
  const fixture = fs.mkdtempSync(path.join(os.tmpdir(), "overtime-build-"));
  try {
    for (const file of [
      "index.html",
      "icon.png",
      "apple-touch-icon.png",
      "manifest.webmanifest",
      "overtime_log.example.json",
    ]) {
      fs.copyFileSync(path.join(root, file), path.join(fixture, file));
    }
    for (const dir of ["scripts", "web"]) {
      fs.cpSync(path.join(root, dir), path.join(fixture, dir), { recursive: true });
    }
    const source = fs.readFileSync(path.join(fixture, "index.html"), "utf8");
    execFileSync(process.execPath, ["scripts/build.cjs"], { cwd: fixture });
    const built = fs.readFileSync(path.join(fixture, "dist/index.html"), "utf8");
    const sourceLink = source.match(/<link\b[^>]*rel="apple-touch-icon"[^>]*>/g);
    const builtLink = built.match(/<link\b[^>]*rel="apple-touch-icon"[^>]*>/g);
    assert.equal(builtLink.length, 1);
    const href = builtLink[0].match(/href="([^"]+)"/)[1];
    assert.ok(href.startsWith("data:image/png;base64,"));
    assert.deepEqual(
      Buffer.from(href.split(",")[1], "base64"),
      fs.readFileSync(path.join(fixture, "apple-touch-icon.png")),
    );
    assert.equal(built.replace(builtLink[0], sourceLink[0]), source);
    assert.equal(fs.readFileSync(path.join(fixture, "index.html"), "utf8"), source);
    for (const file of [
      "apple-touch-icon.png",
      "icon.png",
      "manifest.webmanifest",
      "web/icons/icon-192.png",
      "web/icons/icon-512.png",
    ]) {
      assert.deepEqual(
        fs.readFileSync(path.join(fixture, "dist", file)),
        fs.readFileSync(path.join(fixture, file)),
      );
    }
  } finally {
    fs.rmSync(fixture, { recursive: true, force: true });
  }
});
