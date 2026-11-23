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

    /** CPU threads for the brain (set by the speed test). */
    var threads: Int
        // Dimensity 6400: 2 fast cores; more threads mix in slow ones. Older builds saved 4: start again at 2.
        get() = if (sp.getInt("threadsVer", 0) < 2) 2 else sp.getInt("threads", 2)
        set(v) = sp.edit().putInt("threads", v).putInt("threadsVer", 2).apply()

    /** GitHub token (fine-grained, Contents read/write on the Topshe-v2 repo): uploads data, downloads brains. */
    var githubToken: String
        get() = sp.getString("githubToken", "") ?: ""
        set(v) = sp.edit().putString("githubToken", v.trim()).apply()

    var githubRepo: String
        get() = sp.getString("githubRepo", "annay-de/Topshe-v2") ?: "annay-de/Topshe-v2"
        set(v) = sp.edit().putString("githubRepo", v.trim()).apply()

    /** Version of the brain on the phone (0 = none from the weekly pipeline yet). */
    var brainVersion: Int
        get() = sp.getInt("brainVersion", 0)
        set(v) = sp.edit().putInt("brainVersion", v).apply()

    /** Chat screen: speak Topshe's replies out loud too (off = text only). */
    var speakTyped: Boolean
        get() = sp.getBoolean("speakTyped", false)
        set(v) = sp.edit().putBoolean("speakTyped", v).apply()

    /** Latest status of the brain / voice-model downloads (written by DownloadWorker, shown in the app). */
    var brainDl: String
        get() = sp.getString("brainDl", "") ?: ""
        set(v) = sp.edit().putString("brainDl", v).apply()
    var voiceDl: String
        get() = sp.getString("voiceDl", "") ?: ""
        set(v) = sp.edit().putString("voiceDl", v).apply()

    /** After "go back one brain", don't auto-install this version (or older) again. */
    var skipVersion: Int
        get() = sp.getInt("skipVersion", 0)
        set(v) = sp.edit().putInt("skipVersion", v).apply()

    /** Seed facts (e.g. from your Claude export) already added once, so forgotten ones don't come back. */
    var appliedSeeds: Set<String>
        get() = sp.getStringSet("appliedSeeds", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("appliedSeeds", v).apply()

    /** How many lines of the interaction log have been uploaded already. */
    var uploadedLines: Int
        get() = sp.getInt("uploadedLines", 0)
        set(v) = sp.edit().putInt("uploadedLines", v).apply()

    /** Speech-to-text language: "en" (default) or "" (auto-detect, better for Hindi/Bengali). */
    var sttLanguage: String
        get() = sp.getString("sttLanguage", "en") ?: "en"
        set(v) = sp.edit().putString("sttLanguage", v).apply()
}

/** Where Topshe keeps its files (all inside the app's private storage). */
object Store {
    fun brain(ctx: Context) = File(ctx.filesDir, "topshe_brain.gguf")              // weekly brain (Qwen 3.5 4B)
    fun promptTemplate(ctx: Context) = File(ctx.filesDir, "prompt_template.json")
    fun model(ctx: Context) = File(ctx.filesDir, "topshe.gguf")                    // older v2 model (fallback)
    fun sttModel(ctx: Context) = File(ctx.filesDir, "whisper")
    fun bnModel(ctx: Context) = File(ctx.filesDir, "bn_asr")                     // Bengali speech-to-text
    fun wakeModel(ctx: Context) = File(ctx.filesDir, "kws")
    fun speakerModel(ctx: Context) = File(ctx.filesDir, "speaker.onnx")
    fun voiceProfile(ctx: Context) = File(ctx.filesDir, "voice_profile_v2.bin")
    fun logFile(ctx: Context) = File(ctx.filesDir, "activity.log")
    fun memoryDir(ctx: Context) = File(ctx.filesDir, "memory").apply { mkdirs() }

    fun hasBrain(ctx: Context) = brain(ctx).exists() || model(ctx).exists()

    fun voiceModelsReady(ctx: Context) =
        File(wakeModel(ctx), "tokens.txt").exists() && speakerModel(ctx).length() > 1_000_000 &&
            (sttModel(ctx).listFiles()?.any { it.name.contains("encoder") } == true) &&
            File(bnModel(ctx), "tokens.txt").exists()

    fun missing(ctx: Context): List<String> = buildList {
        if (!hasBrain(ctx)) add("brain (sync with GitHub, or pick a .gguf)")
        if (!voiceModelsReady(ctx)) add("voice models (download)")
        if (!voiceProfile(ctx).exists()) add("your voice recording")
    }
}

/** Small activity log shown in the app, so you can see what Topshe heard and did. */
object ActivityLog {
    private val fmt = SimpleDateFormat("dd MMM HH:mm:ss", Locale.US)

    @Synchronized
    fun add(ctx: Context, msg: String) {
        android.util.Log.i("Topshe", msg)            // also visible over USB (adb logcat -s Topshe)
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
