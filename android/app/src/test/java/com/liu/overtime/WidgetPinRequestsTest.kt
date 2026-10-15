package com.liu.overtime

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.liu.overtime.widget.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetPinRequestsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var now = 10_000L
    private val host = Host()
    private lateinit var requests: WidgetPinRequests

    private class Host : PinLauncher {
        var supported = true
        var accepted = true
        var ids = setOf(10, 20)
        var readThrows = false
        var requestThrows = false
        var calls = 0
        var callback: PendingIntent? = null
        override fun supported() = supported
        override fun ids(provider: ComponentName): Set<Int> {
            if (readThrows) throw IllegalStateException("launcher unavailable")
            return ids
        }
        override fun request(provider: ComponentName, callback: PendingIntent): Boolean {
            calls++
            this.callback = callback
            if (requestThrows) throw IllegalStateException("activity no longer foreground")
            return accepted
        }
    }

    @Before fun prepare() {
        context.getSharedPreferences("widget-pin-request", 0).edit().clear().commit()
        requests = WidgetPinRequests(context, host) { now }
    }

    private fun request() = requests.request(SmallAttendanceWidget::class.java, "2×2")

    @Test fun unsupportedRejectedAndThrowingLaunchersProduceImmediateHelp() {
        host.supported = false
        request()
        assertEquals(PinOutcome.UNAVAILABLE, requests.state.value.outcome)
        assertEquals(0, host.calls)
        host.supported = true
        host.accepted = false
        request()
        assertEquals(PinOutcome.UNAVAILABLE, requests.state.value.outcome)
        host.accepted = true
        host.requestThrows = true
        request()
        assertEquals(PinOutcome.UNAVAILABLE, requests.state.value.outcome)
        assertNull(requests.state.value.request)
    }

    @Test fun acceptedRequestIsUnconfirmedAfterGracePeriodAndOldWidgetsDoNotCount() {
        request()
        request()
        assertEquals(1, host.calls)
        assertEquals(PinOutcome.PENDING, requests.state.value.outcome)
        now += 5_999
        requests.reconcile(promptIfUnknown = true)
        assertEquals(PinOutcome.PENDING, requests.state.value.outcome)
        now++
        requests.reconcile(promptIfUnknown = true)
        assertEquals(PinOutcome.UNCONFIRMED, requests.state.value.outcome)
        assertEquals("2×2", requests.state.value.size)
        // No rejection is inferred: cancelling the launcher has the same observable result.
        assertNotNull(requests.state.value.request)
    }

    @Test fun addingNewIdConfirmsEvenWhenTotalCountDoesNotIncrease() {
        request()
        host.ids = setOf(20, 30)
        requests.reconcile()
        assertEquals(PinOutcome.CONFIRMED, requests.state.value.outcome)
        assertNull(requests.state.value.request)
    }

    @Test fun losingExistingWidgetNeverCountsAsSuccessAndReadFailureStaysUnknown() {
        request()
        host.ids = setOf(20)
        requests.reconcile()
        assertEquals(PinOutcome.PENDING, requests.state.value.outcome)
        host.readThrows = true
        now += 6_000
        requests.reconcile(promptIfUnknown = true)
        assertEquals(PinOutcome.UNCONFIRMED, requests.state.value.outcome)
    }

    @Test fun callbackIsExplicitImmutableAndTokenIdentifiesTheAttempt() {
        request()
        val callback = requireNotNull(host.callback)
        assertTrue(callback.isImmutable)
        val intent = shadowOf(callback).savedIntent
        assertEquals(ComponentName(context, WidgetPinSuccessReceiver::class.java), intent.component)
        assertEquals(WidgetPinRequests.ACTION_SUCCESS, intent.action)
        assertEquals(requests.state.value.request!!.token, intent.data!!.lastPathSegment)
        requests.confirm("stale-token")
        assertEquals(PinOutcome.PENDING, requests.state.value.outcome)
        now += 6_000
        requests.reconcile(promptIfUnknown = true)
        requests.confirm(intent.data!!.lastPathSegment!!)
        assertEquals(PinOutcome.CONFIRMED, requests.state.value.outcome)
    }

    @Test fun lateCallbackCannotCompleteNewAttemptOrReopenDismissedHelp() {
        request()
        val oldToken = requests.state.value.request!!.token
        requests.dismiss()
        request()
        val newToken = requests.state.value.request!!.token
        assertNotEquals(oldToken, newToken)
        requests.confirm(oldToken)
        assertEquals(newToken, requests.state.value.request!!.token)
        requests.dismiss()
        requests.confirm(newToken)
        assertEquals(PinOutcome.IDLE, requests.state.value.outcome)
    }

    @Test fun processRecreationPreservesBaselineAndRecognizesAdditionWhileAway() {
        request()
        val token = requests.state.value.request!!.token
        val restored = WidgetPinRequests(context, host) { now }
        assertEquals(token, restored.state.value.request!!.token)
        assertEquals(setOf(10, 20), restored.state.value.request!!.previousIds)
        host.ids = setOf(10, 20, 50)
        restored.reconcile()
        assertEquals(PinOutcome.CONFIRMED, restored.state.value.outcome)
        assertEquals(PinOutcome.IDLE, WidgetPinRequests(context, host) { now }.state.value.outcome)
    }

    @Test fun rebootClockResetDoesNotLeaveRequestWaitingForever() {
        request()
        now = 1
        requests.reconcile(promptIfUnknown = true)
        assertEquals(PinOutcome.UNCONFIRMED, requests.state.value.outcome)
    }

    @Test fun corruptPreferencesDoNotCrashOrShowUnrelatedProvider() {
        val prefs = context.getSharedPreferences("widget-pin-request", 0)
        prefs.edit().putString("token", "old").putString("provider", "other.package/.Widget").commit()
        assertEquals(PinOutcome.IDLE, WidgetPinRequests(context, host) { now }.state.value.outcome)
        prefs.edit().putString("provider", ComponentName(context, SmallAttendanceWidget::class.java).flattenToString())
            .putStringSet("ids", setOf("invalid")).commit()
        assertEquals(PinOutcome.IDLE, WidgetPinRequests(context, host) { now }.state.value.outcome)
    }

    @Test fun frameworkCallbackTargetsPrivateManifestReceiverAndConfirmsMatchingRequest() {
        shadowOf(AppWidgetManager.getInstance(context)).setRequestPinAppWidgetSupported(true)
        val live = WidgetPinRequests.get(context)
        live.dismiss()
        live.request(SmallAttendanceWidget::class.java, "2×2")
        shadowOf(android.os.Looper.getMainLooper()).idle()
        // Robolectric records explicit broadcasts but does not instantiate a manifest receiver without filters.
        val sent = shadowOf(context as android.app.Application).broadcastIntents.last {
            it.component == ComponentName(context, WidgetPinSuccessReceiver::class.java)
        }
        WidgetPinSuccessReceiver().onReceive(context, sent)
        assertEquals(PinOutcome.CONFIRMED, live.state.value.outcome)
        val receiver = context.packageManager.getReceiverInfo(ComponentName(context, WidgetPinSuccessReceiver::class.java), 0)
        assertFalse(receiver.exported)
        live.dismiss()
    }
}
