package org.annay.topshe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Shows the reminder notification at the set time. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: "Reminder"
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("topshe_reminders", "Reminders", NotificationManager.IMPORTANCE_HIGH))
        nm.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
            Notification.Builder(ctx, "topshe_reminders")
                .setSmallIcon(R.drawable.ic_stat_topshe)
                .setContentTitle("Topshe reminder")
                .setContentText(text.replaceFirstChar { it.uppercase() })
                .setAutoCancel(true)
                .build())
    }
}
