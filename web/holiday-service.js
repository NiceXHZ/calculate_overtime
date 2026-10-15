"use strict";

const HOLIDAY_CACHE_PREFIX = "overtime-holidays-v1-";
const HOLIDAY_CACHE_TTL = 7 * 86400000;
const HOLIDAY_RETRY_DELAY = 5 * 60000;
const holidayMemoryCache = Object.create(null);
const holidayRequests = Object.create(null);
const holidayRetryAfter = Object.create(null);

function fetchHolidayJson(url) {
  var controller =
    typeof AbortController === "function" ? new AbortController() : null;
  var timer = setTimeout(function () {
    if (controller) controller.abort();
  }, 5000);
  return fetch(url, controller ? { signal: controller.signal } : {})
    .then(function (response) {
      if (!response.ok) throw new Error("HTTP " + response.status);
      return response.json();
    })
    .finally(function () {
      clearTimeout(timer);
    });
}

function readHolidayCache(year) {
  try {
    var raw = localStorage.getItem(HOLIDAY_CACHE_PREFIX + year);
    if (!raw) return null;
    var cached = JSON.parse(raw);
    if (
      !cached ||
      cached.year !== year ||
      !Number.isFinite(cached.fetchedAt) ||
      cached.fetchedAt < 0 ||
      cached.fetchedAt > Date.now() + 300000 ||
      typeof cached.source !== "string" ||
      !cached.days ||
      typeof cached.days !== "object" ||
      Array.isArray(cached.days)
    )
      return null;
    Object.keys(cached.days).forEach(function (dateStr) {
      parseDateKey(dateStr);
      if (
        !cached.days[dateStr] ||
        typeof cached.days[dateStr].isOffDay !== "boolean" ||
        typeof cached.days[dateStr].name !== "string"
      )
        throw new Error("缓存日期无效");
    });
    return cached;
  } catch (err) {
    try {
      localStorage.removeItem(HOLIDAY_CACHE_PREFIX + year);
    } catch (_) {}
    return null;
  }
}

function writeHolidayCache(calendar) {
  try {
    localStorage.setItem(
      HOLIDAY_CACHE_PREFIX + calendar.year,
      JSON.stringify(calendar),
    );
  } catch (err) {
    console.warn("无法保存节假日缓存：", err);
  }
}

async function refreshHolidayCalendar(year, staleCache) {
  var sources = [
    {
      name: "holiday-cn",
      load: async function () {
        var days = parseHolidayCn(
          await fetchHolidayJson(
            "https://cdn.jsdelivr.net/gh/NateScarlet/holiday-cn@master/" +
              year +
              ".json",
          ),
          year,
        );
        var followingYearChecked = false;
        var now = new Date();
        // The following year's notice can contain corrections for late December.
        if (now.getFullYear() === year && now.getMonth() === 11) {
          followingYearChecked = true;
          try {
            var followingDays = parseHolidayCn(
              await fetchHolidayJson(
                "https://cdn.jsdelivr.net/gh/NateScarlet/holiday-cn@master/" +
                  (year + 1) +
                  ".json",
              ),
              year + 1,
            );
            Object.keys(followingDays).forEach(function (dateStr) {
              if (dateStr.indexOf(year + "-") === 0)
                days[dateStr] = followingDays[dateStr];
            });
          } catch (err) {
            console.warn("下一年度通知尚不可用，保留本年度节假日", err);
          }
        }
        return { days: days, followingYearChecked: followingYearChecked };
      },
    },
    {
      name: "china-holiday-calender",
      load: async function () {
        return {
          days: parseShuyzCalendar(
            await fetchHolidayJson(
              "https://www.shuyz.com/githubfiles/china-holiday-calender/master/holidayAPI.json",
            ),
            year,
          ),
        };
      },
    },
    {
      name: "Timor",
      load: async function () {
        return {
          days: parseTimorCalendar(
            await fetchHolidayJson(
              "https://timor.tech/api/holiday/year/" + year,
            ),
          ),
        };
      },
    },
  ];

  for (var i = 0; i < sources.length; i++) {
    try {
      var source = sources[i];
      var loaded = await source.load();
      var calendar = {
        year: year,
        fetchedAt: Date.now(),
        source: source.name,
        days: loaded.days,
        followingYearChecked: loaded.followingYearChecked === true,
      };
      delete holidayRetryAfter[year];
      writeHolidayCache(calendar);
      return calendar;
    } catch (err) {
      console.warn("节假日数据源不可用：" + sources[i].name, err);
    }
  }
  holidayRetryAfter[year] = Date.now() + HOLIDAY_RETRY_DELAY;
  return staleCache;
}

function loadHolidayCalendar(year) {
  var cached = holidayMemoryCache[year] || readHolidayCache(year);
  var now = new Date();
  var needsDecemberRefresh =
    cached &&
    cached.source.includes("holiday-cn") &&
    now.getFullYear() === year &&
    now.getMonth() === 11 &&
    cached.followingYearChecked !== true;
  if (
    cached &&
    !needsDecemberRefresh &&
    Date.now() - cached.fetchedAt < HOLIDAY_CACHE_TTL
  ) {
    holidayMemoryCache[year] = cached;
    return cached;
  }
  if (holidayRetryAfter[year] && Date.now() < holidayRetryAfter[year])
    return cached;
  return requestHolidayRefresh(year, cached);
}

function requestHolidayRefresh(year, cached) {
  if (!holidayRequests[year]) {
    holidayRequests[year] = refreshHolidayCalendar(year, cached)
      .then(function (calendar) {
        if (calendar) holidayMemoryCache[year] = calendar;
        return calendar;
      })
      .catch(function (err) {
        console.warn("节假日数据加载失败：", err);
        return cached;
      })
      .finally(function () {
        delete holidayRequests[year];
      });
  }
  return holidayRequests[year];
}
