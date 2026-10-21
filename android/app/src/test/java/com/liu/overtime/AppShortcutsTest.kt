package com.liu.overtime

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ShortcutManager
import com.liu.overtime.data.RecordStore
import com.liu.overtime.shortcuts.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowShortcutManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class AppShortcutsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(ShortcutManager::class.java)

    @Before fun resetLauncher() {
        ShadowShortcutManager.reset()
        shadowOf(manager).setMaxShortcutCountPerActivity(5)
    }

    @Test fun publishesThreeOrderedShortcutsWithExplicitReusableActivityIntents() {
        AppShortcuts.publish(context)
        val shortcuts = manager.dynamicShortcuts.sortedBy { it.rank }
        assertEquals(listOf("记录打卡", "补录打卡", "查看记录"), shortcuts.map { it.shortLabel.toString() })
        assertEquals(ShortcutAction.entries.map { it.id }, shortcuts.map { it.id })
        shortcuts.forEachIndexed { index, shortcut ->
            val intent = requireNotNull(shortcut.intent)
            assertEquals(ShortcutAction.entries[index].intentAction, intent.action)
            assertEquals(MainActivity::class.java.name, intent.component!!.className)
            assertEquals(context.packageName, intent.component!!.packageName)
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
            assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK)
            assertNull(intent.data)
            assertNull(intent.extras)
        }
        AppShortcuts.publish(context)
        assertEquals(3, manager.dynamicShortcuts.size)
    }

    @Test fun respectsTheLaunchersShortcutLimitAndUnavailableService() {
        shadowOf(manager).setMaxShortcutCountPerActivity(2)
        AppShortcuts.publish(context)
        assertEquals(2, manager.dynamicShortcuts.size)
        val unavailable = object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? {
                if (name == SHORTCUT_SERVICE) throw IllegalStateException("Launcher unavailable")
                return super.getSystemService(name)
            }
        }
        AppShortcuts.publish(unavailable)
    }

    @Test fun consumesOnlyKnownCommandsExactlyOnce() {
        for (action in ShortcutAction.entries) {
            val intent = AppShortcuts.intent(context, action)
            assertEquals(action, AppShortcuts.consume(intent))
            assertEquals(Intent.ACTION_MAIN, intent.action)
            assertNull(AppShortcuts.consume(intent))
        }
        val unrelated = Intent("com.example.UNRELATED")
        assertNull(AppShortcuts.consume(unrelated))
        assertEquals("com.example.UNRELATED", unrelated.action)
        assertNull(AppShortcuts.consume(Intent()))
        val history = AppShortcuts.intent(context, ShortcutAction.PUNCH)
            .addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertNull(AppShortcuts.consume(history))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun coldLaunchPunchUsesTheSameStoreAndOrdinaryLaunchDoesNotPunch() {
        // Plain ActivityController and Compose tests share Dispatchers.Main across Robolectric sandboxes.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            RecordStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
            File(context.filesDir, "attendance").deleteRecursively()
            val store = RecordStore.get(context)
            store.load()
            val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
            shadowOf(keyguard).setKeyguardLocked(false)
            shadowOf(keyguard).setIsDeviceLocked(false)
            val normal = Robolectric.buildActivity(MainActivity::class.java,
                Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)).create().start().resume()
            assertTrue(store.state.value.records.isEmpty())
            normal.pause().stop().destroy()
            val controller = Robolectric.buildActivity(MainActivity::class.java,
                AppShortcuts.intent(context, ShortcutAction.PUNCH)).create().start().resume()
            try {
                val deadline = System.nanoTime() + 10_000_000_000L
                while (store.state.value.records.isEmpty() && System.nanoTime() < deadline) {
                    shadowOf(android.os.Looper.getMainLooper()).idle()
                    Thread.sleep(10)
                }
                assertEquals(1, store.state.value.records.sumOf { it.punches.size })
                controller.get().onWindowFocusChanged(true)
                assertEquals(1, store.state.value.records.sumOf { it.punches.size })
            } finally {
                controller.pause().stop().destroy()
                RecordStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
            }
        } finally { Dispatchers.resetMain() }
    }
}
