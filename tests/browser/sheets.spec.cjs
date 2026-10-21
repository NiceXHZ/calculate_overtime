const { test, expect } = require("@playwright/test");
const sample = require("../../overtime_log.example.json");

async function load(page, data = sample) {
  await page.locator("#fileInput").setInputFiles({
    name: "motion-check.json",
    mimeType: "application/json",
    buffer: Buffer.from(JSON.stringify(data)),
  });
  await expect(page.locator("#statHours")).not.toHaveText("—");
}
async function settled(page, id) {
  await expect
    .poll(() => page.locator(id).getAttribute("data-phase"))
    .toBeNull();
}
async function pull(page, id, distance) {
  const box = await page.locator(id + " .sheet-handle").boundingBox();
  const x = box.x + box.width / 2;
  const y = box.y + box.height / 2;
  await page.mouse.move(x, y);
  await page.mouse.down();
  await page.mouse.move(x, y + distance, { steps: 6 });
}
const errors = new WeakMap();
test.beforeEach(async ({ page }) => {
  errors.set(page, []);
  page.on("pageerror", (error) => errors.get(page).push(error.message));
  await page.setViewportSize({ width: 390, height: 844 });
  await page.emulateMedia({ reducedMotion: "no-preference" });
  await page.clock.setFixedTime(new Date("2026-10-21T20:00:00+08:00"));
  await page.route("https://**/*", (route) => route.abort());
  await page.goto("/");
  await load(page);
});
test.afterEach(async ({ page }) => expect(errors.get(page)).toEqual([]));

test("all five panels slide in and out while retaining modal focus and scroll locking", async ({
  page,
}) => {
  test.setTimeout(60000);
  const panels = [
    ["#settingsButton", "#settingsOverlay", "#settingsTitle"],
    ["#monthPickerButton", "#monthOverlay", "#monthTitle"],
    ["#dockAdd", "#editorOverlay", "#editorTitle"],
    ['.heat-cell[data-date="2026-09-12"]', "#deleteOverlay", "#deleteTitle"],
  ];
  async function checkOpen(id, title) {
    await expect(page.locator(id)).toHaveAttribute("data-phase", "entering");
    await expect(page.locator(title)).toBeFocused();
    expect(
      await page
        .locator(id)
        .evaluate((el) =>
          el
            .getAnimations()
            .some(
              (animation) =>
                animation.effect.target === el &&
                animation.effect.getTiming().duration === 480,
            ),
        ),
    ).toBeTruthy();
    await settled(page, id);
    expect(
      await page
        .locator(id)
        .evaluate((el) => el.getBoundingClientRect().bottom),
    ).toBeLessThanOrEqual(845);
  }
  async function checkClose(id) {
    await page
      .locator(id + " [data-close]")
      .first()
      .click();
    await expect(page.locator(id)).toHaveAttribute("data-phase", "closing");
    await expect(page.locator(id)).toHaveAttribute("open", "");
    expect(await page.evaluate(() => document.body.style.overflow)).toBe(
      "hidden",
    );
    await expect(page.locator(id)).not.toBeVisible();
  }
  for (const [trigger, id, title] of panels) {
    await page.locator(trigger).click();
    await checkOpen(id, title);
    if (id === "#editorOverlay") {
      await expect(page.locator("#newDate")).not.toBeFocused();
      await expect(page.locator("#newTime")).not.toBeFocused();
    }
    await checkClose(id);
    await expect(page.locator(trigger)).toBeFocused();
  }
  await page.locator('.heat-cell[data-date="2026-09-12"]').click();
  await settled(page, "#deleteOverlay");
  await page.locator("#deleteList button").first().click();
  await checkOpen("#confirmOverlay", "#confirmTitle");
  await checkClose("#confirmOverlay");
  await expect(page.locator("#deleteOverlay")).toBeVisible();
  await expect(page.locator("#deleteList .delete-option")).toHaveCount(4);
  await page.keyboard.press("Escape");
  await expect(page.locator("#deleteOverlay")).not.toBeVisible();
  expect(await page.evaluate(() => document.body.style.overflow)).toBe("");
});

test("handle dragging follows the pointer, rebounds short pulls, and dismisses a long pull", async ({
  page,
}) => {
  await page.locator("#dockAdd").click();
  await settled(page, "#editorOverlay");
  const before = await page.locator("#editorOverlay").boundingBox();
  await pull(page, "#editorOverlay", 14);
  await expect(page.locator("#editorOverlay")).toHaveAttribute(
    "data-phase",
    "dragging",
  );
  expect(
    (await page.locator("#editorOverlay").boundingBox()).y - before.y,
  ).toBeCloseTo(14, 0);
  await page.mouse.up();
  await settled(page, "#editorOverlay");
  expect((await page.locator("#editorOverlay").boundingBox()).y).toBeCloseTo(
    before.y,
    0,
  );
  await pull(page, "#editorOverlay", 180);
  await page.mouse.up();
  await expect(page.locator("#editorOverlay")).not.toBeVisible();
  await expect(page.locator("#dockAdd")).toBeFocused();
  expect(await page.evaluate(() => document.body.style.overflow)).toBe("");
  await page.locator("#dockAdd").click();
  await settled(page, "#editorOverlay");
  expect((await page.locator("#editorOverlay").boundingBox()).y).toBeCloseTo(
    before.y,
    0,
  );
});

test("content scrolling and interrupted gestures preserve the panel and unsaved input", async ({
  page,
}) => {
  const manyDays = Object.fromEntries(
    Array.from({ length: 30 }, (_, index) => {
      const date = "2026-09-" + String(index + 1).padStart(2, "0");
      return [
        date,
        { entries: [date + "T09:00:00+08:00", date + "T20:00:00+08:00"] },
      ];
    }),
  );
  await load(page, manyDays);
  await page.locator("#dockAdd").click();
  await settled(page, "#editorOverlay");
  await page.locator("#newTime").fill("21:23");
  const body = page.locator("#editorOverlay .sheet-body");
  await body.hover();
  await page.mouse.wheel(0, 220);
  await expect
    .poll(() => body.evaluate((el) => el.scrollTop))
    .toBeGreaterThan(0);
  await expect(page.locator("#editorOverlay")).not.toHaveAttribute(
    "data-phase",
    "dragging",
  );
  await page.locator("#editorOverlay .sheet-handle").evaluate((handle) => {
    handle.addEventListener(
      "pointerdown",
      (event) => {
        window.testPointerId = event.pointerId;
      },
      { once: true },
    );
  });
  await pull(page, "#editorOverlay", 70);
  await page.locator("#editorOverlay .sheet-handle").evaluate((handle) => {
    handle.dispatchEvent(
      new PointerEvent("pointercancel", {
        pointerId: window.testPointerId,
        bubbles: true,
      }),
    );
  });
  await page.mouse.up();
  await settled(page, "#editorOverlay");
  await expect(page.locator("#editorOverlay")).toBeVisible();
  await expect(page.locator("#newTime")).toHaveValue("21:23");
  await pull(page, "#editorOverlay", 50);
  await page.waitForTimeout(150); // A held pull is not a fast flick.
  await page.mouse.up();
  await settled(page, "#editorOverlay");
  await expect(page.locator("#editorOverlay")).toBeVisible();
});

test("reopening during exit and replacing stacked panels cannot leave stale modal state", async ({
  page,
}) => {
  await page.locator("#dockAdd").click();
  await settled(page, "#editorOverlay");
  await page.evaluate(() => {
    closeDialog("editorOverlay");
    openDialog("editorOverlay");
  });
  await settled(page, "#editorOverlay");
  await expect(page.locator("#editorTitle")).toBeFocused();
  await expect(page.locator("#editorOverlay")).toHaveAttribute(
    "aria-hidden",
    "false",
  );
  await page.locator('#tableBody [data-date="2026-09-12"]').click();
  await settled(page, "#deleteOverlay");
  await page.locator("#dayAddButton").click();
  await settled(page, "#editorOverlay");
  await expect(page.locator("#newDate")).toHaveValue("2026-09-12");
  await expect(page.locator("#editorTitle")).toBeFocused();
  await expect(page.locator("dialog[open]")).toHaveCount(1);
  await page.keyboard.press("Escape");
  await expect(page.locator("dialog[open]")).toHaveCount(0);
  await expect(page.locator("html")).toHaveAttribute("data-modal", "off");
  expect(await page.evaluate(() => document.body.style.overflow)).toBe("");
});

test("reduced motion and changes to that preference never leave an unfinished close", async ({
  page,
}) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.locator("#dockAdd").click();
  await settled(page, "#editorOverlay");
  await expect(page.locator("#editorTitle")).toBeFocused();
  expect(
    await page
      .locator("#editorOverlay")
      .evaluate((el) => el.getAnimations().length),
  ).toBe(0);
  await pull(page, "#editorOverlay", 180);
  await page.mouse.up();
  await expect(page.locator("#editorOverlay")).not.toBeVisible();
  await page.emulateMedia({ reducedMotion: "no-preference" });
  await page.locator("#settingsButton").click();
  await settled(page, "#settingsOverlay");
  await page.evaluate(() => closeDialog("settingsOverlay"));
  await page.emulateMedia({ reducedMotion: "reduce" });
  await expect(page.locator("#settingsOverlay")).not.toBeVisible();
  await expect(page.locator("#settingsButton")).toBeFocused();
  expect(await page.evaluate(() => document.body.style.overflow)).toBe("");
});

test("nested day details share the slower timing and let the parent recede and return", async ({
  page,
}) => {
  await page.locator("#dockAdd").click();
  await settled(page, "#editorOverlay");
  await page.locator('#tableBody [data-date="2026-09-12"]').click();
  await expect(page.locator("#editorOverlay")).toHaveAttribute(
    "data-underlay",
    "true",
  );
  const progress = await page.locator("#deleteOverlay").evaluate((dialog) => {
    const animation = dialog
      .getAnimations()
      .find((item) =>
        item.effect.getKeyframes().some((frame) => frame.transform),
      );
    animation.pause();
    animation.currentTime = 120;
    const from = new DOMMatrixReadOnly(
      animation.effect.getKeyframes()[0].transform,
    ).m42;
    const current = new DOMMatrixReadOnly(getComputedStyle(dialog).transform)
      .m42;
    const result = {
      duration: animation.effect.getTiming().duration,
      distanceRatio: (from - current) / from,
    };
    animation.play();
    return result;
  });
  expect(progress.duration).toBe(480);
  expect(progress.distanceRatio).toBeGreaterThan(0.05);
  expect(progress.distanceRatio).toBeLessThan(0.5);
  await settled(page, "#deleteOverlay");
  await page.locator("#deleteOverlay [data-close]").first().click();
  await expect(page.locator("#deleteOverlay")).toHaveAttribute(
    "data-phase",
    "closing",
  );
  await expect(page.locator("#editorOverlay")).not.toHaveAttribute(
    "data-underlay",
    "true",
  );
  await expect(page.locator("#deleteOverlay")).not.toBeVisible();
  await expect(page.locator("#editorTitle")).toBeFocused();
  await expect(page.locator("#editorOverlay")).toBeVisible();
});

test("pointer focus never leaves a close-button ring while keyboard focus stays visible", async ({
  page,
}) => {
  await page.locator("#dockAdd").click();
  await settled(page, "#editorOverlay");
  const close = page.locator("#editorOverlay .sheet-heading [data-close]");
  await close.evaluate((button) => button.focus());
  await expect(page.locator("html")).toHaveAttribute("data-input", "pointer");
  expect(
    await close.evaluate((button) => getComputedStyle(button).outlineStyle),
  ).toBe("none");
  await page.keyboard.press("Tab");
  await page.keyboard.press("Shift+Tab");
  await expect(close).toBeFocused();
  await expect(page.locator("html")).toHaveAttribute("data-input", "keyboard");
  expect(
    await close.evaluate((button) => getComputedStyle(button).outlineStyle),
  ).toBe("solid");
});

test("record filters and notifications animate without an old exit hiding newer content", async ({
  page,
}) => {
  await page.locator('#recordFilters [data-filter="overtime"]').click();
  await expect(page.locator("#result .day-card")).toHaveCount(2);
  expect(
    await page
      .locator("#result")
      .evaluate((element) =>
        element
          .getAnimations()
          .some((animation) => animation.effect.getTiming().duration === 280),
      ),
  ).toBeTruthy();
  await page.locator("#closeToast").click();
  await expect(page.locator("#toast")).not.toBeVisible();
  await page.evaluate(() => {
    notify("旧提示");
    setNoticeVisible("toast", false);
    notify("保留最新提示");
  });
  await expect(page.locator("#toastText")).toHaveText("保留最新提示");
  const halfwayOpacity = await page.locator("#toast").evaluate((element) => {
    const animation = element
      .getAnimations()
      .find((item) => item.effect.getTiming().duration === 280);
    animation.pause();
    animation.currentTime = 140;
    const opacity = Number(getComputedStyle(element).opacity);
    animation.play();
    return opacity;
  });
  expect(halfwayOpacity).toBeGreaterThan(0.3);
  expect(halfwayOpacity).toBeLessThan(0.99);
  await page.waitForTimeout(350);
  await expect(page.locator("#toast")).toBeVisible();
  await page.locator("#closeToast").click();
  await expect(page.locator("#toast")).not.toBeVisible();
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.locator('#recordFilters [data-filter="all"]').click();
  await expect(page.locator("#result .day-card")).toHaveCount(3);
  expect(
    await page
      .locator("#result")
      .evaluate((element) => element.getAnimations().length),
  ).toBe(0);
});
