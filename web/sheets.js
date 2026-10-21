"use strict";

// Native dialogs retain their focus trap and backdrop until the exit finishes.
(() => {
  const states = new Map();
  const stack = [];
  const contentAnimations = new WeakMap();
  const noticeAnimations = new WeakMap();
  const noticeVisibility = new WeakMap();
  const auxiliaryAnimations = new Set();
  const reduced = window.matchMedia("(prefers-reduced-motion: reduce)");
  const root = document.documentElement;
  const duration = (kind = "panel") =>
    parseFloat(
      getComputedStyle(root).getPropertyValue(`--motion-${kind}-duration`),
    ) || (kind === "panel" ? 480 : 280);
  const easing = () =>
    getComputedStyle(root).getPropertyValue("--motion-panel-easing").trim() ||
    "cubic-bezier(0.32, 0, 0.24, 1)";
  let drag = null;
  let dismiss;

  function syncLayers() {
    const top = stack
      .filter((dialog) => dialog.open && !stateFor(dialog).finish)
      .at(-1);
    for (const dialog of states.keys()) {
      if (dialog.open && dialog !== top && !stateFor(dialog).finish)
        dialog.dataset.underlay = "true";
      else delete dialog.dataset.underlay;
    }
  }

  function stateFor(dialog) {
    if (!states.has(dialog))
      states.set(dialog, { animation: null, finish: null, offset: 0 });
    return states.get(dialog);
  }
  function stop(dialog) {
    const state = stateFor(dialog);
    const animation = state.animation;
    state.animation = null;
    if (animation) animation.cancel();
  }
  function pose(dialog, offset) {
    stateFor(dialog).offset = offset;
    dialog.style.setProperty("--sheet-offset", offset + "px");
    dialog.style.setProperty(
      "--sheet-shade",
      String(Math.max(0, 1 - offset / Math.max(1, dialog.offsetHeight))),
    );
  }
  function reset(dialog) {
    stop(dialog);
    const state = stateFor(dialog);
    state.offset = 0;
    state.finish = null;
    dialog.style.removeProperty("--sheet-offset");
    dialog.style.removeProperty("--sheet-shade");
    delete dialog.dataset.phase;
    dialog.inert = false;
    if (!dialog.open) {
      const index = stack.indexOf(dialog);
      if (index >= 0) stack.splice(index, 1);
      syncLayers();
    }
  }
  function travel(dialog) {
    // Phone sheets start fully below the viewport; wider dialogs make a softer lift.
    return window.matchMedia("(max-width: 540px)").matches
      ? window.innerHeight - dialog.getBoundingClientRect().top + 16
      : Math.max(48, Math.min(100, dialog.offsetHeight * 0.2));
  }
  function animate(dialog, frames, duration, easing, done) {
    stop(dialog);
    if (reduced.matches || !dialog.animate) {
      done();
      return;
    }
    const state = stateFor(dialog);
    const animation = dialog.animate(frames, {
      duration,
      easing,
      fill: "both",
    });
    state.animation = animation;
    animation.finished.then(
      () => {
        if (state.animation !== animation) return;
        state.animation = null;
        done();
        animation.cancel();
      },
      () => {}, // Reopening or a new gesture intentionally cancels an old animation.
    );
  }
  function releasePointer() {
    const current = drag;
    drag = null;
    if (current?.handle.hasPointerCapture(current.pointerId))
      current.handle.releasePointerCapture(current.pointerId);
    return current;
  }
  function rebound(dialog) {
    const offset = stateFor(dialog).offset;
    dialog.dataset.phase = "settling";
    pose(dialog, 0);
    animate(
      dialog,
      [
        { transform: `translateY(${offset}px)` },
        { transform: "translateY(0)" },
      ],
      duration("rebound"),
      easing(),
      () => reset(dialog),
    );
  }
  function completeClose(dialog) {
    const state = stateFor(dialog);
    const finish = state.finish;
    const wasTop = stack.filter((item) => item.open).at(-1) === dialog;
    const focused = document.activeElement;
    state.finish = null;
    reset(dialog);
    if (finish) finish();
    const index = stack.indexOf(dialog);
    if (index >= 0) stack.splice(index, 1);
    syncLayers();
    const top = stack
      .filter((item) => item.open && !stateFor(item).finish)
      .at(-1);
    if (wasTop && top && root.dataset.input !== "keyboard")
      top.querySelector("[autofocus]")?.focus({ preventScroll: true });
    else if (
      !wasTop &&
      top?.contains(focused) &&
      document.activeElement !== focused
    )
      focused.focus({ preventScroll: true });
  }
  function prepareOpen(dialog) {
    if (drag) {
      const previous = releasePointer();
      reset(previous.dialog);
    }
    const state = stateFor(dialog);
    const style = getComputedStyle(dialog);
    const resume = dialog.open
      ? { transform: style.transform, opacity: style.opacity }
      : null;
    state.finish = null;
    reset(dialog);
    state.resume = resume;
  }
  function enter(dialog) {
    if (!stack.includes(dialog)) stack.push(dialog);
    syncLayers();
    const resume = stateFor(dialog).resume;
    dialog.dataset.phase = "entering";
    animate(
      dialog,
      [
        resume || { transform: `translateY(${travel(dialog)}px)`, opacity: 0 },
        { transform: "translateY(0)", opacity: 1 },
      ],
      duration(),
      easing(),
      () => reset(dialog),
    );
  }
  function close(dialog, finish) {
    const state = stateFor(dialog);
    if (state.finish) return;
    if (drag?.dialog === dialog) releasePointer();
    const transform = getComputedStyle(dialog).transform;
    const opacity = getComputedStyle(dialog).opacity;
    const offset =
      transform === "none" ? 0 : new DOMMatrixReadOnly(transform).m42;
    stop(dialog);
    state.finish = finish;
    dialog.inert = true;
    dialog.dataset.phase = "closing";
    syncLayers();
    animate(
      dialog,
      [
        { transform: `translateY(${offset}px)`, opacity },
        { transform: `translateY(${offset + travel(dialog)}px)`, opacity: 0 },
      ],
      duration(),
      easing(),
      () => completeClose(dialog),
    );
  }
  function sample(event) {
    const now = performance.now();
    const offset = Math.max(0, drag.offset + event.clientY - drag.y);
    drag.samples.push({ y: offset, time: now });
    while (drag.samples.length > 1 && drag.samples[0].time < now - 100)
      drag.samples.shift();
    pose(drag.dialog, offset);
  }
  function initialize(onDismiss) {
    dismiss = onDismiss;
    root.dataset.input = "pointer";
    window.addEventListener(
      "pointerdown",
      () => {
        root.dataset.input = "pointer";
      },
      true,
    );
    window.addEventListener(
      "keydown",
      (event) => {
        if (!event.metaKey && !event.ctrlKey && !event.altKey)
          root.dataset.input = "keyboard";
      },
      true,
    );
    document.querySelectorAll(".sheet-handle").forEach((handle) => {
      const dialog = handle.closest("dialog");
      handle.addEventListener("pointerdown", (event) => {
        if (
          drag ||
          !dialog.open ||
          stateFor(dialog).finish ||
          event.button !== 0 ||
          event.isPrimary === false
        )
          return;
        event.preventDefault();
        const transform = getComputedStyle(dialog).transform;
        const offset = Math.max(
          0,
          transform === "none" ? 0 : new DOMMatrixReadOnly(transform).m42,
        );
        stop(dialog);
        dialog.dataset.phase = "dragging";
        pose(dialog, offset);
        drag = {
          dialog,
          handle,
          pointerId: event.pointerId,
          y: event.clientY,
          offset,
          samples: [{ y: offset, time: performance.now() }],
        };
        handle.setPointerCapture(event.pointerId);
      });
      handle.addEventListener("pointermove", (event) => {
        if (drag?.handle !== handle || drag.pointerId !== event.pointerId)
          return;
        event.preventDefault();
        sample(event);
      });
      handle.addEventListener("pointerup", (event) => {
        if (drag?.handle !== handle || drag.pointerId !== event.pointerId)
          return;
        sample(event);
        const current = releasePointer();
        const offset = stateFor(dialog).offset;
        const first = current.samples[0];
        const last = current.samples.at(-1);
        const speed = (last.y - first.y) / Math.max(1, last.time - first.time);
        const distance = Math.max(0, event.clientY - current.y);
        const threshold = Math.min(
          128,
          Math.max(56, dialog.offsetHeight * 0.22),
        );
        if (distance >= threshold || (distance >= 28 && speed > 0.6))
          dismiss(dialog.id);
        else rebound(dialog);
      });
      const cancel = (event) => {
        if (drag?.handle !== handle || drag.pointerId !== event.pointerId)
          return;
        releasePointer();
        rebound(dialog);
      };
      handle.addEventListener("pointercancel", cancel);
      handle.addEventListener("lostpointercapture", cancel);
    });
    const cancelGesture = () => {
      if (!drag) return;
      const current = releasePointer();
      reset(current.dialog);
    };
    window.addEventListener("blur", cancelGesture);
    window.addEventListener("resize", cancelGesture);
    document.addEventListener("visibilitychange", () => {
      if (document.hidden) cancelGesture();
    });
    reduced.addEventListener("change", () => {
      if (!reduced.matches) return;
      for (const [dialog, state] of states) {
        if (state.finish) completeClose(dialog);
        else if (dialog.dataset.phase !== "dragging") reset(dialog);
      }
      for (const animation of auxiliaryAnimations)
        if (["running", "paused"].includes(animation.playState))
          animation.finish();
    });
  }
  function reveal(element) {
    if (
      !element ||
      reduced.matches ||
      !element.animate ||
      element.hidden ||
      (element.closest("dialog") && !element.closest("dialog").open)
    )
      return;
    contentAnimations.get(element)?.cancel();
    const animation = element.animate(
      [
        { opacity: 0.45, translate: "0 8px" },
        { opacity: 1, translate: "0 0" },
      ],
      { duration: duration("content"), easing: "ease-out" },
    );
    contentAnimations.set(element, animation);
    auxiliaryAnimations.add(animation);
    animation.finished.then(
      () => {
        auxiliaryAnimations.delete(animation);
        if (contentAnimations.get(element) === animation)
          contentAnimations.delete(element);
        animation.cancel();
      },
      () => auxiliaryAnimations.delete(animation),
    );
  }
  function notice(element, visible) {
    if (
      noticeVisibility.get(element) === visible &&
      (noticeAnimations.has(element) || element.hidden === !visible)
    )
      return;
    noticeVisibility.set(element, visible);
    const opacity = element.hidden
      ? 0
      : Number(getComputedStyle(element).opacity);
    const translate = element.hidden
      ? "0 12px"
      : getComputedStyle(element).translate;
    const previous = noticeAnimations.get(element);
    noticeAnimations.delete(element);
    previous?.cancel();
    if (visible) element.hidden = false;
    element.inert = !visible;
    const finish = () => {
      element.hidden = !visible;
    };
    if (reduced.matches || !element.animate || element.hidden) {
      finish();
      return;
    }
    const animation = element.animate(
      visible
        ? [
            { opacity, translate },
            { opacity: 1, translate: "0 0" },
          ]
        : [
            { opacity, translate },
            { opacity: 0, translate: "0 8px" },
          ],
      { duration: duration("content"), easing: "ease-out", fill: "both" },
    );
    noticeAnimations.set(element, animation);
    auxiliaryAnimations.add(animation);
    animation.finished.then(
      () => {
        auxiliaryAnimations.delete(animation);
        if (noticeAnimations.get(element) !== animation) return;
        noticeAnimations.delete(element);
        finish();
        animation.cancel();
      },
      () => auxiliaryAnimations.delete(animation),
    );
  }
  window.sheetMotion = {
    prepareOpen,
    enter,
    close,
    reset,
    initialize,
    reveal,
    notice,
    isClosing: (dialog) => !!stateFor(dialog).finish,
  };
})();
