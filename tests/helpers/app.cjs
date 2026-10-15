const fs = require("node:fs");
const vm = require("node:vm");
const path = require("node:path");
const script = ["core.js", "holiday-service.js", "app.js"]
  .map((file) =>
    fs.readFileSync(path.join(__dirname, "../../web", file), "utf8"),
  )
  .join("\n");

function app(now = "2026-09-14T00:38:02+08:00", options = {}) {
  let currentNow = now;
  const elements = new Map();
  function element() {
    const classes = new Set();
    const attributes = new Map();
    return {
      textContent: "",
      innerHTML: "",
      value: "",
      files: [],
      style: {},
      children: [],
      dataset: {},
      classList: {
        add: (x) => classes.add(x),
        remove: (x) => classes.delete(x),
        contains: (x) => classes.has(x),
        toggle: (x, force) => {
          const on = force === undefined ? !classes.has(x) : force;
          if (on) classes.add(x);
          else classes.delete(x);
          return on;
        },
      },
      addEventListener() {},
      appendChild(child) {
        this.children.push(child);
      },
      removeChild() {},
      click() {},
      setAttribute(name, value) {
        attributes.set(name, String(value));
      },
      getAttribute(name) {
        return attributes.get(name) || "0";
      },
    };
  }
  function get(id) {
    if (!elements.has(id)) elements.set(id, element());
    return elements.get(id);
  }
  const alerts = [],
    blobs = [],
    frames = new Map(),
    storage = new Map();
  let nextFrame = 0;
  class Clock extends Date {
    constructor(...args) {
      super(...(args.length ? args : [currentNow]));
    }
    static now() {
      return new Date(currentNow).getTime();
    }
  }
  const year = new Date(currentNow).getFullYear();
  if (options.seedCalendar !== false) {
    const calendar = options.cachedCalendar || {
      year,
      fetchedAt: Clock.now(),
      source: "test",
      days: options.calendarDays || {},
    };
    storage.set("overtime-holidays-v1-" + year, JSON.stringify(calendar));
  }
  const context = vm.createContext({
    Date: Clock,
    TextEncoder,
    console: Object.assign({}, console, {
      warn: options.logWarnings ? console.warn : () => {},
    }),
    performance: { now: () => 0 },
    document: {
      getElementById: get,
      body: element(),
      createElement: element,
      addEventListener() {},
    },
    window: { matchMedia: () => ({ matches: true }) },
    alert: (msg) => alerts.push(msg),
    confirm: () => true,
    requestAnimationFrame: (callback) => {
      frames.set(++nextFrame, callback);
      return nextFrame;
    },
    cancelAnimationFrame: (id) => frames.delete(id),
    setTimeout: (callback) => callback(),
    clearTimeout() {},
    AbortController: globalThis.AbortController,
    fetch:
      options.fetch ||
      (() => Promise.reject(new Error("unexpected network request"))),
    localStorage: {
      getItem: (key) => (storage.has(key) ? storage.get(key) : null),
      setItem: (key, value) => storage.set(key, String(value)),
      removeItem: (key) => storage.delete(key),
    },
    Blob: class {
      constructor(parts) {
        blobs.push(parts.join(""));
      }
    },
    URL: { createObjectURL: () => "blob:test", revokeObjectURL() {} },
  });
  vm.runInContext(script, context);
  return {
    context,
    get,
    alerts,
    blobs,
    frames,
    storage,
    setNow: (value) => {
      currentNow = value;
    },
    run: (source) => vm.runInContext(source, context),
    load: (data) =>
      context.loadDataToMemory({
        name: "test.json",
        text: async () => JSON.stringify(data),
      }),
  };
}

module.exports = { app };
