const { test, expect } = require("@playwright/test");
const fs = require("node:fs/promises");
const path = require("node:path");
const AxeBuilder = require("@axe-core/playwright").default;
const sample = JSON.parse(
  require("node:fs").readFileSync(
    path.join(__dirname, "../../overtime_log.example.json"),
    "utf8",
  ),
);
const fixture = {
  ...sample,
  "2025-12-30": { entries: "2025-12-30T20:00:00+08:00", note: "保留历史月份" },
};
const runtimeErrors = new WeakMap();
async function load(page, data = fixture, name = "work.json") {
  await page.locator("#fileInput").setInputFiles({
    name,
    mimeType: "application/json",
    buffer: Buffer.from(JSON.stringify(data)),
  });
  await expect(page.locator("#fileTitle")).toHaveText(name);
  await expect(page.locator("#statHours")).not.toHaveText("—");
}
async function download(page, testInfo, button, name) {
  const event = page.waitForEvent("download");
  await page.locator(button).click();
  const result = await event;
  const file = testInfo.outputPath(name);
  await result.saveAs(file);
  return fs.readFile(file, "utf8");
}
async function insideViewport(page, selector) {
  const box = await page.locator(selector).boundingBox();
  expect(box).toBeTruthy();
  const viewport = page.viewportSize();
  expect(box.x).toBeGreaterThanOrEqual(-1);
  expect(box.y).toBeGreaterThanOrEqual(-1);
  expect(box.x + box.width).toBeLessThanOrEqual(viewport.width + 1);
  expect(box.y + box.height).toBeLessThanOrEqual(viewport.height + 1);
}
test.beforeEach(async ({ page }) => {
  const errors = [];
  runtimeErrors.set(page, errors);
  page.on("pageerror", (error) => errors.push(error.message));
  page.on("dialog", (dialog) => dialog.accept());
  await page.clock.setFixedTime(new Date("2026-10-15T20:00:00+08:00"));
  await page.route("https://**/*", (route) => route.abort());
  await page.goto("/");
});
test.afterEach(async ({ page }) => {
  expect(runtimeErrors.get(page) || []).toEqual([]);
});
test("import auto-selects a recorded month and full JSON / monthly CSV preserve correct scope", async ({
  page,
}, testInfo) => {
  await load(page);
  await expect(page.locator("#selectedMonthLabel")).toHaveText("2026年9月");
  await expect(page.locator("#statHours")).toHaveText("10.5");
  await expect(page.locator("#statDays")).toHaveText("3");
  await expect(page.locator(".heat-cell")).toHaveCount(30);
  await expect(
    page.locator('.heat-cell[data-date="2026-09-12"]'),
  ).toHaveAttribute("data-level", "4");
  const json = JSON.parse(
    await download(page, testInfo, "#dockExport", "backup.json"),
  );
  expect(json["2025-12-30"].note).toBe("保留历史月份");
  expect(json["2026-09-12"].entries).toHaveLength(4);
  const csv = await download(page, testInfo, "#csvButton", "month.csv");
  expect(csv.startsWith("\uFEFF")).toBeTruthy();
  expect(csv).toContain("2026-09-12");
  expect(csv).not.toContain("2025-12-30");
  expect(csv).toContain("Asia/Shanghai");
});
test("heatmap detail, confirmation, single deletion and undo stay consistent", async ({
  page,
}) => {
  await load(page);
  await page.locator('.heat-cell[data-date="2026-09-12"]').click();
  await expect(page.locator("#deleteOverlay")).toBeVisible();
  await expect(page.locator("#deleteList .delete-option")).toHaveCount(4);
  await page.locator("#deleteList button").first().click();
  await expect(page.locator("#confirmOverlay")).toBeVisible();
  await page.locator("#confirmAccept").click();
  await expect(page.locator("#statHours")).toHaveText("7.5");
  await expect(page.locator("#deleteList .delete-option")).toHaveCount(3);
  await expect(page.locator("#dayUndoButton")).toBeVisible();
  await page.locator("#dayUndoButton").click();
  await expect(page.locator("#deleteList .delete-option")).toHaveCount(4);
  await page.locator('[data-close="deleteOverlay"]').last().click();
  await expect(page.locator("#statHours")).toHaveText("10.5");
  await expect(
    page.locator('.heat-cell[data-date="2026-09-12"]'),
  ).toHaveAttribute("aria-label", /4次打卡/);
});
test("past-month manual entry rejects duplicates and future time, then supports undo", async ({
  page,
}) => {
  await load(page);
  await page.locator("#dockAdd").click();
  await page.locator("#newDate").fill("2026-09-10");
  await page.locator("#newTime").fill("19:00");
  await page.locator("#savePunchButton").click();
  await expect(page.locator("#punchError")).toContainText("已有打卡");
  await page.locator("#newTime").fill("20:00");
  await page.locator("#savePunchButton").click();
  await expect(page.locator("#editorOverlay")).not.toBeVisible();
  await expect(page.locator("#statHours")).toHaveText("11.5");
  await page.locator("#undoButton").click();
  await expect(page.locator("#statHours")).toHaveText("10.5");
  await page.locator("#currentMonthButton").click();
  await page.locator("#dockAdd").click();
  await page.locator("#newDate").fill("2026-10-16");
  await page.locator("#newTime").fill("09:00");
  await page.locator("#savePunchButton").click();
  await expect(page.locator("#punchError")).toContainText("未来");
});
test("month picker handles leap year, recorded months and selected cutoff", async ({
  page,
}) => {
  await load(page);
  await page.locator("#monthPickerButton").click();
  await page.locator("#yearInput").fill("2024");
  await page.locator("#yearInput").press("Tab");
  await page.locator('#monthGrid [data-month="2024-02"]').click();
  await expect(page.locator(".heat-cell")).toHaveCount(29);
  await expect(page.locator("#statDays")).toHaveText("0");
  await page.locator("#monthPickerButton").click();
  await page.locator('#savedMonths [data-month="2026-09"]').click();
  await page.locator("#settingsButton").click();
  await page.locator("#cutoffInput").fill("18:00");
  await page.locator("#settingsDone").click();
  await expect(page.locator("#statHours")).toHaveText("10.0");
  await expect(page.locator("#ruleSummary")).toContainText("18:00");
});
test("rest-day single punch is flagged; filters never change monthly totals", async ({
  page,
}) => {
  await load(page, {
    ...fixture,
    "2026-09-13": { entries: "2026-09-13T19:00:00+08:00" },
  });
  await expect(page.locator("#incompleteCount")).toHaveText("1");
  await page.locator('[data-filter="incomplete"]').click();
  await expect(page.locator(".day-card")).toHaveCount(1);
  await expect(page.locator("#statHours")).toHaveText("10.5");
  await expect(page.locator('.heat-cell[data-date="2026-09-13"]')).toHaveClass(
    /is-incomplete/,
  );
  await page.locator('[data-filter="overtime"]').click();
  await expect(page.locator(".day-card")).toHaveCount(2);
});
test("bad replacement preserves current data and readable file names never become HTML", async ({
  page,
}) => {
  await load(page, fixture, "<img src=x onerror=alert(1)>.json");
  await page.locator("#fileInput").setInputFiles({
    name: "bad.json",
    mimeType: "application/json",
    buffer: Buffer.from("{"),
  });
  await expect(page.locator("#toastText")).toContainText("原有记录已保留");
  await expect(page.locator("#statHours")).toHaveText("10.5");
  await expect(page.locator("#fileTitle img")).toHaveCount(0);
});
test("draft survives reload and recovers modified records and selected month", async ({
  page,
}) => {
  await load(page);
  await page.locator("#dockAdd").click();
  await page.locator("#newDate").fill("2026-09-10");
  await page.locator("#newTime").fill("20:00");
  await page.locator("#savePunchButton").click();
  await expect(page.locator("#saveStatus")).toContainText("已暂存");
  await page.waitForFunction(
    () =>
      JSON.parse(localStorage.getItem("overtime-web-draft-v2")).dirty === true,
  );
  await page.reload();
  await expect(page.locator("#recoveryBanner")).toBeVisible();
  await expect(page.locator("#statHours")).toHaveText("—");
  await page.locator("#restoreDraftButton").click();
  await expect(page.locator("#statHours")).toHaveText("11.5");
  await expect(page.locator("#selectedMonthLabel")).toHaveText("2026年9月");
});
test("storage quota failure preserves editing and independent backup export", async ({
  page,
}, testInfo) => {
  await page.evaluate(() => {
    const original = Storage.prototype.setItem;
    Storage.prototype.setItem = function (key, value) {
      if (key === "overtime-web-draft-v2")
        throw new DOMException("quota", "QuotaExceededError");
      return original.call(this, key, value);
    };
  });
  await load(page);
  await expect(page.locator("#saveStatus")).toContainText("无法暂存");
  const result = JSON.parse(
    await download(page, testInfo, "#dockExport", "quota-backup.json"),
  );
  expect(result["2026-09-12"].entries).toHaveLength(4);
});
test("cross-tab updates block overwriting another tab draft", async ({
  page,
  context,
}) => {
  await load(page);
  await page.waitForFunction(
    () => !!localStorage.getItem("overtime-web-draft-v2"),
  );
  const other = await context.newPage();
  await other.clock.setFixedTime(new Date("2026-10-15T20:00:00+08:00"));
  await other.goto("/");
  await other.locator("#restoreDraftButton").click();
  await expect(page.locator("#conflictBanner")).toBeVisible();
  await other.locator("#dockAdd").click();
  await other.locator("#newDate").fill("2026-09-10");
  await other.locator("#newTime").fill("20:00");
  await other.locator("#savePunchButton").click();
  await other.waitForFunction(
    () =>
      JSON.parse(localStorage.getItem("overtime-web-draft-v2")).dirty === true,
  );
  await page.locator("#dockAdd").click();
  await page.locator("#newDate").fill("2026-09-10");
  await page.locator("#newTime").fill("21:00");
  await page.locator("#savePunchButton").click();
  await expect(page.locator("#statHours")).toHaveText("12.5");
  const saved = await page.evaluate(
    () =>
      JSON.parse(localStorage.getItem("overtime-web-draft-v2")).data[
        "2026-09-10"
      ].entries,
  );
  expect(saved.some((x) => x.includes("20:00:00"))).toBeTruthy();
  expect(saved.some((x) => x.includes("21:00:00"))).toBeFalsy();
  await other.close();
});
test("mobile, tablet, desktop and landscape keep controls and modal actions within viewport", async ({
  page,
}, testInfo) => {
  test.setTimeout(60000);
  await load(page);
  for (const size of [
    { width: 320, height: 568 },
    { width: 390, height: 844 },
    { width: 768, height: 1024 },
    { width: 1440, height: 1000 },
    { width: 667, height: 375 },
  ]) {
    await page.setViewportSize(size);
    await page.evaluate(() => scrollTo(0, 0));
    expect(
      await page.evaluate(
        () =>
          document.documentElement.scrollWidth -
          document.documentElement.clientWidth,
      ),
    ).toBeLessThanOrEqual(1);
    await insideViewport(page, ".action-dock");
    for (const dark of [false, true]) {
      await page.emulateMedia({ colorScheme: dark ? "dark" : "light" });
      await expect(page.locator("html")).toHaveAttribute(
        "data-theme",
        dark ? "dark" : "light",
      );
      await page.screenshot({
        path: testInfo.outputPath(
          `workspace-${size.width}-${dark ? "dark" : "light"}.png`,
        ),
        fullPage: true,
      });
      await page.locator("#dockAdd").click();
      await insideViewport(page, "#savePunchButton");
      await page.screenshot({
        path: testInfo.outputPath(
          `editor-${size.width}-${dark ? "dark" : "light"}.png`,
        ),
      });
      await page.locator('[data-close="editorOverlay"]').click();
      await page.locator("#monthPickerButton").click();
      await insideViewport(page, "#monthTodayButton");
      await page.locator('[data-close="monthOverlay"]').click();
    }
  }
});
test("keyboard focus, glass fallback and contrast pass accessibility checks in both themes", async ({
  page,
}) => {
  test.setTimeout(60000);
  await load(page);
  for (const theme of ["light", "dark"]) {
    await page.emulateMedia({ colorScheme: theme });
    await expect(page.locator("html")).toHaveAttribute("data-theme", theme);
    const result = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
      .analyze();
    expect(
      result.violations.map((v) => ({
        id: v.id,
        description: v.description,
        nodes: v.nodes.map((n) => n.target),
      })),
    ).toEqual([]);
    await page.locator("#dockAdd").click();
    const dialog = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
      .analyze();
    expect(
      dialog.violations.map((v) => ({
        id: v.id,
        nodes: v.nodes.map((n) => n.target),
      })),
    ).toEqual([]);
    await page.keyboard.press("Escape");
    await expect(page.locator("#editorOverlay")).not.toBeVisible();
  }
  await page.locator("#settingsButton").click();
  await page.locator("#glassToggle").uncheck();
  await page.locator("#settingsDone").click();
  await expect(page.locator("html")).toHaveAttribute("data-glass", "off");
  await page.locator("#dockAdd").click();
  await expect(page.locator("#newDate")).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(page.locator("#dockAdd")).toBeFocused();
});
test("no continuous animation loop remains when idle and direct file opening stays usable", async ({
  page,
}) => {
  await load(page);
  await page.waitForTimeout(250);
  await page.evaluate(() => {
    window.observedFrames = 0;
    const raf = window.requestAnimationFrame;
    window.requestAnimationFrame = function (callback) {
      window.observedFrames++;
      return raf.call(window, callback);
    };
  });
  await page.waitForTimeout(500);
  expect(await page.evaluate(() => window.observedFrames)).toBe(0);
  await page.goto("file://" + path.resolve(__dirname, "../../index.html"));
  await page.locator("#sampleButton").click();
  await expect(page.locator("#statHours")).toHaveText("10.5");
});

test("cancelling a replacement keeps unsaved changes and permits selecting the same file again", async ({
  page,
}) => {
  await load(page);
  await page.locator("#dockAdd").click();
  await page.locator("#newDate").fill("2026-09-10");
  await page.locator("#newTime").fill("20:00");
  await page.locator("#savePunchButton").click();
  const input = {
    name: "replacement.json",
    mimeType: "application/json",
    buffer: Buffer.from("{}"),
  };
  await page.locator("#fileInput").setInputFiles(input);
  await expect(page.locator("#confirmTitle")).toHaveText("载入另一份记录？");
  await page
    .locator('#confirmOverlay [data-close="confirmOverlay"]')
    .last()
    .click();
  await expect(page.locator("#statHours")).toHaveText("11.5");
  await expect(page.locator("#fileInput")).toHaveValue("");
  await page.locator("#fileInput").setInputFiles(input);
  await page.locator("#confirmAccept").click();
  await expect(page.locator("#fileTitle")).toHaveText("replacement.json");
  await expect(page.locator("#statHours")).toHaveText("0.0");
});
test("large daily timelines page records and invalid years never select a different displayed year", async ({
  page,
}) => {
  const start = new Date("2026-09-12T09:00:00+08:00").getTime();
  await load(page, {
    "2026-09-12": {
      entries: Array.from({ length: 1000 }, (_, index) => start + index * 1000),
    },
  });
  await page.locator('.heat-cell[data-date="2026-09-12"]').click();
  await expect(page.locator("#deleteList .delete-option")).toHaveCount(100);
  await page.locator("#morePunchesButton").click();
  await expect(page.locator("#deleteList .delete-option")).toHaveCount(200);
  await page
    .locator('#deleteOverlay [data-close="deleteOverlay"]')
    .last()
    .click();
  await page.locator("#monthPickerButton").click();
  await page.locator("#yearInput").fill("99999");
  await page.locator("#yearInput").press("Tab");
  await expect(page.locator("#yearError")).not.toBeEmpty();
  await expect(page.locator("#monthGrid button").first()).toBeDisabled();
  await page.locator("#yearInput").fill("2025");
  await page.locator("#yearInput").press("Tab");
  await page.locator('[data-month="2025-02"]').first().click();
  await expect(page.locator(".heat-cell")).toHaveCount(28);
});
test("slow holiday response cannot overwrite a newly selected month or enable stale CSV export", async ({
  page,
}) => {
  let release;
  const held = new Promise((resolve) => (release = resolve));
  let requested = false;
  await page.route("https://cdn.jsdelivr.net/**/2027.json", async (route) => {
    requested = true;
    await held;
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        year: 2027,
        days: [{ date: "2027-01-01", isOffDay: true, name: "元旦" }],
      }),
    });
  });
  await page.locator("#fileInput").setInputFiles({
    name: "future.json",
    mimeType: "application/json",
    buffer: Buffer.from(
      JSON.stringify({
        "2027-01-01": {
          entries: ["2027-01-01T09:00:00+08:00", "2027-01-01T18:00:00+08:00"],
        },
      }),
    ),
  });
  await expect.poll(() => requested).toBeTruthy();
  await expect(page.locator("#calculateButton")).toBeDisabled();
  await expect(page.locator("#csvButton")).toBeDisabled();
  await page.locator("#currentMonthButton").click();
  await expect(page.locator("#statHours")).toHaveText("0.0");
  release();
  await page.waitForTimeout(100);
  await expect(page.locator("#selectedMonthLabel")).toHaveText("2026年10月");
  await expect(page.locator("#statHours")).toHaveText("0.0");
  await expect(page.locator("#csvButton")).toBeDisabled();
});

test("moving from editor history through day detail back to editing preserves focus and accessibility", async ({
  page,
}) => {
  await load(page);
  await page.locator("#dockAdd").click();
  await page.locator('#tableBody [data-date="2026-09-12"]').click();
  await expect(page.locator("#deleteOverlay")).toBeVisible();
  await page.locator("#dayAddButton").click();
  await expect(page.locator("#editorOverlay")).toBeVisible();
  await expect(page.locator("#editorOverlay")).toHaveAttribute(
    "aria-hidden",
    "false",
  );
  await expect(page.locator("#newDate")).toHaveValue("2026-09-12");
  await page.keyboard.press("Tab");
  await page.keyboard.press("Shift+Tab");
  await expect(page.locator("#editorOverlay")).toHaveAttribute(
    "aria-hidden",
    "false",
  );
  await page.locator('[data-close="editorOverlay"]').click();
  await expect(page.locator("#dockAdd")).toBeFocused();
});
