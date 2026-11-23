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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
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
    private lateinit var facts: TextView
    @Volatile private var enrolling = false

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
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ---------- UI ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun heading(t: String) = TextView(this).apply {
        text = t; textSize = 17f; setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(18), 0, dp(4))
    }

    private fun note(t: String) = TextView(this).apply { text = t; textSize = 13f; alpha = 0.75f }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply { text = t; setOnClickListener { onClick() } }
    private fun buttonV(t: String, onClick: (View) -> Unit) = Button(this).apply { text = t; setOnClickListener { onClick(it) } }

    private fun sttLabel() = "Speech language: " + if (prefs.sttLanguage == "en") "English (tap for auto: Hindi/Bengali too)" else "Auto-detect (tap for English only)"

    /** Runs slow work off the screen thread and shows the result. */
    private fun background(start: String, work: () -> String) {
        setStatus(start)
        Thread {
            val msg = runCatching { work() }.getOrElse { "Failed: ${it.message}" }
            setStatus(msg)
            runOnUiThread { refresh() }
        }.start()
    }

    private fun buildUi() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(40)) }

        col.addView(TextView(this).apply { text = "Topshe"; textSize = 28f; setTypeface(typeface, Typeface.BOLD) })
        status = TextView(this).apply { textSize = 15f; setPadding(0, dp(6), 0, dp(6)) }
        col.addView(status)
        startBtn = button("Start listening") { toggle() }
        col.addView(startBtn)

        col.addView(heading("1. Permissions"))
        col.addView(note("Mic, notifications, contacts, calls and SMS. Then allow 'Display over other apps' (needed to open alarms, apps and calls from the background) and unrestricted battery (so Realme doesn't kill Topshe)."))
        col.addView(button("Allow permissions") { askPermissions() })
        col.addView(button("Allow display over other apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        col.addView(button("Allow background battery use") { askBattery() })

        col.addView(heading("2. Voice models"))
        col.addView(note("Wake word, voice check and Whisper speech-to-text. Free and open source, about 330 MB to download once (use Wi-Fi)."))
        col.addView(button("Download voice models") { downloadVoiceModels() })

        col.addView(heading("3. Teach it your voice"))
        col.addView(note("Tap, then talk normally for 25 seconds in a quiet room - read anything out loud. Only this voice will be able to use Topshe."))
        col.addView(button("Record my voice") { enrollVoice() })

        col.addView(heading("4. Tuning"))
        col.addView(slider("Voice check strictness", prefs.voiceThreshold, 0.10f, 0.90f) { prefs.voiceThreshold = it })
        col.addView(note("Higher = stricter. If Topshe ignores you, lower it. The log below shows your score each time."))
        col.addView(slider("Wake word strictness", prefs.wakeStrictness, 0.05f, 0.60f) { prefs.wakeStrictness = it })
        col.addView(note("Lower = wakes more easily (but more false wakes). Restart listening after changing."))
        val spell = EditText(this).apply { setText(prefs.wakeSpellings) }
        col.addView(note("Wake word spellings (comma separated). Add how 'Topshe' sounds to you, e.g. TOP SHAY."))
        col.addView(spell)
        col.addView(button("Save spellings") {
            prefs.wakeSpellings = spell.text.toString()
            val lines = runCatching { VoiceModels.keywordLines(this, prefs.wakeSpellings) }.getOrDefault(emptyList())
            status.text = if (lines.isEmpty()) "Saved. (Download voice models first to check them.)" else "Saved ${lines.size} spelling(s). Restart listening."
        })

        col.addView(heading("5. Brain and learning"))
        col.addView(note("Topshe learns from everything you say. Once a week it retrains itself and the phone downloads the new brain on Wi-Fi, only if it scored better in tests. Needs a GitHub token: github.com -> Settings -> Developer settings -> Fine-grained tokens -> only the Topshe-v2 repo -> Contents: Read and write."))
        val token = EditText(this).apply {
            hint = "Paste GitHub token"; setText(prefs.githubToken)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        col.addView(token)
        col.addView(button("Save token and sync now") {
            prefs.githubToken = token.text.toString()
            Sync.schedule(this)
            background("Syncing...") { Sync.runSync(this) { setStatus(it) } }
        })
        col.addView(button("Go back one brain (re-downloads 2.7 GB, use Wi-Fi)") {
            background("Going back...") { Sync.rollback(this) { setStatus(it) } }
        })
        col.addView(button("Speed test (picks the fastest setting)") {
            background("Testing speed... (takes about a minute)") { Brain.speedTest(this, Memory(this).facts()) }
        })
        col.addView(note("Before the first weekly brain arrives, you can use the older v2 model:"))
        col.addView(button("Pick older model (topshe_v2.gguf)") { pick(PICK_GGUF) })
        col.addView(buttonV(sttLabel()) { b ->
            prefs.sttLanguage = if (prefs.sttLanguage == "en") "" else "en"
            (b as Button).text = sttLabel()
            status.text = "Restart listening to apply."
        })

        col.addView(heading("What Topshe knows about you"))
        facts = TextView(this).apply { textSize = 13f; setTextIsSelectable(true) }
        col.addView(facts)
        col.addView(note("Say \"Topshe, remember that ...\" to add, \"Topshe, forget ...\" to remove. If Topshe gets something wrong, say \"no, I meant ...\" and it won't make that mistake again."))

        col.addView(heading("Activity"))
        col.addView(button("Refresh") { refresh() })
        log = TextView(this).apply { textSize = 12f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        col.addView(log)

        setContentView(ScrollView(this).apply { addView(col) })
        refresh()
    }

    private fun slider(label: String, value: Float, lo: Float, hi: Float, save: (Float) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val t = TextView(this).apply { text = "$label: %.2f".format(value) }
        val bar = SeekBar(this).apply {
            max = 100; progress = (((value - lo) / (hi - lo)) * 100).toInt().coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = lo + (hi - lo) * p / 100f; t.text = "$label: %.2f".format(v); if (fromUser) save(v)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        box.addView(t); box.addView(bar)
        return box
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
        log.text = ActivityLog.read(this)
        val mem = Memory(this)
        val f = mem.facts()
        facts.text = (if (f.isEmpty()) "Nothing yet." else f.joinToString("\n") { "- $it" }) +
            "\n\nBrain: " + (if (Prefs(this).brainVersion > 0) "v${Prefs(this).brainVersion} (weekly)" else if (Store.model(this).exists()) "older v2" else "none yet") +
            " | ${mem.interactionCount()} things learned from so far"
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

    private fun downloadVoiceModels() {
        Thread {
            try {
                VoiceModels.downloadAll(this) { setStatus(it) }
            } catch (e: Exception) {
                setStatus("Download failed: ${e.message}. Check internet and try again.")
            }
            runOnUiThread { refresh() }
        }.start()
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
