package org.annay.topshe

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService

/**
 * "Notification access" lets Alfred see and control other apps' players (Spotify) directly, the way
 * a car or headphones do. That's what makes "play X" start the first result instead of just
 * showing Spotify's search. Alfred doesn't read your notifications; the service below is empty.
 */
class MediaListener : NotificationListenerService()

object MediaAccess {
    fun granted(ctx: Context): Boolean =
        Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
            ?.contains(ctx.packageName) == true

    /** Spotify's player, if Spotify is running (null without notification access). */
    fun controller(ctx: Context, pkg: String): MediaController? = runCatching {
        val msm = ctx.getSystemService(MediaSessionManager::class.java)
        msm.getActiveSessions(ComponentName(ctx, MediaListener::class.java)).firstOrNull { it.packageName == pkg }
    }.getOrNull()

    /** Asks the player to search and play the top result. */
    fun playFromSearch(c: MediaController, query: String): Boolean = runCatching {
        c.transportControls.playFromSearch(query, Bundle()); true
    }.getOrDefault(false)
}
