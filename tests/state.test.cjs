const { test } = require("node:test");
const assert = require("node:assert/strict");
const { app } = require("./helpers/app.cjs");
process.env.TZ = "Asia/Shanghai";
const day = "2026-09-10";
const data = {
  [day]: {
    entries: ["2026-09-10T09:00:00+08:00", "2026-09-10T19:00:00+08:00"],
    note: "保留字段",
  },
};

test("explicit month switching supports historical edits without changing other months", async () => {
  const a = app();
  await a.load({
    ...data,
    "2026-08-20": { entries: "2026-08-20T19:00:00+08:00" },
  });
  await a.context.selectMonth("2026-08");
  a.context.openEditorOverlay();
  a.get("newDate").value = "2026-08-21";
  a.get("newTime").value = "20:00";
  assert.equal(a.context.addEntry(), true);
  assert.equal(a.get("statHours").textContent, "4.0");
  assert.equal(a.run(`globalData['${day}'].note`), "保留字段");
});
test("duplicate and future manual entries do not mutate data or revision", async () => {
  const a = app();
  await a.load(data);
  a.context.openEditorOverlay();
  const revision = a.run("dataRevision");
  a.get("newDate").value = day;
  a.get("newTime").value = "19:00";
  assert.equal(a.context.addEntry(), false);
  assert.match(a.alerts.at(-1), /已有打卡/);
  assert.equal(a.run("dataRevision"), revision);
  a.get("newDate").value = "2026-09-15";
  a.get("newTime").value = "09:00";
  assert.equal(a.context.addEntry(), false);
  assert.match(a.alerts.at(-1), /未来/);
  assert.equal(a.run("Object.keys(globalData).length"), 1);
});
test("undo restores exact duplicate punches, extra fields and milliseconds", async () => {
  const a = app();
  await a.load({
    [day]: {
      entries: [
        "2026-09-10T19:00:00.123+08:00",
        "2026-09-10T19:00:00.123+08:00",
      ],
      note: "原样保留",
    },
  });
  a.context.openDeletePicker(day);
  a.context.deletePunch(0);
  assert.equal(a.run(`globalData['${day}'].entries.length`), 1);
  a.context.undoLastMutation();
  assert.equal(a.run(`globalData['${day}'].entries.length`), 2);
  assert.equal(
    a.run(`globalData['${day}'].entries[0]`),
    "2026-09-10T19:00:00.123+08:00",
  );
  assert.equal(a.run(`globalData['${day}'].note`), "原样保留");
});
test("a stale delete index cannot remove a different punch after the record changes", async () => {
  const a = app();
  await a.load(data);
  a.context.openDeletePicker(day);
  const revision = a.run("dataRevision");
  const instant = new Date("2026-09-10T09:00:00+08:00").getTime();
  a.get("newDate").value = day;
  a.get("newTime").value = "08:30";
  a.context.addEntry();
  a.context.openDeletePicker(day);
  a.context.deletePunch(0, instant, revision);
  assert.equal(a.run(`globalData['${day}'].entries.length`), 3);
  assert.match(a.alerts.at(-1), /记录已变动/);
});
test("loading a new document invalidates undo from the previous document", async () => {
  const a = app();
  await a.load(data);
  a.get("newDate").value = day;
  a.get("newTime").value = "20:00";
  a.context.addEntry();
  await a.load({ "2026-09-01": { entries: [] } });
  a.context.undoLastMutation();
  assert.equal(a.run(`globalData['${day}']`), undefined);
});
test("an old year holiday response never replaces the selected new year report", async () => {
  let resolve;
  const waiting = new Promise((r) => (resolve = r));
  const a = app(undefined, { seedCalendar: false, fetch: () => waiting });
  await a.load({
    ...data,
    "2025-12-30": { entries: "2025-12-30T20:00:00+08:00" },
  });
  const old = a.context.processFile();
  a.storage.set(
    "overtime-holidays-v1-2025",
    JSON.stringify({
      year: 2025,
      fetchedAt: new Date("2026-09-14T00:38:02+08:00").getTime(),
      source: "test",
      days: {},
    }),
  );
  await a.context.selectMonth("2025-12");
  assert.equal(a.get("statHours").textContent, "2.5");
  resolve({
    ok: true,
    json: async () => ({
      year: 2026,
      days: [{ date: day, isOffDay: false, name: "工作日" }],
    }),
  });
  await old;
  assert.equal(a.get("statHours").textContent, "2.5");
  assert.equal(a.run("currentSummary.month"), "2025-12");
});
test("changing cutoff during a holiday request ignores results computed with the older rule", async () => {
  let resolve;
  const waiting = new Promise((r) => (resolve = r));
  const a = app(undefined, { seedCalendar: false, fetch: () => waiting });
  await a.load(data);
  const old = a.context.processFile();
  a.run("preferences.cutoff='18:00'");
  const fresh = a.context.processFile();
  resolve({
    ok: true,
    json: async () => ({
      year: 2026,
      days: [{ date: day, isOffDay: false, name: "工作日" }],
    }),
  });
  await Promise.all([old, fresh]);
  assert.equal(a.get("statHours").textContent, "1.0");
  assert.equal(a.get("calculateButton").disabled, false);
});
test("invalid import during pending holiday loading recovers prior calculation and controls", async () => {
  let resolve;
  const waiting = new Promise((r) => (resolve = r));
  const a = app(undefined, { seedCalendar: false, fetch: () => waiting });
  await a.load(data);
  const old = a.context.processFile();
  assert.equal(
    await a.context.loadDataToMemory({
      name: "bad.json",
      text: async () => "{",
    }),
    false,
  );
  resolve({
    ok: true,
    json: async () => ({
      year: 2026,
      days: [{ date: day, isOffDay: false, name: "工作日" }],
    }),
  });
  await old;
  await new Promise((r) => setImmediate(r));
  assert.equal(a.get("statHours").textContent, "1.5");
  assert.equal(a.get("calculateButton").disabled, false);
  assert.equal(a.run("dataLoaded"), true);
});
test("UTF-8 size validation applies even if the file provider omits its byte size", async () => {
  const a = app();
  const payload = JSON.stringify({
    [day]: { entries: [], note: "汉".repeat(2800000) },
  });
  assert.ok(payload.length < 8 * 1024 * 1024);
  assert.equal(
    await a.context.loadDataToMemory({
      name: "large-unicode.json",
      text: async () => payload,
    }),
    false,
  );
  assert.match(a.alerts.at(-1), /8 MB/);
});
test("a changed editor month is detected before committing an entry", async () => {
  const a = app();
  await a.load(data);
  a.context.openEditorOverlay();
  a.run("selectedMonth='2026-08'");
  a.get("newDate").value = "2026-08-21";
  a.get("newTime").value = "20:00";
  assert.equal(a.context.addEntry(), false);
  assert.match(a.alerts.at(-1), /月份已更新/);
  assert.equal(a.get("newDate").min, "2026-08-01");
});

test("normalization cannot expand a compact numeric import beyond the portable 8 MB limit", async () => {
  const a = app();
  await a.load(data);
  const text = JSON.stringify({
    [day]: {
      entries: Array(300000).fill(
        new Date("2026-09-10T19:00:00+08:00").getTime(),
      ),
    },
  });
  assert.ok(Buffer.byteLength(text) < 8 * 1024 * 1024);
  assert.equal(
    await a.context.loadDataToMemory({
      name: "numeric.json",
      text: async () => text,
    }),
    false,
  );
  assert.match(a.alerts.at(-1), /规范化后的记录超过/);
  assert.equal(a.run(`globalData['${day}'].note`), "保留字段");
});
test("an addition exceeding the file limit rolls back without consuming undo or marking data dirty", async () => {
  const a = app();
  const record = { entries: [], note: "" };
  const empty = JSON.stringify({ [day]: record });
  record.note = "x".repeat(8 * 1024 * 1024 - Buffer.byteLength(empty) - 10);
  await a.load({ [day]: record });
  const revision = a.run("dataRevision");
  a.get("newDate").value = day;
  a.get("newTime").value = "19:00";
  assert.equal(a.context.addEntry(), false);
  assert.equal(a.run(`globalData['${day}'].entries.length`), 0);
  assert.equal(a.run("dataRevision"), revision);
  assert.equal(a.run("dirty"), false);
});
test("near-limit JSON exports compactly so its own backup remains importable", async () => {
  const a = app();
  const record = { entries: [], note: "", fields: Array(200).fill("a") };
  const empty = JSON.stringify({ [day]: record });
  record.note = "x".repeat(8 * 1024 * 1024 - Buffer.byteLength(empty) - 100);
  assert.equal(await a.load({ [day]: record }), true);
  assert.equal(a.context.saveAndDownload(), true);
  assert.ok(Buffer.byteLength(a.blobs[0]) <= 8 * 1024 * 1024);
  assert.equal(JSON.parse(a.blobs[0])[day].fields.length, 200);
  assert.equal(
    await a.context.loadDataToMemory({
      name: "roundtrip.json",
      text: async () => a.blobs[0],
    }),
    true,
  );
});
