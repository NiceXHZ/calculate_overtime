"use strict";

// Shared, side-effect-free data validation and calendar rules.
function localDateKey(date) {
  return (
    String(date.getFullYear()).padStart(4, "0") +
    "-" +
    String(date.getMonth() + 1).padStart(2, "0") +
    "-" +
    String(date.getDate()).padStart(2, "0")
  );
}

function parseDateKey(value) {
  if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    throw new Error("日期必须使用 YYYY-MM-DD 格式");
  }
  var date = new Date(value + "T00:00:00");
  if (!Number.isFinite(date.getTime()) || localDateKey(date) !== value) {
    throw new Error("无效日期：" + value);
  }
  return date;
}

function parseEntry(value, dateStr) {
  var isNumber = typeof value === "number" && Number.isSafeInteger(value);
  var isISO =
    typeof value === "string" &&
    /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?(?:Z|[+-]\d{2}:\d{2})$/.test(
      value,
    );
  if (!isNumber && !isISO)
    throw new Error("打卡时间须为毫秒时间戳或带时区的 ISO 时间字符串");
  if (isISO) {
    parseDateKey(value.slice(0, 10));
    if (
      Number(value.slice(11, 13)) > 23 ||
      Number(value.slice(14, 16)) > 59 ||
      Number(value.slice(17, 19)) > 59
    )
      throw new Error("无效的打卡时间");
  }
  if (isNumber) {
    var secondsDate = new Date(value * 1000);
    if (
      Number.isFinite(secondsDate.getTime()) &&
      dateStr &&
      localDateKey(new Date(value)) !== dateStr &&
      localDateKey(secondsDate) === dateStr
    ) {
      throw new Error("数字时间戳必须使用毫秒，不能使用秒");
    }
  }
  var date = new Date(value);
  if (!Number.isFinite(date.getTime())) throw new Error("无效的打卡时间");
  if (dateStr && localDateKey(date) !== dateStr) {
    throw new Error("打卡时间日期必须与记录日期一致，不支持跨日打卡");
  }
  return date;
}

function formatEntry(date) {
  // Legacy timezone offsets may contain seconds; retain exact instants as supported milliseconds.
  if (date.getSeconds() !== date.getUTCSeconds()) return date.getTime();
  var offset = -date.getTimezoneOffset();
  var sign = offset >= 0 ? "+" : "-";
  var absOffset = Math.abs(offset);
  var milliseconds = date.getMilliseconds();
  return (
    localDateKey(date) +
    "T" +
    date.toTimeString().slice(0, 8) +
    (milliseconds ? "." + String(milliseconds).padStart(3, "0") : "") +
    sign +
    String(Math.floor(absOffset / 60)).padStart(2, "0") +
    ":" +
    String(absOffset % 60).padStart(2, "0")
  );
}

// Normalize legacy iOS strings, scalar timestamps and mixed arrays once on import.
// Keep unrelated record fields and all months when exporting.
function normalizeData(data) {
  if (!data || typeof data !== "object" || Array.isArray(data)) {
    throw new Error("JSON 顶层必须是按日期索引的对象");
  }
  var normalized = {};
  Object.keys(data)
    .sort()
    .forEach(function (dateStr) {
      parseDateKey(dateStr);
      var value = data[dateStr];
      if (
        !value ||
        typeof value !== "object" ||
        Array.isArray(value) ||
        !Object.prototype.hasOwnProperty.call(value, "entries")
      ) {
        throw new Error(dateStr + " 缺少有效的 entries 字段");
      }
      var entries = Array.isArray(value.entries)
        ? value.entries
        : [value.entries];
      var dates = entries
        .map(function (entry) {
          try {
            return parseEntry(entry, dateStr);
          } catch (err) {
            throw new Error(dateStr + "：" + err.message);
          }
        })
        .sort(function (a, b) {
          return a - b;
        });
      normalized[dateStr] = { ...value, entries: dates.map(formatEntry) };
    });
  return normalized;
}

function addHolidayDay(days, dateStr, isOffDay, name) {
  parseDateKey(dateStr);
  days[dateStr] = {
    isOffDay: isOffDay,
    name: name || (isOffDay ? "节假日" : "调休上班"),
  };
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, function (char) {
    return {
      "&": "&amp;",
      "<": "&lt;",
      ">": "&gt;",
      '"': "&quot;",
      "'": "&#39;",
    }[char];
  });
}

function parseHolidayCn(data, year) {
  if (
    !data ||
    data.year !== year ||
    !Array.isArray(data.days) ||
    data.days.length === 0
  ) {
    throw new Error("holiday-cn 数据格式无效");
  }
  var days = {};
  data.days.forEach(function (day) {
    if (!day || typeof day.isOffDay !== "boolean")
      throw new Error("holiday-cn 日期数据无效");
    addHolidayDay(days, day.date, day.isOffDay, day.name);
  });
  return days;
}

function parseShuyzCalendar(data, year) {
  var holidays = data && data.Years && data.Years[String(year)];
  if (!Array.isArray(holidays) || holidays.length === 0)
    throw new Error("china-holiday-calender 数据格式无效");
  var days = {};
  holidays.forEach(function (holiday) {
    var current = parseDateKey(holiday.StartDate);
    var end = parseDateKey(holiday.EndDate);
    if (current > end) throw new Error("china-holiday-calender 日期范围无效");
    while (current <= end) {
      addHolidayDay(days, localDateKey(current), true, holiday.Name);
      current.setDate(current.getDate() + 1);
    }
  });
  // Apply compensation days last so they always override an overlapping holiday range.
  holidays.forEach(function (holiday) {
    (holiday.CompDays || []).forEach(function (dateStr) {
      addHolidayDay(days, dateStr, false, "调休上班");
    });
  });
  return days;
}

function parseTimorCalendar(data) {
  if (
    !data ||
    data.code !== 0 ||
    !data.holiday ||
    typeof data.holiday !== "object"
  ) {
    throw new Error("Timor 节假日数据格式无效");
  }
  var keys = Object.keys(data.holiday);
  if (keys.length === 0) throw new Error("Timor 节假日数据为空");
  var days = {};
  keys.forEach(function (key) {
    var day = data.holiday[key];
    if (!day || typeof day.holiday !== "boolean")
      throw new Error("Timor 日期数据无效");
    addHolidayDay(days, day.date, day.holiday, day.name);
  });
  return days;
}

function monthKey(date) {
  return localDateKey(date).slice(0, 7);
}

function parseMonth(value) {
  if (typeof value !== "string" || !/^\d{4}-(0[1-9]|1[0-2])$/.test(value))
    throw new Error("月份格式应为 YYYY-MM");
  return parseDateKey(value + "-01");
}

function shiftMonth(value, offset) {
  const date = parseMonth(value);
  if (!Number.isInteger(offset)) throw new Error("无效的月份偏移");
  date.setMonth(date.getMonth() + offset);
  if (date.getFullYear() < 0 || date.getFullYear() > 9999) return value;
  return monthKey(date);
}

function monthRange(value) {
  const start = parseMonth(value);
  const end = new Date(start);
  end.setMonth(end.getMonth() + 1);
  end.setDate(0);
  return {
    min: localDateKey(start),
    max: localDateKey(end),
    length: end.getDate(),
    offset: (start.getDay() + 6) % 7,
  };
}

function parseLocalTime(dateKey, time) {
  parseDateKey(dateKey);
  if (!/^([01]\d|2[0-3]):[0-5]\d$/.test(time))
    throw new Error("请输入有效的小时和分钟");
  const date = new Date(dateKey + "T" + time + ":00");
  if (
    !Number.isFinite(date.getTime()) ||
    localDateKey(date) !== dateKey ||
    date.toTimeString().slice(0, 5) !== time
  ) {
    throw new Error("该日期的本地时间无效，请检查夏令时或时区设置");
  }
  return date;
}

function dayClassification(dateKey, calendar) {
  const date = parseDateKey(dateKey);
  const override = calendar && calendar.days && calendar.days[dateKey];
  if (override)
    return {
      type: override.isOffDay ? "holiday" : "adjusted",
      label: override.isOffDay ? override.name : "调休上班",
      rest: override.isOffDay,
    };
  const rest = date.getDay() === 0 || date.getDay() === 6;
  return {
    type: rest ? "weekend" : "workday",
    label: rest ? "周末" : "工作日",
    rest,
  };
}

function summarizeMonth(data, month, calendar, cutoff = "17:30") {
  const range = monthRange(month);
  parseLocalTime(range.min, cutoff);
  const days = [];
  let totalSec = 0,
    overtimeDays = 0,
    punchCount = 0,
    incompleteDays = 0;
  for (const dateKey of Object.keys(data).sort()) {
    if (!dateKey.startsWith(month + "-")) continue;
    const entries = data[dateKey].entries;
    if (!Array.isArray(entries))
      throw new Error(dateKey + "：entries 必须是数组");
    if (!entries.length) continue;
    let earliest = Infinity,
      latest = -Infinity;
    for (const entry of entries) {
      const instant = parseEntry(entry, dateKey).getTime();
      earliest = Math.min(earliest, instant);
      latest = Math.max(latest, instant);
    }
    const classification = dayClassification(dateKey, calendar);
    const durationSec = classification.rest
      ? entries.length > 1
        ? (latest - earliest) / 1000
        : 0
      : Math.max(
          0,
          (latest - parseLocalTime(dateKey, cutoff).getTime()) / 1000,
        );
    const incomplete = classification.rest && entries.length < 2;
    days.push({
      date: dateKey,
      weekday: ["周日", "周一", "周二", "周三", "周四", "周五", "周六"][
        parseDateKey(dateKey).getDay()
      ],
      dayType: classification.type,
      dayTypeLabel: classification.label,
      isRestDay: classification.rest,
      count: entries.length,
      first: earliest,
      last: latest,
      durationSec,
      incomplete,
    });
    totalSec += durationSec;
    punchCount += entries.length;
    if (durationSec > 0) overtimeDays++;
    if (incomplete) incompleteDays++;
  }
  return {
    month,
    days,
    totalSec,
    overtimeDays,
    punchCount,
    incompleteDays,
    averageSec: overtimeDays ? totalSec / overtimeDays : 0,
    maxSec: days.reduce((max, day) => Math.max(max, day.durationSec), 0),
  };
}

function heatLevel(seconds) {
  return seconds <= 0
    ? 0
    : seconds < 3600
      ? 1
      : seconds < 10800
        ? 2
        : seconds < 21600
          ? 3
          : 4;
}

function formatDuration(seconds) {
  if (seconds > 0 && seconds < 60) return "少于1分钟";
  const minutes = Math.floor(Math.max(0, seconds) / 60);
  return Math.floor(minutes / 60) + "小时" + (minutes % 60) + "分钟";
}

function compactDuration(seconds) {
  if (seconds <= 0) return "0h";
  if (seconds < 60) return "<1m";
  const hours = seconds / 3600;
  return hours < 1
    ? Math.floor(seconds / 60) + "m"
    : Number(hours.toFixed(1)) + "h";
}

function csvCell(value) {
  let text = String(value);
  if (/^[\s]*[=+\-@]/.test(text) || /^[\t\r\n]/.test(text)) text = "'" + text;
  return '"' + text.replace(/"/g, '""') + '"';
}

function monthCsv(summary, cutoff, zone) {
  const rows = [
    [
      "日期",
      "星期",
      "日期类型",
      "打卡次数",
      "首次打卡",
      "末次打卡",
      "加班秒数",
      "加班小时",
      "工作日下班时间",
      "时区",
      "提示",
    ],
  ];
  for (const day of summary.days) {
    rows.push([
      day.date,
      day.weekday,
      day.dayTypeLabel,
      day.count,
      new Date(day.first).toTimeString().slice(0, 8),
      new Date(day.last).toTimeString().slice(0, 8),
      day.durationSec,
      (day.durationSec / 3600).toFixed(4),
      cutoff,
      zone,
      day.incomplete ? "仅一次打卡，无法计算休息日首末间隔" : "",
    ]);
  }
  return (
    "\uFEFF" +
    rows.map((row) => row.map(csvCell).join(",")).join("\r\n") +
    "\r\n"
  );
}

if (typeof module !== "undefined" && module.exports)
  module.exports = {
    localDateKey,
    parseDateKey,
    parseEntry,
    formatEntry,
    normalizeData,
    escapeHtml,
    parseHolidayCn,
    parseShuyzCalendar,
    parseTimorCalendar,
    monthKey,
    parseMonth,
    shiftMonth,
    monthRange,
    parseLocalTime,
    dayClassification,
    summarizeMonth,
    heatLevel,
    formatDuration,
    compactDuration,
    csvCell,
    monthCsv,
  };
