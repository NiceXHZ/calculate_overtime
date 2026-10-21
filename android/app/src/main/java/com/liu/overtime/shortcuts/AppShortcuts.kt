package com.liu.overtime.shortcuts

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.util.Log
import com.liu.overtime.MainActivity
import com.liu.overtime.R

internal enum class ShortcutAction(val intentAction: String, val id: String, val label: Int, val icon: Int) {
    PUNCH("com.liu.overtime.shortcut.PUNCH", "punch-now", R.string.shortcut_punch, R.drawable.ic_shortcut_punch),
    ADD("com.liu.overtime.shortcut.ADD", "add-punch", R.string.shortcut_add, R.drawable.ic_shortcut_add),
    RECORDS("com.liu.overtime.shortcut.RECORDS", "view-records", R.string.shortcut_records, R.drawable.ic_shortcut_records);

    companion object {
        fun fromIntentAction(value: String?): ShortcutAction? = entries.firstOrNull { it.intentAction == value }
    }
}

internal data class ShortcutRequest(val action: ShortcutAction, val sequence: Int)

internal object AppShortcuts {
    fun publish(context: Context) {
        runCatching {
            val manager = context.getSystemService(ShortcutManager::class.java) ?: return
            val actions = ShortcutAction.entries.take(manager.maxShortcutCountPerActivity.coerceAtLeast(0))
            val existing = manager.dynamicShortcuts
            if (existing.size == actions.size && actions.withIndex().all { (rank, action) ->
                    existing.any { it.id == action.id && it.rank == rank &&
                        it.shortLabel?.toString() == context.getString(action.label) &&
                        it.intent?.filterEquals(intent(context, action)) == true &&
                        it.intent?.flags == intent(context, action).flags }
                }) return
            manager.dynamicShortcuts = actions.mapIndexed { rank, action ->
                ShortcutInfo.Builder(context, action.id)
                    .setActivity(ComponentName(context, MainActivity::class.java))
                    .setShortLabel(context.getString(action.label))
                    .setLongLabel(context.getString(action.label))
                    .setIcon(Icon.createWithResource(context, action.icon))
                    .setRank(rank)
                    .setIntent(intent(context, action))
                    .build()
            }
        }.onFailure { Log.w("AppShortcuts", "Launcher shortcuts unavailable", it) }
    }

    fun intent(context: Context, action: ShortcutAction) = Intent(context, MainActivity::class.java)
        .setAction(action.intentAction)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** Consume only supported actions. Never reuse a launch command after Activity recreation. */
    fun consume(intent: Intent): ShortcutAction? {
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
        return ShortcutAction.fromIntentAction(intent.action)?.also { intent.action = Intent.ACTION_MAIN }
    }
}
