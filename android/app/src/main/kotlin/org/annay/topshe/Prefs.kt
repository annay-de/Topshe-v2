package org.annay.topshe

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Settings saved on the phone. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("topshe", Context.MODE_PRIVATE)

    /** Wake-word strictness (keyword threshold), 0.05..0.60. Lower = wakes more easily, more false wakes. */
    var wakeStrictness: Float
        get() = sp.getFloat("wakeStrictness", 0.20f)
        set(v) = sp.edit().putFloat("wakeStrictness", v).apply()

    /** Voice-match score needed to accept a command (cosine similarity), 0..1. Higher = stricter. */
    var voiceThreshold: Float
        get() = sp.getFloat("voiceThreshold", 0.50f)
        set(v) = sp.edit().putFloat("voiceThreshold", v).apply()

    /** How the wake word may be spelled, comma separated. Each spelling is tried. */
    var wakeSpellings: String
        get() = sp.getString("wakeSpellings", "TOPSHE, TOP SHAY, TOPSHAY") ?: "TOPSHE"
        set(v) = sp.edit().putString("wakeSpellings", v).apply()
}

/** Where Topshe keeps its files (all inside the app's private storage). */
object Store {
    fun model(ctx: Context) = File(ctx.filesDir, "topshe.gguf")
    fun chatModel(ctx: Context) = File(ctx.filesDir, "topshe_chat.gguf")
    fun speechModel(ctx: Context) = File(ctx.filesDir, "vosk-model")
    fun wakeModel(ctx: Context) = File(ctx.filesDir, "kws")
    fun speakerModel(ctx: Context) = File(ctx.filesDir, "speaker.onnx")
    fun voiceProfile(ctx: Context) = File(ctx.filesDir, "voice_profile_v2.bin")
    fun logFile(ctx: Context) = File(ctx.filesDir, "activity.log")

    fun voiceModelsReady(ctx: Context) =
        File(wakeModel(ctx), "tokens.txt").exists() && speakerModel(ctx).length() > 1_000_000 &&
            (File(speechModel(ctx), "conf").exists() || File(speechModel(ctx), "am").exists())

    fun missing(ctx: Context): List<String> = buildList {
        if (!model(ctx).exists()) add("Topshe model (.gguf)")
        if (!voiceModelsReady(ctx)) add("voice models (download)")
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
