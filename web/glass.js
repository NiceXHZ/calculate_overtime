"use strict";

// Original renderer: a bounded background lens at two small control surfaces.
// Inspired by the scene-alignment / SVG-displacement approach discussed in
// https://github.com/cocolayuan/liquid-glass ; no upstream implementation is bundled.
(() => {
  const ns = "http://www.w3.org/2000/svg";
  let surfaces = [],
    frame = 0,
    svg,
    counter = 0,
    observer;
  const reduced = () =>
    window.matchMedia("(prefers-reduced-transparency: reduce)").matches;
  const enabled = () =>
    document.documentElement.dataset.glass !== "off" &&
    document.documentElement.dataset.modal !== "on" &&
    !reduced();
  function mapFor(width, height, radius) {
    const factor = Math.min(1, 480 / width, 150 / height);
    const w = Math.max(2, Math.round(width * factor)),
      h = Math.max(2, Math.round(height * factor));
    const canvas = document.createElement("canvas");
    canvas.width = w;
    canvas.height = h;
    const context = canvas.getContext("2d");
    if (!context) return null;
    const image = context.createImageData(w, h),
      pixels = image.data;
    const r = Math.min(radius, width / 2, height / 2),
      band = Math.min(13, height / 3);
    for (let y = 0; y < h; y++)
      for (let x = 0; x < w; x++) {
        const px = (x + 0.5) / factor - width / 2,
          py = (y + 0.5) / factor - height / 2;
        const qx = Math.abs(px) - (width / 2 - r),
          qy = Math.abs(py) - (height / 2 - r);
        const ox = Math.max(0, qx),
          oy = Math.max(0, qy),
          length = Math.hypot(ox, oy);
        const distance = length + Math.min(Math.max(qx, qy), 0) - r;
        const nx = length
          ? (Math.sign(px) * ox) / length
          : qx > qy
            ? Math.sign(px)
            : 0;
        const ny = length
          ? (Math.sign(py) * oy) / length
          : qx > qy
            ? 0
            : Math.sign(py);
        const depth = Math.max(0, Math.min(1, -distance / band));
        const bend =
          distance <= 0 && depth < 1
            ? Math.sin(depth * Math.PI) * (1 - depth) * 0.75
            : 0;
        const i = (y * w + x) * 4;
        pixels[i] = Math.round(128 + nx * bend * 110);
        pixels[i + 1] = Math.round(128 + ny * bend * 110);
        pixels[i + 2] = 128;
        pixels[i + 3] = 255;
      }
    context.putImageData(image, 0, 0);
    return canvas.toDataURL("image/png");
  }
  function element(name, attrs) {
    const node = document.createElementNS(ns, name);
    for (const [key, value] of Object.entries(attrs))
      node.setAttribute(key, String(value));
    return node;
  }
  function update() {
    frame = 0;
    if (document.hidden) return;
    for (const surface of surfaces) {
      surface.lens.hidden = !enabled();
      if (!enabled()) continue;
      const rect = surface.host.getBoundingClientRect();
      if (
        !rect.width ||
        !rect.height ||
        rect.bottom < 0 ||
        rect.top > innerHeight
      )
        continue;
      const alignment =
        [rect.left, rect.top, rect.width, rect.height, innerWidth, innerHeight]
          .map((value) => Math.round(value * 10))
          .join(":") +
        ":" +
        document.documentElement.dataset.theme;
      // Scrolling beneath a fixed surface does not move its sampled, fixed gradient scene.
      if (surface.alignment === alignment) continue;
      const key = Math.round(rect.width) + ":" + Math.round(rect.height);
      if (surface.key !== key) {
        const radius =
          parseFloat(getComputedStyle(surface.host).borderTopLeftRadius) || 20;
        const map = mapFor(rect.width, rect.height, radius);
        if (!map) {
          surface.lens.hidden = true;
          continue;
        }
        const filter = element("filter", {
          x: "-10%",
          y: "-30%",
          width: "120%",
          height: "160%",
          "color-interpolation-filters": "sRGB",
        });
        filter.append(
          element("feImage", {
            href: map,
            x: 0,
            y: 0,
            width: "100%",
            height: "100%",
            preserveAspectRatio: "none",
            result: "lens-map",
          }),
        );
        filter.append(
          element("feDisplacementMap", {
            in: "SourceGraphic",
            in2: "lens-map",
            scale: 18,
            xChannelSelector: "R",
            yChannelSelector: "G",
          }),
        );
        if (surface.filter) surface.filter.remove();
        svg.append(filter);
        surface.filter = filter;
        surface.key = key;
      }
      // Version only on geometry events, never an always-running paint loop.
      const id = "overtime-lens-" + ++counter;
      surface.filter.id = id;
      surface.alignment = alignment;
      surface.copy.style.width = innerWidth + "px";
      surface.copy.style.height = innerHeight + "px";
      surface.copy.style.transform =
        "translate(" + -rect.left + "px," + -rect.top + "px)";
      surface.lens.style.filter = 'url("#' + id + '")';
    }
  }
  function schedule() {
    if (!frame && !document.hidden) frame = requestAnimationFrame(update);
  }
  window.refreshLiquidGlass = schedule;
  window.initializeLiquidGlass = () => {
    if (svg || !document.querySelector(".scene-art")) return;
    svg = element("svg", {
      width: 0,
      height: 0,
      "aria-hidden": "true",
      class: "svg-library",
      focusable: "false",
    });
    document.body.append(svg);
    for (const host of document.querySelectorAll(
      ".topbar.liquid,.action-dock.liquid",
    )) {
      const lens = document.createElement("div");
      lens.className = "glass-refraction";
      lens.setAttribute("aria-hidden", "true");
      // Only the gradient scene is sampled. Avoid cloning shadows, page content, or interactive nodes.
      const copy = document
        .querySelector(".scene > .scene-art")
        .cloneNode(false);
      lens.append(copy);
      host.prepend(lens);
      surfaces.push({ host, lens, copy, key: null, filter: null });
    }
    if (typeof ResizeObserver === "function") {
      observer = new ResizeObserver(schedule);
      surfaces.forEach((surface) => observer.observe(surface.host));
    }
    window.addEventListener("resize", schedule, { passive: true });
    window.addEventListener("scroll", schedule, { passive: true });
    document.addEventListener("visibilitychange", schedule);
    const transparency = window.matchMedia(
      "(prefers-reduced-transparency: reduce)",
    );
    if (transparency.addEventListener)
      transparency.addEventListener("change", schedule);
    schedule();
  };
})();
