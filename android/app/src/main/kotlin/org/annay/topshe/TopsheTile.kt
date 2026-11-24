package org.annay.topshe

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** The on/off button in your quick-settings panel (works from the lock screen too). */
class TopsheTile : TileService() {

    override fun onStartListening() {
        val tile = qsTile ?: return
        val s = ListenerService.state
        tile.state = if (s == ListenerService.State.LISTENING || s == ListenerService.State.BUSY || s == ListenerService.State.HEARING ||
            s == ListenerService.State.STARTING) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Topshe"
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = when (s) {
            ListenerService.State.OFF -> "Off"
            ListenerService.State.PAUSED -> "Paused"
            else -> "Listening"
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        when (ListenerService.state) {
            ListenerService.State.OFF -> {
                // Android only lets an app turn on the mic from the foreground, so we open the app briefly
                val i = Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_AUTOSTART, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (Build.VERSION.SDK_INT >= 34) {
                    startActivityAndCollapse(PendingIntent.getActivity(this, 0, i,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                } else {
                    @Suppress("DEPRECATION") startActivityAndCollapse(i)
                }
            }
            ListenerService.State.PAUSED -> ListenerService.send(this, ListenerService.ACTION_START)
            else -> ListenerService.send(this, ListenerService.ACTION_PAUSE)
        }
        onStartListening()
    }
}
