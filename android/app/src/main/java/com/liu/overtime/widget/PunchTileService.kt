package com.liu.overtime.widget

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.*

class PunchTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply { label = "记录打卡"; state = Tile.STATE_INACTIVE; updateTile() }
    }
    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { record() } else record()
    }
    private fun record() {
        scope.launch {
            recordFromOutside(applicationContext)
            withContext(Dispatchers.Main) { qsTile?.apply { state = Tile.STATE_INACTIVE; updateTile() } }
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
