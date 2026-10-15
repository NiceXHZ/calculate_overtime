package com.liu.overtime

import android.content.Context
import android.view.ContextThemeWrapper
import android.widget.RemoteViews
import com.liu.overtime.data.RecordStore
import com.liu.overtime.domain.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecordStoreTest {
    @Before fun isolatedApplicationFiles() {
        RecordStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        File(RuntimeEnvironment.getApplication().filesDir, "attendance").deleteRecursively()
    }
    @Test fun durablePunchGuardImportUndoAndRecovery() {
        val context = RuntimeEnvironment.getApplication() as Context
        val store = RecordStore.get(context)
        store.load()
        val before = store.state.value.records.sumOf { it.punches.size }
        val instant = Instant.parse("2026-10-15T10:00:00.123456Z").truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
        assertTrue(store.punch(instant).added)
        assertFalse(store.punch(instant.plusSeconds(2)).added)
        assertEquals(before + 1, store.state.value.records.sumOf { it.punches.size })
        val file = File(context.filesDir, "attendance/overtime_log.json")
        assertEquals(store.state.value.records, RecordCodec.decode(file.readText()))
        val exported = store.export()
        assertEquals(0, store.import(exported))
        assertTrue(runCatching { store.import("""{"bad":{}}""") }.isFailure)
        assertEquals(exported, store.export())
        store.undoPunch(instant)
        assertEquals(before, store.state.value.records.sumOf { it.punches.size })
        store.recoverLatest()
        assertEquals(before + 1, store.state.value.records.sumOf { it.punches.size })
    }

    @Test fun duplicateGuardExpiresAtExactlyThreeSeconds() {
        val store = RecordStore.get(RuntimeEnvironment.getApplication())
        val instant = Instant.parse("2026-10-15T01:00:00Z")
        assertTrue(store.punch(instant).added)
        assertFalse(store.punch(instant.plusMillis(2999)).added)
        assertTrue(store.punch(instant.plusSeconds(3)).added)
        assertEquals(2, store.state.value.records.sumOf { it.punches.size })
    }

    @Test fun allWidgetLayoutsInflateAndExposePunchTargets() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), android.R.style.Theme_Material_Light)
        listOf(R.layout.widget_quick, R.layout.widget_small, R.layout.widget_today,
            R.layout.widget_strip, R.layout.widget_medium, R.layout.widget_large).forEach { layout ->
            val view = RemoteViews(context.packageName, layout).apply(context, null)
            assertNotNull(view.findViewById<android.view.View>(R.id.widget_punch))
            assertNotNull(view.findViewById<android.view.View>(R.id.widget_status))
        }
    }

    @Test fun widgetBroadcastPersistsAndSharesDuplicateGuard() {
        val context = RuntimeEnvironment.getApplication() as Context
        val store = RecordStore.get(context)
        store.load()
        val before = store.state.value.records.sumOf { it.punches.size }
        val component = android.content.ComponentName(context, com.liu.overtime.widget.PunchReceiver::class.java)
        assertFalse(context.packageManager.getReceiverInfo(component, 0).exported)
        // Robolectric does not instantiate manifest receivers for sendBroadcast; use the same receiver.
        val receiver = com.liu.overtime.widget.PunchReceiver()
        androidx.core.content.ContextCompat.registerReceiver(context, receiver, android.content.IntentFilter("com.liu.overtime.PUNCH"), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        context.sendBroadcast(android.content.Intent("com.liu.overtime.PUNCH").setPackage(context.packageName))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        context.unregisterReceiver(receiver)
        val deadline = System.nanoTime() + 3_000_000_000L
        while (store.state.value.records.sumOf { it.punches.size } == before && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(before + 1, store.state.value.records.sumOf { it.punches.size })
        assertFalse(store.punch().added)
        assertEquals(store.state.value.records, RecordCodec.decode(File(context.filesDir, "attendance/overtime_log.json").readText()))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test fun damagedFileIsNotOverwrittenAndCanRecover() {
        val context = RuntimeEnvironment.getApplication() as Context
        val original = RecordStore.get(context)
        original.punch(Instant.parse("2026-10-15T01:00:00Z"), guard = false)
        original.punch(Instant.parse("2026-10-15T10:00:00Z"), guard = false)
        val file = File(context.filesDir, "attendance/overtime_log.json")
        file.writeText("damaged original")
        val fresh = RecordStore::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(context)
        fresh.load()
        assertNotNull(fresh.state.value.error)
        assertTrue(runCatching { fresh.punch() }.isFailure)
        assertEquals("damaged original", file.readText())
        fresh.recoverLatest()
        assertNull(fresh.state.value.error)
        assertTrue(fresh.state.value.records.sumOf { it.punches.size } > 0)
        assertTrue(File(context.filesDir, "attendance").listFiles()!!.any { it.name.startsWith("damaged-") && it.readText() == "damaged original" })
    }
}
