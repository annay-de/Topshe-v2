package org.annay.topshe

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Settings saved on the phone. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("topshe", Context.MODE_PRIVATE)

    var accessKey: String
        get() = sp.getString("accessKey", "") ?: ""
        set(v) = sp.edit().putString("accessKey", v.trim()).apply()

    /** Porcupine wake-word sensitivity, 0..1 (higher = wakes more easily, more false wakes). */
    var wakeSensitivity: Float
        get() = sp.getFloat("wakeSensitivity", 0.6f)
        set(v) = sp.edit().putFloat("wakeSensitivity", v).apply()

    /** Eagle voice-match score needed to accept a command, 0..1 (higher = stricter). */
    var voiceThreshold: Float
        get() = sp.getFloat("voiceThreshold", 0.6f)
        set(v) = sp.edit().putFloat("voiceThreshold", v).apply()
}

/** Where Topshe keeps its files (all inside the app's private storage). */
object Store {
    fun model(ctx: Context) = File(ctx.filesDir, "topshe.gguf")
    fun wakeWord(ctx: Context) = File(ctx.filesDir, "topshe.ppn")
    fun voiceProfile(ctx: Context) = File(ctx.filesDir, "voice_profile.bin")
    fun speechModel(ctx: Context) = File(ctx.filesDir, "vosk-model")
    fun logFile(ctx: Context) = File(ctx.filesDir, "activity.log")

    fun missing(ctx: Context, prefs: Prefs): List<String> = buildList {
        if (prefs.accessKey.isBlank()) add("Picovoice AccessKey")
        if (!wakeWord(ctx).exists()) add("wake word file (.ppn)")
        if (!model(ctx).exists()) add("Topshe model (.gguf)")
        if (!File(speechModel(ctx), "conf").exists() && !File(speechModel(ctx), "am").exists()) add("speech model")
        if (!voiceProfile(ctx).exists()) add("your voice recording")
    }
}

/** Small activity log shown in the app, so you can see what Topshe heard and did. */
object ActivityLog {
    private val fmt = SimpleDateFormat("dd MMM HH:mm:ss", Locale.US)

    @Synchronized
    fun add(ctx: Context, msg: String) {
        val f = Store.logFile(ctx)
        val lines = (if (f.exists()) f.readLines() else emptyList()).takeLast(199) +
            "${fmt.format(Date())}  $msg"
        f.writeText(lines.joinToString("\n"))
    }

    fun read(ctx: Context): String {
        val f = Store.logFile(ctx)
        return if (f.exists()) f.readLines().reversed().joinToString("\n") else "Nothing yet."
    }
}
