const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
process.env.TZ = "Asia/Shanghai";
const { app } = require("./helpers/app.cjs");
const day = "2026-09-10";
const legacy = {
  [day]: {
    entries: ["2026-09-10T19:00:00+08:00", 1789002000000],
    note: "preserve me",
  },
};

test("imports scalar ISO, scalar milliseconds and mixed arrays; sorts by instant", async () => {
  const a = app();
  assert.equal(
    await a.load({
      ...legacy,
      "2026-09-11": { entries: "2026-09-11T17:20:00+08:00" },
      "2026-09-12": { entries: 1789174800000 },
    }),
    true,
  );
  assert.equal(
    a.run(`globalData['${day}'].entries.join(',')`),
    "2026-09-10T09:00:00+08:00,2026-09-10T19:00:00+08:00",
  );
  a.context.openEditorOverlay();
  assert.match(a.get("tableBody").children[0].innerHTML, /09:00, 19:00/);
  a.context.saveAndDownload();
  const exported = JSON.parse(a.blobs[0]);
  assert.equal(exported[day].note, "preserve me");
  for (const record of Object.values(exported))
    assert.ok(
      Array.isArray(record.entries) &&
        record.entries.every((x) => typeof x === "string"),
    );
});

test("local date defaults do not become yesterday before 08:00 in China", async () => {
  const a = app();
  await a.load(legacy);
  a.context.openEditorOverlay();
  assert.equal(a.get("newDate").value, "2026-09-14");
});

test("add to iOS scalar record yields sorted strings and refreshes totals", async () => {
  const a = app();
  await a.load({ [day]: { entries: "2026-09-10T19:00:00+08:00" } });
  a.get("newDate").value = day;
  a.get("newTime").value = "08:30";
  a.context.addEntry();
  assert.equal(
    a.run(`globalData['${day}'].entries.join(',')`),
    "2026-09-10T08:30:00+08:00,2026-09-10T19:00:00+08:00",
  );
  assert.equal(a.get("statHours").textContent, "1.5");
});

test("single-punch chooser deletes only the selected entry and preserves date metadata", async () => {
  const a = app();
  await a.load(legacy);
  a.context.openEditorOverlay();
  a.context.openDeletePicker(day);
  assert.ok(a.get("deleteOverlay").classList.contains("active"));
  assert.match(a.get("deleteList").innerHTML, /09:00:00/);
  assert.match(a.get("deleteList").innerHTML, /19:00:00/);
  a.context.deletePunch(0);
  assert.equal(
    a.run(`globalData['${day}'].entries.join(',')`),
    "2026-09-10T19:00:00+08:00",
  );
  assert.equal(a.run(`globalData['${day}'].note`), "preserve me");
  assert.equal(a.get("statHours").textContent, "1.5");
  assert.ok(a.get("deleteOverlay").classList.contains("active"));
  assert.doesNotMatch(a.get("deleteList").innerHTML, /09:00:00/);
  assert.match(a.get("deleteList").innerHTML, /19:00:00/);
});

test("deleting the last punch keeps an empty date object; deleting the day removes it", async () => {
  const a = app();
  await a.load({
    [day]: { entries: "2026-09-10T19:00:00+08:00", note: "keep" },
  });
  a.context.openDeletePicker(day);
  a.context.deletePunch(0);
  assert.equal(a.run(`globalData['${day}'].entries.length`), 0);
  assert.equal(a.run(`globalData['${day}'].note`), "keep");
  assert.equal(a.get("deleteOverlay").classList.contains("active"), false);
  a.context.saveAndDownload();
  assert.deepEqual(JSON.parse(a.blobs[0])[day], { entries: [], note: "keep" });

  await a.load(legacy);
  a.context.openDeletePicker(day);
  a.context.deleteDay();
  await a.context.processFile();
  assert.equal(a.run("Object.keys(globalData).length"), 0);
  assert.equal(a.get("statDays").textContent, "0");
  assert.match(a.get("result").innerHTML, /本月暂无打卡记录/);
  a.context.saveAndDownload();
  assert.deepEqual(JSON.parse(a.blobs[1]), {});
  await a.load({});
  a.context.openEditorOverlay();
  assert.ok(a.get("editorOverlay").classList.contains("active"));
  a.get("newDate").value = day;
  a.get("newTime").value = "19:00";
  a.context.addEntry();
  assert.equal(a.get("statHours").textContent, "1.5");
});

test("editor prevents adding a date outside the visible current month", async () => {
  const a = app();
  await a.load({});
  a.context.openEditorOverlay();
  assert.equal(a.get("newDate").min, "2026-09-01");
  assert.equal(a.get("newDate").max, "2026-09-30");
  a.get("newDate").value = "2026-08-31";
  a.get("newTime").value = "19:00";
  a.context.addEntry();
  assert.equal(a.run("Object.keys(globalData).length"), 0);
  assert.match(a.alerts.at(-1), /仅支持添加当前查看月份/);
});

test("editor keeps the explicitly selected historical month across midnight", async () => {
  const a = app("2026-09-30T23:59:00+08:00");
  await a.load({});
  a.context.openEditorOverlay();
  a.get("newDate").value = "2026-09-30";
  a.get("newTime").value = "23:59";
  a.setNow("2026-10-01T00:01:00+08:00");
  a.context.addEntry();
  assert.equal(a.run("Object.keys(globalData).length"), 1);
  assert.equal(a.get("newDate").min, "2026-09-01");
  assert.equal(a.get("newDate").max, "2026-09-30");
  assert.equal(a.run("selectedMonth"), "2026-09");
});

test("weekday/weekend rules and filtering retain expected 3 days / 10.5 hours", async () => {
  const a = app();
  const sample = JSON.parse(
    fs.readFileSync(
      path.join(__dirname, "../overtime_log.example.json"),
      "utf8",
    ),
  );
  sample["2025-12-30"] = { entries: "2025-12-30T20:00:00+08:00" };
  await a.load(sample);
  await a.context.processFile();
  assert.equal(a.get("statDays").textContent, "3");
  assert.equal(a.get("statHours").textContent, "10.5");
  a.context.saveAndDownload();
  assert.ok(JSON.parse(a.blobs[0])["2025-12-30"]);
});

test("holiday overrides apply workday rules on Sunday and rest-day rules on Friday", async () => {
  const a = app(undefined, {
    calendarDays: {
      "2026-09-20": { isOffDay: false, name: "国庆节" },
      "2026-09-25": { isOffDay: true, name: "中秋节<script>" },
    },
  });
  await a.load({
    "2026-09-20": {
      entries: ["2026-09-20T09:00:00+08:00", "2026-09-20T19:00:00+08:00"],
    },
    "2026-09-25": {
      entries: ["2026-09-25T09:00:00+08:00", "2026-09-25T19:00:00+08:00"],
    },
  });
  await a.context.processFile();
  assert.equal(a.get("statHours").textContent, "11.5");
  assert.match(a.get("result").innerHTML, /day-type-badge--adjusted">调休上班/);
  assert.match(
    a.get("result").innerHTML,
    /day-type-badge--holiday">中秋节&lt;script&gt;/,
  );
  assert.match(a.get("result").innerHTML, /周日/);
  assert.match(a.get("result").innerHTML, /周五/);
  assert.doesNotMatch(a.get("result").innerHTML, /<script>/);
});

test("day cards visibly distinguish ordinary workdays and weekends", async () => {
  const a = app();
  await a.load({
    "2026-09-10": { entries: "2026-09-10T19:00:00+08:00" },
    "2026-09-12": {
      entries: ["2026-09-12T09:00:00+08:00", "2026-09-12T18:00:00+08:00"],
    },
  });
  await a.context.processFile();
  assert.match(a.get("result").innerHTML, /day-type-badge--workday">工作日/);
  assert.match(a.get("result").innerHTML, /day-type-badge--weekend">周末/);
});

test("holiday sources fall through in order, cache success, and retain stale cache on total failure", async () => {
  const urls = [];
  const fetch = async (url) => {
    urls.push(url);
    if (urls.length < 3) throw new Error("source unavailable");
    return {
      ok: true,
      status: 200,
      json: async () => ({
        code: 0,
        holiday: {
          "09-20": { holiday: false, name: "中秋节前补班", date: "2026-09-20" },
        },
      }),
    };
  };
  const a = app(undefined, { seedCalendar: false, fetch });
  const calendar = await a.context.loadHolidayCalendar(2026);
  assert.equal(calendar.source, "Timor");
  assert.equal(calendar.days["2026-09-20"].isOffDay, false);
  assert.match(urls[0], /cdn\.jsdelivr\.net/);
  assert.match(urls[1], /shuyz\.com/);
  assert.match(urls[2], /timor\.tech/);
  assert.equal(
    JSON.parse(a.storage.get("overtime-holidays-v1-2026")).source,
    "Timor",
  );

  let attempts = 0;
  const stale = {
    year: 2026,
    fetchedAt: 0,
    source: "old",
    days: {
      "2026-09-20": { isOffDay: false, name: "调休上班" },
    },
  };
  const b = app(undefined, {
    cachedCalendar: stale,
    fetch: async () => {
      attempts++;
      throw new Error("offline");
    },
  });
  const fallback = await b.context.loadHolidayCalendar(2026);
  assert.equal(attempts, 3);
  assert.equal(fallback.source, "old");
  assert.equal(fallback.days["2026-09-20"].isOffDay, false);
  assert.equal((await b.context.loadHolidayCalendar(2026)).source, "old");
  assert.equal(attempts, 3);
});

test("all holiday data formats normalize to the same date override shape", () => {
  const a = app();
  const first = a.context.parseHolidayCn(
    {
      year: 2026,
      days: [{ date: "2026-09-20", isOffDay: false, name: "国庆节" }],
    },
    2026,
  );
  const second = a.context.parseShuyzCalendar(
    {
      Years: {
        2026: [
          {
            Name: "中秋节",
            StartDate: "2026-09-25",
            EndDate: "2026-09-27",
            CompDays: ["2026-09-20"],
          },
        ],
      },
    },
    2026,
  );
  assert.equal(first["2026-09-20"].isOffDay, false);
  assert.equal(second["2026-09-20"].isOffDay, false);
  assert.equal(second["2026-09-25"].isOffDay, true);
});

test("compensation workdays override every holiday range regardless of source order", () => {
  const a = app();
  const days = a.context.parseShuyzCalendar(
    {
      Years: {
        2026: [
          {
            Name: "先声明的补班",
            StartDate: "2026-09-01",
            EndDate: "2026-09-01",
            CompDays: ["2026-09-20"],
          },
          {
            Name: "后出现的假期区间",
            StartDate: "2026-09-20",
            EndDate: "2026-09-21",
            CompDays: [],
          },
        ],
      },
    },
    2026,
  );
  assert.equal(days["2026-09-20"].isOffDay, false);
});

test("empty or out-of-month data calculates immediately without holiday requests or warnings", async () => {
  let attempts = 0;
  const a = app(undefined, {
    seedCalendar: false,
    fetch: async () => {
      attempts++;
      throw new Error("offline");
    },
  });
  await a.load({ "2026-08-31": { entries: "2026-08-31T19:00:00+08:00" } });
  await a.context.processFile();
  assert.equal(attempts, 0);
  assert.equal(a.get("statDays").textContent, "0");
  assert.match(a.get("result").innerHTML, /本月暂无打卡记录/);
  assert.doesNotMatch(a.get("result").innerHTML, /调休数据暂不可用/);
});

test("December holiday-cn refresh merges corrections published in the following year file", async () => {
  const urls = [];
  const a = app("2026-12-15T12:00:00+08:00", {
    seedCalendar: false,
    fetch: async (url) => {
      urls.push(url);
      if (/2026\.json$/.test(url))
        return {
          ok: true,
          status: 200,
          json: async () => ({
            year: 2026,
            days: [{ date: "2026-12-25", isOffDay: false, name: "普通工作日" }],
          }),
        };
      return {
        ok: true,
        status: 200,
        json: async () => ({
          year: 2027,
          days: [
            { date: "2026-12-31", isOffDay: true, name: "元旦调整" },
            { date: "2027-01-01", isOffDay: true, name: "元旦" },
          ],
        }),
      };
    },
  });
  const calendar = await a.context.loadHolidayCalendar(2026);
  assert.equal(urls.length, 2);
  assert.match(urls[1], /2027\.json$/);
  assert.equal(calendar.days["2026-12-31"].isOffDay, true);
  assert.equal(calendar.days["2027-01-01"], undefined);
  assert.equal(calendar.followingYearChecked, true);
});

test("holiday loading disables recalculation and hides stale totals until complete", async () => {
  let resolveFetch;
  const response = new Promise((resolve) => {
    resolveFetch = resolve;
  });
  const a = app(undefined, { seedCalendar: false, fetch: () => response });
  await a.load({ [day]: { entries: "2026-09-10T19:00:00+08:00" } });
  const processing = a.context.processFile();
  assert.equal(a.get("calculateButton").disabled, true);
  assert.equal(a.get("statHours").textContent, "…");
  assert.match(a.get("result").innerHTML, /正在加载调休数据/);
  resolveFetch({
    ok: true,
    status: 200,
    json: async () => ({
      year: 2026,
      days: [{ date: "2026-09-10", isOffDay: false, name: "工作日" }],
    }),
  });
  await processing;
  assert.equal(a.get("calculateButton").disabled, false);
  assert.equal(a.get("statHours").textContent, "1.5");
});

test("a newer empty calculation supersedes pending holiday loading without leaving the button disabled", async () => {
  let resolveFetch;
  const response = new Promise((resolve) => {
    resolveFetch = resolve;
  });
  const a = app(undefined, { seedCalendar: false, fetch: () => response });
  await a.load({ [day]: { entries: "2026-09-10T19:00:00+08:00" } });
  const oldCalculation = a.context.processFile();
  assert.equal(a.get("calculateButton").disabled, true);
  a.run(`globalData['${day}'].entries = []`);
  await a.context.processFile();
  assert.equal(a.get("calculateButton").disabled, false);
  assert.match(a.get("result").innerHTML, /本月暂无打卡记录/);
  resolveFetch({
    ok: true,
    status: 200,
    json: async () => ({
      year: 2026,
      days: [{ date: "2026-09-10", isOffDay: false, name: "工作日" }],
    }),
  });
  await oldCalculation;
  assert.equal(a.get("calculateButton").disabled, false);
  assert.match(a.get("result").innerHTML, /本月暂无打卡记录/);
});

test("shows a clear warning when no holiday source or cache is available", async () => {
  const a = app(undefined, {
    seedCalendar: false,
    fetch: async () => {
      throw new Error("offline");
    },
  });
  await a.load({ [day]: { entries: "2026-09-10T19:00:00+08:00" } });
  await a.context.processFile();
  assert.equal(a.get("statHours").textContent, "1.5");
  assert.match(a.get("result").innerHTML, /调休数据暂不可用/);
});

test("single weekend punch and empty entry array do not add overtime", async () => {
  const a = app();
  await a.load({
    "2026-09-12": { entries: "2026-09-12T18:00:00+08:00" },
    [day]: { entries: [] },
  });
  await a.context.processFile();
  assert.equal(a.get("statHours").textContent, "0.0");
  assert.equal(a.get("statDays").textContent, "1");
});

test("invalid replacement import preserves the loaded records and reports failure", async () => {
  const a = app();
  await a.load(legacy);
  assert.equal(
    await a.context.loadDataToMemory({
      name: "bad.json",
      text: async () => "{",
    }),
    false,
  );
  assert.equal(a.run("dataLoaded"), true);
  assert.equal(a.run("Object.keys(globalData).length"), 1);
  assert.equal(a.get("dropZone").classList.contains("has-file"), true);
  assert.match(a.alerts.at(-1), /原有记录已保留/);
  await a.context.processFile();
  assert.equal(a.get("statHours").textContent, "1.5");
});

test("rejects invalid shapes, dates and entry types without dropping records silently", async () => {
  const a = app();
  for (const input of [
    null,
    [],
    1,
    { bad: { entries: [] } },
    { "2026-02-30": { entries: [] } },
    { [day]: {} },
    { [day]: { entries: null } },
    { [day]: { entries: [true] } },
    { [day]: { entries: ["not a date"] } },
    { [day]: { entries: ["2026-02-30T08:00:00+08:00"] } },
    { [day]: { entries: 1789038000 } },
    { [day]: { entries: ["2026-09-11T00:00:00+08:00"] } },
    { [day]: { entries: ["2026-09-10T24:00:00+08:00"] } },
  ]) {
    assert.equal(await a.load(input), false, JSON.stringify(input));
  }
});

test("rejects a seconds timestamp with a clear unit error", async () => {
  const a = app();
  assert.equal(await a.load({ [day]: { entries: 1789038000 } }), false);
  assert.match(a.alerts.at(-1), /必须使用毫秒，不能使用秒/);
});

test("latest selection wins races; calculation and editing are blocked during loading", async () => {
  const a = app();
  let resolveOld;
  const old = a.context.loadDataToMemory({
    name: "old.json",
    text: () =>
      new Promise((resolve) => {
        resolveOld = resolve;
      }),
  });
  await a.context.processFile();
  a.context.openEditorOverlay();
  assert.equal(a.get("editorOverlay").classList.contains("active"), false);
  assert.match(a.alerts[0], /正在加载/);
  await a.load({});
  resolveOld(JSON.stringify(legacy));
  await old;
  assert.equal(a.run("currentFileName"), "test.json");
  assert.equal(a.run("Object.keys(globalData).length"), 0);
});

test("normalization preserves milliseconds, offsets as instants and duplicate punches", async () => {
  const a = app();
  const stamp = "2026-09-10T11:00:00.123Z";
  await a.load({ [day]: { entries: [stamp, stamp] } });
  a.context.saveAndDownload();
  const entries = JSON.parse(a.blobs[0])[day].entries;
  assert.deepEqual(entries, [
    "2026-09-10T19:00:00.123+08:00",
    "2026-09-10T19:00:00.123+08:00",
  ]);
  assert.equal(new Date(entries[0]).getTime(), new Date(stamp).getTime());
});

test("loading cancels outstanding statistic animations", async () => {
  const a = app();
  a.context.window.matchMedia = () => ({ matches: false });
  a.context.updateStats(3, 10.5);
  assert.equal(a.frames.size, 2);
  await a.load({});
  assert.equal(a.frames.size, 0);
  assert.equal(a.get("statHours").textContent, "—");
});

test("date keys retain month and weekday in a timezone west of UTC", async () => {
  process.env.TZ = "America/Los_Angeles";
  try {
    const a = app("2026-09-14T10:00:00-07:00");
    await a.load({ "2026-09-01": { entries: "2026-09-01T19:00:00-07:00" } });
    await a.context.processFile();
    assert.equal(a.get("statDays").textContent, "1");
    assert.equal(a.get("statHours").textContent, "1.5");
    assert.match(a.get("result").innerHTML, /周二/);
  } finally {
    process.env.TZ = "Asia/Shanghai";
  }
});

test("valid epoch milliseconds and UTF-8 BOM survive import and export", async () => {
  const a = app();
  assert.equal(
    await a.context.loadDataToMemory({
      name: "bom.json",
      text: async () => '\uFEFF{"1970-01-01":{"entries":[0,1,999]}}',
    }),
    true,
  );
  a.context.saveAndDownload();
  assert.deepEqual(
    JSON.parse(a.blobs[0])["1970-01-01"].entries.map((x) =>
      new Date(x).getTime(),
    ),
    [0, 1, 999],
  );
});

test("rejects normalized 24:00 timestamps even if the resulting date matches the key", async () => {
  const a = app();
  assert.equal(
    await a.load({ "2026-09-11": { entries: "2026-09-10T24:00:00+08:00" } }),
    false,
  );
});

test("oversized files are rejected before reading their content", async () => {
  const a = app();
  let read = false;
  assert.equal(
    await a.context.loadDataToMemory({
      name: "large.json",
      size: 8 * 1024 * 1024 + 1,
      text: async () => {
        read = true;
        return "{}";
      },
    }),
    false,
  );
  assert.equal(read, false);
  assert.match(a.alerts.at(-1), /8 MB/);
});

test("missing next-year notice does not discard a valid December calendar", async () => {
  const urls = [];
  const a = app("2026-12-15T12:00:00+08:00", {
    seedCalendar: false,
    fetch: async (url) => {
      urls.push(url);
      if (/2026\.json$/.test(url))
        return {
          ok: true,
          status: 200,
          json: async () => ({
            year: 2026,
            days: [{ date: "2026-12-31", isOffDay: true, name: "元旦调整" }],
          }),
        };
      throw new Error("next-year notice not published");
    },
  });
  const calendar = await a.context.loadHolidayCalendar(2026);
  assert.equal(calendar.source, "holiday-cn");
  assert.equal(calendar.days["2026-12-31"].isOffDay, true);
  assert.equal(urls.length, 2);
});

test("malformed cached holiday entries are discarded instead of marking workdays as unknown", () => {
  const a = app(undefined, {
    cachedCalendar: {
      year: 2026,
      fetchedAt: Date.now(),
      source: "old",
      days: {
        "2026-09-10": { isOffDay: true },
      },
    },
  });
  assert.equal(a.context.readHolidayCache(2026), null);
});

test("extra JSON fields named __proto__ remain data during normalization and export", async () => {
  const a = app();
  const data = JSON.parse(
    '{"2026-09-10":{"entries":[],"__proto__":{"note":"keep me"}}}',
  );
  assert.equal(await a.load(data), true);
  a.context.saveAndDownload();
  assert.deepEqual(JSON.parse(a.blobs[0]), data);
});

test("historical timezone offsets containing seconds do not change backup instants", async () => {
  const a = app();
  const instant = new Date("1900-01-01T00:00:00Z").getTime();
  assert.equal(await a.load({ "1900-01-01": { entries: instant } }), true);
  a.context.saveAndDownload();
  const value = JSON.parse(a.blobs[0])["1900-01-01"].entries[0];
  assert.equal(new Date(value).getTime(), instant);
  assert.equal(await a.load(JSON.parse(a.blobs[0])), true);
});
