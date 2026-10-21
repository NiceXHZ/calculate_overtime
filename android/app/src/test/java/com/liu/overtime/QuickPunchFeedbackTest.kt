package com.liu.overtime

import android.app.Application
import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.VibrationAttributes
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import com.liu.overtime.data.PunchResult
import com.liu.overtime.widget.QuickPunchFeedback
import com.liu.overtime.widget.QuickPunchOutcome
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import org.robolectric.shadows.ShadowVibrator
import java.io.IOException
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class QuickPunchFeedbackTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val vibrator: Vibrator get() = if (Build.VERSION.SDK_INT >= 31)
        context.getSystemService(VibratorManager::class.java).defaultVibrator
        else context.getSystemService(Vibrator::class.java)
    private val success = QuickPunchOutcome.from(PunchResult(Instant.parse("2026-10-21T10:30:25Z"), true))

    @Before fun allowTouchFeedback() {
        ShadowToast.reset()
        ShadowVibrator.reset()
        shadowOf(context).grantPermissions(Manifest.permission.VIBRATE)
        context.getSharedPreferences("preferences", 0).edit().clear().commit()
        Settings.System.putInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1)
        context.getSystemService(AudioManager::class.java).ringerMode = AudioManager.RINGER_MODE_NORMAL
        val notifications = context.getSystemService(NotificationManager::class.java)
        shadowOf(notifications).setNotificationPolicyAccessGranted(true)
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        shadowOf(vibrator).setHasVibrator(true)
    }

    @Test fun savedPunchShowsItsActualTimeAndRequestsOneTouchEffect() {
        assertEquals("打卡已保存 · 18:30:25", success.message)
        QuickPunchFeedback.show(context, success)
        assertEquals(success.message, ShadowToast.getTextOfLatestToast())
        assertTrue(shadowOf(vibrator).isVibrating)
        if (Build.VERSION.SDK_INT >= 33) {
            val attributes = shadowOf(vibrator).vibrationAttributesFromLastVibration as VibrationAttributes
            assertEquals(VibrationAttributes.USAGE_TOUCH, attributes.usage)
        } else {
            assertEquals(18L, shadowOf(vibrator).milliseconds)
            assertEquals(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
                requireNotNull(shadowOf(vibrator).audioAttributesFromLastVibration).usage)
        }
    }

    @Test fun duplicateAndFailureOnlyShowMessagesAndReplaceThePreviousToast() {
        val duplicate = QuickPunchOutcome.from(PunchResult(Instant.now(), false))
        QuickPunchFeedback.show(context, duplicate)
        val previous = ShadowToast.getLatestToast()
        assertFalse(shadowOf(vibrator).isVibrating)
        val failed = QuickPunchOutcome.failed(IOException("存储空间不足"))
        QuickPunchFeedback.show(context, failed)
        assertTrue(shadowOf(previous).isCancelled)
        assertEquals("打卡未保存：存储空间不足", ShadowToast.getTextOfLatestToast())
        assertFalse(shadowOf(vibrator).isVibrating)
        assertFalse(QuickPunchOutcome.failed(IOException()).message.contains("null"))
    }

    @Test fun appAndSystemHapticSwitchesIndependentlySuppressOnlyVibration() {
        context.getSharedPreferences("preferences", 0).edit().putBoolean(QuickPunchFeedback.PreferenceKey, false).commit()
        QuickPunchFeedback.show(context, success)
        assertFalse(shadowOf(vibrator).isVibrating)
        context.getSharedPreferences("preferences", 0).edit().putBoolean(QuickPunchFeedback.PreferenceKey, true).commit()
        Settings.System.putInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 0)
        QuickPunchFeedback.show(context, success)
        assertFalse(shadowOf(vibrator).isVibrating)
        assertEquals(success.message, ShadowToast.getTextOfLatestToast())
    }

    @Test fun silentModeAndDoNotDisturbSuppressOnlyVibration() {
        context.getSystemService(AudioManager::class.java).ringerMode = AudioManager.RINGER_MODE_SILENT
        QuickPunchFeedback.show(context, success)
        assertFalse(shadowOf(vibrator).isVibrating)
        context.getSystemService(AudioManager::class.java).ringerMode = AudioManager.RINGER_MODE_NORMAL
        context.getSystemService(NotificationManager::class.java)
            .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        QuickPunchFeedback.show(context, success)
        assertFalse(shadowOf(vibrator).isVibrating)
        assertEquals(success.message, ShadowToast.getTextOfLatestToast())
    }

    @Test fun missingMotorOrRejectedServiceNeverTurnsSuccessIntoFailure() {
        shadowOf(vibrator).setHasVibrator(false)
        QuickPunchFeedback.show(context, success)
        assertFalse(shadowOf(vibrator).isVibrating)
        val rejected = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? {
                if (name == VIBRATOR_SERVICE || name == VIBRATOR_MANAGER_SERVICE) throw SecurityException("OEM denied haptics")
                return super.getSystemService(name)
            }
        }
        QuickPunchFeedback.show(rejected, success)
        assertEquals(success.message, ShadowToast.getTextOfLatestToast())
    }

    @Test fun deniedVibrationPermissionKeepsTheSavedMessage() {
        shadowOf(context).denyPermissions(Manifest.permission.VIBRATE)
        QuickPunchFeedback.show(context, success)
        assertFalse(shadowOf(vibrator).isVibrating)
        assertEquals(success.message, ShadowToast.getTextOfLatestToast())
    }
}
