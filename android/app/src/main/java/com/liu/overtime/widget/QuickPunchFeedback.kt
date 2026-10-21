package com.liu.overtime.widget

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import com.liu.overtime.data.PunchResult
import com.liu.overtime.data.RecordStore
import com.liu.overtime.domain.WorkZone
import java.time.format.DateTimeFormatter

internal data class QuickPunchOutcome(val message: String, val saved: Boolean) {
    companion object {
        private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")
        fun from(result: PunchResult) = QuickPunchOutcome(
            if (result.added) "打卡已保存 · ${result.instant.atZone(WorkZone).format(timeFormat)}"
            else RecordStore.DUPLICATE_NOTICE, result.added)

        fun failed(error: Exception) = QuickPunchOutcome(
            "打卡未保存：${error.message?.takeIf { it.isNotBlank() }?.take(60) ?: "请打开应用检查本地记录"}", false)
    }
}

/** One feedback path for every widget size and the Quick Settings tile. No Activity is opened. */
internal object QuickPunchFeedback {
    const val PreferenceKey = "quickPunchHaptics"
    private var lastToast: Toast? = null

    @MainThread
    fun show(context: Context, outcome: QuickPunchOutcome) {
        val app = context.applicationContext
        // Feedback failures are independent of one another and can never reclassify a saved punch.
        runCatching { lastToast?.cancel() }
        runCatching {
            lastToast = Toast.makeText(app, outcome.message, Toast.LENGTH_SHORT).also { it.show() }
        }.onFailure { Log.w("QuickPunchFeedback", "Unable to show result toast", it) }
        if (outcome.saved) runCatching { vibrateIfAllowed(app) }
            .onFailure { Log.w("QuickPunchFeedback", "Haptic feedback unavailable", it) }
    }

    @Suppress("DEPRECATION")
    private fun vibrateIfAllowed(context: Context) {
        if (!context.getSharedPreferences("preferences", 0).getBoolean(PreferenceKey, true)) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.VIBRATE) != PackageManager.PERMISSION_GRANTED) return
        if (Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return
        if (context.getSystemService(AudioManager::class.java)?.ringerMode == AudioManager.RINGER_MODE_SILENT) return
        val filter = context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter
        if (filter != null && filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN) return
        val vibrator = if (Build.VERSION.SDK_INT >= 31)
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
                ?: context.getSystemService(Vibrator::class.java)
            else context.getSystemService(Vibrator::class.java)
        if (vibrator == null || !vibrator.hasVibrator()) return
        val effect = if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            else VibrationEffect.createOneShot(18L, if (vibrator.hasAmplitudeControl()) 64 else VibrationEffect.DEFAULT_AMPLITUDE)
        // This is touch feedback. OEM/background policy may suppress it; do not mislabel it as an
        // alarm/notification, force a foreground window, or retry with another vibration category.
        if (Build.VERSION.SDK_INT >= 33) vibrator.vibrate(effect,
            VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_TOUCH).build())
        else vibrator.vibrate(effect, AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
    }
}
