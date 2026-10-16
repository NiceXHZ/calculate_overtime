"use strict";

let globalData = {};
let currentFileName = "overtime_log.json";
let dataLoaded = false;
let loadingData = false;
let calculating = false;
let loadVersion = 0;
let calculationRunVersion = 0;
let dataRevision = 0;
let selectedMonth = monthKey(new Date());
let currentSummary = null;
let currentCalendar = null;
let pendingDeleteDate = null;
let pendingDeleteRevision = 0;
let visiblePunches = 100;
let recordFilter = "all";
let newestFirst = true;
let uiReady = false;
let dirty = false;
let undoState = null;
let confirmAction = null;
let toastTimer = null;
let draftTimer = null;
let draftBase = null;
let recoveredDraft = null;
let draftBlocked = false;
let draftSavedRevision = -1;
let monthPickerYear = new Date().getFullYear();
const MAX_FILE_BYTES = 8 * 1024 * 1024;
const DRAFT_KEY = "overtime-web-draft-v2";
const PREFS_KEY = "overtime-web-preferences-v1";
const TAB_ID =
  typeof crypto !== "undefined" && crypto.randomUUID
    ? crypto.randomUUID()
    : Date.now() + "-" + Math.random().toString(36).slice(2);
let preferences = { appearance: "system", glass: true, cutoff: "17:30" };
const $ = (id) => document.getElementById(id);
const icon = (name) =>
  '<svg class="icon" aria-hidden="true"><use href="#i-' + name + '"/></svg>';
function utf8Size(text) {
  return typeof TextEncoder === "function"
    ? new TextEncoder().encode(text).length
    : unescape(encodeURIComponent(text)).length;
}
function updateUndoUi() {
  for (const id of ["dayUndoButton", "editorUndoButton"])
    $(id).hidden = !undoState || undoState.revision !== dataRevision;
}

function requireData() {
  if (loadingData) {
    showError("文件正在加载，请稍后再试");
    return false;
  }
  if (!dataLoaded) {
    showError("请先导入 JSON 文件，或新建一份记录");
    return false;
  }
  return true;
}

function showError(text, field) {
  if (field) $(field).textContent = text;
  if (!uiReady) {
    alert(text);
    return;
  }
  notify(text, false, true);
}

function notify(text, undo = false, error = false) {
  if (!uiReady) return;
  clearTimeout(toastTimer);
  $("toastText").textContent = text;
  $("undoButton").hidden = !undo;
  $("toast").hidden = false;
  $("toast").classList.toggle("is-error", error);
  toastTimer = setTimeout(
    () => {
      $("toast").hidden = true;
    },
    undo ? 12000 : error ? 9000 : 4500,
  );
}

function openDialog(id) {
  const dialog = $(id);
  dialog.classList.add("active");
  dialog.setAttribute("aria-hidden", "false");
  if (typeof dialog.showModal === "function" && !dialog.open)
    dialog.showModal();
  if (uiReady) {
    document.documentElement.dataset.modal = "on";
    if (window.refreshLiquidGlass) window.refreshLiquidGlass();
  }
  document.body.style.overflow = "hidden";
}

function closeDialog(id) {
  const dialog = $(id);
  if (typeof dialog.close === "function" && dialog.open) dialog.close();
  dialog.classList.remove("active");
  dialog.setAttribute("aria-hidden", "true");
  if (id === "deleteOverlay") pendingDeleteDate = null;
  if (id === "confirmOverlay") confirmAction = null;
  if (!uiReady || !document.querySelector("dialog[open]")) {
    document.body.style.overflow = "";
    if (uiReady) {
      document.documentElement.dataset.modal = "off";
      if (window.refreshLiquidGlass) window.refreshLiquidGlass();
    }
  }
}

function closeEditorOverlay() {
  closeDialog("editorOverlay");
}
function closeDeletePicker() {
  closeDialog("deleteOverlay");
}

function askConfirmation(
  title,
  message,
  accept,
  label = "确认删除",
  destructive = true,
) {
  const dialog = $("confirmOverlay");
  if (typeof dialog.showModal !== "function") {
    if (confirm(message)) accept();
    return;
  }
  $("confirmTitle").textContent = title;
  $("confirmMessage").textContent = message;
  $("confirmAccept").textContent = label;
  $("confirmAccept").classList.toggle("danger", destructive);
  $("confirmAccept").classList.toggle("primary", !destructive);
  confirmAction = accept;
  openDialog("confirmOverlay");
}

function updateFileUi() {
  $("dropZone").classList.toggle("has-file", dataLoaded);
  $("fileBar").classList.toggle("is-empty", !dataLoaded);
  $("fileTitle").textContent = dataLoaded
    ? currentFileName
    : "从一份记录，开始整理";
  $("fileName").textContent = dataLoaded
    ? "已载入 " + Object.keys(globalData).length + " 个日期 · 可切换月份查看"
    : "拖放 JSON 文件，或选择已有打卡记录";
  $("statFile").textContent = dataLoaded ? currentFileName : "未加载文件";
  $("dropZoneLoadedName").textContent = dataLoaded ? currentFileName : "";
  $("importButtonLabel").textContent = dataLoaded ? "更换文件" : "导入 JSON";
  for (const id of ["dockAdd", "dockExport", "addRecordButton"])
    $(id).disabled = !dataLoaded || loadingData;
  $("csvButton").disabled =
    !dataLoaded ||
    loadingData ||
    calculating ||
    !currentSummary ||
    !currentSummary.days.length;
  $("calculateButton").disabled = !dataLoaded || loadingData || calculating;
  $("unsavedDot").hidden = !dirty;
  if (!dataLoaded) $("saveStatus").textContent = "文件留在本机，记录不上传";
  else if (draftBlocked) setSaveStatus("草稿有冲突，请先导出本页记录", true);
  else if (draftSavedRevision === dataRevision)
    setSaveStatus(
      dirty
        ? "更改已暂存此浏览器 · 尚未导出备份"
        : "已暂存此浏览器 · 原文件不受影响",
    );
  else
    setSaveStatus(
      dirty
        ? "尚未导出，请及时保存 JSON 备份"
        : "原文件不受影响 · 导出可保留完整记录",
      dirty,
    );
}

function setSaveStatus(text, warning = false) {
  $("saveStatus").textContent = text;
  $("saveStatus").classList.toggle("is-warning", warning);
}

function hasCurrentMonthEntries(year, month) {
  const key =
    String(year).padStart(4, "0") + "-" + String(month).padStart(2, "0");
  return Object.keys(globalData).some(
    (date) =>
      date.startsWith(key + "-") &&
      Array.isArray(globalData[date].entries) &&
      globalData[date].entries.length,
  );
}

function setCalculationLoading(loading) {
  calculating = loading;
  $("calculateButton").disabled = loading || !dataLoaded || loadingData;
  $("csvButton").disabled =
    loading || !currentSummary || !currentSummary.days.length || loadingData;
  if (loading) {
    for (const id of [
      "statDays",
      "statHours",
      "statAverage",
      "statOvertimeDays",
    ]) {
      cancelAnimationFrame($(id).countFrame);
      $(id).textContent = "…";
    }
    $("calendarGrid").setAttribute("aria-busy", "true");
  } else $("calendarGrid").setAttribute("aria-busy", "false");
}

function resetResults() {
  calculationRunVersion++;
  currentSummary = null;
  currentCalendar = null;
  setCalculationLoading(false);
  $("result").innerHTML = "";
  for (const id of [
    "statDays",
    "statHours",
    "statAverage",
    "statOvertimeDays",
  ]) {
    cancelAnimationFrame($(id).countFrame);
    $(id).textContent = "—";
  }
}

function shouldAnimate() {
  return !window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}
function animateCountUp(el, target, duration, decimals = 0, suffix = "") {
  cancelAnimationFrame(el.countFrame);
  const format = (value) =>
    (decimals ? value.toFixed(decimals) : Math.round(value)) + suffix;
  if (!shouldAnimate()) {
    el.textContent = format(target);
    return;
  }
  const start = performance.now();
  function update(now) {
    const progress = Math.max(0, Math.min((now - start) / duration, 1));
    el.textContent = format(target * (1 - (1 - progress) ** 3));
    if (progress < 1) el.countFrame = requestAnimationFrame(update);
    else el.countFrame = null;
  }
  el.countFrame = requestAnimationFrame(update);
}
function updateStats(days, hours) {
  animateCountUp($("statDays"), days, 380);
  animateCountUp($("statHours"), hours, 420, 1);
}

async function loadDataToMemory(file) {
  const version = ++loadVersion;
  calculationRunVersion++;
  setCalculationLoading(false);
  let recalculatePrevious = false;
  loadingData = true;
  updateFileUi();
  $("fileName").textContent = "正在加载：" + file.name;
  try {
    if (file.size > MAX_FILE_BYTES)
      throw new Error("文件超过 8 MB，请拆分后导入");
    const text = await file.text();
    if (version !== loadVersion) return false;
    const bytes = utf8Size(text);
    if (bytes > MAX_FILE_BYTES) throw new Error("文件超过 8 MB，请拆分后导入");
    const parsed = normalizeData(JSON.parse(text.replace(/^\uFEFF/, "")));
    if (utf8Size(JSON.stringify(parsed)) > MAX_FILE_BYTES)
      throw new Error("规范化后的记录超过 8 MB，请拆分后导入");
    globalData = parsed;
    currentFileName = String(file.name || "overtime_log.json");
    dataLoaded = true;
    dirty = false;
    dataRevision++;
    undoState = null;
    updateUndoUi();
    draftSavedRevision = -1;
    resetResults();
    closeEditorOverlay();
    closeDeletePicker();
    closeDialog("confirmOverlay");
    return true;
  } catch (error) {
    if (version !== loadVersion) return false;
    recalculatePrevious = dataLoaded && !currentSummary;
    showError(
      "文件加载失败：" +
        error.message +
        (dataLoaded ? "。原有记录已保留。" : ""),
    );
    return false;
  } finally {
    if (version === loadVersion) {
      loadingData = false;
      $("fileInput").value = "";
      updateFileUi();
      if (recalculatePrevious) processFile();
    }
  }
}

function bestMonth() {
  const now = monthKey(new Date());
  if (
    Object.keys(globalData).some(
      (date) => date.startsWith(now + "-") && globalData[date].entries.length,
    )
  )
    return now;
  const keys = Object.keys(globalData)
    .filter((date) => globalData[date].entries.length)
    .sort();
  return keys.length ? keys[keys.length - 1].slice(0, 7) : now;
}

async function importAndShow(file) {
  if (await loadDataToMemory(file)) {
    selectedMonth = bestMonth();
    recordFilter = "all";
    if (uiReady) $("recoveryBanner").hidden = true;
    await processFile();
    scheduleDraft();
    notify("已载入记录，正在查看 " + readableMonth(selectedMonth));
  }
}

function requestImport(file) {
  if (!file) return;
  const go = () => importAndShow(file);
  if (dirty && dataLoaded)
    askConfirmation(
      "载入另一份记录？",
      "当前更改还未导出。载入后将替换当前工作区；如需保留，请先取消并导出 JSON。",
      go,
      "继续载入",
      false,
    );
  else if (recoveredDraft && recoveredDraft.dirty && !dataLoaded)
    askConfirmation(
      "替换尚未导出的草稿？",
      "浏览器里有一份尚未导出的草稿。可以先取消并恢复草稿、导出备份，再载入新记录。",
      go,
      "继续载入",
      false,
    );
  else go();
}
function handleFileSelect() {
  const file = $("fileInput").files[0];
  $("fileInput").value = ""; // Retrying the same file must also work after cancelling our replacement dialog.
  if (file) return requestImport(file);
}
function createWorkspace() {
  requestImport({ name: "overtime_log.json", size: 2, text: async () => "{}" });
}
function loadSample() {
  const sample = {
    "2026-09-10": {
      entries: ["2026-09-10T09:00:00+08:00", "2026-09-10T19:00:00+08:00"],
    },
    "2026-09-11": {
      entries: ["2026-09-11T09:00:00+08:00", "2026-09-11T17:20:00+08:00"],
    },
    "2026-09-12": {
      entries: [
        "2026-09-12T09:00:00+08:00",
        "2026-09-12T12:00:00+08:00",
        "2026-09-12T13:00:00+08:00",
        "2026-09-12T18:00:00+08:00",
      ],
    },
  };
  requestImport({
    name: "示例记录.json",
    text: async () => JSON.stringify(sample),
  });
}

function readableMonth(month) {
  return Number(month.slice(0, 4)) + "年" + Number(month.slice(5)) + "月";
}
function updateMonthUi() {
  $("selectedMonthLabel").textContent = readableMonth(selectedMonth);
  $("calendarMonthLabel").textContent = selectedMonth.replace("-", " / ");
  $("previousMonth").disabled = shiftMonth(selectedMonth, -1) === selectedMonth;
  $("nextMonth").disabled = shiftMonth(selectedMonth, 1) === selectedMonth;
  $("currentMonthButton").hidden = selectedMonth === monthKey(new Date());
  $("calendarGrid").setAttribute(
    "aria-label",
    readableMonth(selectedMonth) + "加班热力图",
  );
}
async function selectMonth(month) {
  try {
    parseMonth(month);
  } catch (error) {
    showError(error.message);
    return;
  }
  if (selectedMonth === month && currentSummary) {
    closeDialog("monthOverlay");
    return;
  }
  selectedMonth = month;
  recordFilter = "all";
  resetResults();
  closeDialog("monthOverlay");
  if (dataLoaded && !loadingData) await processFile();
  else {
    updateMonthUi();
    renderCalendar(null);
  }
}

async function processFile() {
  if (!requireData()) return;
  const version = ++calculationRunVersion,
    fileVersion = loadVersion,
    month = selectedMonth,
    cutoff = preferences.cutoff;
  setCalculationLoading(false);
  updateMonthUi();
  const needs = hasCurrentMonthEntries(
    Number(month.slice(0, 4)),
    Number(month.slice(5)),
  );
  let calendar = needs
    ? null
    : holidayMemoryCache[Number(month.slice(0, 4))] ||
      readHolidayCache(Number(month.slice(0, 4)));
  if (needs) {
    calendar = loadHolidayCalendar(Number(month.slice(0, 4)));
    if (calendar && typeof calendar.then === "function") {
      currentSummary = null;
      currentCalendar = null;
      setCalculationLoading(true);
      $("result").innerHTML =
        '<div class="empty-state"><h3>正在加载调休数据…</h3><p>本机记录保持不变，可以继续切换月份。</p></div>';
      renderCalendar(null, true);
      try {
        calendar = await calendar;
      } finally {
        if (version === calculationRunVersion) setCalculationLoading(false);
      }
      if (
        version !== calculationRunVersion ||
        fileVersion !== loadVersion ||
        !dataLoaded ||
        month !== selectedMonth ||
        cutoff !== preferences.cutoff
      )
        return;
    }
  }
  try {
    const summary = summarizeMonth(globalData, month, calendar, cutoff);
    if (version !== calculationRunVersion) return;
    currentSummary = summary;
    currentCalendar = calendar;
    updateStats(summary.days.length, summary.totalSec / 3600);
    $("statOvertimeDays").textContent = summary.overtimeDays;
    $("statAverage").textContent = (summary.averageSec / 3600).toFixed(1);
    $("statTotalCaption").textContent =
      formatDuration(summary.totalSec) + " · " + readableMonth(month);
    $("statPunchCaption").textContent =
      "共留下 " + summary.punchCount + " 次打卡";
    $("recordCount").textContent = summary.days.length;
    $("incompleteCount").textContent = summary.incompleteDays;
    const warning =
      needs && !calendar ? "调休数据暂不可用，当前结果仅按星期计算。" : "";
    $("calendarStatus").textContent =
      warning ||
      (needs
        ? "日期类型参考" +
          calendar.source +
          " · 工作日 " +
          cutoff +
          " 后计入加班"
        : "工作日 " + cutoff + " 后计入加班 · 休息日取首末间隔");
    $("holidaySource").textContent = calendar
      ? calendar.source + " · " + month.slice(0, 4) + " 年"
      : "内置 2025、2026 年安排，其他年份尝试联网获取";
    renderCalendar(summary);
    renderRecords();
    renderInsights(summary);
    updateFileUi();
    if (pendingDeleteDate) renderDayDetail();
    return summary;
  } catch (error) {
    resetResults();
    renderCalendar(null);
    $("result").textContent = "处理出错：" + error.message;
    setCalculationLoading(false);
    $("csvButton").disabled = true;
    showError("计算失败：" + error.message);
  }
}

function renderCalendar(summary, loading = false) {
  const range = monthRange(selectedMonth),
    today = localDateKey(new Date());
  const indexed = new Map(
    summary ? summary.days.map((day) => [day.date, day]) : [],
  );
  let html = Array.from(
    { length: range.offset },
    () => '<span class="calendar-spacer" aria-hidden="true"></span>',
  ).join("");
  for (let number = 1; number <= range.length; number++) {
    const date = selectedMonth + "-" + String(number).padStart(2, "0");
    const day = indexed.get(date),
      level = heatLevel(day ? day.durationSec : 0);
    const classification = dayClassification(date, currentCalendar);
    const label =
      date +
      "，" +
      ["周日", "周一", "周二", "周三", "周四", "周五", "周六"][
        parseDateKey(date).getDay()
      ] +
      "，" +
      classification.label +
      "，" +
      (day
        ? day.count +
          "次打卡，加班" +
          formatDuration(day.durationSec) +
          (day.incomplete ? "，待补全" : "")
        : loading
          ? "正在计算"
          : "暂无记录");
    const classes = [
      "heat-cell",
      day ? "has-record" : "is-empty",
      date === today ? "is-today" : "",
      day && day.incomplete ? "is-incomplete" : "",
    ]
      .filter(Boolean)
      .join(" ");
    html +=
      '<button type="button" class="' +
      classes +
      '" data-date="' +
      date +
      '" data-level="' +
      level +
      '" aria-label="' +
      escapeHtml(label) +
      '"' +
      (date === today ? ' aria-current="date"' : "") +
      (!dataLoaded || loadingData || loading ? " disabled" : "") +
      '><span class="day-number">' +
      number +
      '</span><span class="day-hours">' +
      (day
        ? day.durationSec > 0
          ? compactDuration(day.durationSec)
          : day.incomplete
            ? "待补全"
            : "已记录"
        : loading
          ? "·"
          : "—") +
      "</span></button>";
  }
  $("calendarGrid").innerHTML = html;
}

function renderRecords() {
  const summary = currentSummary;
  if (!summary) return;
  let days = summary.days.filter(
    (day) =>
      recordFilter === "all" ||
      (recordFilter === "overtime" ? day.durationSec > 0 : day.incomplete),
  );
  if (newestFirst) days = days.slice().reverse();
  let html = "";
  if (summary.days.length && !currentCalendar)
    html +=
      '<div class="calendar-warning">调休数据暂不可用，当前结果仅按星期计算。</div>';
  if (!days.length) {
    html +=
      '<div class="empty-state"><span class="empty-symbol">' +
      icon("calendar") +
      "</span><h3>" +
      (summary.days.length
        ? recordFilter === "incomplete"
          ? "没有待补全的休息日记录"
          : "当前筛选下没有记录"
        : "本月暂无打卡记录") +
      "</h3><p>" +
      (summary.days.length
        ? "可以切换筛选，查看其他打卡。"
        : "点按月历中的日期补录，或切换到有记录的月份。") +
      "</p></div>";
  }
  for (const day of days) {
    const first = new Date(day.first).toTimeString().slice(0, 5),
      last = new Date(day.last).toTimeString().slice(0, 5);
    html +=
      '<button type="button" class="day-card' +
      (day.durationSec > 0 ? " day-card--overtime" : " day-card--zero") +
      '" data-date="' +
      day.date +
      '" aria-label="查看' +
      escapeHtml(
        day.date +
          "，" +
          day.dayTypeLabel +
          "，加班" +
          formatDuration(day.durationSec),
      ) +
      '"><span class="day-card__identity"><span class="date-tile"><strong>' +
      Number(day.date.slice(8)) +
      "</strong><span>" +
      Number(day.date.slice(5, 7)) +
      '月</span></span><span class="day-identity-copy"><span class="day-card__date">' +
      day.date +
      '<span class="day-card__weekday">' +
      day.weekday +
      '</span></span><span class="day-type-badge day-type-badge--' +
      day.dayType +
      '">' +
      escapeHtml(day.dayTypeLabel) +
      '</span></span></span><span class="day-card__times">' +
      first +
      (day.count > 1 ? " — " + last : "") +
      "<small>" +
      day.count +
      ' 次打卡</small></span><span class="day-card__duration">' +
      formatDuration(day.durationSec) +
      "<small>" +
      (day.incomplete
        ? "待补全首末记录"
        : day.durationSec > 0
          ? "已计入本月"
          : "未产生加班") +
      '</small></span><svg class="icon record-chevron" aria-hidden="true"><use href="#i-arrow-right"/></svg></button>';
  }
  $("result").innerHTML = html;
  $("recordsSummary").textContent =
    "共 " +
    summary.days.length +
    " 天记录 · " +
    summary.punchCount +
    " 次打卡" +
    (recordFilter !== "all" ? " · 筛选出 " + days.length + " 天" : "");
  if (uiReady)
    document.querySelectorAll("#recordFilters button").forEach((button) => {
      const active = button.dataset.filter === recordFilter;
      button.classList.toggle("selected", active);
      button.setAttribute("aria-pressed", String(active));
    });
}

function renderInsights(summary) {
  const max = summary.days.reduce(
    (best, day) => (!best || day.durationSec > best.durationSec ? day : best),
    null,
  );
  $("insightTitle").textContent = summary.overtimeDays
    ? "这月有 " + summary.overtimeDays + " 天，多努力了一点"
    : summary.days.length
      ? "按自己的节奏，刚刚好"
      : "留白，也是一种节奏";
  $("insightText").textContent =
    max && max.durationSec > 0
      ? Number(max.date.slice(5, 7)) +
        " 月 " +
        Number(max.date.slice(8)) +
        " 日是加班最多的一天，留下了 " +
        formatDuration(max.durationSec) +
        " 的记录。"
      : summary.days.length
        ? "目前没有产生加班时长。完整的打卡，依然值得被记下。"
        : "这个月还没有记录。可以切换月份回看，也可以补上遗漏的打卡。";
  const range = monthRange(selectedMonth),
    weeks = Array(Math.ceil((range.offset + range.length) / 7)).fill(0);
  for (const day of summary.days)
    weeks[Math.floor((range.offset + Number(day.date.slice(8)) - 1) / 7)] +=
      day.durationSec;
  const top = Math.max(...weeks, 1);
  $("weeklyChart").innerHTML = weeks
    .map(
      (value, index) =>
        '<div class="week-bar" role="img" aria-label="第' +
        (index + 1) +
        "周，加班" +
        formatDuration(value) +
        '"><span class="week-bar__fill' +
        (value > 0 && value === top ? " is-max" : "") +
        '" style="height:' +
        Math.max(5, Math.round((value / top) * 100)) +
        '%"></span><span class="week-bar__label">W' +
        (index + 1) +
        "</span></div>",
    )
    .join("");
  $("incompleteNotice").hidden = !summary.incompleteDays;
  $("incompleteNotice").textContent =
    summary.incompleteDays +
    " 天休息日仅有一次打卡，补全首末记录后才能计算时长。";
}

function editorMonthRange(now) {
  return monthRange(monthKey(now));
}
function updateEditorDateRange(now, resetDate) {
  const range = monthRange(selectedMonth),
    input = $("newDate"),
    today = localDateKey(now);
  input.min = range.min;
  input.max = range.max;
  input.dataset && (input.dataset.month = selectedMonth);
  if (
    resetDate ||
    !input.value ||
    input.value < range.min ||
    input.value > range.max
  )
    input.value = today >= range.min && today <= range.max ? today : range.min;
  return range;
}
function openEditorOverlay(date) {
  if (!requireData()) return;
  closeDeletePicker();
  updateEditorDateRange(new Date(), true);
  if (typeof date === "string" && date.startsWith(selectedMonth + "-"))
    $("newDate").value = date;
  $("newTime").value = new Date().toTimeString().slice(0, 5);
  $("punchError").textContent = "";
  $("editorDescription").textContent =
    "正在补录 " + readableMonth(selectedMonth) + "，未来时间不能保存。";
  renderEditorTable();
  updateUndoUi();
  openDialog("editorOverlay");
}
function renderEditorTable() {
  const tbody = $("tableBody");
  tbody.innerHTML = "";
  const keys = Object.keys(globalData)
    .filter(
      (date) =>
        date.startsWith(selectedMonth + "-") && globalData[date].entries.length,
    )
    .sort();
  if (!keys.length) {
    tbody.innerHTML = '<tr><td colspan="3">本月无记录</td></tr>';
    return;
  }
  for (const date of keys) {
    const entries = globalData[date].entries;
    const time = (entry) => new Date(entry).toTimeString().slice(0, 5);
    const times =
      entries.length > 6
        ? time(entries[0]) +
          " … " +
          time(entries[entries.length - 1]) +
          "（" +
          entries.length +
          "次）"
        : entries.map(time).join(", ");
    const tr = document.createElement("tr");
    tr.innerHTML =
      "<td><strong>" +
      date.slice(5) +
      '</strong><br><span class="weekday-tag">' +
      ["周日", "周一", "周二", "周三", "周四", "周五", "周六"][
        parseDateKey(date).getDay()
      ] +
      "</span></td><td>" +
      times +
      '</td><td><button type="button" class="table-action" data-date="' +
      date +
      '">查看</button></td>';
    tbody.appendChild(tr);
  }
}

function mutationFinished(date, before, message) {
  if (utf8Size(JSON.stringify(globalData)) > MAX_FILE_BYTES) {
    if (before === undefined) delete globalData[date];
    else globalData[date] = before;
    showError(
      "完整记录已达到 8 MB 上限，本次更改未保存。请先拆分或整理记录。",
      "punchError",
    );
    return false;
  }
  dataRevision++;
  dirty = true;
  undoState = { date, before, revision: dataRevision };
  updateUndoUi();
  scheduleDraft();
  renderEditorTable();
  processFile();
  notify(message, true);
  return true;
}
function copyRecord(record) {
  return record === undefined ? undefined : JSON.parse(JSON.stringify(record));
}
function addEntry() {
  if (!requireData()) return false;
  const range = monthRange(selectedMonth),
    date = $("newDate").value,
    time = $("newTime").value;
  if (
    $("newDate").min &&
    ($("newDate").min !== range.min || $("newDate").max !== range.max)
  ) {
    updateEditorDateRange(new Date(), true);
    showError("查看月份已更新，请确认日期和时间后重新添加", "punchError");
    return false;
  }
  try {
    if (!date || !time) throw new Error("请选择完整的日期和时间");
    if (!date.startsWith(selectedMonth + "-"))
      throw new Error("仅支持添加当前查看月份的打卡，请先切换月份");
    const value = parseLocalTime(date, time);
    if (value.getTime() > Date.now()) throw new Error("不能添加未来时间的打卡");
    const existing = globalData[date];
    if (
      existing &&
      existing.entries.some(
        (entry) => new Date(entry).getTime() === value.getTime(),
      )
    )
      throw new Error("该时间已有打卡，无需重复添加");
    const before = copyRecord(existing);
    if (!existing) globalData[date] = { entries: [] };
    globalData[date].entries.push(formatEntry(value));
    globalData[date].entries.sort((a, b) => new Date(a) - new Date(b));
    $("punchError").textContent = "";
    if (!mutationFinished(date, before, "已补录 " + date.slice(5) + " " + time))
      return false;
    closeEditorOverlay();
    return true;
  } catch (error) {
    showError(error.message, "punchError");
    return false;
  }
}

function openDeletePicker(date) {
  if (!requireData()) return;
  try {
    parseDateKey(date);
  } catch {
    return;
  }
  if (!date.startsWith(selectedMonth + "-")) return;
  pendingDeleteDate = date;
  pendingDeleteRevision = dataRevision;
  updateUndoUi();
  visiblePunches = 100;
  renderDayDetail();
  openDialog("deleteOverlay");
}
function renderDayDetail() {
  const date = pendingDeleteDate;
  if (!date) return;
  const entries = globalData[date] ? globalData[date].entries : [];
  const day =
    currentSummary && currentSummary.days.find((item) => item.date === date);
  const classification = dayClassification(date, currentCalendar);
  $("deleteTitle").textContent =
    Number(date.slice(5, 7)) + " 月 " + Number(date.slice(8)) + " 日";
  $("deleteSubtitle").textContent =
    date +
    " · " +
    ["周日", "周一", "周二", "周三", "周四", "周五", "周六"][
      parseDateKey(date).getDay()
    ] +
    " · " +
    classification.label;
  $("daySummary").innerHTML =
    "<div><strong>" +
    (day ? formatDuration(day.durationSec) : "暂无加班") +
    "</strong><p>" +
    (day && day.incomplete
      ? "休息日只有一次打卡，待补全首末记录"
      : classification.rest
        ? "按首末打卡的间隔计算"
        : "工作日 " + preferences.cutoff + " 后计入加班") +
    "</p></div><span>" +
    entries.length +
    " 次<br>打卡记录</span>";
  $("deleteList").innerHTML = entries.length
    ? entries
        .slice(0, visiblePunches)
        .map(
          (entry, index) =>
            '<div class="delete-option"><span class="delete-option__label"><span class="punch-dot" aria-hidden="true"></span><span><span class="delete-option__time">' +
            new Date(entry).toTimeString().slice(0, 8) +
            '</span><span class="delete-option__index">第 ' +
            (index + 1) +
            " 条" +
            (new Date(entry).getMilliseconds()
              ? " · " +
                String(new Date(entry).getMilliseconds()).padStart(3, "0") +
                " 毫秒"
              : "") +
            '</span></span></span><button type="button" class="icon-button" data-index="' +
            index +
            '" data-time="' +
            new Date(entry).getTime() +
            '" data-revision="' +
            dataRevision +
            '" aria-label="删除第 ' +
            (index + 1) +
            " 次打卡，" +
            new Date(entry).toTimeString().slice(0, 8) +
            '">' +
            icon("trash") +
            "</button></div>",
        )
        .join("")
    : '<div class="empty-state"><h3>这一天还没有记录</h3><p>如果漏记了，可以在下方补录。</p></div>';
  $("morePunchesButton").hidden = entries.length <= visiblePunches;
  $("deleteDayButton").hidden = !entries.length;
  const future = date > localDateKey(new Date());
  $("dayAddButton").disabled = future;
  $("dayAddButton").innerHTML =
    icon("plus") + (future ? "未来日期不能补录" : "补录这一天");
  pendingDeleteRevision = dataRevision;
}
function deletePunch(index, expectedTime, expectedRevision) {
  if (!requireData()) return;
  const date = pendingDeleteDate,
    record = date && globalData[date],
    revision = dataRevision;
  if (
    !record ||
    !Array.isArray(record.entries) ||
    !Number.isInteger(index) ||
    index < 0 ||
    index >= record.entries.length
  ) {
    closeDeletePicker();
    return;
  }
  const instant = new Date(record.entries[index]).getTime();
  if (
    (expectedTime !== undefined && expectedTime !== instant) ||
    (expectedRevision !== undefined && expectedRevision !== revision)
  ) {
    showError("记录已变动，请重新选择");
    renderDayDetail();
    return;
  }
  const value = record.entries[index];
  askConfirmation(
    "删除这次打卡？",
    date +
      " " +
      new Date(value).toTimeString().slice(0, 8) +
      "。删除后可以撤销。",
    () => {
      if (
        dataRevision !== revision ||
        pendingDeleteDate !== date ||
        globalData[date] !== record ||
        record.entries[index] !== value
      ) {
        showError("记录已变动，请重新选择");
        return;
      }
      const before = copyRecord(record);
      record.entries.splice(index, 1);
      if (!record.entries.length) closeDeletePicker();
      mutationFinished(date, before, "已删除这次打卡");
      if (pendingDeleteDate) renderDayDetail();
    },
  );
}
function deleteDay() {
  if (!requireData()) return;
  const date = pendingDeleteDate,
    record = date && globalData[date],
    revision = dataRevision;
  if (!record || pendingDeleteRevision !== revision) {
    closeDeletePicker();
    return;
  }
  askConfirmation(
    "删除当天全部记录？",
    date +
      " 共 " +
      record.entries.length +
      " 次打卡及当天附加字段会一起移除。删除后可以撤销。",
    () => {
      if (
        revision !== dataRevision ||
        globalData[date] !== record ||
        pendingDeleteDate !== date
      ) {
        showError("记录已变动，请重新选择");
        return;
      }
      const before = copyRecord(record);
      delete globalData[date];
      closeDeletePicker();
      mutationFinished(date, before, "已删除当天全部记录");
    },
  );
}
function undoLastMutation() {
  if (!requireData()) return;
  const undo = undoState;
  if (!undo || undo.revision !== dataRevision) {
    showError("记录已变动，无法撤销此前操作");
    return;
  }
  if (undo.before === undefined) delete globalData[undo.date];
  else globalData[undo.date] = copyRecord(undo.before);
  dataRevision++;
  dirty = true;
  undoState = null;
  updateUndoUi();
  scheduleDraft();
  renderEditorTable();
  processFile();
  notify("已撤销上次修改");
}

function downloadText(text, name, type) {
  const blob = new Blob([text], { type });
  const url = URL.createObjectURL(blob),
    anchor = document.createElement("a");
  try {
    anchor.href = url;
    anchor.download = name;
    document.body.appendChild(anchor);
    anchor.click();
  } finally {
    document.body.removeChild(anchor);
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
}
function saveAndDownload() {
  if (!requireData()) return false;
  try {
    const normalized = normalizeData(globalData);
    let text = JSON.stringify(normalized, null, 2);
    if (utf8Size(text) > MAX_FILE_BYTES) text = JSON.stringify(normalized);
    if (utf8Size(text) > MAX_FILE_BYTES)
      throw new Error("记录超过 8 MB，请先整理或拆分");
    const name = currentFileName
      .replace(/[<>:"/\\|?*\u0000-\u001f]/g, "_")
      .slice(0, 180);
    downloadText(
      text,
      /\.json$/i.test(name) ? name : name + ".json",
      "application/json;charset=utf-8",
    );
    dirty = false;
    updateFileUi();
    scheduleDraft();
    notify("已生成完整 JSON 备份，请确认下载完成");
    return true;
  } catch (error) {
    showError("导出失败：" + error.message);
    return false;
  }
}
function exportCsv() {
  if (
    !requireData() ||
    !currentSummary ||
    currentSummary.month !== selectedMonth
  )
    return false;
  if (!currentSummary.days.length) {
    showError("这个月暂无可导出的记录");
    return false;
  }
  const snapshot = currentSummary,
    cutoff = preferences.cutoff;
  try {
    downloadText(
      monthCsv(snapshot, cutoff, localZone()),
      "overtime-" + snapshot.month + ".csv",
      "text/csv;charset=utf-8",
    );
    notify("已生成 " + readableMonth(snapshot.month) + " 的 CSV 报表");
    return true;
  } catch (error) {
    showError("导出失败：" + error.message);
    return false;
  }
}

function scheduleDraft() {
  if (!uiReady || !dataLoaded) return;
  clearTimeout(draftTimer);
  draftTimer = setTimeout(persistDraft, 350);
}
function persistDraft() {
  if (!uiReady || !dataLoaded || loadingData || draftBlocked) return false;
  clearTimeout(draftTimer);
  // Serialize the compare-and-write across tabs where Web Locks is available.
  if (typeof navigator !== "undefined" && navigator.locks) {
    return navigator.locks
      .request("overtime-web-draft-write", persistDraftNow)
      .catch(() => {
        draftSavedRevision = -1;
        setSaveStatus("浏览器无法暂存，请及时导出 JSON 备份", true);
        return false;
      });
  }
  return persistDraftNow();
}
function persistDraftNow() {
  if (!uiReady || !dataLoaded || loadingData || draftBlocked) return false;
  try {
    const actual = localStorage.getItem(DRAFT_KEY);
    if (actual !== draftBase) {
      draftBlocked = true;
      $("conflictBanner").hidden = false;
      setSaveStatus("草稿有冲突，请先导出本页记录", true);
      return false;
    }
    const envelope = {
      schema: 2,
      fileName: currentFileName,
      data: globalData,
      month: selectedMonth,
      dirty,
      updatedAt: Date.now(),
      writer: TAB_ID,
    };
    const encoded = JSON.stringify(envelope);
    localStorage.setItem(DRAFT_KEY, encoded);
    draftBase = encoded;
    draftSavedRevision = dataRevision;
    updateFileUi();
    return true;
  } catch (error) {
    draftSavedRevision = -1;
    setSaveStatus("浏览器无法暂存，请及时导出 JSON 备份", true);
    return false;
  }
}
function readDraft() {
  try {
    draftBase = localStorage.getItem(DRAFT_KEY);
    if (!draftBase || draftBase.length > MAX_FILE_BYTES + 200000) return;
    const raw = JSON.parse(draftBase);
    if (
      raw.schema !== 2 ||
      typeof raw.fileName !== "string" ||
      !Number.isFinite(raw.updatedAt)
    )
      return;
    const data = normalizeData(raw.data);
    recoveredDraft = { ...raw, data };
    $("recoveryDescription").textContent =
      raw.fileName +
      " · " +
      new Date(raw.updatedAt).toLocaleString("zh-CN", {
        month: "numeric",
        day: "numeric",
        hour: "2-digit",
        minute: "2-digit",
      }) +
      " 暂存";
    $("recoveryBanner").hidden = false;
  } catch {
    recoveredDraft = null;
  }
}
async function restoreDraft() {
  if (!recoveredDraft) return;
  const raw = recoveredDraft;
  if (
    await loadDataToMemory({
      name: raw.fileName,
      text: async () => JSON.stringify(raw.data),
    })
  ) {
    try {
      parseMonth(raw.month);
      selectedMonth = raw.month;
    } catch {
      selectedMonth = bestMonth();
    }
    dirty = raw.dirty === true;
    recoveredDraft = null;
    $("recoveryBanner").hidden = true;
    await processFile();
    scheduleDraft();
    notify("已恢复上次的浏览器草稿");
  }
}
function discardDraft() {
  askConfirmation(
    "忽略这份浏览器草稿？",
    "将移除浏览器中暂存的这份记录，原始 JSON 文件不会改变。",
    () => {
      try {
        if (localStorage.getItem(DRAFT_KEY) === draftBase)
          localStorage.removeItem(DRAFT_KEY);
        draftBase = localStorage.getItem(DRAFT_KEY);
        recoveredDraft = null;
        $("recoveryBanner").hidden = true;
      } catch {
        showError("浏览器不允许清除草稿");
      }
    },
    "忽略草稿",
    false,
  );
}

function localZone() {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || "浏览器本地时区";
  } catch {
    return "浏览器本地时区";
  }
}
function applyPreferences() {
  const dark =
    preferences.appearance === "dark" ||
    (preferences.appearance === "system" &&
      window.matchMedia("(prefers-color-scheme: dark)").matches);
  document.documentElement.dataset.theme = dark ? "dark" : "light";
  document.documentElement.dataset.glass = preferences.glass ? "on" : "off";
  document
    .querySelector('meta[name="theme-color"]')
    .setAttribute("content", dark ? "#111b18" : "#f3f5f4");
  $("appearanceSelect").value = preferences.appearance;
  $("glassToggle").checked = preferences.glass;
  $("cutoffInput").value = preferences.cutoff;
  $("themeButton").setAttribute(
    "aria-label",
    "切换为" + (dark ? "浅色" : "深色") + "外观",
  );
  $("ruleSummary").innerHTML =
    "工作日 " + preferences.cutoff + " 后计入加班<br>休息日按首末打卡间隔计算";
  $("timezoneLabel").textContent = "按本机时区 · " + localZone();
  $("settingsZone").textContent =
    "按本机时区 " +
    localZone() +
    " 计算；与 Android 对比时，请确认手机和浏览器处于相同时区。";
  if (window.refreshLiquidGlass) window.refreshLiquidGlass();
}
function savePreferences() {
  applyPreferences();
  try {
    localStorage.setItem(PREFS_KEY, JSON.stringify(preferences));
  } catch {
    notify("浏览器未能保存偏好，本次会话仍有效", false, true);
  }
}
function updateCutoff() {
  const value = $("cutoffInput").value;
  try {
    parseLocalTime(selectedMonth + "-01", value);
  } catch {
    $("settingsError").textContent = "请输入有效的下班时间";
    return false;
  }
  preferences.cutoff = value;
  $("settingsError").textContent = "";
  savePreferences();
  if (dataLoaded && !loadingData) processFile();
  return true;
}
async function refreshHolidays() {
  const year = Number(selectedMonth.slice(0, 4));
  $("refreshHolidaysButton").disabled = true;
  $("refreshHolidaysButton").textContent = "更新中…";
  try {
    const old = holidayMemoryCache[year] || readHolidayCache(year);
    const updated = await requestHolidayRefresh(year, old);
    if (updated) holidayMemoryCache[year] = updated;
    if (
      year === Number(selectedMonth.slice(0, 4)) &&
      dataLoaded &&
      !loadingData
    )
      await processFile();
    notify(
      updated && updated !== old
        ? "节假日安排已更新"
        : "网络暂不可用，已保留原有安排",
      false,
      !updated || updated === old,
    );
  } finally {
    $("refreshHolidaysButton").disabled = false;
    $("refreshHolidaysButton").textContent = "刷新安排";
  }
}

function renderMonthPicker() {
  $("yearError").textContent = "";
  $("yearInput").value = monthPickerYear;
  const prefixes = new Set(
    Object.keys(globalData)
      .filter((date) => globalData[date].entries.length)
      .map((date) => date.slice(0, 7)),
  );
  $("monthGrid").innerHTML = Array.from({ length: 12 }, (_, index) => {
    const month =
      String(monthPickerYear).padStart(4, "0") +
      "-" +
      String(index + 1).padStart(2, "0");
    return (
      '<button type="button" data-month="' +
      month +
      '" class="' +
      (month === selectedMonth ? "selected " : "") +
      (prefixes.has(month) ? "has-record" : "") +
      '" aria-label="' +
      readableMonth(month) +
      (prefixes.has(month) ? "，有记录" : "") +
      '" aria-pressed="' +
      (month === selectedMonth) +
      '">' +
      (index + 1) +
      "月</button>"
    );
  }).join("");
  const months = Array.from(prefixes).sort().reverse().slice(0, 18);
  $("savedMonths").innerHTML = months.length
    ? "<p>有记录的月份</p>" +
      months
        .map(
          (month) =>
            '<button type="button" data-month="' +
            month +
            '">' +
            readableMonth(month) +
            "</button>",
        )
        .join("")
    : "<p>有打卡的月份，会在这里留下一个小圆点。</p>";
  $("previousYear").disabled = monthPickerYear <= 0;
  $("nextYear").disabled = monthPickerYear >= 9999;
}

function initializeApp() {
  if (uiReady) return;
  uiReady = true;
  try {
    const stored = JSON.parse(localStorage.getItem(PREFS_KEY));
    if (stored && ["system", "light", "dark"].includes(stored.appearance))
      preferences.appearance = stored.appearance;
    if (stored && typeof stored.glass === "boolean")
      preferences.glass = stored.glass;
    if (stored && typeof stored.cutoff === "string") {
      parseLocalTime(selectedMonth + "-01", stored.cutoff);
      preferences.cutoff = stored.cutoff;
    }
  } catch {
    /* A corrupt preference must not prevent importing a backup. */
  }
  if (typeof BUILT_IN_HOLIDAYS !== "undefined") {
    for (const year of Object.keys(BUILT_IN_HOLIDAYS)) {
      const cached = readHolidayCache(Number(year));
      holidayMemoryCache[year] = cached || {
        year: Number(year),
        source: "内置 holiday-cn",
        fetchedAt: Date.now(),
        days: parseHolidayCn(BUILT_IN_HOLIDAYS[year], Number(year)),
        followingYearChecked: false,
      };
    }
  }
  applyPreferences();
  updateFileUi();
  updateMonthUi();
  renderCalendar(null);
  readDraft();
  const on = (id, event, fn) => $(id).addEventListener(event, fn);
  for (const id of ["importButton", "dockImport"])
    on(id, "click", () => $("fileInput").click());
  on("fileInput", "change", handleFileSelect);
  on("sampleButton", "click", loadSample);
  on("newWorkspaceButton", "click", createWorkspace);
  for (const id of ["dockAdd", "addRecordButton"])
    on(id, "click", () => openEditorOverlay());
  on("dockExport", "click", saveAndDownload);
  on("csvButton", "click", exportCsv);
  on("calculateButton", "click", processFile);
  on("previousMonth", "click", () =>
    selectMonth(shiftMonth(selectedMonth, -1)),
  );
  on("nextMonth", "click", () => selectMonth(shiftMonth(selectedMonth, 1)));
  for (const id of ["currentMonthButton", "monthTodayButton"])
    on(id, "click", () => selectMonth(monthKey(new Date())));
  on("monthPickerButton", "click", () => {
    monthPickerYear = Number(selectedMonth.slice(0, 4));
    $("yearError").textContent = "";
    renderMonthPicker();
    openDialog("monthOverlay");
  });
  on("previousYear", "click", () => {
    monthPickerYear = Math.max(0, monthPickerYear - 1);
    renderMonthPicker();
  });
  on("nextYear", "click", () => {
    monthPickerYear = Math.min(9999, monthPickerYear + 1);
    renderMonthPicker();
  });
  on("yearInput", "change", () => {
    const value = $("yearInput").value;
    const year = Number(value);
    if (
      !/^\d{1,4}$/.test(value) ||
      !Number.isInteger(year) ||
      year < 0 ||
      year > 9999
    ) {
      $("yearError").textContent = "年份应为 0 至 9999 的整数";
      document
        .querySelectorAll("#monthGrid button")
        .forEach((button) => (button.disabled = true));
      return;
    }
    monthPickerYear = year;
    renderMonthPicker();
  });
  for (const id of ["monthGrid", "savedMonths"])
    on(id, "click", (event) => {
      const button = event.target.closest("button[data-month]");
      if (button) selectMonth(button.dataset.month);
    });
  for (const id of ["calendarGrid", "result", "tableBody"])
    on(id, "click", (event) => {
      const button = event.target.closest("button[data-date]");
      if (button) openDeletePicker(button.dataset.date);
    });
  on("recordFilters", "click", (event) => {
    const button = event.target.closest("button[data-filter]");
    if (button) {
      recordFilter = button.dataset.filter;
      renderRecords();
    }
  });
  on("sortButton", "click", () => {
    newestFirst = !newestFirst;
    $("sortButton").innerHTML =
      (newestFirst ? "最新在前" : "最早在前") +
      ' <span aria-hidden="true">' +
      (newestFirst ? "↓" : "↑") +
      "</span>";
    $("sortButton").setAttribute(
      "aria-label",
      "切换排序，目前" + (newestFirst ? "最新在前" : "最早在前"),
    );
    renderRecords();
  });
  on("punchForm", "submit", (event) => {
    event.preventDefault();
    addEntry();
  });
  on("deleteList", "click", (event) => {
    const button = event.target.closest("button[data-index]");
    if (button)
      deletePunch(
        Number(button.dataset.index),
        Number(button.dataset.time),
        Number(button.dataset.revision),
      );
  });
  on("morePunchesButton", "click", () => {
    visiblePunches += 100;
    renderDayDetail();
  });
  on("deleteDayButton", "click", deleteDay);
  on("dayAddButton", "click", () => {
    const date = pendingDeleteDate;
    closeDialog("deleteOverlay");
    closeDialog("editorOverlay");
    openEditorOverlay(date);
  });
  on("confirmAccept", "click", () => {
    const action = confirmAction;
    closeDialog("confirmOverlay");
    if (action) action();
  });
  on("undoButton", "click", undoLastMutation);
  on("dayUndoButton", "click", undoLastMutation);
  on("editorUndoButton", "click", undoLastMutation);
  on("closeToast", "click", () => {
    $("toast").hidden = true;
  });
  on("restoreDraftButton", "click", restoreDraft);
  on("discardDraftButton", "click", discardDraft);
  on("themeButton", "click", () => {
    preferences.appearance =
      document.documentElement.dataset.theme === "dark" ? "light" : "dark";
    savePreferences();
  });
  on("settingsButton", "click", () => openDialog("settingsOverlay"));
  on("appearanceSelect", "change", () => {
    preferences.appearance = $("appearanceSelect").value;
    savePreferences();
  });
  on("glassToggle", "change", () => {
    preferences.glass = $("glassToggle").checked;
    savePreferences();
  });
  on("cutoffInput", "change", updateCutoff);
  on("settingsDone", "click", () => {
    if (updateCutoff()) closeDialog("settingsOverlay");
  });
  on("refreshHolidaysButton", "click", refreshHolidays);
  document
    .querySelectorAll("[data-close]")
    .forEach((button) =>
      button.addEventListener("click", () => closeDialog(button.dataset.close)),
    );
  document.querySelectorAll("dialog").forEach((dialog) => {
    dialog.addEventListener("click", (event) => {
      if (event.target === dialog) {
        const rect = dialog.getBoundingClientRect();
        if (
          event.clientX < rect.left ||
          event.clientX > rect.right ||
          event.clientY < rect.top ||
          event.clientY > rect.bottom
        )
          closeDialog(dialog.id);
      }
    });
    dialog.addEventListener("close", () => {
      // A queued close event may belong to an earlier presentation of this same dialog.
      if (dialog.open) return;
      dialog.classList.remove("active");
      dialog.setAttribute("aria-hidden", "true");
      if (dialog.id === "confirmOverlay") confirmAction = null;
      if (dialog.id === "deleteOverlay") pendingDeleteDate = null;
      if (!document.querySelector("dialog[open]")) {
        document.body.style.overflow = "";
        document.documentElement.dataset.modal = "off";
        if (window.refreshLiquidGlass) window.refreshLiquidGlass();
      }
    });
  });
  for (const event of ["dragenter", "dragover", "dragleave", "drop"])
    on("dropZone", event, (e) => {
      e.preventDefault();
      e.stopPropagation();
      $("dropZone").classList.toggle(
        "dragover",
        event === "dragenter" || event === "dragover",
      );
      if (event === "drop" && e.dataTransfer.files.length)
        requestImport(e.dataTransfer.files[0]);
    });
  window.addEventListener("beforeunload", (event) => {
    persistDraft();
    if (dirty) {
      event.preventDefault();
      event.returnValue = "";
    }
  });
  document.addEventListener("visibilitychange", () => {
    if (document.hidden) persistDraft();
    else if (dataLoaded && !loadingData) processFile();
    else {
      updateMonthUi();
      renderCalendar(null);
    }
  });
  window.addEventListener("storage", (event) => {
    if (event.key === DRAFT_KEY && event.newValue !== draftBase && dataLoaded) {
      draftBlocked = true;
      clearTimeout(draftTimer);
      $("conflictBanner").hidden = false;
      setSaveStatus("草稿有冲突，请先导出本页记录", true);
    }
  });
  const scheme = window.matchMedia("(prefers-color-scheme: dark)");
  if (scheme.addEventListener)
    scheme.addEventListener("change", () => {
      if (preferences.appearance === "system") applyPreferences();
    });
  document.querySelectorAll(".top-nav a").forEach((link) =>
    link.addEventListener("click", () => {
      document
        .querySelectorAll(".top-nav a")
        .forEach((item) => item.classList.toggle("active", item === link));
    }),
  );
  if (window.initializeLiquidGlass) window.initializeLiquidGlass();
}

if (document.readyState === "loading" || !document.readyState)
  document.addEventListener("DOMContentLoaded", initializeApp);
else initializeApp();
