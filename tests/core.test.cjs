const { test } = require("node:test");
const assert = require("node:assert/strict");
const core = require("../web/core.js");
process.env.TZ = "Asia/Shanghai";

test("month ranges handle Monday alignment, leap days and years below 100", () => {
  assert.deepEqual(core.monthRange("2026-09"), {
    min: "2026-09-01",
    max: "2026-09-30",
    length: 30,
    offset: 1,
  });
  assert.equal(core.monthRange("2024-02").length, 29);
  assert.equal(core.monthRange("2100-02").length, 28);
  assert.equal(core.monthRange("0096-02").length, 29);
  assert.equal(core.monthRange("0000-02").length, 29);
  assert.equal(core.monthRange("9999-12").length, 31);
});
test("month navigation crosses years and stops at supported date boundaries", () => {
  assert.equal(core.shiftMonth("2026-01", -1), "2025-12");
  assert.equal(core.shiftMonth("2026-12", 1), "2027-01");
  assert.equal(core.shiftMonth("0000-01", -1), "0000-01");
  assert.equal(core.shiftMonth("9999-12", 1), "9999-12");
  for (const value of ["2026-00", "2026-13", "2026-1", "bad", "2026-02-01"])
    assert.throws(() => core.parseMonth(value));
});
test("summaries retain exact seconds and use the selected month and custom cutoff", () => {
  const data = core.normalizeData({
    "2026-09-10": { entries: ["2026-09-10T19:00:00.500+08:00"] },
    "2026-10-01": { entries: "2026-10-01T22:00:00+08:00" },
  });
  const result = core.summarizeMonth(data, "2026-09", null, "18:15");
  assert.equal(result.totalSec, 2700.5);
  assert.equal(result.days.length, 1);
  assert.equal(result.overtimeDays, 1);
  assert.equal(result.averageSec, 2700.5);
  assert.equal(core.summarizeMonth(data, "2026-08", null).totalSec, 0);
});
test("incomplete means a single rest-day punch; a single workday punch still calculates", () => {
  const data = core.normalizeData({
    "2026-09-12": { entries: "2026-09-12T19:00:00+08:00" },
    "2026-09-10": { entries: "2026-09-10T19:00:00+08:00" },
  });
  const result = core.summarizeMonth(data, "2026-09", null);
  assert.equal(result.incompleteDays, 1);
  assert.equal(result.totalSec, 5400);
  assert.equal(result.punchCount, 2);
});
test("heat levels use fixed duration thresholds across all months", () => {
  assert.deepEqual(
    [0, 0.5, 3599, 3600, 10799, 10800, 21599, 21600, 90000].map(core.heatLevel),
    [0, 1, 1, 2, 2, 3, 3, 4, 4],
  );
  assert.equal(core.compactDuration(30), "<1m");
  assert.equal(core.compactDuration(1800), "30m");
});
test("CSV includes seconds, Unicode BOM, selected rule and neutralizes spreadsheet formulas", () => {
  const data = core.normalizeData({
    "2026-09-10": { entries: "2026-09-10T19:00:00+08:00" },
  });
  const summary = core.summarizeMonth(data, "2026-09", {
    days: { "2026-09-10": { isOffDay: false, name: "ignored" } },
  });
  summary.days[0].dayTypeLabel = '=HYPERLINK("x")';
  const csv = core.monthCsv(summary, "17:30", "Asia/Shanghai");
  assert.ok(csv.startsWith("\uFEFF"));
  assert.match(csv, /5400/);
  assert.match(csv, /Asia\/Shanghai/);
  assert.match(csv, /'=HYPERLINK/);
  assert.equal(core.csvCell('hello,"world"'), '"hello,""world"""');
  assert.equal(core.csvCell("\t=1+1"), '"\'\t=1+1"');
});
test("DST-gap manual times are rejected while elapsed rest-day hours remain exact", () => {
  process.env.TZ = "America/New_York";
  try {
    assert.throws(() => core.parseLocalTime("2026-03-08", "02:30"), /夏令时/);
    const data = core.normalizeData({
      "2026-03-08": {
        entries: ["2026-03-08T01:30:00-05:00", "2026-03-08T03:30:00-04:00"],
      },
    });
    assert.equal(core.summarizeMonth(data, "2026-03", null).totalSec, 3600);
  } finally {
    process.env.TZ = "Asia/Shanghai";
  }
});
test("large punch collections summarize without argument spread or per-punch UI allocation", () => {
  const entries = Array.from(
    { length: 100000 },
    (_, index) =>
      "2026-09-12T" + (index % 2 ? "18:00:00" : "09:00:00") + "+08:00",
  );
  const start = performance.now();
  const summary = core.summarizeMonth(
    { "2026-09-12": { entries } },
    "2026-09",
    null,
  );
  assert.equal(summary.punchCount, 100000);
  assert.equal(summary.totalSec, 32400);
  assert.ok(performance.now() - start < 3000);
});
