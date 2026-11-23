package org.annay.topshe

import android.content.Context
import org.json.JSONObject

/** The C++ llama.cpp bridge (src/main/cpp/topshe_llama.cpp). */
object NativeLlama {
    init { System.loadLibrary("topshe_llama") }
    external fun load(path: String): Long
    external fun unload(handle: Long)
    external fun generate(handle: Long, prefix: String, suffix: String, maxTokens: Int, temp: Float, threads: Int): ByteArray
}

/**
 * Topshe's brain: one model (Qwen 3.5 4B, retrained weekly on your usage) for both commands and chat.
 * Everything Topshe knows about you (Memory facts) is added to every prompt.
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
        "You are Topshe, a friendly assistant. Answer in 1-3 short, simple sentences. " +
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
    @Volatile var legacy = false
        private set
    @Volatile var loadedName = ""
    /** The loaded brain was trained on both hearings (English + Bengali speech models). */
    @Volatile var dualHearing = false
        private set
    private var template = CHATML
    @Volatile var threads = 4

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
        handle = NativeLlama.load(file.absolutePath)
        loadedName = if (legacy) "v2 (1.5B, older)" else "v${Prefs(ctx).brainVersion}"
        return handle != 0L
    }

    @Synchronized
    fun unload() {
        if (handle != 0L) NativeLlama.unload(handle)
        handle = 0L
    }

    private fun readTemplate(ctx: Context): String = runCatching {
        JSONObject(Store.promptTemplate(ctx).readText()).getString("template")
    }.getOrDefault("$CHATML<think>\n\n</think>\n\n")

    fun withFacts(system: String, facts: List<String>): String =
        if (facts.isEmpty() || legacy) system
        else system + "\n\n" + FACTS_HEADER + "\n" + facts.joinToString("\n") { "- $it" }

    /** Splits the template at {USER}: the part before is cached, only the rest is new each time. */
    private fun run(system: String, user: String, maxTokens: Int, temp: Float): String {
        val h = handle
        if (h == 0L) return ""
        val full = template.replace("{SYSTEM}", system)
        val cut = full.indexOf("{USER}")
        val prefix = full.substring(0, cut)
        val suffix = user + full.substring(cut + "{USER}".length)
        return String(NativeLlama.generate(h, prefix, suffix, maxTokens, temp, threads), Charsets.UTF_8)
            .replace(Regex("(?s)<think>.*?</think>"), "").trim()
    }

    /** Your words -> one action (JSON), or null if the reply wasn't readable. */
    fun command(text: String, facts: List<String>): JSONObject? =
        parseJson(run(withFacts(if (legacy) LEGACY_CMD_SYSTEM else CMD_SYSTEM, facts), text, 96, 0f))

    fun chat(question: String, facts: List<String>): String =
        run(withFacts(CHAT_SYSTEM, facts), question, 200, 0.6f)
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

    /** Times a few commands at different thread counts; keeps the fastest. Returns a readable report. */
    fun speedTest(ctx: Context, facts: List<String>): String {
        if (!load(ctx)) return "No brain on the phone yet."
        val samples = listOf("wake me up at 6:45 tomorrow", "remind me to call maa in 20 minutes", "open spotify")
        command(samples[0], facts)   // warm-up (also caches the fixed instructions)
        val results = mutableListOf<Pair<Int, Long>>()
        for (t in listOf(2, 4, 6)) {
            threads = t
            val t0 = System.currentTimeMillis()
            samples.forEach { command(it, facts) }
            results += t to (System.currentTimeMillis() - t0) / samples.size
        }
        val best = results.minBy { it.second }
        threads = best.first
        Prefs(ctx).threads = best.first
        val t0 = System.currentTimeMillis()
        val answer = chat("In one sentence, what is inflation?", facts)
        val chatMs = System.currentTimeMillis() - t0
        return "Brain $loadedName\n" +
            results.joinToString("\n") { "${it.first} threads: %.1f s per command".format(it.second / 1000.0) } +
            "\nUsing ${best.first} threads.\nChat answer took %.1f s: \"%s\"".format(chatMs / 1000.0, answer.take(120))
    }
}
