package com.pocketlm.app

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews

object IntentActions {
    /** Opens the chat with the message box focused. Used by the tile, widget and app shortcut. */
    const val ASK = "com.pocketlm.app.ASK"

    /** Opens a fresh chat with what was on the screen when the assistant gesture was used. */
    const val ASK_SCREEN = "com.pocketlm.app.ASK_SCREEN"
    const val EXTRA_SCREEN_TEXT = "screen_text"
    const val EXTRA_SCREENSHOT = "screenshot_path"

    /** Opens a saved chat, such as the one a scheduled prompt left. */
    const val OPEN_CHAT = "com.pocketlm.app.OPEN_CHAT"
    const val EXTRA_CHAT_ID = "chat_id"
}

private fun askIntent(context: Context) =
    Intent(context, MainActivity::class.java).setAction(IntentActions.ASK).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** Quick Settings tile: pull down the shade, tap "Ask PocketLM". */
class QuickAskTileService : TileService() {

    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val intent = askIntent(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            // Only this overload exists before Android 14.
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}

/** Home-screen bar that opens PocketLM ready to type. */
class AskWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val tap = PendingIntent.getActivity(
            context, 0, askIntent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        appWidgetIds.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_ask)
            views.setOnClickPendingIntent(R.id.widget_root, tap)
            manager.updateAppWidget(id, views)
        }
    }
}
