package org.annay.topshe

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/** Setup screen: one button per step, plus Start/Stop and the activity log. */
class MainActivity : Activity() {

    companion object {
        const val EXTRA_AUTOSTART = "autostart"
        private const val PICK_GGUF = 2
    }

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var startBtn: Button
    @Volatile private var enrolling = false
    private lateinit var dlStatus: TextView
    private lateinit var brainStatus: TextView
    private lateinit var updStatus: TextView
    private lateinit var voiceBar: android.widget.ProgressBar
    private lateinit var brainBar: android.widget.ProgressBar
    private lateinit var updBar: android.widget.ProgressBar
    private lateinit var setupCard: LinearLayout
    private var setupForced = false
    private lateinit var updCard: LinearLayout
    private lateinit var permBtn: Button
    private lateinit var voiceBtn: Button
    private lateinit var recordBtn: Button
    private var home: HomeScreen? = null
    private var memoryScreen: MemoryScreen? = null
    private lateinit var pages: android.widget.FrameLayout
    private var tabs: List<TextView> = emptyList()
    private var current = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (intent?.getBooleanExtra(EXTRA_AUTOSTART, false) == true) { autostart(); return }
        buildUi()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra(EXTRA_AUTOSTART, false) == true) autostart()
    }

    /** Opened by the tile: start listening and get out of the way. */
    private fun autostart() {
        if (Store.missing(this).isEmpty() && hasMic()) {
            ListenerService.send(this, ListenerService.ACTION_START)
            moveTaskToBack(true)
            if (!::status.isInitialized) finish()
        } else if (!::status.isInitialized) buildUi()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) refresh()
        metal?.setRunning(true)
        ticker.removeCallbacks(tick); ticker.post(tick)
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ---------- UI ----------

    private lateinit var kit: Kit
    private var metal: MetalBackground? = null
    private lateinit var statusDot: View

    private fun sttLabel() = "English speech model: " + if (prefs.sttLanguage == "en") "English only (recommended)" else "auto-detect (tap for English only)"

    /** Runs slow work off the screen thread and shows the result. */
    private fun background(start: String, work: () -> String) {
        setStatus(start)
        Thread {
            val msg = runCatching { work() }.getOrElse { "Failed: ${it.message}" }
            setStatus(msg)
            runOnUiThread { refresh() }
        }.start()
    }

    override fun onPause() {
        super.onPause()
        metal?.setRunning(false)
        ticker.removeCallbacks(tick)
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN || ev.actionMasked == android.view.MotionEvent.ACTION_MOVE)
            metal?.touch(ev.x, ev.y)
        return super.dispatchTouchEvent(ev)
    }

    private fun buildUi() {
        kit = Kit(this)
        val k = kit
        window.statusBarColor = Look.BG
        window.navigationBarColor = Look.BG

        // ---- Settings page: setup, tuning, brain, updates, activity ----
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(k.dp(18), k.dp(24), k.dp(18), k.dp(24))
        }
        col.addView(k.text("Settings", 30f, Look.TEXT, Look.black))
        val top = k.card(null)
        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        statusDot = k.dot(Look.OFF)
        status = k.text("", 15f, Look.TEXT)
        statusRow.addView(statusDot)
        statusRow.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(statusRow)
        startBtn = k.primary("Start listening") { toggle() }
        top.addView(startBtn)
        col.addView(top)

        // ---- Setup: one card, hidden once everything is done ----
        setupCard = k.card("Setup")
        setupCard.addView(k.text("Three steps, once. This card disappears when they're done.", 13f))
        permBtn = k.button("1. Allow permissions") { nextPermission() }
        setupCard.addView(permBtn)
        voiceBtn = k.button("2. Download voice models (about 330 MB, use Wi-Fi)") { downloadVoiceModels() }
        setupCard.addView(voiceBtn)
        dlStatus = k.text(if (Store.voiceModelsReady(this)) "Voice models ready." else prefs.voiceDl.ifBlank { "" }, 13f, Look.TEXT)
            .apply { setPadding(0, k.dp(6), 0, 0) }
        setupCard.addView(dlStatus)
        voiceBar = k.bar(); setupCard.addView(voiceBar)
        recordBtn = k.button("3. Record my voice (talk for 25 seconds)") { enrollVoice() }
        setupCard.addView(recordBtn)
        col.addView(setupCard)

        // ---- Brain ----
        val c5 = k.card("Brain")
        val token = k.input("Paste GitHub token", prefs.githubToken, secret = true)
        c5.addView(token)
        c5.addView(k.button("Sync now") {
            prefs.githubToken = token.text.toString()
            Sync.schedule(this)
            background("Syncing...") { Sync.runSync(this) { setStatus(it) } }
        })
        brainStatus = k.text(prefs.brainDl.ifBlank { brainLine() }, 13f, Look.TEXT).apply { setPadding(0, k.dp(10), 0, 0) }
        c5.addView(brainStatus)
        brainBar = k.bar(); c5.addView(brainBar)
        col.addView(c5)

        // ---- App updates: only when a new build is out ----
        updCard = k.card("App update")
        updCard.addView(k.button("Install the new version") {
            if (!Updater.canInstall(this)) {
                updStatus.text = "Allow Alfred to install updates, then come back and tap again."
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } else {
                updStatus.text = "Downloading..."
                k.showProgress(updBar, updStatus.text.toString())
                Thread {
                    val msg = runCatching {
                        Updater.downloadAndInstall(this) { m -> runOnUiThread { updStatus.text = m; k.showProgress(updBar, m) } }
                    }.getOrElse { "Update failed: ${it.message}" }
                    runOnUiThread { updStatus.text = msg; k.showProgress(updBar, msg) }
                }.start()
            }
        })
        updStatus = k.text("", 13f, Look.TEXT).apply { setPadding(0, k.dp(10), 0, 0) }
        updCard.addView(updStatus)
        updBar = k.bar(); updCard.addView(updBar)
        updCard.visibility = View.GONE
        col.addView(updCard)
        Thread {
            val v = runCatching { Updater.available(this) }.getOrNull()
            runOnUiThread {
                if (v != null) { updCard.visibility = View.VISIBLE; updStatus.text = "Build $v is ready." }
            }
        }.start()

        // ---- Advanced: collapsed ----
        val adv = k.card(null)
        val advBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val advHead = k.text("Advanced  +", 16f, Look.TEXT, Look.semibold).apply { setPadding(0, k.dp(2), 0, k.dp(2)) }
        advHead.setOnClickListener {
            val open = advBody.visibility != View.VISIBLE
            advBody.visibility = if (open) View.VISIBLE else View.GONE
            advHead.text = if (open) "Advanced  -" else "Advanced  +"
            if (open) refresh()
        }
        adv.addView(advHead)
        advBody.addView(k.slider("Voice check strictness", prefs.voiceThreshold, 0.10f, 0.90f) { prefs.voiceThreshold = it })
        advBody.addView(k.text("If Alfred ignores you, lower it.", 13f))
        advBody.addView(k.slider("Wake word strictness", prefs.wakeStrictness, 0.05f, 0.60f) { prefs.wakeStrictness = it })
        advBody.addView(k.text("Lower = wakes more easily. Restart listening after changing.", 13f))
        advBody.addView(k.text("Wake word spellings (comma separated), e.g. TOP SHAY", 13f).apply { setPadding(0, k.dp(12), 0, 0) })
        val spell = k.input("ALFRED", prefs.wakeSpellings)
        advBody.addView(spell)
        advBody.addView(k.button("Save spellings") {
            prefs.wakeSpellings = spell.text.toString()
            val lines = runCatching { VoiceModels.keywordLines(this, prefs.wakeSpellings) }.getOrDefault(emptyList())
            status.text = if (lines.isEmpty()) "Saved. (Download voice models first to check them.)" else "Saved ${lines.size} spelling(s). Restart listening."
        })
        advBody.addView(k.buttonV(sttLabel()) { b ->
            prefs.sttLanguage = if (prefs.sttLanguage == "en") "" else "en"
            (b as Button).text = sttLabel()
            status.text = "Restart listening to apply."
        })
        advBody.addView(k.buttonV(bnLabel()) { b ->
            prefs.bengaliHearing = !prefs.bengaliHearing
            (b as Button).text = bnLabel()
            status.text = "Saved."
        })
        advBody.addView(k.button("Speed test") {
            background("Testing speed... (up to 3 minutes)") { Brain.speedTest(this, Memory(this).facts()) }
        })
        advBody.addView(k.button("Go back one brain") {
            DownloadWorker.brain(this, rollback = true)
            brainStatus.text = prefs.brainDl
        })
        advBody.addView(k.button("Redo setup (permissions, voice models, voice recording)") {
            setupForced = true; setupCard.visibility = View.VISIBLE
        })
        advBody.addView(k.button("Clear chat history") { ChatLog.clear(this); home?.tick() })
        advBody.addView(k.text("Activity", 15f, Look.TEXT, Look.semibold).apply { setPadding(0, k.dp(14), 0, 0) })
        log = k.text("", 12f, Look.FAINT, Typeface.MONOSPACE).apply { setTextIsSelectable(true); setPadding(0, k.dp(6), 0, 0) }
        advBody.addView(log)
        advBody.addView(k.text("Build ${runCatching { Updater.installedBuild(this) }.getOrDefault(0)}", 12f).apply { setPadding(0, k.dp(10), 0, 0) })
        adv.addView(advBody)
        col.addView(adv)


        // ---- the three pages + tab bar, over the liquid-metal background ----
        val dm = resources.displayMetrics
        val bg = MetalBackground(this)
        metal = bg
        val setupPage = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(col)
            setOnScrollChangeListener { _, _, y, _, _ -> bg.scrolled(y) }
        }
        home = HomeScreen(this, k, onSettings = { show(2) }, onToggle = { toggle() })
        memoryScreen = MemoryScreen(this, k)
        pages = android.widget.FrameLayout(this)
        pages.addView(home!!.view, android.widget.FrameLayout.LayoutParams(-1, -1))
        pages.addView(memoryScreen!!.view, android.widget.FrameLayout.LayoutParams(-1, -1))
        pages.addView(setupPage, android.widget.FrameLayout.LayoutParams(-1, -1))
        val (bar, t) = k.tabBar(listOf("Chat", "Memory", "Settings")) { show(it) }
        tabs = t
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(pages, LinearLayout.LayoutParams(-1, 0, 1f))
        column.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { setMargins(k.dp(16), k.dp(4), k.dp(16), k.dp(12)) })

        val root = android.widget.FrameLayout(this).apply { setBackgroundColor(Look.BG) }
        root.addView(bg, android.widget.FrameLayout.LayoutParams(
            (dm.widthPixels / MetalBackground.SCALE).toInt() + 2, (dm.heightPixels / MetalBackground.SCALE).toInt() + 40))
        root.addView(column, android.widget.FrameLayout.LayoutParams(-1, -1))
        // Hide the tab bar while the keyboard is open (more room for the chat)
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val r = android.graphics.Rect()
            root.getWindowVisibleDisplayFrame(r)
            val keyboard = root.rootView.height - r.bottom > root.rootView.height / 4
            val want = if (keyboard) View.GONE else View.VISIBLE
            if (bar.visibility != want) bar.visibility = want
        }
        setContentView(root)
        bg.setRunning(true)
        show(if (Store.missing(this).isEmpty()) 0 else 2)    // first time: straight to setup
        refresh()
    }

    /** Switches tab: 0 Chat, 1 Memory, 2 Settings. */
    private fun show(i: Int) {
        current = i
        for (j in 0 until pages.childCount) pages.getChildAt(j).visibility = if (j == i) View.VISIBLE else View.GONE
        kit.selectTab(tabs, i)
        if (i == 1) memoryScreen?.render()
        if (i == 0) home?.warmUp()
        if (i != 0) (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(pages.windowToken, 0)
    }

    override fun onDestroy() {
        home?.destroy()
        if (ListenerService.state == ListenerService.State.OFF) Brain.unloadInBackground()   // chat loaded it; free the memory (never on the screen thread)
        super.onDestroy()
    }

    private fun setStatus(s: String) = runOnUiThread { status.text = s }

    private fun refresh() {
        val missing = Store.missing(this)
        val s = ListenerService.state
        status.text = when {
            missing.isNotEmpty() -> "Still needed: ${missing.joinToString()}"
            s == ListenerService.State.OFF -> "Ready. Alfred is off."
            s == ListenerService.State.PAUSED -> "Paused - mic is off."
            else -> "On - say \"Alfred\"."
        }
        startBtn.text = if (s == ListenerService.State.OFF || s == ListenerService.State.PAUSED) "Start listening" else "Stop (kill)"
        if (::statusDot.isInitialized) (statusDot.background as android.graphics.drawable.GradientDrawable).setColor(when {
            missing.isNotEmpty() || s == ListenerService.State.OFF -> Look.OFF
            s == ListenerService.State.PAUSED -> Look.PAUSED
            else -> Look.ON
        })
        log.text = ActivityLog.read(this)
        if (::setupCard.isInitialized) {
            val pOk = permsDone(); val vOk = Store.voiceModelsReady(this); val rOk = Store.voiceProfile(this).exists()
            permBtn.text = if (pOk) "1. Permissions - done" else "1. Allow permissions"
            voiceBtn.text = if (vOk) "2. Voice models - done" else "2. Download voice models (about 330 MB, use Wi-Fi)"
            recordBtn.text = if (rOk) "3. Voice recorded - done (tap to redo)" else "3. Record my voice (talk for 25 seconds)"
            if (pOk && vOk && rOk && setupCard.visibility == View.VISIBLE && !setupForced) setupCard.visibility = View.GONE
            if (!(pOk && vOk && rOk)) setupCard.visibility = View.VISIBLE
        }
        home?.refresh(missing, s)
        if (current == 1) memoryScreen?.render()
    }

    private fun toggle() {
        val s = ListenerService.state
        if (s == ListenerService.State.OFF || s == ListenerService.State.PAUSED) {
            val missing = Store.missing(this)
            if (missing.isNotEmpty()) { status.text = "Still needed: ${missing.joinToString()}"; return }
            if (!hasMic()) { askPermissions(); return }
            ListenerService.send(this, ListenerService.ACTION_START)
        } else ListenerService.send(this, ListenerService.ACTION_KILL)
        status.postDelayed({ refresh() }, 1500)
    }

    // ---------- permissions ----------

    private fun askPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(perms.toTypedArray(), 10)
    }

    @SuppressLint("BatteryLife")
    private fun bnLabel() = "Bengali hearing: " + if (prefs.bengaliHearing) "on (tap to turn off)" else "off (tap to turn on)"

    private fun brainLine() =
        if (prefs.brainVersion > 0) "Brain v${prefs.brainVersion} installed" + (if (prefs.chatVersion > 0) " + chat brain v${prefs.chatVersion}." else ".")
        else "No brain yet: paste the token and tap Sync now (on Wi-Fi)."

    private fun runtimePermsOk(): Boolean {
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        return need.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun permsDone() = runtimePermsOk() && Settings.canDrawOverlays(this) && MediaAccess.granted(this) &&
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    /** One button, three Android screens in turn: app permissions, open apps from background, battery. */
    private fun nextPermission() {
        when {
            !runtimePermsOk() -> askPermissions()
            !Settings.canDrawOverlays(this) -> {
                setStatus("Turn on 'Allow display over other apps' (lets Alfred open alarms, apps and calls), then come back.")
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            !MediaAccess.granted(this) -> {
                setStatus("Turn on notification access for Alfred (it lets Alfred play songs on Spotify), then come back.")
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            !getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName) -> askBattery()
            else -> setStatus("All permissions done.")
        }
    }

    private fun askBattery() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) { setStatus("Battery: already unrestricted."); return }
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }.onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    // ---------- files ----------

    private fun pick(code: Int) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), code)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        val target = when (requestCode) {
            PICK_GGUF -> Store.model(this)
            else -> return
        }
        setStatus("Copying file... (about 1 GB, give it a minute)")
        Thread {
            try {
                val tmp = File(target.path + ".tmp")
                contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
                tmp.renameTo(target)
                setStatus("Saved ${target.name} (${target.length() / 1_000_000} MB).")
            } catch (e: Exception) {
                setStatus("Copy failed: ${e.message}")
            }
            runOnUiThread { refresh() }
        }.start()
    }

    /** Runs as a foreground download (survives screen-off and app switching); progress shows under the button. */
    private fun downloadVoiceModels() {
        DownloadWorker.voice(this)
        dlStatus.text = prefs.voiceDl
    }

    // Live download progress (read from what DownloadWorker writes), refreshed every second while visible
    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastVoice = ""
    private var lastBrain = ""
    private val tick = object : Runnable {
        override fun run() {
            if (::dlStatus.isInitialized) {
                val v = prefs.voiceDl
                if (v.isNotBlank() && v != lastVoice) { lastVoice = v; dlStatus.text = v; kit.showProgress(voiceBar, v); if (v.startsWith("Voice models ready")) refresh() }
                val b = prefs.brainDl
                if (b.isNotBlank() && b != lastBrain) { lastBrain = b; brainStatus.text = b; kit.showProgress(brainBar, b); if (b.startsWith("Brain v") || b.startsWith("Chat brain v")) refresh() }
                home?.tick()
                home?.refresh(Store.missing(this@MainActivity), ListenerService.state)
            }
            ticker.postDelayed(this, 1000)
        }
    }

    // ---------- voice enrollment ----------

    @SuppressLint("MissingPermission")
    private fun enrollVoice() {
        if (enrolling) return
        if (!Store.voiceModelsReady(this)) { setStatus("Download the voice models first."); return }
        if (!hasMic()) { askPermissions(); return }
        if (ListenerService.state != ListenerService.State.OFF) ListenerService.send(this, ListenerService.ACTION_KILL)
        enrolling = true
        Thread {
            var rec: AudioRecord? = null
            val ex = runCatching { VoiceModels.speakerExtractor(this) }.getOrNull()
            try {
                if (ex == null) { setStatus("Voice-check model failed to load. Download again."); return@Thread }
                val seconds = 25
                val pcm = ShortArray(SAMPLE_RATE * seconds)
                rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), SAMPLE_RATE * 2))
                rec.startRecording()
                var off = 0
                while (off < pcm.size) {
                    val n = rec.read(pcm, off, minOf(SAMPLE_RATE / 10, pcm.size - off))
                    if (n <= 0) break
                    off += n
                    setStatus("Keep talking... ${seconds - off / SAMPLE_RATE} s left")
                }
                setStatus("Learning your voice...")
                // 5 clips of 5 s each -> average voice fingerprint
                val clip = SAMPLE_RATE * 5
                val embs = (0 until off / clip).mapNotNull { VoiceModels.embed(ex, pcm.copyOfRange(it * clip, (it + 1) * clip)) }
                if (embs.size < 3) { setStatus("Not enough clear speech. Try again somewhere quieter."); return@Thread }
                val avg = FloatArray(embs[0].size)
                for (e in embs) for (i in avg.indices) avg[i] += e[i]
                val profile = VoiceModels.normalise(avg)
                VoiceModels.saveProfile(this, profile)
                // How consistent were the clips? (helps pick the strictness)
                val selfScores = embs.map { VoiceModels.cosine(it, profile) }
                ActivityLog.add(this, "Voice enrolled. Your clips scored %.2f to %.2f".format(selfScores.min(), selfScores.max()))
                setStatus("Done! Alfred knows your voice. (Your clips scored %.2f-%.2f)".format(selfScores.min(), selfScores.max()))
            } catch (e: Exception) {
                setStatus("Voice recording failed: ${e.message}")
            } finally {
                rec?.run { runCatching { stop() }; release() }
                ex?.release()
                enrolling = false
                runOnUiThread { refresh() }
            }
        }.start()
    }
}
