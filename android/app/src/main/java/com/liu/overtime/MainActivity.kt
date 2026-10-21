package com.liu.overtime

import android.os.Bundle
import android.content.Intent
import android.app.KeyguardManager
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.liu.overtime.ui.OvertimeApp
import com.liu.overtime.ui.MainViewModel
import com.liu.overtime.shortcuts.*

class MainActivity : ComponentActivity() {
    private var todayRequest by mutableIntStateOf(0)
    private var shortcutRequest by mutableStateOf<ShortcutRequest?>(null)
    private var pendingShortcut: ShortcutAction? = null
    private val vm by viewModels<MainViewModel>()
    companion object {
        const val EXTRA_SHOW_TODAY = "show_today"
        private const val PENDING_SHORTCUT = "pending_shortcut"
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingShortcut = if (savedInstanceState == null) AppShortcuts.consume(intent)
            else ShortcutAction.fromIntentAction(savedInstanceState.getString(PENDING_SHORTCUT))
        if (savedInstanceState == null && pendingShortcut == null) showTodayIfRequested(intent)
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) dispatchShortcutIfReady()
        })
        AppShortcuts.publish(this)
        setContent { OvertimeApp(vm, todayRequest = todayRequest, shortcutRequest = shortcutRequest) }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingShortcut = AppShortcuts.consume(intent)
        if (pendingShortcut == null) showTodayIfRequested(intent)
        dispatchShortcutIfReady()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) dispatchShortcutIfReady()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        pendingShortcut?.let { outState.putString(PENDING_SHORTCUT, it.intentAction) }
        super.onSaveInstanceState(outState)
    }
    private fun dispatchShortcutIfReady() {
        val action = pendingShortcut ?: return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard.isKeyguardLocked || keyguard.isDeviceLocked) return
        // Clear before invoking anything that can re-enter or trigger recreation.
        pendingShortcut = null
        shortcutRequest = ShortcutRequest(action, (shortcutRequest?.sequence ?: 0) + 1)
        if (action == ShortcutAction.PUNCH) vm.punchFromShortcut()
    }
    private fun showTodayIfRequested(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_SHOW_TODAY,false)) {
            todayRequest++
            intent.removeExtra(EXTRA_SHOW_TODAY)
        }
    }
}
