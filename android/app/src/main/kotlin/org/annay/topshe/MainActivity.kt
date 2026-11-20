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
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import ai.picovoice.eagle.EagleProfiler
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Setup screen: one button per step, plus Start/Stop and the activity log. */
class MainActivity : Activity() {

    companion object {
        const val EXTRA_AUTOSTART = "autostart"
        private const val PICK_PPN = 1
        private const val PICK_GGUF = 2
        private const val SPEECH_MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip"
    }

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var startBtn: Button
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
        if (Store.missing(this, prefs).isEmpty() && hasMic()) {
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

        col.addView(heading("2. Picovoice AccessKey"))
        col.addView(note("From console.picovoice.ai (free). Used for the wake word and the voice check."))
        val key = EditText(this).apply {
            hint = "Paste AccessKey"; setText(prefs.accessKey)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        col.addView(key)
        col.addView(button("Save key") { prefs.accessKey = key.text.toString(); refresh() })

        col.addView(heading("3. Files"))
        col.addView(button("Pick wake word file (topshe .ppn)") { pick(PICK_PPN) })
        col.addView(button("Pick Topshe model (topshe_v2.gguf)") { pick(PICK_GGUF) })
        col.addView(button("Download speech model (36 MB, once)") { downloadSpeechModel() })

        col.addView(heading("4. Teach it your voice"))
        col.addView(note("Tap, then talk normally for about 30 seconds in a quiet room - read anything out loud. Only this voice will be able to use Topshe."))
        col.addView(button("Record my voice") { enrollVoice() })

        col.addView(heading("5. Tuning"))
        col.addView(slider("Voice check strictness", prefs.voiceThreshold) { prefs.voiceThreshold = it })
        col.addView(note("Higher = stricter. If Topshe ignores you, lower it. Check the log below for your scores."))
        col.addView(slider("Wake word sensitivity", prefs.wakeSensitivity) { prefs.wakeSensitivity = it })
        col.addView(note("Higher = wakes more easily (but more false wakes). Restart listening after changing."))

        col.addView(heading("Activity"))
        col.addView(button("Refresh log") { refresh() })
        log = TextView(this).apply { textSize = 12f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        col.addView(log)

        setContentView(ScrollView(this).apply { addView(col) })
        refresh()
    }

    private fun slider(label: String, value: Float, save: (Float) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val t = TextView(this).apply { text = "$label: %.2f".format(value) }
        val bar = SeekBar(this).apply {
            max = 90; progress = (value * 100).toInt().coerceIn(10, 100) - 10   // 0.10 .. 1.00
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = (p + 10) / 100f; t.text = "$label: %.2f".format(v); if (fromUser) save(v)
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
        val missing = Store.missing(this, prefs)
        val s = ListenerService.state
        status.text = when {
            missing.isNotEmpty() -> "Still needed: ${missing.joinToString()}"
            s == ListenerService.State.OFF -> "Ready. Topshe is off."
            s == ListenerService.State.PAUSED -> "Paused - mic is off."
            else -> "On - say \"Topshe\"."
        }
        startBtn.text = if (s == ListenerService.State.OFF || s == ListenerService.State.PAUSED) "Start listening" else "Stop (kill)"
        log.text = ActivityLog.read(this)
    }

    private fun toggle() {
        val s = ListenerService.state
        if (s == ListenerService.State.OFF || s == ListenerService.State.PAUSED) {
            val missing = Store.missing(this, prefs)
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
        val target = if (requestCode == PICK_PPN) Store.wakeWord(this) else Store.model(this)
        setStatus("Copying file... (the model is about 1 GB, give it a minute)")
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

    private fun downloadSpeechModel() {
        setStatus("Downloading speech model...")
        Thread {
            try {
                val dest = Store.speechModel(this)
                dest.deleteRecursively()
                val conn = URL(SPEECH_MODEL_URL).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                ZipInputStream(conn.inputStream.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        // Strip the top folder ("vosk-model-small-en-in-0.4/")
                        val rel = entry.name.substringAfter('/', "")
                        if (rel.isNotEmpty() && !rel.contains("..")) {
                            val f = File(dest, rel)
                            if (entry.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { zip.copyTo(it) } }
                        }
                        entry = zip.nextEntry
                    }
                }
                setStatus("Speech model ready.")
            } catch (e: Exception) {
                setStatus("Download failed: ${e.message}")
            }
            runOnUiThread { refresh() }
        }.start()
    }

    // ---------- voice enrollment ----------

    @SuppressLint("MissingPermission")
    private fun enrollVoice() {
        if (enrolling) return
        if (prefs.accessKey.isBlank()) { setStatus("Save your AccessKey first."); return }
        if (!hasMic()) { askPermissions(); return }
        if (ListenerService.state != ListenerService.State.OFF) ListenerService.send(this, ListenerService.ACTION_KILL)
        enrolling = true
        Thread {
            var rec: AudioRecord? = null
            var profiler: EagleProfiler? = null
            try {
                profiler = EagleProfiler.Builder().setAccessKey(prefs.accessKey).build(this)
                val chunk = ShortArray(profiler.frameLength)
                rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), SAMPLE_RATE * 2))
                rec.startRecording()
                var pct = 0f
                val t0 = System.currentTimeMillis()
                while (pct < 100f && System.currentTimeMillis() - t0 < 120_000) {
                    var off = 0
                    while (off < chunk.size) { val n = rec.read(chunk, off, chunk.size - off); if (n <= 0) break; off += n }
                    pct = profiler.enroll(chunk)
                    setStatus("Keep talking... %.0f%%".format(pct))
                }
                if (pct < 100f) pct = profiler.flush()
                if (pct < 100f) { setStatus("Not enough clear speech (%.0f%%). Try again somewhere quieter.".format(pct)); return@Thread }
                val profile = profiler.export()
                Store.voiceProfile(this).writeBytes(profile.bytes)
                profile.delete()
                ActivityLog.add(this, "Voice enrolled")
                setStatus("Done! Topshe knows your voice.")
            } catch (e: Exception) {
                setStatus("Voice recording failed: ${e.message}")
            } finally {
                rec?.run { runCatching { stop() }; release() }
                profiler?.delete()
                enrolling = false
                runOnUiThread { refresh() }
            }
        }.start()
    }
}
