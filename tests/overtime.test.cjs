const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
process.env.TZ = 'Asia/Shanghai';
const script = fs.readFileSync(path.join(__dirname, '../index.html'), 'utf8').match(/<script>([\s\S]*?)<\/script>/)[1];

function app(now = '2026-09-14T00:38:02+08:00') {
  let currentNow = now;
  const elements = new Map();
  function element() {
    const classes = new Set();
    const attributes = new Map();
    return {
      textContent: '', innerHTML: '', value: '', files: [], style: {}, children: [],
      classList: { add: x => classes.add(x), remove: x => classes.delete(x), contains: x => classes.has(x) },
      addEventListener() {}, appendChild(child) { this.children.push(child); }, removeChild() {}, click() {},
      setAttribute(name, value) { attributes.set(name, String(value)); },
      getAttribute(name) { return attributes.get(name) || '0'; },
    };
  }
  function get(id) { if (!elements.has(id)) elements.set(id, element()); return elements.get(id); }
  const alerts = [], blobs = [], frames = new Map();
  let nextFrame = 0;
  class Clock extends Date {
    constructor(...args) { super(...(args.length ? args : [currentNow])); }
    static now() { return new Date(currentNow).getTime(); }
  }
  const context = vm.createContext({
    Date: Clock, console, performance: { now: () => 0 },
    document: { getElementById: get, body: element(), createElement: element, addEventListener() {} },
    window: { matchMedia: () => ({ matches: true }) },
    alert: msg => alerts.push(msg), confirm: () => true,
    requestAnimationFrame: callback => { frames.set(++nextFrame, callback); return nextFrame; },
    cancelAnimationFrame: id => frames.delete(id),
    setTimeout: callback => callback(),
    Blob: class { constructor(parts) { blobs.push(parts.join('')); } },
    URL: { createObjectURL: () => 'blob:test', revokeObjectURL() {} },
  });
  vm.runInContext(script, context);
  return { context, get, alerts, blobs, frames,
    setNow: value => { currentNow = value; },
    run: source => vm.runInContext(source, context),
    load: data => context.loadDataToMemory({ name: 'test.json', text: async () => JSON.stringify(data) }),
  };
}
const day = '2026-09-10';
const legacy = { [day]: { entries: ['2026-09-10T19:00:00+08:00', 1789002000000], note: 'preserve me' } };

test('imports scalar ISO, scalar milliseconds and mixed arrays; sorts by instant', async () => {
  const a = app();
  assert.equal(await a.load({ ...legacy, '2026-09-11': { entries: '2026-09-11T17:20:00+08:00' }, '2026-09-12': { entries: 1789174800000 } }), true);
  assert.equal(a.run(`globalData['${day}'].entries.join(',')`), '2026-09-10T09:00:00+08:00,2026-09-10T19:00:00+08:00');
  a.context.openEditorOverlay();
  assert.match(a.get('tableBody').children[0].innerHTML, /09:00, 19:00/);
  a.context.saveAndDownload();
  const exported = JSON.parse(a.blobs[0]);
  assert.equal(exported[day].note, 'preserve me');
  for (const record of Object.values(exported)) assert.ok(Array.isArray(record.entries) && record.entries.every(x => typeof x === 'string'));
});

test('local date defaults do not become yesterday before 08:00 in China', async () => {
  const a = app(); await a.load(legacy); a.context.openEditorOverlay();
  assert.equal(a.get('newDate').value, '2026-09-14');
});

test('add to iOS scalar record yields sorted strings and refreshes totals', async () => {
  const a = app(); await a.load({ [day]: { entries: '2026-09-10T19:00:00+08:00' } });
  a.get('newDate').value = day; a.get('newTime').value = '08:30'; a.context.addEntry();
  assert.equal(a.run(`globalData['${day}'].entries.join(',')`), '2026-09-10T08:30:00+08:00,2026-09-10T19:00:00+08:00');
  assert.equal(a.get('statHours').textContent, '1.5');
});

test('single-punch chooser deletes only the selected entry and preserves date metadata', async () => {
  const a = app(); await a.load(legacy); a.context.openEditorOverlay();
  a.context.openDeletePicker(day);
  assert.ok(a.get('deleteOverlay').classList.contains('active'));
  assert.match(a.get('deleteList').innerHTML, /09:00:00/);
  assert.match(a.get('deleteList').innerHTML, /19:00:00/);
  a.context.deletePunch(0);
  assert.equal(a.run(`globalData['${day}'].entries.join(',')`), '2026-09-10T19:00:00+08:00');
  assert.equal(a.run(`globalData['${day}'].note`), 'preserve me');
  assert.equal(a.get('statHours').textContent, '1.5');
});

test('deleting the last punch keeps an empty date object; deleting the day removes it', async () => {
  const a = app(); await a.load({ [day]: { entries: '2026-09-10T19:00:00+08:00', note: 'keep' } });
  a.context.openDeletePicker(day); a.context.deletePunch(0);
  assert.equal(a.run(`globalData['${day}'].entries.length`), 0);
  assert.equal(a.run(`globalData['${day}'].note`), 'keep');
  a.context.saveAndDownload();
  assert.deepEqual(JSON.parse(a.blobs[0])[day], { entries: [], note: 'keep' });

  await a.load(legacy); a.context.openDeletePicker(day); a.context.deleteDay(); await a.context.processFile();
  assert.equal(a.run('Object.keys(globalData).length'), 0);
  assert.equal(a.get('statDays').textContent, '0');
  assert.match(a.get('result').innerHTML, /本月暂无打卡记录/);
  a.context.saveAndDownload(); assert.deepEqual(JSON.parse(a.blobs[1]), {});
  await a.load({}); a.context.openEditorOverlay();
  assert.ok(a.get('editorOverlay').classList.contains('active'));
  a.get('newDate').value = day; a.get('newTime').value = '19:00'; a.context.addEntry();
  assert.equal(a.get('statHours').textContent, '1.5');
});

test('editor prevents adding a date outside the visible current month', async () => {
  const a = app(); await a.load({}); a.context.openEditorOverlay();
  assert.equal(a.get('newDate').min, '2026-09-01');
  assert.equal(a.get('newDate').max, '2026-09-30');
  a.get('newDate').value = '2026-08-31'; a.get('newTime').value = '19:00';
  a.context.addEntry();
  assert.equal(a.run('Object.keys(globalData).length'), 0);
  assert.match(a.alerts.at(-1), /仅支持添加本月/);
});

test('editor refreshes its date range when the month changes while open', async () => {
  const a = app('2026-09-30T23:59:00+08:00'); await a.load({}); a.context.openEditorOverlay();
  a.get('newDate').value = '2026-09-30'; a.get('newTime').value = '23:59';
  a.setNow('2026-10-01T00:01:00+08:00'); a.context.addEntry();
  assert.equal(a.run('Object.keys(globalData).length'), 0);
  assert.equal(a.get('newDate').min, '2026-10-01');
  assert.equal(a.get('newDate').max, '2026-10-31');
  assert.equal(a.get('newDate').value, '2026-10-01');
  assert.match(a.alerts.at(-1), /月份已更新/);
});

test('weekday/weekend rules and filtering retain expected 3 days / 10.5 hours', async () => {
  const a = app();
  const sample = JSON.parse(fs.readFileSync(path.join(__dirname, '../overtime_log.example.json'), 'utf8'));
  sample['2025-12-30'] = { entries: '2025-12-30T20:00:00+08:00' };
  await a.load(sample); await a.context.processFile();
  assert.equal(a.get('statDays').textContent, '3'); assert.equal(a.get('statHours').textContent, '10.5');
  a.context.saveAndDownload(); assert.ok(JSON.parse(a.blobs[0])['2025-12-30']);
});

test('single weekend punch and empty entry array do not add overtime', async () => {
  const a = app(); await a.load({ '2026-09-12': { entries: '2026-09-12T18:00:00+08:00' }, [day]: { entries: [] } });
  await a.context.processFile(); assert.equal(a.get('statHours').textContent, '0.0'); assert.equal(a.get('statDays').textContent, '1');
});

test('invalid import clears prior data and never shows successful loading', async () => {
  const a = app(); await a.load(legacy);
  assert.equal(await a.context.loadDataToMemory({ name: 'bad.json', text: async () => '{' }), false);
  assert.equal(a.run('dataLoaded'), false); assert.equal(a.run('Object.keys(globalData).length'), 0);
  assert.equal(a.get('dropZone').classList.contains('has-file'), false);
  await a.context.processFile(); assert.equal(a.get('statHours').textContent, '—');
});

test('rejects invalid shapes, dates and entry types without dropping records silently', async () => {
  const a = app();
  for (const input of [null, [], 1, { bad: { entries: [] } }, { '2026-02-30': { entries: [] } },
    { [day]: {} }, { [day]: { entries: null } }, { [day]: { entries: [true] } },
    { [day]: { entries: ['not a date'] } }, { [day]: { entries: ['2026-02-30T08:00:00+08:00'] } },
    { [day]: { entries: 1789038000 } }, { [day]: { entries: ['2026-09-11T00:00:00+08:00'] } },
    { [day]: { entries: ['2026-09-10T24:00:00+08:00'] } }]) {
    assert.equal(await a.load(input), false, JSON.stringify(input));
  }
});

test('rejects a seconds timestamp with a clear unit error', async () => {
  const a = app();
  assert.equal(await a.load({ [day]: { entries: 1789038000 } }), false);
  assert.match(a.alerts.at(-1), /必须使用毫秒，不能使用秒/);
});

test('latest selection wins races; calculation and editing are blocked during loading', async () => {
  const a = app(); let resolveOld;
  const old = a.context.loadDataToMemory({ name: 'old.json', text: () => new Promise(resolve => { resolveOld = resolve; }) });
  await a.context.processFile(); a.context.openEditorOverlay();
  assert.equal(a.get('editorOverlay').classList.contains('active'), false);
  assert.match(a.alerts[0], /正在加载/);
  await a.load({}); resolveOld(JSON.stringify(legacy)); await old;
  assert.equal(a.run('currentFileName'), 'test.json'); assert.equal(a.run('Object.keys(globalData).length'), 0);
});

test('normalization preserves milliseconds, offsets as instants and duplicate punches', async () => {
  const a = app(); const stamp = '2026-09-10T11:00:00.123Z';
  await a.load({ [day]: { entries: [stamp, stamp] } }); a.context.saveAndDownload();
  const entries = JSON.parse(a.blobs[0])[day].entries;
  assert.deepEqual(entries, ['2026-09-10T19:00:00.123+08:00', '2026-09-10T19:00:00.123+08:00']);
  assert.equal(new Date(entries[0]).getTime(), new Date(stamp).getTime());
});

test('loading cancels outstanding statistic animations', async () => {
  const a = app(); a.context.window.matchMedia = () => ({ matches: false });
  a.context.updateStats(3, 10.5); assert.equal(a.frames.size, 2);
  await a.load({}); assert.equal(a.frames.size, 0); assert.equal(a.get('statHours').textContent, '—');
});

test('date keys retain month and weekday in a timezone west of UTC', async () => {
  process.env.TZ = 'America/Los_Angeles';
  try {
    const a = app('2026-09-14T10:00:00-07:00');
    await a.load({ '2026-09-01': { entries: '2026-09-01T19:00:00-07:00' } });
    await a.context.processFile();
    assert.equal(a.get('statDays').textContent, '1'); assert.equal(a.get('statHours').textContent, '1.5');
    assert.match(a.get('result').innerHTML, /周二/);
  } finally { process.env.TZ = 'Asia/Shanghai'; }
});
