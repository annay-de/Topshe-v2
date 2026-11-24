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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The listening service. Three states, all switchable from the notification and the tile:
 *  - LISTENING: mic on, waiting for "Topshe"
 *  - PAUSED:    mic fully off, service waiting for you to resume
 *  - OFF:       killed. Mic off, model unloaded, nothing running.
 * It never turns itself on - only you can (app, tile or notification).
 */
private val CORRECTION = Regex("^(no|nope|nahi|wrong|not that|that's wrong|that is wrong)(?:[,.! ]+((i meant|i said|i want|i wanted)[,: ]*)?(.*))?[.!]?$",
    RegexOption.IGNORE_CASE)
// The same in Bengali: "না, আমি বলেছিলাম ...", "ভুল, মানে ...", "না না ..."
private val CORRECTION_BN = Regex("^(না না|না|নাহ|ভুল|উঁহু|ওটা না)(?:[,।! ]+((আমি (বলেছি|বললাম|বলেছিলাম|চেয়েছিলাম|চাইছিলাম)|মানে)[,: ]*)?(.*))?[।!]?$")

class ListenerService : Service() {

    enum class State { OFF, STARTING, LISTENING, HEARING, BUSY, PAUSED }

    companion object {
        const val ACTION_START = "org.annay.topshe.START"
        const val ACTION_PAUSE = "org.annay.topshe.PAUSE"
        const val ACTION_KILL = "org.annay.topshe.KILL"
        const val ACTION_RELOAD = "org.annay.topshe.RELOAD"   // a new brain was installed
        const val ACTION_TALK = "org.annay.topshe.TALK"       // 🎙 button: listen to me now, no wake word
        private const val CHANNEL = "topshe_listener"
        private const val NOTIF_ID = 7

        @Volatile var state = State.OFF
            private set
        /** What just happened, for the screen ("Heard Topshe, but it didn't sound like you"). */
        @Volatile var lastEvent = ""
            private set
        @Volatile var lastEventAt = 0L
            private set
        @Volatile private var talkNow = false

        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, ListenerService::class.java).setAction(action)
            if ((action == ACTION_START || action == ACTION_TALK) && state == State.OFF)
                ctx.startForegroundService(i) else ctx.startService(i)
        }

        private fun event(text: String) { lastEvent = text; lastEventAt = System.currentTimeMillis() }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var ear: Ear? = null
    private var inForeground = false
    private lateinit var prefs: Prefs
    private lateinit var speaker: Speaker
    private lateinit var actions: Actions
    private lateinit var music: Music
    private lateinit var memory: Memory

    /** The last thing Topshe did, so "no, I meant ..." can correct it. */
    private data class Last(val heard: String, val action: JSONObject, val at: Long)
    private var last: Last? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        speaker = Speaker(this)
        memory = Memory(this)
        music = Music(this, speaker)
        actions = Actions(this, speaker, memory, music)
        speaker.echo = { ChatLog.add(this, false, it, "voice") }      // replies show in the chat screen
        Sync.schedule(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_KILL -> kill()
            ACTION_PAUSE -> pause()
            ACTION_RELOAD -> reload()
            ACTION_TALK -> { talkNow = true; startListening() }
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
                    event("Can't listen yet - missing: ${missing.joinToString()}")
                    ActivityLog.add(this@ListenerService, "Can't start. Missing: ${missing.joinToString()}")
                    kill(); return@launch
                }
                if (!Brain.load(this@ListenerService)) {
                    event("The brain didn't load - open Settings and Sync")
                    ActivityLog.add(this@ListenerService, "Model failed to load")
                    kill(); return@launch
                }
                val e = Ear(this@ListenerService, prefs).also { ear = it }
                e.open()
                setState(State.LISTENING, "Listening for \"Topshe\"")
                ActivityLog.add(this@ListenerService, "Listening (brain ${Brain.loadedName}, ${memory.facts().size} facts)")
                // Get the brain's fixed instructions ready while you're not talking yet
                scope.launch { runCatching { Brain.warmUp(memory.facts()) }; logSpeed() }
                while (isActive) {
                    val woke = e.waitForWake { isActive && !talkNow }
                    val pushed = talkNow
                    talkNow = false
                    if (!woke && !pushed) break
                    if (!handleWake(e, pushed)) { pauseFromInside(); break }
                    e.flush()
                    if (isActive) setState(State.LISTENING, "Listening for \"Topshe\"")
                }
            } catch (t: Throwable) {
                if (t !is kotlinx.coroutines.CancellationException) {
                    event("Stopped because of an error: ${t.message}")
                    ActivityLog.add(this@ListenerService, "Error: ${t.message}")
                    kill()
                }
            } finally {
                ear?.release(); ear = null
            }
        }
    }

    /** Handles one "Topshe ..." request. Returns false if you asked it to stop. */
    private suspend fun handleWake(e: Ear, pushed: Boolean = false): Boolean {
        speaker.beep()
        setState(State.HEARING, "Go ahead, I'm listening...")
        event(if (pushed) "Listening - speak now" else "Heard \"Topshe\" - speak now")
        val u = if (pushed) e.capture(waitMs = 6000) else e.capture()
        if (pushed && u.speech.isEmpty()) { event("Didn't hear anything - tap 🎙 and speak"); return true }
        if (!pushed) {                     // 🎙 tapped on your phone = it's you; a wake word must be your voice
            val score = e.voiceScore(u.all)
            if (score == null || score < prefs.voiceThreshold) {
                event("Heard \"Topshe\", but it didn't sound like you (" +
                    (score?.let { "%.2f".format(it) } ?: "too short") + "). Re-record your voice or lower the voice check in Settings.")
                ActivityLog.add(this, "Ignored a wake - voice score ${score?.let { "%.2f".format(it) } ?: "n/a"}")
                return true
            }
        }
        val score = 1f
        val h = e.hear(u.speech).withoutWakeWord()
        val dual = Brain.dualHearing
        val heard = h.forBrain(dual)                   // what the brain sees (and what it learns from)
        ActivityLog.add(this, "Heard (voice %.2f): \"%s\"".format(score, h.shown()))
        if (if (dual) h.isBlank() else h.en.isBlank()) { event("Didn't catch the words"); speaker.say("Sorry, I didn't catch that."); return true }
        setState(State.BUSY, "Working: ${h.shown()}")
        event("Heard: \"${h.shown()}\"")
        ChatLog.add(this, true, if (dual && h.bn.isNotBlank()) "${h.en}\n${h.bn}" else h.en, "voice")

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
                val r = e.hear(reply.speech)
                ActivityLog.add(this@ListenerService, "Reply: \"${r.shown()}\"")
                return r.forBrain(dual)
            }
        }

        // 1) "No, I meant ..." within 2 minutes corrects the last action (and is remembered)
        val cEn = CORRECTION.find(h.en)
        val cBn = if (dual) CORRECTION_BN.find(h.bn) else null
        val prev = last
        if ((cEn != null || cBn != null) && prev != null && System.currentTimeMillis() - prev.at < 120_000) {
            val restEn = cEn?.groupValues?.get(4)?.trim()
            val restBn = cBn?.groupValues?.get(4)?.trim()
            val said = if (dual) (if (cBn != null) restBn else restEn).orEmpty() else restEn.orEmpty()
            val fixed = if (said.isBlank()) asker.ask("Okay, what did you mean?") ?: ""
                        else if (dual) Hearing(restEn ?: h.en, restBn ?: h.bn).forBrain(true) else said
            if (fixed.isBlank()) return true
            val newCmd = (if (said.isNotBlank()) QuickRules.match(restEn.orEmpty()) ?: QuickRules.match(restBn.orEmpty()) else QuickRules.match(fixed))
                ?: Brain.command(fixed, memory.facts()).also { logSpeed() }
                ?: run { speaker.say("Sorry, I still didn't get it."); return true }
            memory.addCorrection(prev.heard, newCmd)
            memory.log(prev.heard, prev.action, "corrected", newCmd)
            ActivityLog.add(this, "Learned: \"${prev.heard}\" -> $newCmd")
            val r = actions.run(newCmd, fixed, asker)
            last = Last(prev.heard, newCmd, System.currentTimeMillis())
            return r.keepListening
        }

        // 2) Something you corrected before? Do what you meant last time.
        val learned = memory.correctionFor(heard)?.also { ActivityLog.add(this, "Using your earlier correction") }

        // 3) Everyday commands (music, timers, clear alarms, apps, calls, Bengali basics): instant, no brain
        val quick = if (learned != null) null
                    else (if (dual) QuickRules.match(h.bn) else null) ?: QuickRules.match(h.en)
        if (learned == null && quick == null) {
            QuickRules.reply(h.en)?.let { say ->
                speaker.say(say)
                memory.log(heard, JSONObject().put("action", "chat").put("query", h.en), "chat")
                return true
            }
        }

        // 4) The brain (only when nothing above matched)
        val cmd = learned ?: quick ?: Brain.command(heard, memory.facts()).also { logSpeed() }
        if (cmd == null) { speaker.say("Sorry, I got confused. Try again?"); memory.log(heard, null, "failed"); return true }

        val r = actions.run(cmd, h.en.ifBlank { h.bn }, asker)
        memory.log(heard, cmd, r.outcome)
        last = if (r.outcome == "ok" || r.outcome == "confirmed") Last(heard, cmd, System.currentTimeMillis()) else null
        return r.keepListening
    }

    /** The brain's timings, so slow answers show where the time went (Settings > Activity). */
    private fun logSpeed() {
        if (Brain.lastStats.isNotBlank()) ActivityLog.add(this, Brain.lastStats)
    }

    /** Brain swapped by Sync: reload it (keeps listening if it was). */
    private fun reload() {
        val wasOn = loop?.isActive == true
        val job = loop
        Brain.stopNow()
        scope.launch {
            job?.cancelAndJoin()
            Brain.unload()
            if (wasOn) startListening()
        }
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
        Brain.stopNow()                        // don't wait for a half-finished answer
        scope.launch {
            job?.cancelAndJoin()
            pauseFromInside()
        }
    }

    /** Kill switch: everything off, service gone. */
    private fun kill() {
        val job = loop
        loop?.cancel()
        shutDownInBackground(job)
        state = State.OFF
        runCatching { TileService.requestListeningState(this, ComponentName(this, TopsheTile::class.java)) }
        ActivityLog.add(this, "Killed - everything off")
        stopForeground(STOP_FOREGROUND_REMOVE)
        inForeground = false
        stopSelf()
    }

    /** Stops the brain at once, then frees the mic models and the brain off the screen thread
     *  (after the listening loop has really stopped), so the kill switch never freezes the phone. */
    private fun shutDownInBackground(job: Job?) {
        Brain.stopNow()
        val e = ear
        ear = null
        Thread {
            runBlocking { withTimeoutOrNull(5_000) { job?.cancelAndJoin() } }
            runCatching { e?.release() }
            Brain.unload()
        }.start()
    }

    override fun onDestroy() {
        shutDownInBackground(loop)
        scope.cancel()
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
            .setSmallIcon(R.drawable.ic_stat_topshe)
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
