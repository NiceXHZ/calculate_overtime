package com.liu.overtime.data

import android.content.Context
import android.util.AtomicFile
import com.liu.overtime.domain.*
import java.io.File
import java.time.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class StoreState(val records: List<DayRecord> = emptyList(), val error: String? = null, val loaded: Boolean = false)
data class PunchResult(val instant: Instant, val added: Boolean)

/** One process, one lock, one atomic file shared by the app, widgets and tile. */
class RecordStore private constructor(context: Context) {
    private val dir = File(context.filesDir, "attendance").apply { mkdirs() }
    private val file = AtomicFile(File(dir, "overtime_log.json"))
    private val history = File(dir, "history").apply { mkdirs() }
    private val mutable = MutableStateFlow(StoreState())
    val state = mutable.asStateFlow()

    @Synchronized fun load() {
        if (mutable.value.loaded) return
        try {
            val records = if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
                RecordCodec.decode(file.openRead().bufferedReader().use { it.readText() })
            } else {
                persist(emptyList(), backup = false)
                emptyList()
            }
            mutable.value = StoreState(records, loaded = true)
        } catch (e: Exception) {
            mutable.value = StoreState(error = "本地记录读取失败，已保留原文件。可在设置中恢复最近备份。${e.message}", loaded = true)
        }
    }

    @Synchronized private fun current(): List<DayRecord> {
        load()
        check(mutable.value.error == null) { mutable.value.error.orEmpty() }
        return mutable.value.records
    }

    private fun persist(records: List<DayRecord>, backup: Boolean = true) {
        val bytes = RecordCodec.encode(records).toByteArray()
        require(bytes.size <= RecordCodec.MAX_BYTES) { "记录文件已达到 8 MB，请先导出归档" }
        if (backup && file.baseFile.exists()) {
            // Failure to create the recovery copy aborts the write too.
            file.baseFile.copyTo(File(history, "${System.currentTimeMillis()}-${System.nanoTime()}.json"))
        }
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
        history.listFiles()?.sortedByDescending { it.name }?.drop(10)?.forEach { it.delete() }
    }

    private fun commit(records: List<DayRecord>) {
        persist(records)
        mutable.value = StoreState(records.sortedBy { it.date }, loaded = true)
    }

    @Synchronized fun punch(now: Instant = Instant.now(), guard: Boolean = true): PunchResult {
        val instant = now.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
        val records = current()
        val date = instant.atZone(WorkZone).toLocalDate()
        val day = records.find { it.date == date } ?: DayRecord(date, emptyList())
        val last = day.punches.maxOrNull()
        if (guard && last != null && kotlin.math.abs(Duration.between(last, instant).toMillis()) < DUPLICATE_GUARD_MILLIS) return PunchResult(last, false)
        commit(records.filterNot { it.date == date } + day.copy(punches = (day.punches + instant).sorted()))
        return PunchResult(instant, true)
    }

    @Synchronized fun remove(date: LocalDate, index: Int) {
        val records = current()
        val day = records.first { it.date == date }
        require(index in day.punches.indices)
        // Keep the day and metadata, matching the web editor's single-entry deletion.
        commit(records.map { if (it.date == date) it.copy(punches = day.punches.filterIndexed { i, _ -> i != index }) else it })
    }

    @Synchronized fun undoPunch(instant: Instant) {
        val day = current().find { instant in it.punches } ?: return
        remove(day.date, day.punches.indexOfLast { it == instant })
    }

    @Synchronized fun import(text: String): Int {
        val incoming = RecordCodec.decode(text) // Fully validate before touching persisted records.
        val merged = RecordCodec.merge(current(), incoming)
        val added = merged.sumOf { it.punches.size } - mutable.value.records.sumOf { it.punches.size }
        commit(merged)
        return added
    }

    @Synchronized fun export(): String = RecordCodec.encode(current())
    @Synchronized fun recoverLatest() {
        val recovered = history.listFiles()?.sortedByDescending { it.name }?.firstNotNullOfOrNull {
            runCatching { RecordCodec.decode(it.readText()) }.getOrNull()
        } ?: error("暂无可恢复的本地备份")
        // Preserve an unreadable original for manual recovery.
        if (file.baseFile.exists()) file.baseFile.copyTo(File(dir, "damaged-${System.currentTimeMillis()}.json"))
        persist(recovered, backup = false)
        mutable.value = StoreState(recovered, loaded = true)
    }

    companion object {
        const val DUPLICATE_GUARD_MILLIS = 3_000L
        const val DUPLICATE_NOTICE = "3秒内已打卡，请稍后再试"
        @Volatile private var instance: RecordStore? = null
        fun get(context: Context): RecordStore = instance ?: synchronized(this) {
            instance ?: RecordStore(context.applicationContext).also { instance = it }
        }
    }
}
