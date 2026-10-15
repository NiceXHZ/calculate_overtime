package com.liu.overtime.data

import android.content.Context
import android.util.AtomicFile
import com.liu.overtime.domain.*
import java.io.File
import java.time.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class StoreState(val records: List<DayRecord> = emptyList(), val error: String? = null, val loaded: Boolean = false)
data class PunchResult(val instant: Instant, val added: Boolean, val revision: Long = 0)

/** One process, one lock, one atomic file shared by the app, widgets and tile. */
class RecordStore private constructor(context: Context) {
    private val dir = File(context.filesDir, "attendance").apply { mkdirs() }
    private val file = AtomicFile(File(dir, "overtime_log.json"))
    private val history = File(dir, "history").apply { mkdirs() }
    // Remains ordered even when the wall clock moves backwards.
    private var snapshotTime = history.listFiles().orEmpty().maxOfOrNull { it.name.substringBefore('-').toLongOrNull() ?: 0L } ?: 0L
    private val mutable = MutableStateFlow(StoreState())
    private var revision = 0L
    val state = mutable.asStateFlow()

    @Synchronized fun load() {
        if (mutable.value.loaded) return
        try {
            val records = if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
                file.openRead().use { RecordCodec.decode(it.readLimited(RecordCodec.MAX_BYTES).toString(Charsets.UTF_8)) }
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
        var snapshot: File? = null
        if (backup && file.baseFile.exists()) {
            // Failure to create the recovery copy aborts the write too.
            snapshotTime = maxOf(System.currentTimeMillis(), snapshotTime + 1)
            val copy = File(history, "$snapshotTime-${System.nanoTime()}.json")
            val pending = File(history, copy.name + ".pending")
            try {
                file.baseFile.inputStream().use { input ->
                    pending.outputStream().use { output -> input.copyTo(output); output.fd.sync() }
                }
                check(pending.renameTo(copy)) { "无法保存修改前副本" }
                snapshot = copy
            } finally { pending.delete() }
        }
        val stream = try { file.startWrite() } catch (e: Exception) { snapshot?.delete(); throw e }
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); snapshot?.delete(); throw e }
        snapshots().drop(10).forEach { it.delete() }
    }

    private fun snapshots() = history.listFiles().orEmpty().filter { it.isFile && it.extension == "json" }
        .sortedWith(compareByDescending<File> { it.name.substringBefore('-').toLongOrNull() ?: Long.MIN_VALUE }.thenByDescending { it.name })

    private fun commit(records: List<DayRecord>) {
        persist(records)
        revision++
        mutable.value = StoreState(records.sortedBy { it.date }, loaded = true)
    }

    @Synchronized fun punch(now: Instant = Instant.now(), guard: Boolean = true): PunchResult {
        val instant = now.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
        val records = current()
        val date = instant.atZone(WorkZone).toLocalDate()
        val day = records.find { it.date == date } ?: DayRecord(date, emptyList())
        // Compare the adjacent date too, and do not let an imported future punch hide a nearby one.
        val duplicate = if (guard) records.asSequence().filter { kotlin.math.abs(it.date.toEpochDay() - date.toEpochDay()) <= 1 }
            .flatMap { it.punches.asSequence() }.firstOrNull { kotlin.math.abs(Duration.between(it, instant).toMillis()) < DUPLICATE_GUARD_MILLIS } else null
        if (duplicate != null) return PunchResult(duplicate, false, revision)
        commit(records.filterNot { it.date == date } + day.copy(punches = (day.punches + instant).sorted()))
        return PunchResult(instant, true, revision)
    }

    @Synchronized fun remove(date: LocalDate, index: Int, expected: Instant? = null) {
        val records = current()
        val day = records.first { it.date == date }
        require(index in day.punches.indices)
        require(expected == null || day.punches[index] == expected) { "记录已变化，请重新选择要删除的打卡" }
        // Keep the day and metadata, matching the web editor's single-entry deletion.
        commit(records.map { if (it.date == date) it.copy(punches = day.punches.filterIndexed { i, _ -> i != index }) else it })
    }

    @Synchronized fun undoPunch(instant: Instant, expectedRevision: Long? = null) {
        current()
        check(expectedRevision == null || revision == expectedRevision) { "记录已发生后续修改，本次撤销已失效；当前数据已保留" }
        val day = current().find { instant in it.punches } ?: return
        remove(day.date, day.punches.indexOfLast { it == instant })
    }

    @Synchronized fun import(text: String): Int {
        val incoming = RecordCodec.decode(text) // Fully validate before touching persisted records.
        val merged = RecordCodec.merge(current(), incoming)
        val added = merged.sumOf { it.punches.size } - mutable.value.records.sumOf { it.punches.size }
        if (merged != mutable.value.records) commit(merged)
        return added
    }

    @Synchronized fun export(): String = RecordCodec.encode(current())
    @Synchronized fun recoverLatest() {
        load()
        val recovered = snapshots().firstNotNullOfOrNull {
            runCatching { it.inputStream().use { input -> RecordCodec.decode(input.readLimited(RecordCodec.MAX_BYTES).toString(Charsets.UTF_8)) } }.getOrNull()
        } ?: error("暂无可恢复的本地备份")
        // Preserve an unreadable original for manual recovery.
        val damaged = mutable.value.error != null
        if (damaged && file.baseFile.exists()) file.baseFile.copyTo(File(dir, "damaged-${System.currentTimeMillis()}-${System.nanoTime()}.json"))
        persist(recovered, backup = !damaged)
        revision++
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
