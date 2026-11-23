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
        col.addView(k.text("Setup, tuning, brain and app updates", 14f))
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

        val c1 = k.card("Permissions", "1")
        c1.addView(k.text("Mic, notifications, contacts, calls and SMS. Then allow 'Display over other apps' (needed to open alarms, apps and calls from the background) and unrestricted battery (so Realme doesn't kill Topshe)."))
        c1.addView(k.button("Allow permissions") { askPermissions() })
        c1.addView(k.button("Allow display over other apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        c1.addView(k.button("Allow background battery use") { askBattery() })
        col.addView(c1)

        val c2 = k.card("Voice models", "2")
        c2.addView(k.text("Wake word, voice check, and two speech-to-text models: Whisper for English and a Bengali model (Topshe listens with both). Free and open source, about 330 MB to download once (use Wi-Fi)."))
        c2.addView(k.button("Download voice models") { downloadVoiceModels() })
        dlStatus = k.text(if (Store.voiceModelsReady(this)) "Voice models ready." else prefs.voiceDl.ifBlank { "Not downloaded yet." }, 13f, Look.TEXT)
            .apply { setPadding(0, k.dp(10), 0, 0) }
        c2.addView(dlStatus)
        voiceBar = k.bar(); c2.addView(voiceBar)
        col.addView(c2)

        val c3 = k.card("Teach it your voice", "3")
        c3.addView(k.text("Tap, then talk normally for 25 seconds in a quiet room - read anything out loud. Only this voice will be able to use Topshe."))
        c3.addView(k.button("Record my voice") { enrollVoice() })
        col.addView(c3)

        val c4 = k.card("Tuning", "4")
        c4.addView(k.slider("Voice check strictness", prefs.voiceThreshold, 0.10f, 0.90f) { prefs.voiceThreshold = it })
        c4.addView(k.text("Higher = stricter. If Topshe ignores you, lower it. The log below shows your score each time.", 13f))
        c4.addView(k.slider("Wake word strictness", prefs.wakeStrictness, 0.05f, 0.60f) { prefs.wakeStrictness = it })
        c4.addView(k.text("Lower = wakes more easily (but more false wakes). Restart listening after changing.", 13f))
        c4.addView(k.text("Wake word spellings (comma separated). Add how 'Topshe' sounds to you, e.g. TOP SHAY.", 13f).apply { setPadding(0, k.dp(12), 0, 0) })
        val spell = k.input("TOPSHE", prefs.wakeSpellings)
        c4.addView(spell)
        c4.addView(k.button("Save spellings") {
            prefs.wakeSpellings = spell.text.toString()
            val lines = runCatching { VoiceModels.keywordLines(this, prefs.wakeSpellings) }.getOrDefault(emptyList())
            status.text = if (lines.isEmpty()) "Saved. (Download voice models first to check them.)" else "Saved ${lines.size} spelling(s). Restart listening."
        })
        col.addView(c4)

        val c5 = k.card("Brain and learning", "5")
        c5.addView(k.text("Topshe learns from everything you say. Once a week it retrains itself and the phone downloads the new brain on Wi-Fi, only if it scored better in tests. Needs a GitHub token: github.com -> Settings -> Developer settings -> Fine-grained tokens -> only the Topshe-v2 repo -> Contents: Read and write."))
        val token = k.input("Paste GitHub token", prefs.githubToken, secret = true)
        c5.addView(token)
        c5.addView(k.button("Save token and sync now") {
            prefs.githubToken = token.text.toString()
            Sync.schedule(this)
            background("Syncing...") { Sync.runSync(this) { setStatus(it) } }
        })
        brainStatus = k.text(prefs.brainDl.ifBlank { if (prefs.brainVersion > 0) "Brain v${prefs.brainVersion} installed." else "No brain downloaded yet." },
            13f, Look.TEXT).apply { setPadding(0, k.dp(10), 0, 0) }
        c5.addView(brainStatus)
        brainBar = k.bar(); c5.addView(brainBar)
        c5.addView(k.text("The brain downloads in the background with a notification: you can leave Topshe or turn the screen off. If the connection drops it continues where it stopped.", 13f).apply { setPadding(0, k.dp(6), 0, 0) })
        c5.addView(k.button("Go back one brain (re-downloads 2.7 GB, use Wi-Fi)") {
            DownloadWorker.brain(this, rollback = true)
            brainStatus.text = prefs.brainDl
        })
        c5.addView(k.button("Speed test (picks the fastest setting)") {
            background("Testing speed... (takes about a minute)") { Brain.speedTest(this, Memory(this).facts()) }
        })
        c5.addView(k.text("Before the first weekly brain arrives, you can use the older v2 model:", 13f).apply { setPadding(0, k.dp(12), 0, 0) })
        c5.addView(k.button("Pick older model (topshe_v2.gguf)") { pick(PICK_GGUF) })
        c5.addView(k.buttonV(sttLabel()) { b ->
            prefs.sttLanguage = if (prefs.sttLanguage == "en") "" else "en"
            (b as Button).text = sttLabel()
            status.text = "Restart listening to apply."
        })
        col.addView(c5)

        val c8 = k.card("App updates")
        c8.addView(k.text("New versions come from your GitHub Releases. Topshe downloads them and Android asks you to confirm - no browser, no need to switch Play Protect off."))
        c8.addView(k.button("Update the app") {
            if (!Updater.canInstall(this)) {
                updStatus.text = "Allow Topshe to install updates, then come back and tap again."
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } else {
                updStatus.text = "Checking for a new app build..."
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
        c8.addView(updStatus)
        updBar = k.bar(); c8.addView(updBar)
        c8.addView(k.text("This is build ${runCatching { Updater.installedBuild(this) }.getOrDefault(0)}.", 13f).apply { setPadding(0, k.dp(10), 0, 0) })
        col.addView(c8)

        val c7 = k.card("Activity")
        c7.addView(k.button("Refresh") { refresh() })
        c7.addView(k.button("Clear chat history") { ChatLog.clear(this); home?.tick() })
        log = k.text("", 12f, Look.FAINT, Typeface.MONOSPACE).apply { setTextIsSelectable(true); setPadding(0, k.dp(10), 0, 0) }
        c7.addView(log)
        col.addView(c7)


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
        if (i != 0) (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(pages.windowToken, 0)
    }

    override fun onDestroy() {
        home?.destroy()
        if (ListenerService.state == ListenerService.State.OFF) Brain.unload()   // chat loaded it; free the memory
        super.onDestroy()
    }

    private fun setStatus(s: String) = runOnUiThread { status.text = s }

    private fun refresh() {
        val missing = Store.missing(this)
        val s = ListenerService.state
        status.text = when {
            missing.isNotEmpty() -> "Still needed: ${missing.joinToString()}"
            s == ListenerService.State.OFF -> "Ready. Topshe is off."
            s == ListenerService.State.PAUSED -> "Paused - mic is off."
            else -> "On - say \"Topshe\"."
        }
        startBtn.text = if (s == ListenerService.State.OFF || s == ListenerService.State.PAUSED) "Start listening" else "Stop (kill)"
        if (::statusDot.isInitialized) (statusDot.background as android.graphics.drawable.GradientDrawable).setColor(when {
            missing.isNotEmpty() || s == ListenerService.State.OFF -> Look.OFF
            s == ListenerService.State.PAUSED -> Look.PAUSED
            else -> Look.ON
        })
        log.text = ActivityLog.read(this)
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
                if (b.isNotBlank() && b != lastBrain) { lastBrain = b; brainStatus.text = b; kit.showProgress(brainBar, b); if (b.startsWith("Brain v")) refresh() }
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
                setStatus("Done! Topshe knows your voice. (Your clips scored %.2f-%.2f)".format(selfScores.min(), selfScores.max()))
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
