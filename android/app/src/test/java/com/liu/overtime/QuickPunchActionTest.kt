package com.liu.overtime

import android.Manifest
import android.app.Application
import android.os.Vibrator
import com.liu.overtime.data.RecordStore
import com.liu.overtime.domain.RecordCodec
import com.liu.overtime.widget.recordFromOutside
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import org.robolectric.shadows.ShadowVibrator
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QuickPunchActionTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()

    @Before fun prepareFiles() {
        resetStore()
        File(context.filesDir, "attendance").deleteRecursively()
        ShadowToast.reset()
        ShadowVibrator.reset()
        shadowOf(context).grantPermissions(Manifest.permission.VIBRATE)
    }

    @After fun resetStore() {
        RecordStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }

    @Test fun sharedExternalEntryAcknowledgesCommittedDataAndSuppressesDuplicates() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            recordFromOutside(context)
            val store = RecordStore.get(context)
            val record = store.state.value.records.single()
            assertEquals(1, record.punches.size)
            assertTrue(ShadowToast.getTextOfLatestToast().startsWith("打卡已保存 · "))
            assertEquals(store.state.value.records,
                RecordCodec.decode(File(context.filesDir, "attendance/overtime_log.json").readText()))
            recordFromOutside(context)
            assertEquals(RecordStore.DUPLICATE_NOTICE, ShadowToast.getTextOfLatestToast())
            assertEquals(1, store.state.value.records.single().punches.size)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun corruptDataIsNeverReportedAsSavedOrOverwritten() = runTest {
        val file = File(context.filesDir, "attendance/overtime_log.json")
        file.parentFile!!.mkdirs()
        file.writeText("damaged original")
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            recordFromOutside(context)
            assertTrue(ShadowToast.getTextOfLatestToast().startsWith("打卡未保存："))
            assertEquals("damaged original", file.readText())
            assertFalse(shadowOf(context.getSystemService(Vibrator::class.java)).isVibrating)
        } finally { Dispatchers.resetMain() }
    }
}
