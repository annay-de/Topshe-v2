package org.annay.topshe

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Big downloads (the brains, the voice models) as a foreground job with its own notification:
 * Android keeps it running with the screen off, while you use other apps, and through Realme's
 * battery saver. Tapping buttons in Alfred doesn't touch it, a second tap doesn't start a second
 * copy, and if the network drops it pauses and later resumes where it stopped (not from zero).
 */
class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        private const val BRAIN = "topshe-brain-download"
        private const val VOICE = "topshe-voice-download"
        private const val CHANNEL = "topshe_downloads"

        /** Newest brain (or [target], or going back one with [rollback]). Waits for Wi-Fi. */
        fun brain(ctx: Context, target: Int? = null, rollback: Boolean = false, anyNetwork: Boolean = false) {
            Prefs(ctx).brainDl = if (anyNetwork) "Brain download queued..." else "Brain download queued (starts on Wi-Fi)..."
            enqueue(ctx, BRAIN, workDataOf("kind" to "brain", "target" to (target ?: -1), "rollback" to rollback),
                if (anyNetwork) NetworkType.CONNECTED else NetworkType.UNMETERED, replace = rollback || anyNetwork)
        }

        /** Wake word, voice check and speech models (about 330 MB). */
        fun voice(ctx: Context) {
            Prefs(ctx).voiceDl = "Voice model download queued..."
            enqueue(ctx, VOICE, workDataOf("kind" to "voice"), NetworkType.CONNECTED, replace = false)
        }

        private fun enqueue(ctx: Context, name: String, data: androidx.work.Data, net: NetworkType, replace: Boolean) {
            val req = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(data)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(net).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            // KEEP: tapping again while it runs does nothing (no restart from zero)
            WorkManager.getInstance(ctx).enqueueUniqueWork(name, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, req)
        }
    }

    private val isBrain = inputData.getString("kind") == "brain"
    private val notifId = if (isBrain) 51 else 52
    private var lastShown = 0L

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        runCatching { setForeground(info("Starting download...")) }   // keeps the process alive
        return try {
            val msg = withContext(Dispatchers.IO) {
                if (isBrain) {
                    val target = inputData.getInt("target", -1)
                    if (inputData.getBoolean("rollback", false)) Sync.rollback(ctx) { report(it) }
                    else if (target >= 0) Sync.updateBrain(ctx, target) { report(it) }
                    else Sync.updateBrains(ctx) { report(it) }
                } else {
                    VoiceModels.downloadAll(ctx) { report(it) }
                    "Voice models ready."
                }
            }
            report(msg, force = true)
            ActivityLog.add(ctx, msg)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 10) {
                report("Download paused (${e.message}). It will continue by itself in a minute or so.", force = true)
                Result.retry()
            } else {
                report("Download failed: ${e.message}. Tap the button to try again.", force = true)
                ActivityLog.add(ctx, "Download failed: ${e.message}")
                Result.failure()
            }
        }
    }

    /** Status for the app screen (read every second while it's open) and the notification. */
    private fun report(msg: String, force: Boolean = false) {
        val p = Prefs(applicationContext)
        if (isBrain) p.brainDl = msg else p.voiceDl = msg
        val now = SystemClock.elapsedRealtime()
        if (force || now - lastShown > 2000) {
            lastShown = now
            android.util.Log.i("Alfred", msg)
            runCatching { setForegroundAsync(info(msg)) }
        }
    }

    private fun info(text: String): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Alfred downloads", NotificationManager.IMPORTANCE_LOW))
        val m = Regex("(\\d+) / (\\d+) MB").find(text)
        val pct = m?.let { (it.groupValues[1].toLong() * 100 / it.groupValues[2].toLong().coerceAtLeast(1)).toInt() }
        val n = android.app.Notification.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (isBrain) "Alfred: downloading the brain" else "Alfred: downloading voice models")
            .setContentText(text)
            .setProgress(100, pct ?: 0, pct == null)       // progress bar in the notification too
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(notifId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
               else ForegroundInfo(notifId, n)
    }
}
