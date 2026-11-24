package org.annay.topshe

import android.content.Context
import org.json.JSONObject

/** The C++ llama.cpp bridge (src/main/cpp/topshe_llama.cpp). */
object NativeLlama {
    init { System.loadLibrary("topshe_llama") }
    external fun load(path: String): Long
    external fun unload(handle: Long)
    external fun generate(handle: Long, prefix: String, suffix: String, maxTokens: Int, temp: Float, threads: Int,
                          listener: Listener?): ByteArray
    /** Gets each new piece of the answer as it's written (UTF-8 bytes; a letter may be split across pieces). */
    fun interface Listener { fun onPiece(bytes: ByteArray) }
    external fun abort()
    external fun stats(): String
}

/**
 * Alfred's brains: the command brain (Qwen 3.5 0.8B, retrained weekly on your usage) turns every
 * request into an action, quickly; the chat brain (Qwen 3.5 2B) answers questions and small talk.
 * Without a chat brain on the phone, the command brain answers chat too.
 * Everything Alfred knows about you (Memory facts) is added to every prompt.
 *
 * The prompt format comes from prompt_template.json, written by the trainer together with the model,
 * so the phone always uses exactly the format the model was trained with.
 */
object Brain {
    // Must match training/make_dataset_v3.py exactly
    const val CMD_SYSTEM =
        "You are Topshe, a phone assistant. Turn the user's request into ONE JSON object. " +
        "Actions: set_alarm, set_timer, set_reminder, open_app, call, message, tasker, media, " +
        "remember, forget, stop, chat. Use chat for questions or small talk. Reply with JSON only."
    const val CHAT_SYSTEM =
        "You are Alfred, a friendly assistant. Answer in 1-3 short, simple sentences. " +
        "Talk to Annay plainly, like an adult. No lectures or disclaimers."
    const val FACTS_HEADER = "What you know about Annay:"

    // The older v2 model (Qwen 2.5 1.5B) used a smaller action list and no memory
    private const val LEGACY_CMD_SYSTEM =
        "You are Topshe, a phone assistant. Turn the user's request into ONE JSON object. " +
        "Actions: set_alarm, set_timer, set_reminder, open_app, call, message, tasker, stop, chat. " +
        "Use chat for questions or small talk. Reply with JSON only."
    private const val CHATML =
        "<|im_start|>system\n{SYSTEM}<|im_end|>\n<|im_start|>user\n{USER}<|im_end|>\n<|im_start|>assistant\n"

    @Volatile private var handle = 0L
    @Volatile private var chatHandle = 0L
    private var chatTpl = CHATML
    private var chatFailed = false
    private var appCtx: Context? = null
    @Volatile var legacy = false
        private set
    @Volatile var loadedName = ""
    /** The loaded brain was trained on both hearings (English + Bengali speech models). */
    @Volatile var dualHearing = false
        private set
    private var template = CHATML
    @Volatile var threads = 2
    /** Timings of the last answer (prompt, your words, reply speed) for the activity log. */
    @Volatile var lastStats = ""

    /** The last answer was cut short by Stop / the kill switch. */
    @Volatile var wasStopped = false
        private set

    /** Ends the current answer right away (kill switch, Stop button). Never blocks. */
    fun stopNow() {
        if (handle == 0L && chatHandle == 0L) return
        wasStopped = true
        runCatching { NativeLlama.abort() }
    }

    /** Kill/pause: stop the answer and free the memory off the screen thread (never freezes the phone). */
    fun unloadInBackground() {
        stopNow()
        Thread { unload() }.start()
    }

    val loaded get() = handle != 0L

    /** Loads the newest brain on the phone (falls back to the old v2 model if that's all there is). */
    @Synchronized
    fun load(ctx: Context): Boolean {
        if (handle != 0L) return true
        val brain = Store.brain(ctx)
        val file = when {
            brain.exists() -> brain
            Store.model(ctx).exists() -> Store.model(ctx)
            else -> return false
        }
        legacy = file != brain
        template = if (legacy) CHATML else readTemplate(ctx)
        dualHearing = !legacy && runCatching {
            JSONObject(Store.promptTemplate(ctx).readText()).optString("hearing") == "dual-v1"
        }.getOrDefault(false)
        threads = Prefs(ctx).threads
        appCtx = ctx.applicationContext
        chatFailed = false
        handle = NativeLlama.load(file.absolutePath)
        loadedName = if (legacy) "v2 (1.5B, older)" else "v${Prefs(ctx).brainVersion}" +
            (if (Store.chatBrain(ctx).exists()) " + chat v${Prefs(ctx).chatVersion}" else "")
        return handle != 0L
    }

    /** The chat brain, loaded the first time a question comes (null: use the command brain). */
    @Synchronized
    private fun chatEngine(): Long {
        if (chatHandle != 0L || chatFailed || legacy) return chatHandle
        val ctx = appCtx ?: return 0L
        val f = Store.chatBrain(ctx)
        if (!f.exists()) return 0L
        chatTpl = runCatching { JSONObject(Store.chatTemplate(ctx).readText()).getString("template") }
            .getOrDefault("$CHATML<think>\n\n</think>\n\n")
        chatHandle = NativeLlama.load(f.absolutePath)
        chatFailed = chatHandle == 0L
        return chatHandle
    }

    val hasChatBrain get() = chatHandle != 0L || appCtx?.let { Store.chatBrain(it).exists() } == true

    @Synchronized
    fun unload() {
        if (handle != 0L) NativeLlama.unload(handle)
        if (chatHandle != 0L) NativeLlama.unload(chatHandle)
        handle = 0L
        chatHandle = 0L
    }

    private fun readTemplate(ctx: Context): String = runCatching {
        JSONObject(Store.promptTemplate(ctx).readText()).getString("template")
    }.getOrDefault("$CHATML<think>\n\n</think>\n\n")

    fun withFacts(system: String, facts: List<String>): String =
        if (facts.isEmpty() || legacy) system
        else system + "\n\n" + FACTS_HEADER + "\n" + facts.joinToString("\n") { "- $it" }

    /** Splits the template at {USER}: the part before is cached, only the rest is new each time.
     *  Synchronized with load/unload: the chat screen and the listener can't free it mid-answer. */
    @Synchronized
    private fun run(system: String, user: String, maxTokens: Int, temp: Float, chat: Boolean = false,
                    onText: ((String) -> Unit)? = null): String {
        val ch = if (chat) chatEngine() else 0L
        val h = if (ch != 0L) ch else handle
        if (h == 0L) return ""
        wasStopped = false
        val full = (if (ch != 0L) chatTpl else template).replace("{SYSTEM}", system)
        val cut = full.indexOf("{USER}")
        val prefix = full.substring(0, cut)
        val suffix = user + full.substring(cut + "{USER}".length)
        val listener = onText?.let { cb ->
            val sofar = java.io.ByteArrayOutputStream()
            NativeLlama.Listener { bytes ->
                sofar.write(bytes)
                val t = clean(String(sofar.toByteArray(), Charsets.UTF_8).trimEnd('\uFFFD'))
                if (t.isNotEmpty()) runCatching { cb(t) }
            }
        }
        val out = clean(String(NativeLlama.generate(h, prefix, suffix, maxTokens, temp, threads, listener), Charsets.UTF_8))
        lastStats = (if (ch != 0L) "chat " else "") + runCatching { NativeLlama.stats() }.getOrDefault("")
        android.util.Log.i("Alfred", lastStats)
        return out
    }

    /** The visible answer: no thinking block (a half-written one hides everything after it). */
    private fun clean(s: String): String =
        s.replace(Regex("(?s)<think>.*?</think>"), "").replace(Regex("(?s)<think>.*$"), "").trim()

    /** Processes the fixed instructions once in advance, so the first real request is quicker. */
    fun warmUp(facts: List<String>) {
        run(withFacts(if (legacy) LEGACY_CMD_SYSTEM else CMD_SYSTEM, facts), "hi", 1, 0f)
        if (!wasStopped && hasChatBrain) run(withFacts(CHAT_SYSTEM, facts), "hi", 1, 0f, chat = true)
    }

    /** Your words -> one action (JSON), or null if the reply wasn't readable. */
    fun command(text: String, facts: List<String>): JSONObject? =
        parseJson(run(withFacts(if (legacy) LEGACY_CMD_SYSTEM else CMD_SYSTEM, facts), text, 96, 0f))

    fun chat(question: String, facts: List<String>, onText: ((String) -> Unit)? = null): String =
        run(withFacts(CHAT_SYSTEM, facts), question, 120, 0.6f, chat = true,
            onText = onText?.let { cb -> { t: String -> cb(renamed(t)) } }).let(::renamed)

    /** The current brains were trained as "Topshe"; until they're retrained, say the new name. */
    private fun renamed(t: String) = t.replace("Topshe", "Alfred").replace("topshe", "Alfred")
        .replace("TOPSHE", "ALFRED").replace("টপসে", "আলফ্রেড")
            .ifBlank { "Sorry, I don't have an answer for that." }

    /** Lenient: finds the first balanced {...} in the output. */
    fun parseJson(raw: String): JSONObject? {
        val text = raw.replace(Regex("```(?:json)?"), "")
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        for (i in start until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return runCatching { JSONObject(text.substring(start, i + 1)) }.getOrNull()
            }
        }
        return null
    }

    /** Times one short command at 2, 3 and 4 threads (after warming up); keeps the fastest. */
    fun speedTest(ctx: Context, facts: List<String>): String {
        val t0 = System.currentTimeMillis()
        if (!load(ctx)) return "No brain on the phone yet."
        val loadS = (System.currentTimeMillis() - t0) / 1000.0
        warmUp(facts)
        val warm = lastStats
        val results = mutableListOf<Triple<Int, Long, String>>()
        for (t in listOf(2, 3, 4)) {
            threads = t
            val s = System.currentTimeMillis()
            command("open spotify", facts)
            val ms = System.currentTimeMillis() - s
            results += Triple(t, ms, lastStats)
            if (ms > 90_000) break                     // hopeless: don't keep the phone busy
        }
        val best = results.minBy { it.second }
        threads = best.first
        Prefs(ctx).threads = best.first
        var chatLine = ""
        if (hasChatBrain) {
            val s = System.currentTimeMillis()
            chat("what is a good name for a cat?", facts)
            chatLine = "\nChat brain: %.1fs - $lastStats".format((System.currentTimeMillis() - s) / 1000.0)
        }
        return "Brain $loadedName (load %.1fs)\nWarm-up: %s\n".format(loadS, warm) +
            results.joinToString("\n") { (t, ms, st) -> "$t threads: %.1fs - $st".format(ms / 1000.0) } +
            "\nUsing ${best.first} threads.$chatLine"
    }
}
