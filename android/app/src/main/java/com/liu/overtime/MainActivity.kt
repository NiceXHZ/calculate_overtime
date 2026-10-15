package com.liu.overtime

import android.os.Bundle
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.liu.overtime.ui.OvertimeApp

class MainActivity : ComponentActivity() {
    private var todayRequest by mutableIntStateOf(0)
    companion object { const val EXTRA_SHOW_TODAY = "show_today" }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        showTodayIfRequested(intent)
        setContent { OvertimeApp(todayRequest = todayRequest) }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        showTodayIfRequested(intent)
    }
    private fun showTodayIfRequested(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_SHOW_TODAY,false)) {
            todayRequest++
            intent.removeExtra(EXTRA_SHOW_TODAY)
        }
    }
}
