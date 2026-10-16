const http = require("node:http");
const fs = require("node:fs");
const path = require("node:path");
const { inlineTouchIcon } = require("./inline-touch-icon.cjs");
const root = path.resolve(__dirname, "..");
const port = Number(process.env.PORT || 4173);
const types = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".webmanifest": "application/manifest+json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".txt": "text/plain; charset=utf-8",
};
http
  .createServer((req, res) => {
    let name;
    try {
      name = decodeURIComponent(new URL(req.url, "http://localhost").pathname);
    } catch {
      res.writeHead(400).end();
      return;
    }
    if (name.includes("\0")) {
      res.writeHead(400).end();
      return;
    }
    if (name === "/") name = "/index.html";
    if (
      ![
        "/index.html",
        "/icon.png",
        "/apple-touch-icon.png",
        "/manifest.webmanifest",
        "/overtime_log.example.json",
      ].includes(name) &&
      !name.startsWith("/web/")
    ) {
      res.writeHead(404).end();
      return;
    }
    const file = path.resolve(root, "." + name);
    if (!file.startsWith(root + path.sep)) {
      res.writeHead(403).end();
      return;
    }
    if (
      name.startsWith("/web/") &&
      !file.startsWith(path.join(root, "web") + path.sep)
    ) {
      res.writeHead(403).end();
      return;
    }
    fs.readFile(file, (error, data) => {
      if (error) {
        res.writeHead(404).end();
        return;
      }
      if (name === "/index.html") {
        try {
          data = inlineTouchIcon(
            data.toString("utf8"),
            fs.readFileSync(path.join(root, "apple-touch-icon.png")),
          );
        } catch {
          res.writeHead(500).end("Unable to prepare the home-screen icon.");
          return;
        }
      }
      res.writeHead(200, {
        "Content-Type": types[path.extname(file)] || "application/octet-stream",
        "Cache-Control": "no-store",
        "X-Content-Type-Options": "nosniff",
      });
      res.end(data);
    });
  })
  .listen(port, "127.0.0.1", () =>
    console.log(`Overtime web: http://127.0.0.1:${port}`),
  );
