package org.annay.topshe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The listening service. Three states, all switchable from the notification and the tile:
 *  - LISTENING: mic on, waiting for "Topshe"
 *  - PAUSED:    mic fully off, service waiting for you to resume
 *  - OFF:       killed. Mic off, model unloaded, nothing running.
 * It never turns itself on - only you can (app, tile or notification).
 */
class ListenerService : Service() {

    enum class State { OFF, STARTING, LISTENING, BUSY, PAUSED }

    companion object {
        const val ACTION_START = "org.annay.topshe.START"
        const val ACTION_PAUSE = "org.annay.topshe.PAUSE"
        const val ACTION_KILL = "org.annay.topshe.KILL"
        private const val CHANNEL = "topshe_listener"
        private const val NOTIF_ID = 7

        @Volatile var state = State.OFF
            private set

        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, ListenerService::class.java).setAction(action)
            if (action == ACTION_START && state == State.OFF) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var ear: Ear? = null
    private var inForeground = false
    private lateinit var prefs: Prefs
    private lateinit var speaker: Speaker
    private lateinit var actions: Actions
    private lateinit var music: Music

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        speaker = Speaker(this)
        actions = Actions(this, speaker)
        music = Music(this, speaker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_KILL -> kill()
            ACTION_PAUSE -> pause()
            else -> startListening()
        }
        return START_NOT_STICKY   // never restarts on its own
    }

    private fun setState(s: State, text: String) {
        state = s
        showNotification(text)
        runCatching { TileService.requestListeningState(this, ComponentName(this, TopsheTile::class.java)) }
    }

    private fun startListening() {
        if (loop?.isActive == true) return
        setState(State.STARTING, "Starting...")
        loop = scope.launch {
            try {
                val missing = Store.missing(this@ListenerService)
                if (missing.isNotEmpty()) {
                    ActivityLog.add(this@ListenerService, "Can't start. Missing: ${missing.joinToString()}")
                    kill(); return@launch
                }
                val chat = Store.chatModel(this@ListenerService).takeIf { it.exists() }?.absolutePath
                if (!Brain.loaded && !Brain.load(Store.model(this@ListenerService).absolutePath, chat)) {
                    ActivityLog.add(this@ListenerService, "Model failed to load")
                    kill(); return@launch
                }
                val e = Ear(this@ListenerService, prefs).also { ear = it }
                e.open()
                setState(State.LISTENING, "Listening for \"Topshe\"")
                ActivityLog.add(this@ListenerService, "Listening")
                while (isActive && e.waitForWake { isActive }) {
                    if (!handleWake(e)) { pauseFromInside(); break }
                    e.flush()
                    if (isActive) setState(State.LISTENING, "Listening for \"Topshe\"")
                }
            } catch (t: Throwable) {
                if (t !is kotlinx.coroutines.CancellationException) {
                    ActivityLog.add(this@ListenerService, "Error: ${t.message}")
                    kill()
                }
            } finally {
                ear?.release(); ear = null
            }
        }
    }

    /** Handles one "Topshe ..." request. Returns false if you asked it to stop. */
    private suspend fun handleWake(e: Ear): Boolean {
        speaker.beep()
        val u = e.capture()
        val score = e.voiceScore(u.all)
        if (score == null || score < prefs.voiceThreshold) {
            // Not your voice (or too little voice): stay silent, like v1
            ActivityLog.add(this, "Ignored a wake - voice score ${score?.let { "%.2f".format(it) } ?: "n/a"}")
            return true
        }
        val heard = e.transcribe(u.speech).replace(Regex("^(hey )?topshe\\s*", RegexOption.IGNORE_CASE), "").trim()
        ActivityLog.add(this, "Heard (voice %.2f): \"%s\"".format(score, heard))
        if (heard.isBlank()) { speaker.say("Sorry, I didn't catch that."); return true }

        setState(State.BUSY, "Working: $heard")
        // Music / media first: simple rules, instant, no model needed
        if (music.handle(heard)) { ActivityLog.add(this, "Media command"); return true }
        val cmd = Brain.command(heard)
        if (cmd == null) { speaker.say("Sorry, I got confused. Try again?"); return true }

        val asker = object : Actions.Asker {
          override suspend fun ask(question: String): String? {
            speaker.say(question)
            e.flush(150)
            val reply = e.capture(waitMs = 5000, endSilenceMs = 800, maxMs = 6000)
            if (reply.speech.isEmpty()) return null
            val s = e.voiceScore(reply.speech)
            // Short answers like "yes" may be too short to score; that's fine (you were just verified).
            // But if it clearly isn't your voice, refuse.
            if (s != null && s < prefs.voiceThreshold) {
                ActivityLog.add(this@ListenerService, "Ignored a reply - not your voice (%.2f)".format(s)); return null
            }
            return e.transcribe(reply.speech).also { ActivityLog.add(this@ListenerService, "Reply: \"$it\"") }
          }
        }
        return actions.run(cmd, heard, asker)
    }

    private fun pauseFromInside() {
        ear?.release(); ear = null
        Brain.unload()
        setState(State.PAUSED, "Paused - mic is off")
        ActivityLog.add(this, "Paused")
    }

    private fun pause() {
        if (state == State.OFF) { stopSelf(); return }
        val job = loop
        scope.launch {
            job?.cancelAndJoin()
            pauseFromInside()
        }
    }

    /** Kill switch: everything off, service gone. */
    private fun kill() {
        loop?.cancel()
        ear?.release(); ear = null
        Brain.unload()
        state = State.OFF
        runCatching { TileService.requestListeningState(this, ComponentName(this, TopsheTile::class.java)) }
        ActivityLog.add(this, "Killed - everything off")
        stopForeground(STOP_FOREGROUND_REMOVE)
        inForeground = false
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        ear?.release(); ear = null
        Brain.unload()
        speaker.shutdown()
        state = State.OFF
        super.onDestroy()
    }

    // ---------- notification ----------

    private fun actionIntent(action: String, code: Int) = PendingIntent.getService(this, code,
        Intent(this, ListenerService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun showNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Topshe listening", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val toggle = if (state == State.PAUSED)
            Notification.Action.Builder(null, "Resume", actionIntent(ACTION_START, 1)).build()
        else Notification.Action.Builder(null, "Pause", actionIntent(ACTION_PAUSE, 2)).build()
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Topshe")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)   // buttons work from the lock screen
            .addAction(toggle)
            .addAction(Notification.Action.Builder(null, "Kill", actionIntent(ACTION_KILL, 3)).build())
            .build()
        if (!inForeground) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            inForeground = true
        } else nm.notify(NOTIF_ID, n)
    }
}
