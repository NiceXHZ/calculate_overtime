package com.liu.overtime.widget

import android.app.Application
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class PinOutcome { IDLE, PENDING, CONFIRMED, UNCONFIRMED, UNAVAILABLE }
data class PendingPin(val token: String, val provider: ComponentName, val previousIds: Set<Int>, val startedAt: Long, val size: String)
data class PinUiState(val outcome: PinOutcome = PinOutcome.IDLE, val request: PendingPin? = null, val size: String = "")

internal interface PinLauncher {
    fun supported(): Boolean
    fun ids(provider: ComponentName): Set<Int>
    fun request(provider: ComponentName, callback: PendingIntent): Boolean
}

private class AndroidPinLauncher(context: Context) : PinLauncher {
    private val manager = AppWidgetManager.getInstance(context)
    override fun supported() = manager.isRequestPinAppWidgetSupported
    override fun ids(provider: ComponentName) = manager.getAppWidgetIds(provider).toSet()
    override fun request(provider: ComponentName, callback: PendingIntent) = manager.requestPinAppWidget(provider, null, callback)
}

/** Tracks only explicit add attempts. No permanent polling, and a timeout is never a failure verdict. */
class WidgetPinRequests internal constructor(
    context: Context,
    private val launcher: PinLauncher = AndroidPinLauncher(context.applicationContext),
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
) {
    private val application = context.applicationContext as Application
    private val prefs = application.getSharedPreferences("widget-pin-request", Context.MODE_PRIVATE)
    private val restored = runCatching {
        val token = prefs.getString("token", null) ?: return@runCatching null
        val provider = ComponentName.unflattenFromString(prefs.getString("provider", "").orEmpty()) ?: return@runCatching null
        if (provider.packageName != application.packageName) return@runCatching null
        PendingPin(token, provider, prefs.getStringSet("ids", emptySet()).orEmpty().map { it.toInt() }.toSet(),
            prefs.getLong("started", 0), prefs.getString("size", "").orEmpty())
    }.getOrNull()
    private val mutable = MutableStateFlow(restored?.let { PinUiState(PinOutcome.PENDING, it, it.size) } ?: PinUiState())
    val state = mutable.asStateFlow()

    @Synchronized fun request(provider: Class<out AttendanceWidget>, size: String) {
        if (mutable.value.outcome == PinOutcome.PENDING) return
        val component = ComponentName(application, provider)
        var started = false
        try {
            if (!launcher.supported()) { unavailable(size); return }
            val pending = PendingPin(UUID.randomUUID().toString(), component, launcher.ids(component), elapsed(), size)
            prefs.edit {
                clear()
                putString("token", pending.token)
                putString("provider", component.flattenToString())
                putStringSet("ids", pending.previousIds.map { it.toString() }.toSet())
                putLong("started", pending.startedAt)
                putString("size", size)
            }
            mutable.value = PinUiState(PinOutcome.PENDING, pending, size)
            started = true
            val intent = Intent(application, WidgetPinSuccessReceiver::class.java).setAction(ACTION_SUCCESS)
                .setData("overtime-widget-pin://success/${pending.token}".toUri())
            val callback = PendingIntent.getBroadcast(application, 0, intent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
            // The fixed URI identifies the attempt; no mutable callback extras are needed.
            if (!launcher.request(component, callback) && mutable.value.outcome == PinOutcome.PENDING) {
                callback.cancel()
                unavailable(size)
            }
        } catch (_: RuntimeException) {
            if (!started || mutable.value.outcome != PinOutcome.CONFIRMED) unavailable(size)
        }
    }

    @Synchronized private fun unavailable(size: String) {
        prefs.edit { clear() }
        mutable.value = PinUiState(PinOutcome.UNAVAILABLE, size = size)
    }

    @Synchronized internal fun confirm(token: String) {
        val pending = mutable.value.request ?: return
        if (pending.token != token) return // A late callback must not complete a newer attempt.
        prefs.edit { clear() }
        mutable.value = PinUiState(PinOutcome.CONFIRMED, size = pending.size)
    }

    @Synchronized fun remainingWaitMillis(): Long {
        val pending = mutable.value.request ?: return 0
        val age = elapsed() - pending.startedAt
        return if (age < 0) 0 else (CONFIRMATION_WAIT_MILLIS - age).coerceAtLeast(0)
    }

    @Synchronized fun reconcile(promptIfUnknown: Boolean = false) {
        val pending = mutable.value.request ?: return
        val ids = runCatching { launcher.ids(pending.provider) }.getOrNull()
        if (ids != null && (ids - pending.previousIds).isNotEmpty()) { confirm(pending.token); return }
        if (promptIfUnknown && remainingWaitMillis() == 0L) {
            mutable.value = PinUiState(PinOutcome.UNCONFIRMED, pending, pending.size)
        }
    }

    @Synchronized fun dismiss() {
        prefs.edit { clear() }
        mutable.value = PinUiState()
    }

    companion object {
        const val CONFIRMATION_WAIT_MILLIS = 6_000L
        const val ACTION_SUCCESS = "com.liu.overtime.WIDGET_PIN_SUCCESS"
        @Volatile private var instance: WidgetPinRequests? = null
        fun get(context: Context): WidgetPinRequests = instance ?: synchronized(this) {
            instance ?: WidgetPinRequests(context.applicationContext).also { instance = it }
        }
    }
}

class WidgetPinSuccessReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WidgetPinRequests.ACTION_SUCCESS) return
        val data = intent.data ?: return
        if (data.scheme != "overtime-widget-pin" || data.host != "success") return
        WidgetPinRequests.get(context).confirm(data.lastPathSegment ?: return)
    }
}
